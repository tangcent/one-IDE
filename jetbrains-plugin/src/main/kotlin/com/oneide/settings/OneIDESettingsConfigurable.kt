package com.oneide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.openapi.project.Project
import com.oneide.services.ConfigService
import com.oneide.models.Config
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Provides the settings UI for One-IDE in the IDE settings dialog.
 *
 * Responsibilities:
 * - Displays and edits configuration options:
 *   - File exclusions (global).
 *   - .gitignore exclusion toggle (global).
 *   - AI Project Rules sync toggle (project-specific).
 *   - Folder State sync toggle (global).
 *   - Current AI Tool selection (project-specific).
 * - Delegates loading and saving of configuration to [ConfigService].
 */
class OneIDESettingsConfigurable(private val project: Project) : Configurable {

    private val configService: ConfigService = ConfigService.getInstance(project)

    private var excludeFilesArea: JBTextArea? = null
    private var excludeGitIgnoreCheckBox: JBCheckBox? = null

    // AI Settings
    private var syncRulesCheckBox: JBCheckBox? = null
    private var currentToolComboBox: ComboBox<ToolOption>? = null

    override fun getDisplayName(): String {
        return "One-IDE"
    }

    data class ToolOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    override fun createComponent(): JComponent {
        val panel = JPanel(VerticalFlowLayout())

        panel.add(JBLabel("Exclude Files (Glob patterns, one per line):"))
        excludeFilesArea = JBTextArea(10, 50)
        panel.add(JBScrollPane(excludeFilesArea))

        excludeGitIgnoreCheckBox = JBCheckBox("Exclude files ignored by .gitignore")
        panel.add(excludeGitIgnoreCheckBox)

        panel.add(JBLabel("AI Project Rules:"))
        syncRulesCheckBox = JBCheckBox("Enable AI Project Rules Sync")
        panel.add(syncRulesCheckBox)

        panel.add(JBLabel("Current AI Tool:"))

        val options = mutableListOf<ToolOption>()

        // Auto Option
        val detected = com.oneide.services.AITool.instance.resolveTool("Auto") { id ->
            com.oneide.utils.PluginUtils.isPluginInstalled(id)
        }
        options.add(ToolOption("Auto", "Auto (${detected ?: "None"})"))

        // Other Tools
        val aiConfigs = com.oneide.services.AITool.instance.getAllAIConfigs()
        for (config in aiConfigs.values) {
            val isInstalled = config.plugins.isNotEmpty() && config.plugins.any {
                com.oneide.utils.PluginUtils.isPluginInstalled(it)
            }
            val status = if (isInstalled) "installed" else "not installed"
            options.add(ToolOption(config.name, "${config.name} ($status)"))
        }

        currentToolComboBox = ComboBox(options.toTypedArray())
        panel.add(currentToolComboBox)

        return panel
    }

    override fun isModified(): Boolean {
        val config = configService.getConfig()

        val currentExcludeFiles = excludeFilesArea?.text?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val currentExcludeGitIgnore = excludeGitIgnoreCheckBox?.isSelected ?: false

        val currentSyncRules = config.syncRules
        val currentTool = config.currentTool

        val newSyncRules = syncRulesCheckBox?.isSelected ?: true
        val newTool = (currentToolComboBox?.selectedItem as? ToolOption)?.value ?: "Auto"

        return config.excludeFiles != currentExcludeFiles ||
                config.excludeGitIgnore != currentExcludeGitIgnore ||
                currentSyncRules != newSyncRules ||
                currentTool != newTool
    }

    override fun apply() {
        val newExcludeFiles = excludeFilesArea?.text?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val newExcludeGitIgnore = excludeGitIgnoreCheckBox?.isSelected ?: false
        val newSyncRules = syncRulesCheckBox?.isSelected ?: true
        val newTool = (currentToolComboBox?.selectedItem as? ToolOption)?.value ?: "Auto"

        val newConfig = Config(
            excludeFiles = newExcludeFiles,
            excludeGitIgnore = newExcludeGitIgnore,
            syncRules = newSyncRules,
            currentTool = newTool
        )

        configService.updateConfig(newConfig)
    }

    override fun reset() {
        val config = configService.getConfig()

        excludeFilesArea?.text = config.excludeFiles.joinToString("\n")
        excludeGitIgnoreCheckBox?.isSelected = config.excludeGitIgnore

        syncRulesCheckBox?.isSelected = config.syncRules

        val options = currentToolComboBox?.model
        if (options != null) {
            for (i in 0 until options.size) {
                val option = options.getElementAt(i)
                if (option.value == config.currentTool) {
                    currentToolComboBox?.selectedItem = option
                    break
                }
            }
        }
    }
}
