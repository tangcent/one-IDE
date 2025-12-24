package com.oneide.utils

import com.oneide.models.FileState
import com.oneide.models.FolderState
import com.oneide.models.State
import com.oneide.models.IdeMetaData
import java.nio.file.Paths

/**
 * Helper class for building and manipulating State objects.
 */
object StateHelper {

    /**
     * Builds a flattened State object where all opened files are directly under the root FolderState.
     * All paths are converted to lowercase and normalized.
     *
     * @param metaData The metadata of the current IDE instance
     * @param rootPath The root path of the workspace
     * @param openedFiles List of opened files with their cursor/selection info
     * @param activePath The currently active file path
     * @return A State object
     */
    fun buildState(
        metaData: IdeMetaData,
        rootPath: String,
        openedFiles: List<FileState>,
        activePath: String?
    ): State {
        val rootPathLower = rootPath.normalizePath()
        val activePathLower = activePath?.normalizePath()

        val fileStates = openedFiles
            .filter { it.filePath.normalizePath().startsWith(rootPathLower) }
            .map { f ->
                val fsPathLower = f.filePath.normalizePath()
                FileState(
                    filePath = fsPathLower,
                    cursor = f.cursor,
                    column = f.column,
                    isActive = fsPathLower == activePathLower
                )
            }.toMutableList()

        val rootNode = FolderState(
            path = rootPathLower,
            openedFiles = fileStates
        )

        return State(
            timestamp = System.currentTimeMillis(),
            source = metaData.id,
            ide = metaData.ide,
            root = rootNode
        )
    }

    /**
     * Extracts a list of files from the state that belong to the given root path.
     *
     * @param state The state object
     * @param rootPath The root path of the current workspace
     * @return List of FileState objects
     */
    fun getFiles(state: State, rootPath: String): List<FileState> {
        val normalizedRootPath = rootPath.normalizePath()

        return state.root.openedFiles.filter { f ->
            val fPath = f.filePath.normalizePath()
            fPath.startsWith(normalizedRootPath)
        }
    }

    /**
     * Checks if two paths have an intersection (one is a parent of the other or they are the same).
     *
     * @param pathOrState1 First path or State object
     * @param path2 Second path
     * @return True if there is an intersection
     */
    fun hasIntersection(pathOrState1: Any, path2: String): Boolean {
        val p1Raw = if (pathOrState1 is State) pathOrState1.root.path else pathOrState1.toString()
        val p1 = p1Raw.normalizePath()
        val p2 = path2.normalizePath()
        return p1.startsWith(p2) || p2.startsWith(p1)
    }

    /**
     * Checks if a path is inside the root path.
     *
     * @param rootPath The root path
     * @param filePath The file path to check
     * @return True if the file is inside the root
     */
    fun isInsideRoot(rootPath: String, filePath: String): Boolean {
        val root = rootPath.normalizePath()
        val file = filePath.normalizePath()
        // Simple path comparison as requested, but ensuring directory boundary
        return file.startsWith(root) && (file.length == root.length || file[root.length] == java.io.File.separatorChar)
    }

    /**
     * Checks if the given path belongs to the root of the state.
     *
     * @param state The state object
     * @param filePath The file path to check
     * @return True if the file belongs to the state root
     */
    fun checkPathBelongsToState(state: State, filePath: String): Boolean {
        return isInsideRoot(state.root.path, filePath)
    }

    /**
     * Normalizes a file path to its absolute, normalized, and lowercase form.
     *
     * @return The normalized file path
     */
    private fun String.normalizePath() = Paths.get(this).toAbsolutePath().normalize().toString().lowercase()
}
