package com.oneide.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.oneide.models.Config
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.atomic.AtomicBoolean

class ConfigService(private val oneIdeDir: Path) {
    private val logger = Logger.getInstance(ConfigService::class.java)
    private val mapper = jacksonObjectMapper()
    private val configFile = oneIdeDir.resolve("config.json").toFile()

    private var config: Config = Config()
    private val isRunning = AtomicBoolean(true)

    init {
        initConfigFile()
        loadConfig()
        watchConfigFile()
    }

    fun getConfig(): Config {
        return config
    }

    fun updateConfig(newConfig: Config) {
        config = newConfig
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
    }

    private fun saveConfig() {
        try {
            configFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config))
            try {
                logger.info("Config updated and saved: $config")
            } catch (logError: Exception) {
                // Logger might not be available in test environment
            }
        } catch (e: Exception) {
            try {
                logger.error("Failed to save config", e)
            } catch (logError: Exception) {
                // Logger might not be available in test environment
            }
        }
    }

    private fun initConfigFile() {
        if (!configFile.exists()) {
            val defaultConfig = Config(excludeFiles = emptyList(), excludeGitIgnore = false)
            try {
                configFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(defaultConfig))
            } catch (e: Exception) {
                try {
                    logger.error("Failed to create config file", e)
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
                oneIdeDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE
                )

                while (isRunning.get()) {
                    val key = watchService.take() // Blocks until event
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue

                        val filename = event.context() as Path
                        if (filename.toString() == "config.json") {
                            // Slight delay to ensure file write is complete
                            Thread.sleep(50)
                            logger.info("Config file changed, reloading...")
                            loadConfig()
                        }
                    }

                    if (!key.reset()) {
                        break
                    }
                }
            } catch (e: Exception) {
                try {
                    logger.error("Error watching config file", e)
                } catch (logError: Exception) {
                    // Logger might not be available in test environment
                }
            }
        }
        thread.isDaemon = true
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
            logger.warn("Invalid glob pattern: $pattern", e)
            return false
        }
    }

    private fun isIgnoredByGit(filePath: String): Boolean {
        try {
            // Check if IntelliJ components are available
            val projectManager = ProjectManager.getInstance()
            val openProjects = projectManager.openProjects

            for (project in openProjects) {
                if (isFileInProject(project, filePath)) {
                    val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(filePath)) ?: return false
                    return ChangeListManager.getInstance(project).isIgnoredFile(virtualFile)
                }
            }
        } catch (e: Exception) {
            // Handle cases where IntelliJ components are not available (e.g., in tests)
            try {
                logger.debug("IntelliJ components not available for git ignore check", e)
            } catch (logError: Exception) {
                // Logger might not be available in test environment
            }
        }

        return false
    }

    private fun isFileInProject(project: Project, filePath: String): Boolean {
        val projectPath = project.basePath ?: return false
        return filePath.startsWith(projectPath)
    }
}
