package com.oneide.utils

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.oneide.models.FileState
import com.oneide.models.FolderState
import com.oneide.models.IdeMetaData
import com.oneide.models.State
import org.junit.Test
import java.nio.file.Paths

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
            FileState(filePath = "/User/Project/src/main.kt", cursor = 10, column = 5),
            FileState(filePath = "/User/Project/README.md", cursor = 0, column = 0)
        )
        val activePath = "/User/Project/src/main.kt"

        val state = StateHelper.buildState(metaData, rootPath, openedFiles, activePath)

        val rootPathLower = Paths.get(rootPath).toAbsolutePath().normalize().toString().lowercase()
        assertEquals(rootPathLower, state.root.path)
        assertEquals(2, state.root.openedFiles.size)

        val file1 = state.root.openedFiles.find { it.filePath.endsWith("main.kt") }
        assertNotNull(file1)
        assertTrue(file1!!.isActive)
        val expectedPath1 = Paths.get("/User/Project/src/main.kt").toAbsolutePath().normalize().toString().lowercase()
        assertEquals(expectedPath1, file1.filePath)

        val file2 = state.root.openedFiles.find { it.filePath.endsWith("readme.md") }
        assertNotNull(file2)
        assertFalse(file2!!.isActive)
        val expectedPath2 = Paths.get("/User/Project/README.md").toAbsolutePath().normalize().toString().lowercase()
        assertEquals(expectedPath2, file2.filePath)
    }

    @Test
    fun `test buildState filters out files outside of root`() {
        val rootPath = "/User/Project"
        val openedFiles = listOf(
            FileState(filePath = "/User/Project/in.kt", cursor = 0),
            FileState(filePath = "/User/Other/out.kt", cursor = 0)
        )

        val state = StateHelper.buildState(metaData, rootPath, openedFiles, null)
        assertEquals(1, state.root.openedFiles.size)
        assertTrue(state.root.openedFiles[0].filePath.contains("in.kt"))
    }

    @Test
    fun `test getFiles returns files belonging to root`() {
        val rootPath = "/User/Project"
        val rootPathLower = Paths.get(rootPath).toAbsolutePath().normalize().toString().lowercase()
        
        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test",
            ide = "jetbrains",
            root = FolderState(
                path = rootPathLower,
                openedFiles = mutableListOf(
                    FileState(
                        filePath = Paths.get("/User/Project/file1.kt").toAbsolutePath().normalize().toString().lowercase(),
                        cursor = 0,
                        isActive = true
                    ),
                    FileState(
                        filePath = Paths.get("/User/Other/file2.kt").toAbsolutePath().normalize().toString().lowercase(),
                        cursor = 0,
                        isActive = false
                    )
                )
            )
        )

        val files = StateHelper.getFiles(state, rootPath)
        assertEquals(1, files.size)
        assertTrue(files[0].filePath.endsWith("file1.kt"))
    }

    @Test
    fun `test getFiles handles empty state`() {
        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test",
            ide = "jetbrains",
            root = FolderState(
                path = "/root",
                openedFiles = mutableListOf()
            )
        )
        val files = StateHelper.getFiles(state, "/root")
        assertEquals(0, files.size)
    }
}
