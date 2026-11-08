package dev.bun.actions

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import java.awt.Component
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

class BunSettingsConfigurable(private val project: Project) : Configurable {
    private val disable = JBRadioButton("Disable Bun")
    private val automatic = JBRadioButton("Automatic Bun configuration")
    private val manual = JBRadioButton("Manual Bun configuration")
    private val format = JBCheckBox("Enable bun fmt")
    private val lint = JBCheckBox("Enable bun lint --fix")
    private val showLintWarnings = JBCheckBox("Show bun lint warnings in editor")
    private val executable = TextFieldWithBrowseButton()
    private lateinit var formatFileTypes: BunFileTypeDropdown
    private lateinit var lintFileTypes: BunFileTypeDropdown
    private var selectedFormatTypes = emptySet<String>()
    private var selectedLintTypes = emptySet<String>()
    private var panel: JPanel? = null
    private var manualPanel: JPanel? = null

    override fun getDisplayName() = "Bun"

    override fun createComponent(): JComponent {
        ButtonGroup().apply {
            add(disable)
            add(automatic)
            add(manual)
        }
        val settings = BunSettings.get(project)
        executable.addBrowseFolderListener(
            "Select Bun executable",
            "Choose the Bun executable to use in Manual Bun configuration",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )
        selectedFormatTypes = BunFileTypes.selected(settings, BunTask.FORMAT)
        selectedLintTypes = BunFileTypes.selected(settings, BunTask.LINT)
        formatFileTypes = BunFileTypes.dropdown(BunTask.FORMAT, selectedFormatTypes) { selectedFormatTypes = it }
        lintFileTypes = BunFileTypes.dropdown(BunTask.LINT, selectedLintTypes) { selectedLintTypes = it }

        manualPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("bun fmt file types:", formatFileTypes.component)
            .addLabeledComponent("bun lint file types:", lintFileTypes.component)
            .addLabeledComponent("Bun executable:", executable)
            .panel
        manualPanel!!.border = BorderFactory.createEmptyBorder(0, 24, 0, 0)
        manualPanel!!.isVisible = false
        listOf(disable, automatic, manual, manualPanel!!).forEach { it.alignmentX = Component.LEFT_ALIGNMENT }

        val modes = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(disable)
            add(Box.createVerticalStrut(4))
            add(automatic)
            add(Box.createVerticalStrut(4))
            add(manual)
            add(Box.createVerticalStrut(2))
            add(manualPanel)
        }
        listOf(disable, automatic, manual).forEach { it.addActionListener { updateControls() } }
        lint.addActionListener { updateControls() }
        showLintWarnings.addActionListener { updateControls() }

        panel = FormBuilder.createFormBuilder()
            .addComponent(modes)
            .addComponent(format)
            .addComponent(lint)
            .addComponent(showLintWarnings)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = BunSettings.get(project)
        val selectedMode = selectedMode()
        return selectedMode != s.configurationMode || format.isSelected != s.formatEnabled ||
            lint.isSelected != s.lintEnabled ||
            showLintWarnings.isSelected != s.showLintWarnings ||
            executable.text.trim() != manualExecutableValue(s) || selectedFormatTypes != BunFileTypes.selected(s, BunTask.FORMAT) ||
            selectedLintTypes != BunFileTypes.selected(s, BunTask.LINT)
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val s = BunSettings.get(project)
        if (manual.isSelected && (executable.text.isBlank() || !File(executable.text.trim()).isFile)) {
            throw ConfigurationException("Choose a valid Bun executable for Manual Bun configuration.")
        }
        s.configurationMode = selectedMode()
        s.formatEnabled = !disable.isSelected && format.isSelected
        s.lintEnabled = !disable.isSelected && lint.isSelected
        s.showLintWarnings = !disable.isSelected && showLintWarnings.isSelected
        if (manual.isSelected) s.bunExecutable = executable.text.trim()
        BunFileTypes.store(s, BunTask.FORMAT, selectedFormatTypes)
        BunFileTypes.store(s, BunTask.LINT, selectedLintTypes)
        project.getService(BunLiveLintService::class.java).configurationChanged()
    }

    override fun reset() {
        val s = BunSettings.get(project)
        when (s.configurationMode) {
            BunConfigurationMode.DISABLED.name -> disable.isSelected = true
            BunConfigurationMode.MANUAL.name -> manual.isSelected = true
            else -> automatic.isSelected = true
        }
        format.isSelected = s.configurationMode != BunConfigurationMode.DISABLED.name && s.formatEnabled
        lint.isSelected = s.configurationMode != BunConfigurationMode.DISABLED.name && s.lintEnabled
        showLintWarnings.isSelected = s.showLintWarnings
        executable.text = manualExecutableValue(s)
        selectedFormatTypes = BunFileTypes.selected(s, BunTask.FORMAT)
        selectedLintTypes = BunFileTypes.selected(s, BunTask.LINT)
        formatFileTypes.setSelected(selectedFormatTypes)
        lintFileTypes.setSelected(selectedLintTypes)
        updateControls()
    }

    private fun updateControls() {
        val active = !disable.isSelected
        format.isEnabled = active
        lint.isEnabled = active
        showLintWarnings.isEnabled = active
        manualPanel?.isVisible = manual.isSelected
        manualPanel?.parent?.revalidate()
        manualPanel?.parent?.repaint()
    }

    private fun selectedMode(): String = when {
        disable.isSelected -> BunConfigurationMode.DISABLED.name
        manual.isSelected -> BunConfigurationMode.MANUAL.name
        else -> BunConfigurationMode.AUTOMATIC.name
    }

    private fun manualExecutableValue(settings: BunSettings): String =
        settings.bunExecutable.takeUnless { it == "bun" }.orEmpty()
}
