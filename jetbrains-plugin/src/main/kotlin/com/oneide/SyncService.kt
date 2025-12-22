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
class SyncService(private val project: Project) : Disposable {
    val metaData = IdeMetaData.getInstance(project)
    private val oneIdeDir = Paths.get(System.getProperty("user.home"), ".one-ide")

    private val configService = ConfigService(oneIdeDir)
    private val stateService = StateService(oneIdeDir, metaData)
    private val ideConnector = IdeConnector(project, configService)
    private val clusterService = ClusterService(oneIdeDir.toFile(), metaData.id, project, ideConnector, stateService)

    var isEnabled: Boolean = true
        set(value) {
            field = value
            Logger.info("One-IDE Sync enabled: $value", metaData)
            updateAllStatusBars()
        }

    init {
        Logger.info("One-IDE SyncService initialized. SourceID: ${metaData.id}", metaData)
    }

    override fun dispose() {
        Logger.info("Disposing SyncService for ${project.name}", metaData)
        configService.dispose()
        stateService.dispose()
        clusterService.dispose()
    }

    companion object {
        fun getInstance(project: Project): SyncService = project.getService(SyncService::class.java)
    }

    fun updateConfig(newConfig: Config) {
        configService.updateConfig(newConfig)
    }

    fun getConfig(): Config {
        return configService.getConfig()
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
