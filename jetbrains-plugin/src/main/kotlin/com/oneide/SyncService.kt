package com.oneide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.oneide.models.IdeMetaData
import com.oneide.models.Role
import com.oneide.models.State
import com.oneide.services.ClusterService
import com.oneide.services.IdeConnector
import com.oneide.services.StateService
import com.oneide.services.cluster.ActionRegistry
import com.oneide.utils.Logger

/**
 * The core service that orchestrates One-IDE synchronization for a specific project.
 *
 * Responsibilities:
 * - Initializes and manages lifecycle of StateService.
 * - Subscribes to ClusterService role changes and user activity.
 * - Holds the project metadata ([IdeMetaData]).
 * - Manages the global enabled/disabled state for synchronization.
 * - Updates status bar widgets across open projects when state changes.
 */
@Service(Service.Level.PROJECT)
class SyncService(private val project: Project) : Disposable {
    val metaData = IdeMetaData.getInstance(project)
    private val logger = Logger.withMetaData(metaData)

    private val clusterService = ClusterService.getInstance(project)
    private val stateService = StateService(metaData)
    private val ideConnector: IdeConnector = clusterService.getIdeConnector()

    private var unsubscribeRoleChange: (() -> Unit)? = null
    private var unsubscribeLeaderUserActivity: (() -> Unit)? = null
    private var lastState: State? = null

    var isEnabled: Boolean = true
        set(value) {
            field = value
            logger.info("One-IDE Sync enabled: $value")
            updateAllStatusBars()
        }

    init {
        logger.info("One-IDE SyncService initialized. SourceID: ${metaData.id}")

        // Setup state received callback
        stateService.setOnStateReceived { state -> onStateReceived(state) }

        // Subscribe to role changes
        unsubscribeRoleChange = clusterService.addRoleChangeListener { role ->
            handleRoleChange(role)
        }

        // Subscribe to user activity only when LEADER role
        // The Leader role fires this action, SyncService listens
        unsubscribeLeaderUserActivity = clusterService.addAction(Role.LEADER, ActionRegistry.ACTION_USER_ACTIVITY) {
            if (isEnabled) {
                publishCurrentState()
            }
        }

        // Handle initial role
        handleRoleChange(clusterService.getRoleType())
    }

    private fun handleRoleChange(role: Role) {
        when (role) {
            Role.LEADER -> {
                stateService.stopWatching()
                // Publish current state immediately when becoming leader
                publishCurrentState()
            }
            Role.FOLLOWER -> {
                stateService.startWatching()
            }
            Role.CANDIDATE -> {
                // No action needed for candidate
            }
        }
    }

    private fun publishCurrentState() {
        val state = ideConnector.captureState()
        if (state != lastState) {
            logger.info("State changed, publishing new state")
            stateService.publishState(state, clusterService.getNodeId())
            lastState = state
        }
    }

    private fun onStateReceived(state: State) {
        if (clusterService.getRoleType() == Role.FOLLOWER && isEnabled) {
            ideConnector.applyState(state)
        }
    }

    override fun dispose() {
        logger.info("Disposing SyncService for ${project.name}")
        unsubscribeRoleChange?.invoke()
        unsubscribeLeaderUserActivity?.invoke()
        stateService.dispose()
    }

    companion object {
        fun getInstance(project: Project): SyncService = project.getService(SyncService::class.java)
    }

    private fun updateAllStatusBars() {
        ApplicationManager.getApplication().invokeLater {
            val projects = ProjectManager.getInstance().openProjects
            for (project in projects) {
                val statusBar = WindowManager.getInstance().getStatusBar(project)
                statusBar?.updateWidget("OneIDEStatusBar")
            }
        }
    }
}
