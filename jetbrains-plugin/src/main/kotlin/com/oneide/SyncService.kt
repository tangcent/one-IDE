package com.oneide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.oneide.models.Config
import com.oneide.models.IdeMetaData
import com.oneide.services.ClusterService
import com.oneide.services.ConfigService
import com.oneide.services.IdeConnector
import com.oneide.services.StateService
import com.oneide.utils.Logger
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
/**
 * The core service that orchestrates One-IDE synchronization for a specific project.
 *
 * Responsibilities:
 * - Initializes and manages lifecycle of sub-services: [ConfigService], [StateService], [IdeConnector], [ClusterService].
 * - Holds the project metadata ([IdeMetaData]).
 * - Manages the global enabled/disabled state for synchronization.
 * - Updates status bar widgets across open projects when state changes.
 */
class SyncService(private val project: Project) : Disposable {
    val metaData = IdeMetaData.getInstance(project)
    private val logger = Logger.withMetaData(metaData)

    private val configService = ConfigService.getInstance(project)
    private val stateService = StateService(metaData)
    private val ideConnector = IdeConnector(project, configService)
    private val clusterService = ClusterService(metaData.id, project, ideConnector, stateService)

    var isEnabled: Boolean = true
        set(value) {
            field = value
            logger.info("One-IDE Sync enabled: $value")
            updateAllStatusBars()
        }

    init {
        logger.info("One-IDE SyncService initialized. SourceID: ${metaData.id}")
    }

    override fun dispose() {
        logger.info("Disposing SyncService for ${project.name}")
        // ConfigService is a project service, so it will be disposed by the platform
        // configService.dispose() 
        stateService.dispose()
        clusterService.dispose()
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
