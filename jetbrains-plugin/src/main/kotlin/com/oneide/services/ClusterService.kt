package com.oneide.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.Project
import com.oneide.models.*
import com.oneide.services.cluster.roles.*
import com.oneide.utils.Logger
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages the distributed role of this IDE instance within the One-IDE cluster.
 *
 * Implements a Leader Election algorithm using a file-based lock mechanism.
 * The cluster coordinates via a shared directory (~/.one-ide/cluster).
 *
 * Roles:
 * - LEADER: The instance responsible for capturing its state and publishing it to the cluster.
 *           Only the active IDE instance should be the Leader.
 * - FOLLOWER: Listens for state changes published by the Leader and applies them locally.
 * - CANDIDATE: A temporary state when a Follower detects an unhealthy Leader and attempts to become Leader.
 */
class ClusterService(
    oneIdeDir: File,
    private val nodeId: String,
    val project: Project,
    private val ideConnector: IdeConnector,
    private val stateService: StateService
) {
    private val clusterDir: File = File(oneIdeDir, "cluster")
    private val nodesDir: File = File(oneIdeDir, "nodes")

    private var currentRole: IRole? = null
    private var roleType: Role = Role.FOLLOWER

    private val leaderFile: File
    private val myHeartbeatFile: File

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val mapper = jacksonObjectMapper()

    init {
        if (!clusterDir.exists()) clusterDir.mkdirs()
        if (!nodesDir.exists()) nodesDir.mkdirs()

        val nodeDir = File(nodesDir, nodeId)
        if (!nodeDir.exists()) nodeDir.mkdirs()

        leaderFile = File(clusterDir, "leader.json")
        myHeartbeatFile = File(nodeDir, "heartbeat.json")

        // Setup dependencies
        ideConnector.setOnUserActivity { onUserActivity() }
        stateService.setOnStateReceived { state -> onStateReceived(state) }

        startHeartbeat()

        // Start as Follower
        becomeFollower()
    }

    fun getIdeConnector(): IdeConnector = ideConnector
    fun getStateService(): StateService = stateService
    fun getNodeId(): String = nodeId

    @Synchronized
    fun becomeFollower() {
        if (roleType == Role.FOLLOWER && currentRole != null) return
        switchRole(Follower(this), Role.FOLLOWER)
    }

    @Synchronized
    fun becomeCandidate() {
        if (roleType == Role.CANDIDATE && currentRole != null) return
        switchRole(Candidate(this), Role.CANDIDATE)
    }

    @Synchronized
    fun becomeLeader() {
        if (roleType == Role.LEADER && currentRole != null) return
        switchRole(Leader(this), Role.LEADER)
    }

    private fun switchRole(newRole: IRole, type: Role) {
        currentRole?.dispose()
        Logger.info("Switching role to $type", IdeMetaData.getInstance(project))
        roleType = type
        currentRole = newRole
        currentRole?.init()
    }

    fun isLeader(): Boolean = roleType == Role.LEADER

    private fun onUserActivity() {
        currentRole?.onUserActivity()
    }

    private fun onStateReceived(state: State) {
        if (roleType == Role.FOLLOWER) {
            ideConnector.applyState(state)
        }
    }

    private fun startHeartbeat() {
        scheduler.scheduleAtFixedRate({
            try {
                currentRole?.onHeartbeat()
            } catch (e: Exception) {
                // ignore
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    fun updateNodeHeartbeat() {
        val info = NodeInfo(id = nodeId, timestamp = System.currentTimeMillis())
        try {
            myHeartbeatFile.writeText(mapper.writeValueAsString(info))
        } catch (e: Exception) {
        }
    }

    fun updateLeaderHeartbeat() {
        val info =
            NodeInfo(id = nodeId, timestamp = System.currentTimeMillis(), lastHeartbeat = System.currentTimeMillis())
        try {
            leaderFile.writeText(mapper.writeValueAsString(info))
        } catch (e: Exception) {
        }
    }

    fun checkLeaderHealth(): Boolean {
        if (!leaderFile.exists()) return false
        return try {
            val info: NodeInfo = mapper.readValue(leaderFile)
            System.currentTimeMillis() - info.timestamp <= 3000
        } catch (_: Exception) {
            false
        }
    }

    fun checkIfLeaderIsMe(): Boolean {
        if (!leaderFile.exists()) return false
        return try {
            val info: NodeInfo = mapper.readValue(leaderFile)
            info.id == nodeId
        } catch (_: Exception) {
            false
        }
    }

    fun tryAcquireLeadership(): Boolean {
        val lockDir = File(clusterDir, "election.lock")
        if (acquireLock(lockDir)) {
            try {
                if (!checkLeaderHealth()) {
                    updateLeaderHeartbeat()
                    return true
                }
            } finally {
                releaseLock(lockDir)
            }
        }
        return false
    }

    private fun acquireLock(lockDir: File): Boolean {
        try {
            return lockDir.mkdir()
        } catch (e: Exception) {
            try {
                if (System.currentTimeMillis() - lockDir.lastModified() > 5000) {
                    lockDir.delete()
                    return lockDir.mkdir()
                }
            } catch (e2: Exception) {
                return false
            }
            return false
        }
    }

    private fun releaseLock(lockDir: File) {
        try {
            lockDir.delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun dispose() {
        scheduler.shutdown()
        currentRole?.dispose()
    }
}
