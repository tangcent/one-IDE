package com.oneide.services.cluster

import com.oneide.models.Role
import com.oneide.services.ClusterService
import com.oneide.utils.Logger

interface IRole {
    fun init()
    fun dispose()
    fun onUserActivity()
    fun onHeartbeat()
}

abstract class BaseRole(protected val cluster: ClusterService) : IRole {
    protected val logger
        get() = Logger.withProject(cluster.project)
    
    protected val actionRegistry: ActionRegistry
        get() = cluster.actionRegistry
    
    protected abstract val role: Role

    override fun dispose() {
        // Default cleanup
    }

    override fun onUserActivity() {
        // Fire user activity action for this role
        actionRegistry.fireAction(role, ActionRegistry.ACTION_USER_ACTIVITY)
    }

    override fun onHeartbeat() {
        // Fire heartbeat action for this role
        actionRegistry.fireAction(role, ActionRegistry.ACTION_HEARTBEAT)
    }
}

class Follower(cluster: ClusterService) : BaseRole(cluster) {
    override val role = Role.FOLLOWER
    private var lastLeaderId: String? = null

    override fun init() {
        logger.info("Role: Follower initialized")
        actionRegistry.fireAction(role, ActionRegistry.ACTION_INIT)
    }

    override fun dispose() {
        super.dispose()
    }

    override fun onUserActivity() {
        // Fire action first
        super.onUserActivity()
        
        // Then handle role-specific logic
        if (!cluster.checkLeaderHealth()) {
            logger.info(
                "User activity detected on Follower and Leader unhealthy -> Switching to Candidate"
            )
            cluster.becomeCandidate()
        }
    }

    override fun onHeartbeat() {
        super.onHeartbeat()
        val leaderInfo = cluster.getLeaderInfo()
        if (leaderInfo != null && leaderInfo.id != lastLeaderId) {
            cluster.getIdeConnector().checkPluginVersion(leaderInfo)
            lastLeaderId = leaderInfo.id
        }
    }
}

class Candidate(cluster: ClusterService) : BaseRole(cluster) {
    override val role = Role.CANDIDATE
    
    override fun init() {
        logger.info("Role: Candidate initialized")
        actionRegistry.fireAction(role, ActionRegistry.ACTION_INIT)
        tryElection()
    }

    override fun onUserActivity() {
        super.onUserActivity()
        tryElection()
    }

    override fun onHeartbeat() {
        super.onHeartbeat()
        tryElection()
    }

    private fun tryElection() {
        if (!cluster.getIdeConnector().isWindowFocused()) {
            logger.info("Window not focused, becoming Follower")
            cluster.becomeFollower()
            return
        }

        logger.info("Candidate attempting election...")
        if (cluster.tryAcquireLeadership()) {
            logger.info("Election successful, becoming Leader")
            cluster.becomeLeader()
        } else {
            logger.info("Election failed, reverting to Follower")
            cluster.becomeFollower()
        }
    }
}

class Leader(cluster: ClusterService) : BaseRole(cluster) {
    override val role = Role.LEADER

    override fun init() {
        logger.info("Role: Leader initialized")
        cluster.updateLeaderHeartbeat()
        actionRegistry.fireAction(role, ActionRegistry.ACTION_INIT)
    }

    override fun onUserActivity() {
        cluster.updateLeaderHeartbeat()
        // Fire action - SyncService listens for this to publish state
        super.onUserActivity()
    }

    override fun onHeartbeat() {
        super.onHeartbeat()
        if (!cluster.checkIfLeaderIsMe()) {
            logger.info(
                "Leader: Found another leader or leader file missing, downgrading to Follower"
            )
            cluster.becomeFollower()
        }
    }
}
