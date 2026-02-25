package com.oneide

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import com.oneide.services.ClusterService
import com.oneide.services.RuleService

/**
 * The main entry point for the One-IDE plugin post-startup activity.
 *
 * Responsibilities:
 * - Initializes the [SyncService] for the project.
 * - Starts the [RuleService] to handle AI rule synchronization.
 */
class OneIDEPlugin : StartupActivity {
    override fun runActivity(project: Project) {
        SyncService.getInstance(project)
        
        // Start Rule Service
        RuleService(project, ClusterService.getInstance(project)).start()
    }
}

/**
 * Action to toggle the enabled state of One-IDE synchronization.
 */
class ToggleSyncAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val syncService = SyncService.getInstance(project)
        syncService.isEnabled = !syncService.isEnabled
        
        val status = if (syncService.isEnabled) "Enabled" else "Disabled"
        Messages.showInfoMessage("One-IDE Sync for ${project.name}: $status", "One-IDE")
    }
}

/**
 * Factory for creating the One-IDE status bar widget.
 * Displays the current sync status (On/Off) and allows toggling via click.
 */
class OneIDEStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "OneIDEStatusBar"

    override fun getDisplayName(): String = "One-IDE Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return object : StatusBarWidget, StatusBarWidget.TextPresentation {
        
            override fun ID(): String = "OneIDEStatusBar"

            override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

            override fun install(statusBar: StatusBar) {
                // Initial update handled by SyncService or polling
            }

            override fun dispose() {}

            override fun getText(): String {
                return if (SyncService.getInstance(project).isEnabled) "One-IDE: On" else "One-IDE: Off"
            }

            override fun getTooltipText(): String = "Click to toggle One-IDE Synchronization"

            override fun getAlignment(): Float = 0.0f

            override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
                // Toggle on click
                val syncService = SyncService.getInstance(project)
                syncService.isEnabled = !syncService.isEnabled
                // Update is handled by SyncService setter calling updateAllStatusBars
            }
        }
    }

    override fun disposeWidget(widget: StatusBarWidget) {}
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
