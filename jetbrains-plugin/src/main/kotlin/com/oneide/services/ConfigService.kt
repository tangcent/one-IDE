package com.oneide.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.oneide.models.Config
import com.intellij.ide.util.PropertiesComponent
import com.oneide.OneIde
import com.oneide.utils.Logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for managing One-IDE configuration.
 *
 * Responsibilities:
 * - Loading and saving global configuration (config.json) which is shared across IDEs.
 * - Loading and saving project-specific configuration (PropertiesComponent).
 * - Watching for changes in the global configuration file.
 */
@Service(Service.Level.PROJECT)
class ConfigService(private val project: Project) : Disposable {
    private val oneIdeDir = OneIde.oneIdeDir
    private val mapper = jacksonObjectMapper()
    private val configFile = oneIdeDir.resolve("config.json").toFile()

    private var config: Config = Config()
    private val isRunning = AtomicBoolean(true)
    private var watchService: WatchService? = null
    private var watchThread: Thread? = null

    init {
        initConfigFile()
        loadConfig()
        watchConfigFile()
    }

    override fun dispose() {
        isRunning.set(false)

        // Interrupt the watch thread if it's still running
        watchThread?.interrupt()

        try {
            watchService?.close()
        } catch (e: Exception) {
            // Ignore
        }

        // Clear references
        watchThread = null
        watchService = null
    }

    companion object {
        fun getInstance(project: Project): ConfigService = project.getService(ConfigService::class.java)
    }

    /**
     * Retrieves the current configuration, merging global and project-specific settings.
     */
    fun getConfig(): Config {
        val properties = PropertiesComponent.getInstance(project)
        config.syncRules = properties.getBoolean("com.oneide.ai.syncRules", true)
        config.currentTool = properties.getValue("com.oneide.ai.currentTool", "Auto")
        return config
    }

    /**
     * Updates the configuration.
     * Global settings are saved to config.json.
     * Project settings are saved to PropertiesComponent.
     */
    fun updateConfig(newConfig: Config) {
        // 1. Update Global Config (and save to file)
        config.excludeFiles = newConfig.excludeFiles
        config.excludeGitIgnore = newConfig.excludeGitIgnore

        try {
            val application = ApplicationManager.getApplication()
            if (application != null) {
                application.executeOnPooledThread {
                    saveConfig()
                }
            } else {
                // In test environment, save directly
                saveConfig()
            }
        } catch (_: Exception) {
            // Handle case where ApplicationManager is not available
            saveConfig()
        }

        // 2. Update Project Config (and save to PropertiesComponent)
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue("com.oneide.ai.syncRules", newConfig.syncRules)
        properties.setValue("com.oneide.ai.currentTool", newConfig.currentTool)

        // Update local memory copy
        config.syncRules = newConfig.syncRules
        config.currentTool = newConfig.currentTool
    }

    private fun saveConfig() {
        try {
            configFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config))
            try {
                Logger.info("Config updated and saved: $config")
            } catch (logError: Exception) {
                // Logger might not be available in test environment
            }
        } catch (e: Exception) {
            try {
                Logger.error("Failed to save config", e)
            } catch (logError: Exception) {
                // Logger might not be available in test environment
            }
        }
    }

    private fun initConfigFile() {
        if (!configFile.exists()) {
            configFile.parentFile?.mkdirs()
            val defaultConfig = Config(excludeFiles = emptyList(), excludeGitIgnore = false)
            try {
                configFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(defaultConfig))
            } catch (e: Exception) {
                try {
                    Logger.error("Failed to create config file", e)
                } catch (logError: Exception) {
                    // Logger might not be available in test environment
                }
            }
        }
    }

    private fun loadConfig() {
        try {
            if (configFile.exists()) {
                val content = configFile.readText()
                if (content.isNotBlank()) {
                    config = try {
                        mapper.readValue<Config>(content)
                        // Silently handle successful load - no logging in test environment
                    } catch (_: Exception) {
                        // Malformed JSON - keep default config, no error logging
                        Config()
                    }
                }
            }
        } catch (_: Exception) {
            // File read error - keep default config, no error logging
            config = Config()
        }
    }

    private fun watchConfigFile() {
        val thread = Thread {
            try {
                val watchService = FileSystems.getDefault().newWatchService()
                this.watchService = watchService
                oneIdeDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE
                )

                while (isRunning.get()) {
                    try {
                        val key = watchService?.take() ?: break // Blocks until event
                        for (event in key.pollEvents()) {
                            val kind = event.kind()
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue

                            val filename = event.context() as Path
                            if (filename.toString() == "config.json") {
                                // Slight delay to ensure file write is complete
                                Thread.sleep(50)
                                Logger.info("Config file changed, reloading...")
                                loadConfig()
                            }
                        }

                        if (!key.reset()) {
                            break
                        }
                    } catch (e: java.nio.file.ClosedWatchServiceException) {
                        // Watch service was closed, exit gracefully
                        break
                    } catch (e: java.lang.InterruptedException) {
                        // Thread was interrupted, exit gracefully
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } catch (e: Exception) {
                try {
                    Logger.error("Error watching config file", e)
                } catch (logError: Exception) {
                    // Logger might not be available in test environment
                }
            }
        }
        thread.isDaemon = true
        watchThread = thread
        thread.start()
    }

    fun shouldSyncFile(filePath: String): Boolean {
        // 1. Check excludeFiles (glob patterns)
        val fileName = File(filePath).name
        for (pattern in config.excludeFiles) {
            // Check against both filename and full path
            if (matchesGlob(fileName, pattern) || matchesGlob(filePath, pattern)) {
                return false
            }
        }

        // 2. Check excludeGitIgnore
        if (config.excludeGitIgnore) {
            if (isIgnoredByGit(filePath)) {
                return false
            }
        }

        return true
    }

    private fun matchesGlob(text: String, pattern: String): Boolean {
        try {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            return matcher.matches(Paths.get(text))
        } catch (e: Exception) {
            Logger.warn("Invalid glob pattern: $pattern", e)
            return false
        }
    }

    private fun isIgnoredByGit(filePath: String): Boolean {
        try {
            // Check if IntelliJ components are available
            if (isFileInProject(project, filePath)) {
                val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(filePath)) ?: return false
                return ChangeListManager.getInstance(project).isIgnoredFile(virtualFile)
            }
        } catch (e: Exception) {
            // Handle cases where IntelliJ components are not available (e.g., in tests)
            try {
                // Logger.debug("IntelliJ components not available for git ignore check", e) // Logger doesn't have debug? 
                // Using warn for now or just skip
                Logger.warn("IntelliJ components not available for git ignore check", e)
            } catch (logError: Exception) {
                // Logger might not be available in test environment
            }
        }

        return false
    }

    private fun isFileInProject(project: Project, filePath: String): Boolean {
        val projectPath = project.basePath ?: return false
        return filePath.lowercase().startsWith(projectPath.lowercase())
    }
}
