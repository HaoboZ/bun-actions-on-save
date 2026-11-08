package dev.bun.actions

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel

enum class BunTask(val displayName: String, val supportedExtensions: Set<String>) {
    FORMAT("bun fmt", setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "json", "jsonc", "css", "scss", "less", "html", "vue", "svelte", "md", "mdx", "yaml", "yml", "toml", "graphql", "gql", "hbs", "handlebars")),
    LINT("bun lint", setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts")),
}

object BunFileTypes {
    fun available(task: BunTask): List<FileType> = FileTypeManager.getInstance().registeredFileTypes
        .filter { !it.isBinary && it.defaultExtension.lowercase() in task.supportedExtensions }
        .distinctBy { it.name }
        .sortedBy { it.displayName.lowercase() }

    fun selected(settings: BunSettings, task: BunTask): Set<String> {
        val configured = when (task) {
            BunTask.FORMAT -> settings.formatFileTypesConfigured
            BunTask.LINT -> settings.lintFileTypesConfigured
        }
        val stored = when (task) {
            BunTask.FORMAT -> settings.formatFileTypes
            BunTask.LINT -> settings.lintFileTypes
        }
        if (configured) return stored.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

        val legacyExtensions = when (task) {
            BunTask.FORMAT -> settings.formatExtensions
            BunTask.LINT -> settings.lintExtensions
        }.split(',').map { it.trim().removePrefix(".").lowercase() }.toSet()

        return available(task).filter { it.defaultExtension.lowercase() in legacyExtensions }.map { it.name }.toSet()
    }

    fun store(settings: BunSettings, task: BunTask, selected: Set<String>) {
        val encoded = selected.sorted().joinToString(",")
        when (task) {
            BunTask.FORMAT -> {
                settings.formatFileTypes = encoded
                settings.formatFileTypesConfigured = true
            }
            BunTask.LINT -> {
                settings.lintFileTypes = encoded
                settings.lintFileTypesConfigured = true
            }
        }
    }

    fun matches(settings: BunSettings, task: BunTask, extension: String): Boolean {
        val normalized = extension.lowercase()
        if (normalized !in task.supportedExtensions) return false
        if (settings.configurationMode != BunConfigurationMode.MANUAL.name) return true
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(normalized)
        return fileType.name in selected(settings, task)
    }

    fun dropdown(
        task: BunTask,
        selectedInitially: Set<String>,
        onChanged: (Set<String>) -> Unit,
    ): BunFileTypeDropdown = BunFileTypeDropdown(task, selectedInitially, onChanged)

    internal fun createPopup(
        task: BunTask,
        link: DropDownLink<String>,
        initialSelection: Set<String>,
        onChanged: (Set<String>) -> Unit,
    ): JBPopup {
        val selected = initialSelection.toMutableSet()
        val fileTypes = available(task)
        val checks = mutableMapOf<String, JBCheckBox>()
        val rows = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        }

        fun updateSelection() {
            selected.clear()
            checks.forEach { (name, checkbox) -> if (checkbox.isSelected) selected += name }
            link.selectedItem = summary(task, selected)
            onChanged(selected.toSet())
        }

        fileTypes.forEach { fileType ->
            val checkbox = JBCheckBox(fileType.displayName, fileType.name in selected)
            checkbox.addActionListener { updateSelection() }
            checks[fileType.name] = checkbox
            rows.add(checkbox)
        }

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        val selectAll = javax.swing.JButton("Select all")
        val clearAll = javax.swing.JButton("Clear")
        selectAll.addActionListener {
            checks.values.forEach { it.isSelected = true }
            updateSelection()
        }
        clearAll.addActionListener {
            checks.values.forEach { it.isSelected = false }
            updateSelection()
        }
        controls.add(selectAll)
        controls.add(clearAll)

        val content = JPanel(BorderLayout(0, 6)).apply {
            add(controls, BorderLayout.NORTH)
            add(JBScrollPane(rows), BorderLayout.CENTER)
            preferredSize = Dimension(320, 380)
        }
        return JBPopupFactory.getInstance().createComponentPopupBuilder(content, link)
            .setTitle("${task.displayName} file types")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()
    }

    internal fun summary(task: BunTask, selected: Set<String>): String {
        val eligible = available(task).map { it.name }.toSet()
        return if (selected.containsAll(eligible) && eligible.isNotEmpty()) "All file types"
        else if (selected.isEmpty()) "No file types"
        else "${selected.size} file types"
    }
}

class BunFileTypeDropdown(
    private val task: BunTask,
    selectedInitially: Set<String>,
    private val onChanged: (Set<String>) -> Unit,
) {
    private var currentSelection = selectedInitially.toSet()
    lateinit var component: DropDownLink<String>
        private set

    init {
        component = DropDownLink(BunFileTypes.summary(task, currentSelection)) { _: DropDownLink<String> ->
            BunFileTypes.createPopup(task, component, currentSelection) { selected ->
                currentSelection = selected
                onChanged(selected)
            }
        }
    }

    fun setSelected(selected: Set<String>) {
        currentSelection = selected.toSet()
        component.selectedItem = BunFileTypes.summary(task, currentSelection)
    }
}
