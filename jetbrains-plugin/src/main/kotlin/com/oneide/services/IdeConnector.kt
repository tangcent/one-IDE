package com.oneide.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import com.oneide.models.FileState
import com.oneide.models.FolderState
import com.oneide.models.IdeMetaData
import com.oneide.models.State
import com.oneide.utils.Logger
import java.io.File
import java.nio.file.Paths
import java.util.ArrayDeque

import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
import com.oneide.utils.Debouncer

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
        val rootPathLower = rootPath.lowercase()
        val rootNode = FolderState(rootPath)

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val selectedFiles = fileEditorManager.selectedFiles
        val activePath = if (selectedFiles.isNotEmpty()) selectedFiles[0].path else null
        val activePathLower = activePath?.lowercase()

        for (file in openFiles) {
            val path = file.path
            val pathLower = path.lowercase()
            if (!configService.shouldSyncFile(path)) continue

            // Find or create folder node
            var currentNode = rootNode
            if (pathLower.startsWith(rootPathLower) && pathLower != rootPathLower) {
                val relative = File(path).relativeTo(File(rootPath)).parent
                if (relative != null) {
                    val parts = relative.split(File.separator)
                    var currentPath = rootPath

                    for (part in parts) {
                        if (part.isEmpty()) continue
                        currentPath = currentPath + File.separator + part
                        val currentPathLower = currentPath.lowercase()

                        var next = currentNode.subFolders.find { it.path.lowercase() == currentPathLower }
                        if (next == null) {
                            next = FolderState(currentPath)
                            currentNode.subFolders.add(next)
                        }
                        currentNode = next
                    }
                }
            }
            
            // Get cursor
            var cursor = 0
            var column = 0
            val editor = fileEditorManager.getSelectedEditor(file)
            if (editor is TextEditor) {
                val logicalPosition = editor.editor.caretModel.logicalPosition
                cursor = logicalPosition.line
                column = logicalPosition.column
            }
            
            val isActive = pathLower == activePathLower
            currentNode.openedFiles.add(FileState(path, cursor, column, isActive))

            if (isActive) {
                currentNode.activeFile = path
            }
        }

        return State(
            timestamp = System.currentTimeMillis(),
            source = metaData.id,
            ide = "jetbrains",
            root = rootNode
        )
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

                val filesToOpen = mutableListOf<FileState>()
                val traverseQueue = ArrayDeque<FolderState>()
                traverseQueue.add(state.root)

                while (!traverseQueue.isEmpty()) {
                    val node = traverseQueue.poll()
                    if (projectPath != null) {
                        node.openedFiles.filter { fs ->
                            try {
                                val fPath = Paths.get(fs.filePath).toAbsolutePath().normalize().toString().lowercase()
                                fPath.startsWith(projectPathStr)
                            } catch (_: Exception) {
                                false
                            }
                        }.forEach { filesToOpen.add(it) }
                    } else {
                        filesToOpen.addAll(node.openedFiles)
                    }
                    traverseQueue.addAll(node.subFolders)
                }

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

                    val keep = filesToOpen.any { Paths.get(it.filePath).toAbsolutePath().normalize().toString().lowercase() == Paths.get(file.path).toAbsolutePath().normalize().toString().lowercase() }
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
