package com.oneide.services.cluster.roles

import com.oneide.models.IdeMetaData
import com.oneide.models.State
import com.oneide.services.ClusterService
import com.oneide.utils.Logger
import com.oneide.models.NodeInfo

interface IRole {
    fun init()
    fun dispose()
    fun onUserActivity()
    fun onHeartbeat()
}

abstract class BaseRole(protected val cluster: ClusterService) : IRole {
    protected val logger
        get() = Logger.withProject(cluster.project)

    override fun dispose() {
        // Default cleanup
    }

    override fun onUserActivity() {
        // Default: do nothing
    }

    override fun onHeartbeat() {
        // Default: do nothing
    }
}

class Follower(cluster: ClusterService) : BaseRole(cluster) {
    private var lastLeaderId: String? = null

    override fun init() {
        logger.info("Role: Follower initialized")
        cluster.getStateService().startWatching()
    }

    override fun dispose() {
        super.dispose()
        cluster.getStateService().stopWatching()
    }

    override fun onUserActivity() {
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
    override fun init() {
        logger.info("Role: Candidate initialized")
        tryElection()
    }

    override fun onUserActivity() {
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
    private var lastState: State? = null

    override fun init() {
        logger.info("Role: Leader initialized")
        cluster.updateLeaderHeartbeat()
        publishCurrentState()
    }

    override fun onUserActivity() {
        cluster.updateLeaderHeartbeat()
        publishCurrentState()
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

    private fun publishCurrentState() {
        val connector = cluster.getIdeConnector()
        val state = connector.captureState()

        if (state != lastState) {
            Logger.info("State changed, publishing new state", IdeMetaData.getInstance(cluster.project))
            cluster.getStateService().publishState(state, cluster.getNodeId())
            logger.info("State changed, publishing new state")
            lastState = state
        } else {
            // Logger.info("State unchanged, skipping publish", IdeMetaData.getInstance(cluster.project))
            logger.info("State unchanged, skipping publish")
        }
    }
}
