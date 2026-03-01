package com.oneide.services.cluster

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.oneide.OneIde
import com.oneide.models.Role
import com.oneide.services.ClusterService
import org.junit.Test
import java.nio.file.Path

/**
 * Tests for Leader role behavior, specifically verifying that
 * the leader steps down when the IDE window loses focus.
 *
 * In a test environment, isWindowFocused() returns false (no real window),
 * which directly validates the focus-check fix.
 */
class LeaderRoleTest : BasePlatformTestCase() {

    private lateinit var oneIdeDir: Path
    private lateinit var clusterService: ClusterService

    override fun setUp() {
        super.setUp()
        oneIdeDir = createTempDir("one-ide-test").toPath()
        OneIde.oneIdeDir = oneIdeDir
        clusterService = project.getService(ClusterService::class.java)
    }

    @Test
    fun `test leader steps down on heartbeat when window not focused`() {
        // Force transition to Leader
        clusterService.becomeLeader()
        assertEquals("Should be Leader", Role.LEADER, clusterService.getRoleType())

        // Simulate a heartbeat - in test env, window is not focused
        // so the leader should step down to Follower
        clusterService.becomeLeader()
        val leader = Leader(clusterService)
        leader.onHeartbeat()

        // After heartbeat with unfocused window, should have stepped down
        assertEquals("Should step down to Follower", Role.FOLLOWER, clusterService.getRoleType())
    }

    @Test
    fun `test leader updates heartbeat on user activity`() {
        clusterService.becomeLeader()
        val leader = Leader(clusterService)

        // Should not throw - verifies updateLeaderHeartbeat works
        leader.onUserActivity()

        // Verify heartbeat was written via the service's own cluster directory
        assertNotNull("Leader info should exist after user activity", clusterService.getLeaderInfo())
    }
}
