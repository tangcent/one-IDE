package com.oneide.services.cluster.roles

import com.oneide.models.IdeMetaData
import com.oneide.models.State
import com.oneide.services.ClusterService
import com.oneide.utils.Logger

interface IRole {
    fun init()
    fun dispose()
    fun onUserActivity()
    fun onHeartbeat()
}

abstract class BaseRole(protected val cluster: ClusterService) : IRole {
    override fun dispose() {
        // Default cleanup
    }

    override fun onUserActivity() {
        // Default: do nothing
    }

    override fun onHeartbeat() {
        cluster.updateNodeHeartbeat()
    }
}

class Follower(cluster: ClusterService) : BaseRole(cluster) {
    override fun init() {
        Logger.info("Role: Follower initialized", IdeMetaData.getInstance(cluster.project))
        cluster.getStateService().startWatching()
    }

    override fun dispose() {
        super.dispose()
        cluster.getStateService().stopWatching()
    }

    override fun onUserActivity() {
        if (!cluster.checkLeaderHealth()) {
            Logger.info("User activity detected on Follower and Leader unhealthy -> Switching to Candidate", IdeMetaData.getInstance(cluster.project))
            cluster.becomeCandidate()
        }
    }

}

class Candidate(cluster: ClusterService) : BaseRole(cluster) {
    override fun init() {
        Logger.info("Role: Candidate initialized", IdeMetaData.getInstance(cluster.project))
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
            return
        }

        Logger.info("Candidate attempting election...", IdeMetaData.getInstance(cluster.project))
        if (cluster.tryAcquireLeadership()) {
            Logger.info("Election successful, becoming Leader", IdeMetaData.getInstance(cluster.project))
            cluster.becomeLeader()
        } else {
            Logger.info("Election failed, reverting to Follower", IdeMetaData.getInstance(cluster.project))
            cluster.becomeFollower()
        }
    }
}

class Leader(cluster: ClusterService) : BaseRole(cluster) {
    private var lastState: State? = null

    override fun init() {
        Logger.info("Role: Leader initialized", IdeMetaData.getInstance(cluster.project))
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
            Logger.info("Leader: Found another leader or leader file missing, downgrading to Follower", IdeMetaData.getInstance(cluster.project))
            cluster.becomeFollower()
        }
    }

    private fun publishCurrentState() {
        val connector = cluster.getIdeConnector()
        val state = connector.captureState()
        
        if (state != lastState) {
            Logger.info("State changed, publishing new state", IdeMetaData.getInstance(cluster.project))
            cluster.getStateService().publishState(state, cluster.getNodeId())
            lastState = state
        } else {
            // Logger.info("State unchanged, skipping publish", IdeMetaData.getInstance(cluster.project))
        }
    }
}
