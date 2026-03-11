package com.oneide.services

import com.intellij.psi.PsiFileSystemItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.oneide.models.*
import com.oneide.utils.Debouncer
import com.oneide.utils.Logger
import com.oneide.utils.StateHelper
import java.nio.file.Paths
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.WindowManager
import com.oneide.utils.StateHelper.normalizePath
import kotlin.math.log

/**
 * Service responsible for connecting the IDE events with the synchronization logic.
 * It listens for user activities (file selection, edits) and captures/applies the IDE state.
 */
class IdeConnector(private val project: Project, private val configService: ConfigService) {
    private val metaData = IdeMetaData.getInstance(project)
    private val logger = Logger.withMetaData(metaData)

    // Callback to be invoked when user activity is detected
    private var onUserActivityCallback: (() -> Unit)? = null

    // Flag to prevent triggering activity while applying state from external source
    private var isApplyingState = false

    // Debouncer for user activity (typing, selection changes) -> Outbound
    private val debouncer = Debouncer(300)

    // Debouncer for applying state (received from other IDEs) -> Inbound
    internal var applyStateDebouncer = Debouncer(300)

    private var activeNotification: Notification? = null

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
     * Skips if currently applying state or if there are unsaved documents
     * (which may indicate AI tools are making edits).
     */
    fun triggerActivity() {
        if (isApplyingState) return
        
        // Skip triggering activity if there are unsaved/modified documents
        // This prevents interference with AI coding tools that may be making edits
        val fileDocumentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val hasUnsavedChanges = fileDocumentManager.unsavedDocuments.isNotEmpty()
        
        if (hasUnsavedChanges) {
            return
        }
        
        debouncer.debounce {
            onUserActivityCallback?.invoke()
        }
    }



    /**
     * Captures the current state of the IDE (opened files, active file, cursor positions).
     */
    fun captureState(): State {
        // 1. Capture Editor State (Read Action)
        return ApplicationManager.getApplication().runReadAction(Computable {
            val rootPath = project.basePath ?: ""
            val editorState = captureEditorState()

            StateHelper.buildState(
                metaData,
                rootPath,
                editorState.openedFiles,
                editorState.activeFile
            )
        })
    }

    private fun captureEditorState(): EditorState {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val selectedFiles = fileEditorManager.selectedFiles
        val activePath = if (selectedFiles.isNotEmpty()) selectedFiles[0].path else null

        val openedFilesList = mutableListOf<String>()
        var activeFile: ActiveFile? = null

        for (file in openFiles) {
            val path = file.path
            if (configService.shouldSyncFile(path)) {
                openedFilesList.add(path)

                if (path == activePath) {
                    // Get cursor for active file
                    var cursor = 0
                    var column = 0
                    var selectionEndCursor: Int? = null
                    var selectionEndColumn: Int? = null
                    val editor = fileEditorManager.getSelectedEditor(file)
                    if (editor is TextEditor) {
                        val selectionModel = editor.editor.selectionModel
                        if (selectionModel.hasSelection()) {
                            val startOffset = selectionModel.selectionStart
                            val endOffset = selectionModel.selectionEnd

                            val startPos = editor.editor.offsetToLogicalPosition(startOffset)
                            val endPos = editor.editor.offsetToLogicalPosition(endOffset)

                            cursor = startPos.line
                            column = startPos.column
                            selectionEndCursor = endPos.line
                            selectionEndColumn = endPos.column
                        } else {
                            val logicalPosition = editor.editor.caretModel.logicalPosition
                            cursor = logicalPosition.line
                            column = logicalPosition.column
                        }
                    }
                    activeFile = ActiveFile(
                        filePath = path,
                        cursor = cursor,
                        column = column,
                        selectionEndCursor = selectionEndCursor,
                        selectionEndColumn = selectionEndColumn
                    )
                }
            }
        }
        return EditorState(openedFilesList, activeFile)
    }

