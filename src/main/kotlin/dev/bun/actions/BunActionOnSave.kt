package dev.bun.actions

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    if (format) run(project, executable, projectPath, listOf("fmt", file.path))
                    if (lint) run(project, executable, projectPath, buildList {
                        add("lint")
                        add("--fix")
                        add(file.path)
                    })
                    if (format || lint) refreshDocument(project, document, file)
                } finally {
                    running.remove(key)
                }
            }
        }
    }

    private fun refreshDocument(project: Project, document: Document, file: VirtualFile) {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(file.path)) ?: return
        ApplicationManager.getApplication().invokeLater {
            val manager = FileDocumentManager.getInstance()
            if (!project.isDisposed && !manager.isDocumentUnsaved(document)) manager.reloadFromDisk(document)
        }
    }

    private fun run(project: Project, executable: String, workingDirectory: String, args: List<String>): ProcessOutput {
        val command = GeneralCommandLine(executable).withWorkDirectory(workingDirectory).withParameters(args)
        val output = CapturingProcessHandler(command).runProcess(60_000)
        if (output.exitCode != 0 && !project.isDisposed) {
            val text = (output.stderr.ifBlank { output.stdout }).take(1500)
                .ifBlank { "Bun exited with code ${output.exitCode}." }
            NotificationGroupManager.getInstance().getNotificationGroup("Bun Actions")
                .createNotification("Bun ${args.first()} failed", text, NotificationType.WARNING)
                .notify(project)
        }
        return output
    }

    companion object {
        private val running = ConcurrentHashMap.newKeySet<String>()
    }
}
