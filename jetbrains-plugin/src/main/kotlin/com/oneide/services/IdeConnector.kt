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

/**
 * Service responsible for connecting the IDE events with the synchronization logic.
 * It listens for user activities (file selection, edits) and captures/applies the IDE state.
 */
class IdeConnector(private val project: Project, private val configService: ConfigService) {
    private val metaData = IdeMetaData.getInstance(project)
    
    // Callback to be invoked when user activity is detected
    private var onUserActivityCallback: (() -> Unit)? = null
    
    // Flag to prevent triggering activity while applying state from external source
    private var isApplyingState = false
    
    // Debouncer for user activity (typing, selection changes) -> Outbound
    private val debouncer = Debouncer(300)
    
    // Debouncer for applying state (received from other IDEs) -> Inbound
    internal var applyStateDebouncer = Debouncer(300)

    init {
        setupListeners()
    }

    /**
     * Sets the callback to be invoked when user activity occurs.
     */
    fun setOnUserActivity(callback: () -> Unit) {
        onUserActivityCallback = callback
    }

    /**
     * Checks if the IDE window is currently focused.
     * This handles both EDT and non-EDT threads.
     */
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

    /**
     * Sets up listeners for file editor events, caret movements, and document changes.
     */
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

    /**
     * Triggers the user activity callback after a debounce period.
     * Skips if currently applying state.
     */
    fun triggerActivity() {
        if (isApplyingState) return
        debouncer.debounce {
            onUserActivityCallback?.invoke()
        }
    }

    /**
     * Captures the current state of the IDE (opened files, active file, cursor positions).
     */
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

    /**
     * Applies the received state to the IDE.
     * This involves opening files, closing irrelevant files, and moving the cursor.
     */
    fun applyState(state: State, onComplete: (() -> Unit) = {}) {
        applyStateDebouncer.debounce {
            Logger.info("Applying state from ${state.source}", metaData)
            isApplyingState = true

            ApplicationManager.getApplication().invokeLater {
                    try {
                        if (project.isDisposed) return@invokeLater

                        val basePath = project.basePath ?: return@invokeLater
                    val projectPath = Paths.get(basePath).toAbsolutePath().normalize()
                    val projectPathStr = projectPath.toString().lowercase()

                    // 1. Check intersection
                    // If the current project root has no relationship with the state root, we should not apply the state.
                    if (!StateHelper.hasIntersection(state, projectPathStr)) {
                        Logger.info(
                            "Skipping apply state: Project path $projectPathStr has no intersection with state root ${state.root.path}",
                            metaData
                        )
                        return@invokeLater
                    }

                    val filesToOpen = StateHelper.getFiles(state, projectPathStr)

                    val fileEditorManager = FileEditorManager.getInstance(project)

                    // 2. Close files not in state
                    // We only close files that are part of the project but not in the new state.
                    val currentOpenFiles = fileEditorManager.openFiles
                    for (file in currentOpenFiles) {
                        // Check if file belongs to current project root AND the incoming state's scope
                        // If the file is outside the scope of the incoming state (e.g. syncing a subfolder),
                        // we should not close it as the incoming state has no authority over it.
                        if (!StateHelper.isInsideRoot(projectPathStr, file.path) || !StateHelper.checkPathBelongsToState(state, file.path)) {
                            continue
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

                    // 3. Open or Update files
                    // Iterate through files in the state and open/activate/scroll them.
                    val localFileSystem = LocalFileSystem.getInstance()
                    for (fileState in filesToOpen) {
                        val virtualFile = localFileSystem.findFileByPath(fileState.filePath)
                        if (virtualFile != null) {
                            // 3.1 Open if need open
                            if (!fileEditorManager.isFileOpen(virtualFile) || fileState.isActive) {
                                Logger.info("Opening file: ${fileState.filePath}", metaData)
                                fileEditorManager.openFile(virtualFile, fileState.isActive)
                            }

                            // 3.2 Move caret if need move
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
    }

    private fun FileEditorManager.getTextEditor(file: VirtualFile): TextEditor? {
        return getEditors(file).filterIsInstance<TextEditor>().firstOrNull()
    }
}
