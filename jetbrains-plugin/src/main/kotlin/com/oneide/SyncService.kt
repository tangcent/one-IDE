package com.oneide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.oneide.utils.Logger
import com.oneide.utils.Debouncer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import com.oneide.models.Config
import com.oneide.models.FileState
import com.oneide.models.FolderState
import com.oneide.models.IdeMetaData
import com.oneide.models.State
import com.oneide.services.ConfigService
import com.oneide.services.StateService
import java.io.File
import java.nio.file.Paths
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class SyncService(private val project: Project) : Disposable {
    val metaData = IdeMetaData.getInstance(project)
    private val pendingState = AtomicReference<State?>()
    private val isProcessingState = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val oneIdeDir = Paths.get(System.getProperty("user.home"), ".one-ide")

    private val configService = ConfigService(oneIdeDir)
    private val stateService = StateService(oneIdeDir, metaData, this::handleStateChange) {
        project.basePath
    }

    private var activeProject: Project? = project

    private val postDebouncer = Debouncer(300)
    private val applyDebouncer = Debouncer(300)

    var isEnabled: Boolean = true
        set(value) {
            field = value
            Logger.info("One-IDE Sync enabled: $value", metaData)
            updateAllStatusBars()
        }

    init {
        Logger.info("One-IDE SyncService initialized. SourceID: ${metaData.id}", metaData)
    }

    override fun dispose() {
        Logger.info("Disposing SyncService for ${project.name}", metaData)
        postDebouncer.cancel()
        applyDebouncer.cancel()
        executor.shutdownNow()
        configService.dispose()
        stateService.dispose()
    }

    companion object {
        fun getInstance(project: Project): SyncService = project.getService(SyncService::class.java)
    }

    fun updateConfig(newConfig: Config) {
        configService.updateConfig(newConfig)
    }

    fun getConfig(): Config {
        return configService.getConfig()
    }

    fun shouldSyncFile(filePath: String): Boolean {
        return configService.shouldSyncFile(filePath)
    }

    fun handleLocalEvent(project: Project) {
        if (!isEnabled || stateService.isSyncing()) return

        activeProject = project

        // Background Check:
        // If window is not focused, and the last sync (from others) was less than 5s ago,
        // we assume this event is a delayed echo or side-effect of the sync, so we ignore it.
        val window = WindowManager.getInstance().suggestParentWindow(project)
        if (window != null && !window.isActive) {
            val timeSinceLastSync = System.currentTimeMillis() - stateService.getLastCheckPoint()
            if (timeSinceLastSync < 5000) {
                // Logger.info("Ignoring background event (Last sync: ${timeSinceLastSync}ms ago)")
                return
            }
        }

        postDebouncer.debounce {
            generateAndSaveState()
        }
    }

    private fun updateAllStatusBars() {
        ApplicationManager.getApplication().invokeLater {
            val projects = ProjectManager.getInstance().openProjects
            for (project in projects) {
                val statusBar = WindowManager.getInstance().getStatusBar(project)
                statusBar?.updateWidget("OneIDEStatusBar")
            }
        }
    }

    private fun generateAndSaveState() {
        val project = activeProject ?: return
        if (project.isDisposed) return

        ApplicationManager.getApplication().invokeLater {
            if (stateService.isSyncing()) return@invokeLater

            val state = buildState(project)

            // Execute IO in background
            ApplicationManager.getApplication().executeOnPooledThread {
                stateService.appendState(state)
            }
        }
    }

    private fun buildState(project: Project): State {
        val rootPath = project.basePath ?: ""
        val rootNode = FolderState(rootPath)

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val selectedFiles = fileEditorManager.selectedFiles
        val activePath = if (selectedFiles.isNotEmpty()) selectedFiles[0].path else null

        for (file in openFiles) {
            val path = file.path
            if (!shouldSyncFile(path)) continue

            // Find or create folder node
            var currentNode = rootNode
            if (path.startsWith(rootPath) && path != rootPath) {
                val relative = File(path).relativeTo(File(rootPath)).parent
                if (relative != null) {
                    val parts = relative.split(File.separator)
                    var currentPath = rootPath

                    for (part in parts) {
                        if (part.isEmpty()) continue
                        currentPath = currentPath + File.separator + part

                        var next = currentNode.subFolders.find { it.path == currentPath }
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
            if (editor is com.intellij.openapi.fileEditor.TextEditor) {
                val logicalPosition = editor.editor.caretModel.logicalPosition
                cursor = logicalPosition.line
                column = logicalPosition.column
            }

            val isActive = path == activePath
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

    private fun handleStateChange(state: State) {
        if (!isEnabled || state.source == metaData.id) return

        pendingState.set(state)

        // Debounce the processing if not already processing
        if (!isProcessingState.get()) {
            applyDebouncer.debounce {
                if (isProcessingState.compareAndSet(false, true)) {
                    // processPendingStates runs in the debounced thread (pooled thread)
                    // We can also submit to our single thread executor if we want strict ordering, 
                    // but the lock protects us.
                    executor.submit {
                        processPendingStates()
                    }
                }
            }
        }
    }

    private fun processPendingStates() {
        try {
            while (true) {
                val state = pendingState.getAndSet(null) ?: break
                applyState(state)
            }
        } finally {
            isProcessingState.set(false)
            // Double check in case a state arrived just as we were exiting
            if (pendingState.get() != null) {
                if (isProcessingState.compareAndSet(false, true)) {
                    executor.submit { processPendingStates() }
                }
            }
        }
    }

    private fun applyState(state: State) {
        Logger.info("Received state from ${state.source}")
        Logger.info("Start syncing...")
        stateService.startSync()

        ApplicationManager.getApplication().invokeAndWait {
            try {
                // Find best project to apply state
                // If we have activeProject, use it. Otherwise guess.
                val project = activeProject ?: ProjectManager.getInstance().openProjects.firstOrNull()
                if (project == null || project.isDisposed) return@invokeAndWait

                val filesToOpen = mutableListOf<FileState>()
                val traverseQueue = ArrayDeque<FolderState>()
                traverseQueue.add(state.root)

                val projectPath = project.basePath?.let { Paths.get(it).toAbsolutePath().normalize() }

                while (!traverseQueue.isEmpty()) {
                    val node = traverseQueue.poll()
                    if (projectPath != null) {
                        node.openedFiles.filter { fs ->
                            try {
                                val fPath = Paths.get(fs.filePath).toAbsolutePath().normalize()
                                fPath.startsWith(projectPath) || projectPath.startsWith(fPath)
                            } catch (e: Exception) {
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
                    // Only close files that are within the state root (project path)
                    if (projectPath != null) {
                        try {
                            val fPath = Paths.get(file.path).toAbsolutePath().normalize()
                            if (!fPath.startsWith(projectPath)) continue
                        } catch (e: Exception) {
                            continue
                        }
                    }

                    val keep = filesToOpen.any { it.filePath == file.path }
                    if (!keep) {
                        fileEditorManager.closeFile(file)
                    }
                }

                // Open or Update files
                for (fileState in filesToOpen) {
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fileState.filePath)
                    if (virtualFile != null) {
                        // Check if needs update
                        var needsUpdate = true
                        if (fileEditorManager.isFileOpen(virtualFile)) {
                            // Already open, check if active/cursor matches
                            // This is a simplification; for strict sync we might want to force it
                            // But checking if we are already at the spot is good UX
                            val editors = fileEditorManager.getEditors(virtualFile)
                            for (editor in editors) {
                                if (editor is com.intellij.openapi.fileEditor.TextEditor) {
                                    val caretModel = editor.editor.caretModel
                                    if (caretModel.logicalPosition.line == fileState.cursor &&
                                        caretModel.logicalPosition.column == fileState.column &&
                                        fileState.isActive // If it needs to be active but isn't, we need update (openFile handles focus)
                                    ) {

                                        if (fileState.isActive) {
                                            val isSelected = fileEditorManager.selectedFiles.contains(virtualFile)
                                            if (isSelected) needsUpdate = false
                                        } else {
                                            needsUpdate = false
                                        }
                                    }
                                }
                            }
                        }

                        if (needsUpdate) {
                            fileEditorManager.openFile(virtualFile, fileState.isActive)

                            if (fileState.cursor >= 0) {
                                val editors = fileEditorManager.getEditors(virtualFile)
                                for (editor in editors) {
                                    if (editor is com.intellij.openapi.fileEditor.TextEditor) {
                                        val caretModel = editor.editor.caretModel
                                        val scrollingModel = editor.editor.scrollingModel

                                        if (caretModel.logicalPosition.line != fileState.cursor || caretModel.logicalPosition.column != fileState.column) {
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
                    }
                }

            } catch (e: Exception) {
                Logger.error("Error applying state", e)
            } finally {
                Logger.info("Sync completed.")
                // Short delay to prevent echo
                Timer(100) {
                    stateService.endSync()
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        }
    }
}
