package com.oneide

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

class OneIDEPlugin : StartupActivity {
    override fun runActivity(project: Project) {
        val syncService = SyncService.instance
        
        // Listen for file activation and close
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                syncService.handleLocalEvent(project)
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                syncService.handleLocalEvent(project)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                syncService.handleLocalEvent(project)
            }
        })

        // Listen for caret moves
        val editorFactory = EditorFactory.getInstance()
        
        // Attach to existing editors
        for (editor in editorFactory.allEditors) {
            if (editor.project == project) {
                addCaretListener(editor, project, syncService)
            }
        }

        // Listen for new editors
        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project != project) return
                addCaretListener(editor, project, syncService)
            }
        }, project)
    }

    private fun addCaretListener(editor: com.intellij.openapi.editor.Editor, project: Project, syncService: SyncService) {
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent) {
                syncService.handleLocalEvent(project)
            }
        }, project)
    }
}

class ToggleSyncAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val syncService = SyncService.instance
        syncService.isEnabled = !syncService.isEnabled
        
        val status = if (syncService.isEnabled) "Enabled" else "Disabled"
        Messages.showInfoMessage("One-IDE Sync: $status", "One-IDE")
    }
}

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
                return if (SyncService.instance.isEnabled) "One-IDE: On" else "One-IDE: Off"
            }

            override fun getTooltipText(): String = "Click to toggle One-IDE Synchronization"

            override fun getAlignment(): Float = 0.0f

            override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
                // Toggle on click
                SyncService.instance.isEnabled = !SyncService.instance.isEnabled
                // Update is handled by SyncService setter calling updateAllStatusBars
            }
        }
    }

    override fun disposeWidget(widget: StatusBarWidget) {}
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
