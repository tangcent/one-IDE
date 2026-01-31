package com.oneide.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.oneide.models.*
import com.oneide.OneIde
import com.oneide.services.cluster.ClusterConstants
import com.oneide.services.cluster.ActionRegistry
import com.oneide.services.cluster.Candidate
import com.oneide.services.cluster.Follower
import com.oneide.services.cluster.IRole
import com.oneide.services.cluster.Leader
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
@Service(Service.Level.PROJECT)
class ClusterService(
    val project: Project
) : Disposable {
    private val logger = Logger.withProject(project)
    private val clusterDir: File = OneIde.oneIdeDir.resolve("cluster").toFile()
    private val metaData = IdeMetaData.getInstance(project)
    private val configService = ConfigService.getInstance(project)
    private val ideConnector: IdeConnector = IdeConnector(project, configService)
    private val nodeId: String = metaData.id

    private var currentRole: IRole? = null
    private var roleType: Role = Role.FOLLOWER

    private val roleChangeListeners = mutableSetOf<(role: Role) -> Unit>()
    
    /**
     * Action registry for role-based actions.
     * Business services register actions, roles fire them.
     */
    val actionRegistry = ActionRegistry()

    private val leaderFile: File

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val mapper = jacksonObjectMapper()

    init {
        if (!clusterDir.exists()) clusterDir.mkdirs()

        leaderFile = File(clusterDir, "leader.json")

        // Setup IdeConnector to notify roles of user activity
        ideConnector.setOnUserActivity { currentRole?.onUserActivity() }

        startHeartbeat()

        // Start as Follower
        becomeFollower()
    }

    fun getIdeConnector(): IdeConnector = ideConnector
    fun getNodeId(): String = nodeId
    fun getRoleType(): Role = roleType

    fun addRoleChangeListener(listener: (role: Role) -> Unit): () -> Unit {
        roleChangeListeners.add(listener)
        return {
            roleChangeListeners.remove(listener)
        }
    }

    /**
     * Register an action listener for a specific role and action name.
     * Delegates to ActionRegistry.
     * 
     * @param role The role to listen for, or null to listen for all roles
     * @param actionName The name of the action to listen for (e.g., "userActivity", "heartbeat")
     * @param action The callback to invoke when the action occurs
     * @return An unsubscribe function to remove the listener
     */
    fun addAction(role: Role?, actionName: String, action: () -> Unit): () -> Unit {
        return actionRegistry.addAction(role, actionName, action)
    }

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
        logger.info("Switching role to $type")
        roleType = type
        currentRole = newRole
        currentRole?.init()
        for (listener in roleChangeListeners) {
            try {
                listener(type)
            } catch (e: Exception) {
                logger.error("Role change listener error", e)
            }
        }
    }

    private fun startHeartbeat() {
        scheduler.scheduleAtFixedRate({
            try {
                currentRole?.onHeartbeat()
            } catch (e: Exception) {
                // ignore
            }
        }, 0, ClusterConstants.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS)
    }

    fun updateLeaderHeartbeat() {
        val pluginVersion = metaData.pluginVersion
        val info =
            NodeInfo(
                id = nodeId,
                timestamp = System.currentTimeMillis(),
                lastHeartbeat = System.currentTimeMillis(),
                pluginVersion = pluginVersion,
                ide = metaData.ide
            )
        try {
            leaderFile.writeText(mapper.writeValueAsString(info))
        } catch (_: Exception) {
        }
    }

    fun getLeaderInfo(): NodeInfo? {
        if (!leaderFile.exists()) return null
        return try {
            val info: NodeInfo = mapper.readValue(leaderFile)
            info.takeIf { it.isHealthy() }
        } catch (_: Exception) {
            null
        }
    }

    fun checkLeaderHealth(): Boolean {
        if (!leaderFile.exists()) return false
        return try {
            val info: NodeInfo = mapper.readValue(leaderFile)
            info.isHealthy()
        } catch (_: Exception) {
            false
        }
    }

    private fun NodeInfo.isHealthy(): Boolean {
        return System.currentTimeMillis() - lastHeartbeat <= ClusterConstants.LEADER_TIMEOUT
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
        } catch (_: Exception) {
            try {
                if (System.currentTimeMillis() - lockDir.lastModified() > ClusterConstants.LOCK_STALE_TIMEOUT) {
                    lockDir.delete()
                    return lockDir.mkdir()
                }
            } catch (_: Exception) {
                return false
            }
            return false
        }
    }

    private fun releaseLock(lockDir: File) {
        try {
            lockDir.delete()
        } catch (_: Exception) {
            // ignore
        }
    }

    override fun dispose() {
        scheduler.shutdown()
        currentRole?.dispose()
        roleChangeListeners.clear()
        actionRegistry.clear()
    }

    companion object {
        fun getInstance(project: Project): ClusterService = project.getService(ClusterService::class.java)
    }
}
