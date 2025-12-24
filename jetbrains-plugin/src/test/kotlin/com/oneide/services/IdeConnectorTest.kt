package com.oneide.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.oneide.models.FolderState
import com.oneide.models.State
import com.oneide.utils.Debouncer
import com.intellij.util.ui.UIUtil
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for IdeConnector.
 */
class IdeConnectorTest : BasePlatformTestCase() {

    private lateinit var ideConnector: IdeConnector
    private lateinit var configService: ConfigService
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("one-ide-test")
        configService = ConfigService(tempDir)
        ideConnector = IdeConnector(project, configService)
        println("Project BasePath: ${project.basePath}")
    }

    override fun tearDown() {
        configService.dispose()
        super.tearDown()
    }

    /**
     * Verifies that applyState uses a debouncer to prevent rapid successive calls.
     */
    fun testApplyStateUsesDebouncer() {
        val testDebouncer = TestDebouncer()
        ideConnector.applyStateDebouncer = testDebouncer

        val state1 = State(
            timestamp = System.currentTimeMillis(),
            source = "test-source-1",
            ide = "test-ide",
            root = FolderState("/")
        )

        val state2 = State(
            timestamp = System.currentTimeMillis(),
            source = "test-source-2",
            ide = "test-ide",
            root = FolderState("/")
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

        assertEquals("Debounce should be called twice", 2, testDebouncer.callCount)
        assertNotNull("Last action should be captured", testDebouncer.lastAction)

        // Execute the last action (simulating the debounce delay passing)
        testDebouncer.lastAction?.invoke()

        // Wait for invokeLater to run. 
        UIUtil.dispatchAllInvocationEvents()
        
        assertEquals("Second call should complete", 0, latch2.count)
        
        // And latch1 should NOT be completed (because we didn't run the first action)
        assertEquals("First call should not complete", 1, latch1.count)
    }

    class TestDebouncer : Debouncer() {
        var lastAction: (() -> Unit)? = null
        var callCount = 0

        override fun debounce(delay: Long, action: () -> Unit) {
            lastAction = action
            callCount++
        }
    }
}

