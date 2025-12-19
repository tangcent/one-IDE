package com.oneide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.oneide.SyncService
import com.oneide.models.Config
import javax.swing.JComponent
import javax.swing.JPanel

class OneIDESettingsConfigurable : Configurable {

    private var excludeFilesArea: JBTextArea? = null
    private var excludeGitIgnoreCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String {
        return "One-IDE"
    }

    override fun createComponent(): JComponent {
        val panel = JPanel(VerticalFlowLayout())
        
        panel.add(JBLabel("Exclude Files (Glob patterns, one per line):"))
        excludeFilesArea = JBTextArea(10, 50)
        panel.add(JBScrollPane(excludeFilesArea))

        excludeGitIgnoreCheckBox = JBCheckBox("Exclude files ignored by .gitignore")
        panel.add(excludeGitIgnoreCheckBox)

        return panel
    }

    override fun isModified(): Boolean {
        val config = SyncService.instance.getConfig()
        val currentExcludeFiles = excludeFilesArea?.text?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val currentExcludeGitIgnore = excludeGitIgnoreCheckBox?.isSelected ?: false

        return config.excludeFiles != currentExcludeFiles || config.excludeGitIgnore != currentExcludeGitIgnore
    }

    override fun apply() {
        val newExcludeFiles = excludeFilesArea?.text?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val newExcludeGitIgnore = excludeGitIgnoreCheckBox?.isSelected ?: false
        
        val newConfig = Config(
            excludeFiles = newExcludeFiles,
            excludeGitIgnore = newExcludeGitIgnore
        )
        
        SyncService.instance.updateConfig(newConfig)
    }

    override fun reset() {
        val config = SyncService.instance.getConfig()
        excludeFilesArea?.text = config.excludeFiles.joinToString("\n")
        excludeGitIgnoreCheckBox?.isSelected = config.excludeGitIgnore
    }
}
