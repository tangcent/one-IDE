package com.oneide.services

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.oneide.SyncService
import com.oneide.utils.PluginUtils
import java.io.InputStreamReader

/**
 * Represents the configuration for a specific AI tool.
 *
 * @property name The display name of the AI tool (e.g., "Cursor", "Trae").
 * @property patterns List of glob patterns to identify rule files associated with this tool.
 * @property ruleRoot The root directory or file path where rules for this tool are stored.
 * @property strategy The rule syncing strategy: "folder" (syncs entire directory) or "single-file".
 * @property plugins List of plugin IDs (extensions) associated with this tool for detection purposes.
 */
data class AIConfig(
    val name: String,
    val patterns: List<String>,
    val ruleRoot: String,
    val strategy: String,
    val plugins: List<String> = emptyList()
)

/**
 * Data class representing the JSON structure of the `ai-tools.json` configuration file.
 *
 * @property tools List of configured [AIConfig] objects.
 */
data class AIConfigJson(
    val tools: List<AIConfig>
)

@Service
/**
 * Service for managing AI tools configuration and detection.
 *
 * This service loads supported AI tools from `ai-tools.json` and provides functionality
 * to detect the currently active AI tool based on user configuration or installed plugins.
 */
class AITool {
    private val logger = Logger.getInstance(AITool::class.java)
    private val aiTools = mutableMapOf<String, AIConfig>()

    init {
        loadAIConfig()
    }

    companion object {
        val instance: AITool
            get() = service()
    }

    /**
     * Loads AI tool configurations from the `ai-tools.json` resource file.
     */
    private fun loadAIConfig() {
        try {
            val resource = this.javaClass.getResourceAsStream("/ai-tools.json")
            if (resource != null) {
                InputStreamReader(resource).use { reader ->
                    val config = Gson().fromJson(reader, AIConfigJson::class.java)
                    config.tools.forEach { aiTools[it.name] = it }
                }
            } else {
                logger.error("ai-tools.json not found")
            }
        } catch (e: Exception) {
            logger.error("Failed to load ai-tools.json", e)
        }
    }

    /**
     * Returns a map of all configured AI tools.
     *
     * @return A map where the key is the tool name and the value is the [AIConfig] object.
     */
    fun getAllAIConfigs(): Map<String, AIConfig> {
        return aiTools
    }

    /**
     * Retrieves the configuration for a specific AI tool by name.
     *
     * @param name The name of the AI tool.
     * @return The [AIConfig] object if found, null otherwise.
     */
    fun getAIConfig(name: String): AIConfig? {
        return aiTools[name]
    }

    /**
     * Detects the currently active AI tool for a specific project.
     *
     * Detection logic:
     * 1. Checks the user's configuration in [SyncService] for the given project.
     * 2. If "Auto" is selected, it iterates through configured tools and checks if their associated plugins are installed.
     *
     * @param project The project to detect the tool for.
     * @return The name of the detected tool, or null if no tool is detected.
     */
    fun detectCurrentTool(project: Project): String? {
        // 1. User Config
        val configuredTool = try {
            SyncService.getInstance(project).getConfig().currentTool
        } catch (_: Exception) {
            "Auto"
        }

        return resolveTool(configuredTool) { id -> PluginUtils.isPluginInstalled(id) }
    }

    /**
     * Resolves the current AI tool based on configuration and installed plugins.
     * Exposed for testing purposes to bypass static dependencies.
     *
     * @param configuredTool The tool configured by the user (e.g., "Auto", "Cursor").
     * @param isPluginInstalled A predicate function to check if a plugin with a given ID is installed.
     * @return The name of the detected tool, or null if no tool is detected.
     */
    fun resolveTool(configuredTool: String, isPluginInstalled: (String) -> Boolean): String? {
        // 1. User Config
        if (configuredTool != "Auto" && aiTools.containsKey(configuredTool)) {
            return configuredTool
        }

        // 2. Plugin Detection
        for (tool in aiTools.values) {
            if (tool.plugins.isNotEmpty()) {
                for (pluginId in tool.plugins) {
                    if (isPluginInstalled(pluginId)) {
                        logger.info("Detected installed plugin $pluginId for ${tool.name}")
                        return tool.name
                    }
                }
            }
        }

        return null
    }
}
