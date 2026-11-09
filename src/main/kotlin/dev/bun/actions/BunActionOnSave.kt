package dev.bun.actions

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BunFormatActionOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext): Collection<ActionOnSaveInfo> =
        listOf(BunActionInfo(context, BunAction.FORMAT, draftFor(context)))
}

class BunLintActionOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext): Collection<ActionOnSaveInfo> =
        listOf(BunActionInfo(context, BunAction.LINT, draftFor(context)))
}

private val DRAFT_KEY = Key.create<BunActionsDraft>("dev.bun.actionsOnSaveDraft")

private fun draftFor(context: ActionOnSaveContext): BunActionsDraft =
    context.getUserData(DRAFT_KEY) ?: BunActionsDraft(BunSettings.get(context.project)).also {
        context.putUserData(DRAFT_KEY, it)
    }

private enum class BunAction(val label: String, val task: BunTask) {
    FORMAT("Run bun fmt", BunTask.FORMAT),
    LINT("Run bun lint --fix", BunTask.LINT),
}

private class BunActionsDraft(val settings: BunSettings) {
    var mode = settings.configurationMode
    private var formatEnabled = settings.formatEnabled
    private var lintEnabled = settings.lintEnabled
    private val selectedTypes = mutableMapOf(
        BunTask.FORMAT to BunFileTypes.selected(settings, BunTask.FORMAT),
        BunTask.LINT to BunFileTypes.selected(settings, BunTask.LINT),
    )

    fun enabled(action: BunAction): Boolean = mode != BunConfigurationMode.DISABLED.name && when (action) {
        BunAction.FORMAT -> formatEnabled
        BunAction.LINT -> lintEnabled
    }

    fun setEnabled(action: BunAction, enabled: Boolean) {
        when (action) {
            BunAction.FORMAT -> formatEnabled = enabled
            BunAction.LINT -> lintEnabled = enabled
        }
        if (enabled && mode == BunConfigurationMode.DISABLED.name) mode = BunConfigurationMode.AUTOMATIC.name
    }

    fun selected(task: BunTask): Set<String> = selectedTypes.getValue(task)

    fun setSelected(task: BunTask, selected: Set<String>) {
        selectedTypes[task] = selected
        mode = BunConfigurationMode.MANUAL.name
    }

    fun apply(action: BunAction) {
        settings.configurationMode = mode
        settings.formatEnabled = mode != BunConfigurationMode.DISABLED.name && formatEnabled
        settings.lintEnabled = mode != BunConfigurationMode.DISABLED.name && lintEnabled
        BunFileTypes.store(settings, action.task, selected(action.task))
    }
}

private class BunActionInfo(
    context: ActionOnSaveContext,
    private val action: BunAction,
    private val draft: BunActionsDraft,
) : ActionOnSaveInfo(context) {
    private var initialEnabled = draft.enabled(action)
    private var initialMode = draft.mode
    private var initialSelectedTypes = draft.selected(action.task)
    private val fileTypeDropdown = BunFileTypes.dropdown(action.task, draft.selected(action.task)) {
        draft.setSelected(action.task, it)
    }

    override fun getActionOnSaveName(): String = action.label
    override fun isActionOnSaveEnabled(): Boolean = draft.enabled(action)
    override fun setActionOnSaveEnabled(enabled: Boolean) = draft.setEnabled(action, enabled)
    override fun getDropDownLinks() = listOf(fileTypeDropdown.component)
    override fun isModified(): Boolean = draft.enabled(action) != initialEnabled || draft.mode != initialMode ||
        draft.selected(action.task) != initialSelectedTypes

    override fun apply() {
        draft.apply(action)
        initialEnabled = draft.enabled(action)
        initialMode = draft.mode
        initialSelectedTypes = draft.selected(action.task)
    }
}

