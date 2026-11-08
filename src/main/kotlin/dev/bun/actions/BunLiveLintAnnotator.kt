package dev.bun.actions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

open class BunLiveLintAnnotator(private val expectedLanguage: String) : ExternalAnnotator<BunLintInput, List<BunLintDiagnostic>>() {
    override fun collectInformation(file: PsiFile): BunLintInput? {
        if (file.language.id != expectedLanguage) return null
        val virtualFile = file.virtualFile ?: return null
        val project = file.project
        val settings = BunSettings.get(project)
        if (settings.configurationMode == BunConfigurationMode.DISABLED.name || !settings.showLintWarnings) return null
        val extension = virtualFile.extension?.lowercase().orEmpty()
        if (!BunFileTypes.matches(settings, BunTask.LINT, extension)) return null

        val manual = settings.configurationMode == BunConfigurationMode.MANUAL.name
        val executable = if (manual) settings.bunExecutable else "bun"
        if (executable.isBlank() || (manual && !File(executable).isFile)) return null
        val projectPath = project.basePath ?: return null
        val text = PsiDocumentManager.getInstance(project).getDocument(file)?.text ?: file.text
        return BunLintInput(executable, projectPath, virtualFile.path, text)
    }

    override fun doAnnotate(input: BunLintInput): List<BunLintDiagnostic> {
        val output = try {
            val process = ProcessBuilder(
                input.executable, "lint", "--no-script", "--format", "json", "--stdin", "--stdin-filename", input.filePath,
            ).directory(File(input.projectPath)).redirectErrorStream(true).start()
            val outputReader = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            process.outputStream.bufferedWriter().use { it.write(input.text) }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            outputReader.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return emptyList()
        }
        return parse(output, input.text)
    }

    override fun apply(file: PsiFile, annotationResult: List<BunLintDiagnostic>?, holder: AnnotationHolder) {
        annotationResult.orEmpty().forEach { diagnostic ->
            if (diagnostic.startOffset < 0 || diagnostic.endOffset <= diagnostic.startOffset ||
                diagnostic.endOffset > file.textLength) return@forEach
            holder.newAnnotation(
                if (diagnostic.isError) HighlightSeverity.ERROR else HighlightSeverity.WARNING,
                diagnostic.description,
            ).range(TextRange(diagnostic.startOffset, diagnostic.endOffset))
                .tooltip(diagnostic.tooltip)
                .create()
        }
    }

    private fun parse(output: String, text: String): List<BunLintDiagnostic> = try {
        val jsonStart = output.indexOf('[')
        if (jsonStart < 0) return emptyList()
        val reports = JsonParser.parseString(output.substring(jsonStart)).asJsonArray
        val lines = lineOffsets(text)
        buildList {
            reports.forEach { report ->
                val messages = report.asJsonObject.getAsJsonArray("messages") ?: return@forEach
                messages.forEach messageLoop@{ item ->
                    val message = item.asJsonObject
                    val line = message.number("line") ?: return@messageLoop
                    val column = message.number("column") ?: 1
                    val endLine = message.number("endLine") ?: line
                    val endColumn = message.number("endColumn") ?: column + 1
                    val start = offset(lines, text, line, column) ?: return@messageLoop
                    val end = offset(lines, text, endLine, endColumn) ?: (start + 1)
                    if (start >= text.length || end <= start) return@messageLoop

                    val ruleId = message.get("ruleId")?.takeUnless { it.isJsonNull }?.asString ?: "rule"
                    val reason = message.get("message")?.asString.orEmpty()
                    val fix = message.get("fix")?.takeUnless { it.isJsonNull }?.asJsonObject
                    val replacement = fix?.get("text")?.takeUnless { it.isJsonNull }?.asString
                    val description = "Bun linter ($ruleId): $reason"
                    val tooltip = buildString {
                        append("Bun linter\nRule: ").append(ruleId).append("\n").append(reason)
                        if (!replacement.isNullOrBlank()) {
                            append("\nSuggested replacement: ").append(replacement)
                            append("\nBun can apply this fix with bun lint --fix.")
                        }
                    }
                    add(BunLintDiagnostic(
                        start,
                        end.coerceIn(start + 1, text.length),
                        description,
                        tooltip,
                        message.number("severity") == 2,
                    ))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun JsonObject.number(name: String): Int? =
        get(name)?.takeUnless { it.isJsonNull }?.asInt

    private fun lineOffsets(text: String): IntArray = buildList {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }.toIntArray()

    private fun offset(lines: IntArray, text: String, line: Int, column: Int): Int? {
        if (line < 1 || line > lines.size) return null
        val start = lines[line - 1]
        val next = lines.getOrNull(line) ?: text.length
        val end = if (next > start && text[next - 1] == '\n') next - 1 else next
        return (start + column - 1).coerceIn(start, end)
    }
}

class BunJavaScriptLiveLintAnnotator : BunLiveLintAnnotator("JavaScript")
class BunTypeScriptLiveLintAnnotator : BunLiveLintAnnotator("TypeScript")
class BunTypeScriptJsxLiveLintAnnotator : BunLiveLintAnnotator("TypeScript JSX")

data class BunLintInput(
    val executable: String,
    val projectPath: String,
    val filePath: String,
    val text: String,
)

data class BunLintDiagnostic(
    val startOffset: Int,
    val endOffset: Int,
    val description: String,
    val tooltip: String,
    val isError: Boolean,
)
