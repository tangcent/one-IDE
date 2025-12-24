package com.oneide.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.oneide.models.FileState
import com.oneide.models.IdeMetaData
import com.oneide.models.State
import com.oneide.utils.Debouncer
import com.oneide.utils.Logger
import com.oneide.utils.StateHelper
import java.nio.file.Paths

class IdeConnector(private val project: Project, private val configService: ConfigService) {
    private val metaData = IdeMetaData.getInstance(project)
    private var onUserActivityCallback: (() -> Unit)? = null
    private var isApplyingState = false
    private val debouncer = Debouncer(300)

    init {
        setupListeners()
    }

    fun setOnUserActivity(callback: () -> Unit) {
        onUserActivityCallback = callback
    }

    fun isWindowFocused(): Boolean {
        if (ApplicationManager.getApplication().isDispatchThread) {
            val window = WindowManager.getInstance().suggestParentWindow(project)
            return window?.isActive == true
        } else {
            var isFocused = false
            ApplicationManager.getApplication().invokeAndWait {
                val window = WindowManager.getInstance().suggestParentWindow(project)
                isFocused = window?.isActive == true
            }
            return isFocused
        }
    }

    private fun setupListeners() {
        val connection = project.messageBus.connect()

        // File Selection Changes
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                triggerActivity()
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                triggerActivity()
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                triggerActivity()
            }
        })

        // Editor Changes (Text, Caret)
        // We can attach to EditorFactory to get all editors
        val eventMulticaster = com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster
        val caretListener = object : CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                triggerActivity()
            }
        }
        val documentListener = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                triggerActivity()
            }
        }

        eventMulticaster.addCaretListener(caretListener, project)
        eventMulticaster.addDocumentListener(documentListener, project)
    }

    fun triggerActivity() {
        if (isApplyingState) return
        debouncer.debounce {
            onUserActivityCallback?.invoke()
        }
    }

    fun captureState(): State {
        val rootPath = project.basePath ?: ""

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val selectedFiles = fileEditorManager.selectedFiles
        val activePath = if (selectedFiles.isNotEmpty()) selectedFiles[0].path else null

        val openedFilesList = mutableListOf<FileState>()

        for (file in openFiles) {
            val path = file.path
            if (!configService.shouldSyncFile(path)) continue

            // Get cursor
            var cursor = 0
            var column = 0
            val editor = fileEditorManager.getSelectedEditor(file)
            if (editor is TextEditor) {
                val logicalPosition = editor.editor.caretModel.logicalPosition
                cursor = logicalPosition.line
                column = logicalPosition.column
            }

            openedFilesList.add(FileState(filePath = path, cursor = cursor, column = column))
        }

        return StateHelper.buildState(metaData, rootPath, openedFilesList, activePath)
    }

    fun applyState(state: State, onComplete: (() -> Unit) = {}) {
        Logger.info("Applying state from ${state.source}", metaData)
        isApplyingState = true

        ApplicationManager.getApplication().invokeLater {
            try {
                if (project.isDisposed) return@invokeLater

                val basePath = project.basePath ?: return@invokeLater
                val projectPath = Paths.get(basePath).toAbsolutePath().normalize()
                val projectPathStr = projectPath.toString().lowercase()

                // 2. Check intersection
                val stateRoot = Paths.get(state.root.path).toAbsolutePath().normalize()
                val stateRootStr = stateRoot.toString().lowercase()
                val hasIntersection = projectPathStr.startsWith(stateRootStr) || stateRootStr.startsWith(projectPathStr)

                if (!hasIntersection) {
                    Logger.info(
                        "Skipping apply state: Project path $projectPath has no intersection with state root $stateRoot",
                        metaData
                    )
                    return@invokeLater
                }

                val filesToOpen = StateHelper.getFiles(state, projectPathStr)

                val fileEditorManager = FileEditorManager.getInstance(project)

                // Close files not in state
                val currentOpenFiles = fileEditorManager.openFiles
                for (file in currentOpenFiles) {
                    if (projectPath != null) {
                        try {
                            val fPath = Paths.get(file.path).toAbsolutePath().normalize().toString().lowercase()
                            if (!fPath.startsWith(projectPathStr)) continue
                        } catch (_: Exception) {
                            continue
                        }
                    }

                    val keep = filesToOpen.any {
                        Paths.get(it.filePath).toAbsolutePath().normalize().toString()
                            .lowercase() == Paths.get(file.path).toAbsolutePath().normalize().toString().lowercase()
                    }
                    if (!keep) {
                        Logger.info("Closing file: ${file.path}", metaData)
                        fileEditorManager.closeFile(file)
                    }
                }

                // Open or Update files
                for (fileState in filesToOpen) {
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fileState.filePath)
                    if (virtualFile != null) {
                        // 1. Open if need open
                        if (!fileEditorManager.isFileOpen(virtualFile) || fileState.isActive) {
                            Logger.info("Opening file: ${fileState.filePath}", metaData)
                            fileEditorManager.openFile(virtualFile, fileState.isActive)
                        }

                        // 2. Move caret if need move
                        if (fileState.cursor >= 0) {
                            val textEditor = fileEditorManager.getTextEditor(virtualFile)
                            if (textEditor != null) {
                                val caretModel = textEditor.editor.caretModel
                                val scrollingModel = textEditor.editor.scrollingModel

                                if (caretModel.logicalPosition.line != fileState.cursor || caretModel.logicalPosition.column != fileState.column) {
                                    Logger.info(
                                        "Moving cursor to ${fileState.cursor}:${fileState.column} in ${fileState.filePath}",
                                        metaData
                                    )
                                    caretModel.moveToLogicalPosition(
                                        com.intellij.openapi.editor.LogicalPosition(
                                            fileState.cursor,
                                            fileState.column
                                        )
                                    )
                                    scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Logger.error("Error applying state", e, metaData)
            } finally {
                isApplyingState = false
                onComplete()
            }
        }
    }

    private fun FileEditorManager.getTextEditor(file: VirtualFile): TextEditor? {
        return getEditors(file).filterIsInstance<TextEditor>().firstOrNull()
    }
}