    /**
     * Applies the received state to the IDE.
     * This involves opening files, closing irrelevant files, and moving the cursor.
     */
    fun applyState(state: State, onComplete: (() -> Unit) = {}) {
        applyStateDebouncer.debounce {
            logger.info("Applying state from ${state.source}")
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
                        logger.info(
                            "Skipping apply state: Project path $projectPathStr has no intersection with state root ${state.root}"
                        )
                        return@invokeLater
                    }

                    // 2. Apply Editor State
                    applyEditorState(state, projectPathStr)

                } catch (e: Exception) {
                    logger.error("Error applying state", e)
                } finally {
                    isApplyingState = false
                    onComplete()
                }
            }
        }
    }

    private fun applyEditorState(state: State, projectPathStr: String) {
        val filesToOpen = StateHelper.getFiles(state, projectPathStr)
        val activeFile = StateHelper.getActiveFile(state, projectPathStr)

        val fileEditorManager = FileEditorManager.getInstance(project)

        // 2. Close files not in state
        // We only close files that are part of the project but not in the new state.
        val currentOpenFiles = fileEditorManager.openFiles
        for (file in currentOpenFiles) {
            // Check if file belongs to current project root AND the incoming state's scope
            // If the file is outside the scope of the incoming state (e.g. syncing a subfolder),
            // we should not close it as the incoming state has no authority over it.
            if (!StateHelper.isInsideRoot(
                    projectPathStr,
                    file.path
                ) || !StateHelper.checkPathBelongsToState(state, file.path)
            ) {
                continue
            }

            // Skip files with unsaved changes — these may be pending edits from
            // AI coding tools (e.g. Claude Code, Copilot) and closing them would
            // abort the edit operation.
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
            if (document != null && com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                logger.info("Skipping close for modified file: ${file.path}")
                continue
            }

            val fileNorm = file.path.normalizePath()
            val keep = filesToOpen.contains(fileNorm)
            if (!keep) {
                logger.info("Closing file: ${file.path}")
                fileEditorManager.closeFile(file)
            }
        }


        // 3. Open or Update files
        // Iterate through files in the state and open/activate/scroll them.
        val localFileSystem = LocalFileSystem.getInstance()
        for (filePath in filesToOpen) {
            val virtualFile = localFileSystem.findFileByPath(filePath)
            if (virtualFile != null) {
                // 3.1 Open if need open
                if (!fileEditorManager.isFileOpen(virtualFile)) {
                    logger.info("Opening file: $filePath")
                    fileEditorManager.openFile(virtualFile, false)
                }
            }
        }

        // 4. Activate file and move cursor
        if (activeFile != null) {
            val virtualFile = localFileSystem.findFileByPath(activeFile.filePath)
            if (virtualFile != null) {
                logger.info("Activating file: ${activeFile.filePath}")
                fileEditorManager.openFile(virtualFile, true)

                val textEditor = fileEditorManager.getTextEditor(virtualFile)
                if (textEditor != null) {
                    val caretModel = textEditor.editor.caretModel
                    val scrollingModel = textEditor.editor.scrollingModel
                    val selectionModel = textEditor.editor.selectionModel

                    val newCursor = activeFile.cursor
                    val newColumn = activeFile.column
                    val newEndCursor = activeFile.selectionEndCursor
                    val newEndColumn = activeFile.selectionEndColumn

                    if (newEndCursor != null && newEndColumn != null) {
                        // Apply selection
                        val startPos = com.intellij.openapi.editor.LogicalPosition(newCursor, newColumn)
                        val endPos = com.intellij.openapi.editor.LogicalPosition(newEndCursor, newEndColumn)

                        val startOffset = textEditor.editor.logicalPositionToOffset(startPos)
                        val endOffset = textEditor.editor.logicalPositionToOffset(endPos)

                        if (selectionModel.selectionStart != startOffset || selectionModel.selectionEnd != endOffset) {
                            logger.info("Setting selection to $newCursor:$newColumn - $newEndCursor:$newEndColumn")
                            selectionModel.setSelection(startOffset, endOffset)
                            caretModel.moveToLogicalPosition(endPos)
                            scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                        }
                    } else {
                        if (caretModel.logicalPosition.line != newCursor || caretModel.logicalPosition.column != newColumn) {
                            logger.info(
                                "Moving cursor to $newCursor:$newColumn in ${activeFile.filePath}"
                            )
                            caretModel.moveToLogicalPosition(
                                com.intellij.openapi.editor.LogicalPosition(
                                    newCursor,
                                    newColumn
                                )
                            )
                            selectionModel.removeSelection()
                            scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                        }
                    }
                } else {
                    logger.warn("TextEditor is null for $virtualFile")
                }
            }
        }
    }

    fun checkPluginVersion(remoteNode: NodeInfo?) {
        val remoteVersion = remoteNode?.pluginVersion
        val currentVersion = metaData.pluginVersion

        if (remoteVersion != currentVersion) {
            if (activeNotification?.isExpired == false) {
                return
            }

            val remoteIde = if (remoteNode?.ide != null) " (${remoteNode.ide})" else "Other IDE"
            logger.info("Plugin version mismatch, remote: $remoteVersion$remoteIde, local: $currentVersion")

            val title = "One-IDE Plugin Version Mismatch"
            val content = "$remoteIde: $remoteVersion, Local: $currentVersion"

            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("OneIDE Notification Group")
                .createNotification(title, content, NotificationType.WARNING)

            if (StringUtil.compareVersionNumbers(remoteVersion, currentVersion) > 0) {
                notification.addAction(object : AnAction("Update Plugin") {
                    override fun actionPerformed(e: AnActionEvent) {
                        try {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins")
                        } catch (e: Exception) {
                            BrowserUtil.browse("https://plugins.jetbrains.com/plugin/23842-one-ide")
                        }
                        notification.expire()
                    }
                })
            }
            activeNotification = notification
            notification.whenExpired {
                if (activeNotification == notification) {
                    activeNotification = null
                }
            }
            notification.notify(project)
        }
    }

    private fun FileEditorManager.getTextEditor(file: VirtualFile): TextEditor? {
        return getEditors(file).filterIsInstance<TextEditor>().firstOrNull()
    }
}
