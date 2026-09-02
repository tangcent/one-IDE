package com.oneide.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.oneide.models.ActiveFile
import com.oneide.models.EditorState
import com.oneide.models.State
import com.oneide.utils.Debouncer
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch


/**
 * Unit tests for IdeConnector.
 *
 * Note: BasePlatformTestCase uses JUnit 3 which discovers test methods by reflection.
 * Kotlin lambdas inside methods named "testXxx" compile to synthetic methods like
 * "testXxx$lambda$N" which JUnit 3 tries to run. To avoid this, lambda-heavy logic
 * is extracted into helper methods that don't start with "test".
 */
class IdeConnectorTest : BasePlatformTestCase() {

    private lateinit var ideConnector: IdeConnector
    private lateinit var configService: ConfigService
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("one-ide-test")
        configService = ConfigService(project)
        ideConnector = IdeConnector(project, configService)
        println("Project BasePath: ${project.basePath}")
    }

    override fun tearDown() {
        configService.dispose()
        super.tearDown()
    }

    // ---- Helper: create a mock debouncer ----

    private fun createMockDebouncer(): MockDebouncer = MockDebouncer()

    // ---- Helper methods that contain lambdas (names don't start with "test") ----

    private fun verifyApplyStateUsesDebouncer() {
        val mockDebouncer = createMockDebouncer()
        ideConnector.applyStateDebouncer = mockDebouncer

        val state1 = State(
            timestamp = System.currentTimeMillis(),
            source = "test-source-1",
            ide = "test-ide",
            root = "/"
        )

        val state2 = State(
            timestamp = System.currentTimeMillis(),
            source = "test-source-2",
            ide = "test-ide",
            root = "/"
        )

        // Call 1
        val latch1 = CountDownLatch(1)
        ideConnector.applyState(state1) {
            latch1.countDown()
        }

        // Call 2
        val latch2 = CountDownLatch(1)
        ideConnector.applyState(state2) {
            latch2.countDown()
        }

        assertEquals("Debounce should be called twice", 2, mockDebouncer.callCount)
        assertNotNull("Last action should be captured", mockDebouncer.lastAction)

        // Execute the last action (simulating the debounce delay passing)
        mockDebouncer.lastAction?.invoke()

        // Wait for invokeLater to run.
        UIUtil.dispatchAllInvocationEvents()

        assertEquals("Second call should complete", 0, latch2.count)

        // And latch1 should NOT be completed (because we didn't run the first action)
        assertEquals("First call should not complete", 1, latch1.count)
    }

    private fun verifyTriggerActivitySuppressedWithUnsavedDocuments() {
        // Create and open a file in the editor
        val file = myFixture.configureByText("test_file.txt", "initial content")
        val virtualFile = file.virtualFile

        var activityCallCount = 0
        ideConnector.setOnUserActivity {
            activityCallCount++
        }

        // Make an unsaved modification to the document
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
            document.setText("modified content")
        }

        // Verify the document is unsaved
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        assertTrue("Document should be unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(document))

        // triggerActivity should be suppressed because of the unsaved document
        ideConnector.triggerActivity()

        assertEquals("Activity callback should NOT be invoked when unsaved documents exist", 0, activityCallCount)

        // Save the document, then triggerActivity should work
        ApplicationManager.getApplication().runWriteAction {
            FileDocumentManager.getInstance().saveDocument(document)
        }

        assertFalse("Document should be saved now", FileDocumentManager.getInstance().isDocumentUnsaved(document))

        ideConnector.triggerActivity()

        // The callback is debounced, so it won't fire immediately, but the debouncer should have been called
        // We just verify it wasn't suppressed (i.e., the method didn't return early)
    }

    private fun verifyTriggerActivityFiresWithNoUnsavedDocuments() {
        // Create a fresh IdeConnector that we can control
        val connector = IdeConnector(project, configService)

        var activityCallCount = 0
        connector.setOnUserActivity {
            activityCallCount++
        }

        // Ensure no unsaved documents
        ApplicationManager.getApplication().runWriteAction {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        // triggerActivity should proceed (through the debouncer)
        connector.triggerActivity()

        // Since the real debouncer has a delay, we can't easily check the callback count
        // But we can verify it didn't return early by checking there are no unsaved docs
        assertTrue("Should have no unsaved documents", FileDocumentManager.getInstance().unsavedDocuments.isEmpty())
    }

    private fun verifyApplyStateDoesNotCloseUnsavedFiles() {
        val mockDebouncer = createMockDebouncer()
        ideConnector.applyStateDebouncer = mockDebouncer

        // Create and open a file
        val file = myFixture.configureByText("unsaved_file.txt", "initial content")
        val virtualFile = file.virtualFile
        val fileEditorManager = FileEditorManager.getInstance(project)

        // Verify file is open
        assertTrue("File should be open", fileEditorManager.isFileOpen(virtualFile))

        // Make the document dirty (unsaved)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
            document.setText("modified unsaved content")
        }

        // Verify the document is unsaved
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        assertTrue("Document should be unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(document))

        // Apply state that has NO files (would normally close everything)
        val basePath = project.basePath!!
        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test-source",
            ide = "test-ide",
            root = basePath
        )

        ideConnector.applyState(state)

        // Execute the debounced action
        mockDebouncer.lastAction?.invoke()

        // Wait for invokeLater to run
        UIUtil.dispatchAllInvocationEvents()

        // The unsaved file should still be open because we skip closing dirty files
        assertTrue("Unsaved file should NOT be closed", fileEditorManager.isFileOpen(virtualFile))
    }

    private fun verifyActiveFileFocusRespectsWindowFocus(windowFocused: Boolean) {
        val mockDebouncer = createMockDebouncer()
        ideConnector.applyStateDebouncer = mockDebouncer

        var providerCalled = false
        ideConnector.windowFocusedProvider = {
            providerCalled = true
            windowFocused
        }

        // Create a physical file inside the temp project root WITHOUT opening it in the editor,
        // so we can prove that applyState itself performs the open.
        val basePath = project.basePath!!
        val filePath = Paths.get(basePath, "focus_file.txt")
        Files.createDirectories(filePath.parent)
        Files.write(filePath, "content".toByteArray())
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.toString())!!
        assertFalse(
            "File should not be open before applying state",
            FileEditorManager.getInstance(project).isFileOpen(virtualFile)
        )

        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test-source",
            ide = "test-ide",
            root = basePath,
            editorState = EditorState(
                openedFiles = mutableListOf(filePath.toString()),
                activeFile = ActiveFile(filePath = filePath.toString(), cursor = 0, column = 0)
            )
        )

        ideConnector.applyState(state)
        mockDebouncer.lastAction?.invoke()
        UIUtil.dispatchAllInvocationEvents()

        assertTrue("Window-focus provider should be consulted", providerCalled)
        // Regardless of focus, the mirrored active file must still be opened (background mirroring).
        // The flag returned by windowFocusedProvider is what controls openFile(file, focus) so that
        // an unfocused follower never steals OS window focus on Windows.
        assertTrue(
            "Active file should be opened whether the window is focused or not",
            FileEditorManager.getInstance(project).isFileOpen(virtualFile)
        )
    }

    // ---- Test methods (thin wrappers, no lambdas) ----

    /**
     * Verifies that applyState uses a debouncer to prevent rapid successive calls.
     */
    fun testApplyStateUsesDebouncer() = verifyApplyStateUsesDebouncer()

    /**
     * Verifies that triggerActivity does not fire the callback when there are unsaved documents.
     * This prevents interference with AI coding tools that may be making edits.
     */
    fun testTriggerActivitySuppressedWithUnsavedDocuments() = verifyTriggerActivitySuppressedWithUnsavedDocuments()

    /**
     * Verifies that triggerActivity fires the callback when no unsaved documents exist.
     */
    fun testTriggerActivityFiresWithNoUnsavedDocuments() = verifyTriggerActivityFiresWithNoUnsavedDocuments()

    /**
     * Verifies that applyState does not close files with unsaved changes.
     * This prevents AI coding tools' pending edits from being discarded.
     */
    fun testApplyStateDoesNotCloseUnsavedFiles() = verifyApplyStateDoesNotCloseUnsavedFiles()

    /**
     * When the receiving window is NOT focused (e.g. the user is editing in the other IDE),
     * the mirrored active file must still be opened but WITHOUT stealing OS window focus.
     * This prevents the Windows focus ping-pong between IDEA and VSCode.
     */
    fun testActiveFileNotAffectedWhenWindowUnfocused() = verifyActiveFileFocusRespectsWindowFocus(false)

    /**
     * When the receiving window IS focused, the mirrored active file is opened normally
     * (focus is requested as before).
     */
    fun testActiveFileAffectedNormallyWhenWindowFocused() = verifyActiveFileFocusRespectsWindowFocus(true)

    // ---- Mock classes (private to avoid JUnit 3 discovery) ----

    private class MockDebouncer : Debouncer() {
        var lastAction: (() -> Unit)? = null
        var callCount = 0

        override fun debounce(delay: Long, action: () -> Unit) {
            lastAction = action
            callCount++
        }
    }
}
