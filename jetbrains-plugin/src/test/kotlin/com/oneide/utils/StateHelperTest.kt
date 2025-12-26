package com.oneide.utils

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.oneide.models.ActiveFile
import com.oneide.models.EditorState
import com.oneide.models.IdeMetaData
import com.oneide.models.State
import org.junit.Test
import java.nio.file.Paths

/**
 * Unit tests for StateHelper.
 */
class StateHelperTest : BasePlatformTestCase() {

    private lateinit var metaData: IdeMetaData

    override fun setUp() {
        super.setUp()
        metaData = IdeMetaData(project)
    }

    @Test
    fun `test buildState builds a flat state with normalized paths`() {
        val rootPath = "/User/Project"
        val openedFiles = listOf(
            "/User/Project/src/main.kt",
            "/User/Project/README.md"
        )
        val activeFile = ActiveFile(filePath = "/User/Project/src/main.kt", cursor = 10, column = 5)

        val state = StateHelper.buildState(metaData, rootPath, openedFiles, activeFile)

        val rootPathLower = Paths.get(rootPath).toAbsolutePath().normalize().toString().lowercase()
        assertEquals(rootPathLower, state.root)
        assertEquals(2, state.editorState.openedFiles.size)

        val file1 = state.editorState.openedFiles.find { it.endsWith("main.kt") }
        assertNotNull(file1)
        val expectedPath1 = Paths.get("/User/Project/src/main.kt").toAbsolutePath().normalize().toString().lowercase()
        assertEquals(expectedPath1, file1)

        val file2 = state.editorState.openedFiles.find { it.endsWith("readme.md") }
        assertNotNull(file2)
        val expectedPath2 = Paths.get("/User/Project/README.md").toAbsolutePath().normalize().toString().lowercase()
        assertEquals(expectedPath2, file2)
        
        val active = state.editorState.activeFile
        assertNotNull(active)
        assertEquals(expectedPath1, active!!.filePath)
        assertEquals(10, active.cursor)
    }

    @Test
    fun `test buildState filters out files outside of root`() {
        val rootPath = "/User/Project"
        val openedFiles = listOf(
            "/User/Project/in.kt",
            "/User/Other/out.kt"
        )

        val state = StateHelper.buildState(metaData, rootPath, openedFiles, null)
        assertEquals(1, state.editorState.openedFiles.size)
        assertTrue(state.editorState.openedFiles[0].contains("in.kt"))
    }

    @Test
    fun `test getFiles returns files belonging to root`() {
        val rootPath = "/User/Project"
        val rootPathLower = Paths.get(rootPath).toAbsolutePath().normalize().toString().lowercase()
        
        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test",
            ide = "jetbrains",
            root = rootPathLower,
            editorState = EditorState(
                openedFiles = mutableListOf(
                    Paths.get("/User/Project/file1.kt").toAbsolutePath().normalize().toString().lowercase(),
                    Paths.get("/User/Other/file2.kt").toAbsolutePath().normalize().toString().lowercase()
                ),
                activeFile = ActiveFile(
                     filePath = Paths.get("/User/Project/file1.kt").toAbsolutePath().normalize().toString().lowercase(),
                     cursor = 0
                )
            )
        )

        val files = StateHelper.getFiles(state, rootPath)
        assertEquals(1, files.size)
        assertTrue(files[0].endsWith("file1.kt"))
    }

    @Test
    fun `test getFiles handles empty state`() {
        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test",
            ide = "jetbrains",
            root = "/root",
            editorState = EditorState(
                openedFiles = mutableListOf()
            )
        )
        val files = StateHelper.getFiles(state, "/root")
        assertEquals(0, files.size)
    }

    @Test
    fun `test isInsideRoot`() {
        val root = "/User/Project"
        val fileInside = "/User/Project/src/file.kt"
        val fileOutside = "/User/Other/file.kt"
        val filePartial = "/User/ProjectSuffix/file.kt"

        assertTrue(StateHelper.isInsideRoot(root, fileInside))
        assertFalse(StateHelper.isInsideRoot(root, fileOutside))
        assertFalse(StateHelper.isInsideRoot(root, filePartial))
    }

    @Test
    fun `test checkPathBelongsToState`() {
        val rootPath = "/User/Project"
        val rootPathLower = Paths.get(rootPath).toAbsolutePath().normalize().toString().lowercase()
        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test",
            ide = "jetbrains",
            root = rootPathLower,
            editorState = EditorState(
                openedFiles = mutableListOf()
            )
        )

        assertTrue(StateHelper.checkPathBelongsToState(state, "/User/Project/src/file.kt"))
        assertFalse(StateHelper.checkPathBelongsToState(state, "/User/Other/file.kt"))
    }

    @Test
    fun `test hasIntersection`() {
        assertTrue(StateHelper.hasIntersection("/User/Project", "/User/Project/Sub"))
        assertTrue(StateHelper.hasIntersection("/User/Project/Sub", "/User/Project"))
        assertFalse(StateHelper.hasIntersection("/User/Project", "/User/Other"))

        val rootPath = "/User/Project"
        val rootPathLower = Paths.get(rootPath).toAbsolutePath().normalize().toString().lowercase()
        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test",
            ide = "jetbrains",
            root = rootPathLower,
            editorState = EditorState(
                openedFiles = mutableListOf()
            )
        )

        assertTrue(StateHelper.hasIntersection(state, "/User/Project/Sub"))
        assertFalse(StateHelper.hasIntersection(state, "/User/Other"))
    }
}