class BunActionOnSave : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean {
        val settings = BunSettings.get(project)
        return settings.configurationMode != BunConfigurationMode.DISABLED.name && (settings.formatEnabled || settings.lintEnabled)
    }

    override fun processDocuments(project: Project, documents: Array<Document>) {
        if (project.isDisposed) return
        val settings = BunSettings.get(project)
        if (settings.configurationMode == BunConfigurationMode.DISABLED.name) return
        val manualMode = settings.configurationMode == BunConfigurationMode.MANUAL.name
        val executable = if (manualMode) settings.bunExecutable else "bun"
        if (manualMode && (executable.isBlank() || !File(executable).isFile)) {
            NotificationGroupManager.getInstance().getNotificationGroup("Bun Actions")
                .createNotification(
                    "Manual Bun configuration needs an executable",
                    "Choose the Bun executable under Settings | Tools | Bun.",
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }
        val formatEnabled = settings.formatEnabled
        val lintEnabled = settings.lintEnabled
        val projectPath = project.basePath ?: return

        documents.forEach { document ->
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@forEach
            if (!file.isInLocalFileSystem || file.isDirectory) return@forEach
            val extension = file.extension?.lowercase().orEmpty()
            val format = formatEnabled && BunFileTypes.matches(settings, BunTask.FORMAT, extension)
            val lint = lintEnabled && BunFileTypes.matches(settings, BunTask.LINT, extension)
            if (!format && !lint) return@forEach

            val key = file.path
            if (!running.add(key)) return@forEach
            val originalText = document.text
            val originalStamp = document.modificationStamp
            ApplicationManager.getApplication().executeOnPooledThread {
                var scheduledApply = false
                try {
                    var updatedText = originalText
                    if (format) {
                        updatedText = runWithText(
                            project, executable, projectPath,
                            listOf("fmt", "--stdin-filepath", file.path), updatedText,
                        ) ?: return@executeOnPooledThread
                    }
                    if (lint) {
                        val lintOutput = runWithText(
                            project, executable, projectPath,
                            listOf("lint", "--no-script", "--fix-dry-run", "--format", "json", "--stdin", "--stdin-filename", file.path),
                            updatedText,
                            allowLintFindings = true,
                        ) ?: return@executeOnPooledThread
                        updatedText = fixedText(lintOutput) ?: updatedText
                    }
                    if (updatedText != originalText) {
                        scheduledApply = true
                        ApplicationManager.getApplication().invokeLater {
                            try {
                                if (project.isDisposed || document.modificationStamp != originalStamp) return@invokeLater
                                WriteCommandAction.runWriteCommandAction(project, "Bun Actions on Save", null, Runnable {
                                    if (!project.isDisposed && document.modificationStamp == originalStamp) {
                                        document.setText(updatedText)
                                    }
                                })
                                if (!project.isDisposed && document.text == updatedText) {
                                    FileDocumentManager.getInstance().saveDocument(document)
                                }
                            } finally {
                                running.remove(key)
                            }
                        }
                    }
                } finally {
                    if (!scheduledApply) running.remove(key)
                }
            }
        }
    }

    private fun runWithText(
        project: Project,
        executable: String,
        workingDirectory: String,
        args: List<String>,
        inputText: String,
        allowLintFindings: Boolean = false,
    ): String? {
        val process = try {
            GeneralCommandLine(executable).withWorkDirectory(workingDirectory).withParameters(args).createProcess()
        } catch (error: Exception) {
            notifyFailure(project, args.first(), error.message ?: "Could not start Bun.")
            return null
        }
        val stdout = CompletableFuture.supplyAsync {
            process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        }
        val stderr = CompletableFuture.supplyAsync {
            process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
        }
        try {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(inputText) }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                notifyFailure(project, args.first(), "Bun did not finish within 60 seconds.")
                return null
            }
            val outputText = stdout.get(2, TimeUnit.SECONDS)
            val errorText = stderr.get(2, TimeUnit.SECONDS)
            val expectedLintExit = allowLintFindings && process.exitValue() == 1
            if (process.exitValue() != 0 && !expectedLintExit) {
                notifyFailure(project, args.first(), (errorText.ifBlank { outputText }).take(1500)
                    .ifBlank { "Bun exited with code ${process.exitValue()}." })
                return null
            }
            return outputText
        } catch (error: Exception) {
            process.destroyForcibly()
            notifyFailure(project, args.first(), error.message ?: "Could not read Bun output.")
            return null
        }
    }

    private fun fixedText(output: String): String? = try {
        val jsonStart = output.indexOf('[')
        if (jsonStart < 0) return null
        val report = JsonParser.parseString(output.substring(jsonStart)).asJsonArray.firstOrNull()?.asJsonObject ?: return null
        report.get("output")?.takeUnless { it.isJsonNull }?.asString
    } catch (_: Exception) {
        null
    }

    private fun notifyFailure(project: Project, command: String, text: String) {
        if (!project.isDisposed) {
            NotificationGroupManager.getInstance().getNotificationGroup("Bun Actions")
                .createNotification("Bun $command failed", text, NotificationType.WARNING)
                .notify(project)
        }
    }

    companion object {
        private val running = ConcurrentHashMap.newKeySet<String>()
    }
}
