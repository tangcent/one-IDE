package com.oneide.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.oneide.models.IdeMetaData
import com.oneide.models.State
import com.oneide.models.FolderState
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StateServiceTest : BasePlatformTestCase() {

    private lateinit var oneIdeDir: Path
    private lateinit var stateService: StateService
    private lateinit var metaData: IdeMetaData

    override fun setUp() {
        super.setUp()
        oneIdeDir = createTempDir("one-ide-test").toPath()
        metaData = IdeMetaData(project)
        stateService = StateService(oneIdeDir, metaData)
    }

    override fun tearDown() {
        stateService.dispose()
        super.tearDown()
    }

    @Test
    fun `test state publication and observation`() {
        val latch = CountDownLatch(1)
        var receivedState: State? = null

        stateService.setOnStateReceived { state ->
            receivedState = state
            latch.countDown()
        }

        stateService.startWatching()
        
        // Wait for watcher to start
        Thread.sleep(200)

        val state = State(
            timestamp = System.currentTimeMillis(),
            source = "test-source",
            ide = "test-ide",
            root = FolderState("/")
        )

        stateService.publishState(state, "leader-id")

        assertTrue("Should receive state update", latch.await(5, TimeUnit.SECONDS))
        assertNotNull("Received state should not be null", receivedState)
        assertEquals("Source should match", state.source, receivedState?.source)
    }
}
