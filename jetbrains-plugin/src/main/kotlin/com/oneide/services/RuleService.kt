package com.oneide.services

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.Disposer
import com.oneide.models.Role
import com.oneide.utils.Debouncer
import com.oneide.utils.Logger
import java.io.File
import java.util.*

/**
 * Represents a rule file with its content and metadata.
 * @property path Relative path or filename.
 * @property content Content of the file.
 * @property lastModified Last modified timestamp.
 */
data class RuleFile(val path: String, val content: String, val lastModified: Long = 0)

data class RuleStat(val ai: String, val file: File, val mtime: Long)

/**
 * Interface for building rule files for a target AI tool.
 */
interface RuleBuilder {
    fun buildRules(sourceFiles: List<RuleFile>, sourceAi: String): List<RuleFile>
}

/**
 * Builds rules for tools that support folder-based rule structure.
 *
 * @property ruleRoot The root directory for the rules.
 */
class FolderRuleBuilder(private val ruleRoot: String, private val extension: String? = null) : RuleBuilder {
    override fun buildRules(sourceFiles: List<RuleFile>, sourceAi: String): List<RuleFile> {
        return sourceFiles.map { file ->
            var name = File(file.path).name

            if (extension != null) {
                val ext = File(name).extension
                if (ext.isNotEmpty() && !name.endsWith(extension)) {
                    name = name.substring(0, name.length - ext.length - 1) + extension
                } else if (ext.isEmpty()) {
                    name += extension
                }
            }
            // name = "${sourceAi.lowercase()}_$name" // Keep original filename

            val path = if (ruleRoot.isEmpty()) name else "$ruleRoot/$name"
            RuleFile(path, file.content, file.lastModified)
        }
    }
}

/**
 * Builds rules for tools that require a single file configuration.
 * Concatenates all source rules into one file.
 *
 * @property targetPath The path of the single target file.
 */
class SingleFileRuleBuilder(private val targetPath: String) : RuleBuilder {
    override fun buildRules(sourceFiles: List<RuleFile>, sourceAi: String): List<RuleFile> {
        // Iterate through each source file and extract its content
        val sb = StringBuilder()
        var maxTime = 0L
        for (file in sourceFiles) {
            sb.append(file.content).append("\n\n")
            if (file.lastModified > maxTime) maxTime = file.lastModified
        }
        // Return the single target file with the merged content
        return listOf(RuleFile(targetPath, sb.toString(), maxTime))
    }
}

/**
 * Represents the synchronization state of a rule set.
 *
 * @property source The name of the AI tool that is the source of the rules.
 * @property lastModified The timestamp when the rules were last modified.
 * @property isFromSynced Indicates if the current state is a result of a sync operation.
 * @property syncedTo List of AI tools that this rule set has been synced to.
 */
data class SyncState(
    var source: String? = null,
    var lastModified: Long = 0,
    var isFromSynced: Boolean = false,
    val syncedTo: MutableList<String> = mutableListOf()
)

/**
 * Service responsible for synchronizing AI rules between different tools.
 * It triggers a synchronization check only when this IDE instance becomes cluster Leader.
 *
 * @property project The IntelliJ project.
 */
class RuleService(
    private val project: Project,
    private val clusterService: ClusterService
) {
    private val debouncer = Debouncer(1000)
    private val currentAppName = ApplicationNamesInfo.getInstance().fullProductName
    private val logger = Logger.withProject(project)
    private var unsubscribeRoleChange: (() -> Unit)? = null

    /**
     * Starts the RuleService.
     * Triggers synchronization only when this instance becomes the cluster Leader.
     */
    fun start() {
        val configService = ConfigService.getInstance(project)
        if (!configService.getConfig().syncRules) {
            logger.info("AI Rule Sync is disabled.")
            return
        }

        logger.info("Starting RuleService for project: ${project.name}. App: $currentAppName")
        unsubscribeRoleChange = clusterService.addRoleChangeListener { role ->
            if (role != Role.LEADER) return@addRoleChangeListener
            debouncer.debounce { checkAndSync() }
        }
        Disposer.register(project, Disposable {
            unsubscribeRoleChange?.invoke()
            debouncer.cancel()
        })
    }

    private fun listRuleFiles(rootPath: String, config: AIConfig): List<File> {
        val rootDir = File(rootPath)
        val ruleRootFile = File(rootDir, config.ruleRoot)
        if (!ruleRootFile.exists()) return emptyList()

        if (config.strategy == "single-file") {
            return if (ruleRootFile.isFile) listOf(ruleRootFile) else emptyList()
        }

        val extension = config.extension?.lowercase()
        return ruleRootFile.walkTopDown()
            .filter { it.isFile }
            .filter { extension == null || it.name.lowercase().endsWith(extension) }
            .toList()
    }

    private fun collectRuleStats(rootPath: String, aiTools: Map<String, AIConfig>): List<RuleStat> {
        val allRules = mutableListOf<RuleStat>()
        for ((key, config) in aiTools) {
            val files = listRuleFiles(rootPath, config)
            files.forEach { file ->
                allRules.add(RuleStat(key, file, file.lastModified()))
            }
        }
        return allRules
    }

    private fun readRuleFiles(rootPath: String, config: AIConfig): List<RuleFile> {
        val rootDir = File(rootPath)
        return listRuleFiles(rootPath, config).map { file ->
            val relativePath = file.relativeTo(rootDir).path
            RuleFile(relativePath, file.readText(), file.lastModified())
        }
    }

    private fun getSyncState(ruleRoot: File, currentMtime: Long): SyncState? {
        val state = LocalStorage.getData(ruleRoot.absolutePath, SyncState::class.java)
        if (state != null) {
            // Check if lastModified matches
            if (state.lastModified != currentMtime) return null
        }
        return state
    }

    private fun checkAndSync() {
        if (!ConfigService.getInstance(project).getConfig().syncRules) return

        val basePath = project.basePath ?: return

        // 1. Collect all rule files and their mtimes
        val aiTools = AITool.instance.getAllAIConfigs()
        val allRules = collectRuleStats(basePath, aiTools)

        if (allRules.isEmpty()) return

        val aiMaxMtimes = allRules.groupBy { it.ai }
            .mapValues { entry -> entry.value.maxOf { it.mtime } }

        // 2. Filter candidates (ignore isFromSynced)
        val validCandidates = allRules.filter { rule ->
            val config = aiTools[rule.ai]!!
            val ruleRoot = File(basePath, config.ruleRoot)
            val mtime = aiMaxMtimes[rule.ai]!!
            val state = getSyncState(ruleRoot, mtime)
            state == null || !state.isFromSynced
        }

        if (validCandidates.isEmpty()) return

        // 3. Find the latest modified rule from valid candidates
        val latest = validCandidates.maxByOrNull { it.mtime } ?: return

        // 4. Identify current AI tool
        val currentToolKey = detectCurrentTool(project)
        if (currentToolKey == null) {
            logger.info("Current IDE '$currentAppName' is not a known AI tool target. Skipping sync.")
            return
        }

        if (latest.ai == currentToolKey) {
            return
        }

        // Check locks
        if (isLocked(basePath, latest.ai)) {
            logger.info("Sync ignored: Source ${latest.ai} is currently locked.")
            return
        }

        // Check if already synced
        val targetConfig = aiTools[currentToolKey]!!
        val targetRuleRoot = File(basePath, targetConfig.ruleRoot)
        val targetMtime = aiMaxMtimes[currentToolKey] ?: 0L

        val targetState = getSyncState(targetRuleRoot, targetMtime)

        if (targetState != null && targetState.source == latest.ai && targetMtime == latest.mtime) {
            return
        }

        // 4. Sync
        logger.info("Latest rule modification: ${latest.ai} - ${latest.file.name}")
        logger.info("Syncing rules from ${latest.ai} to $currentToolKey...")

        if (tryAcquireLock(basePath, currentToolKey)) {
            try {
                val syncedMtime = syncRules(latest.ai, currentToolKey, basePath)
                if (syncedMtime > 0) {
                    updateSyncStates(basePath, aiTools, latest, currentToolKey, aiMaxMtimes, syncedMtime)
                }
            } catch (e: Exception) {
                logger.error("Sync failed", e)
            } finally {
                releaseLock(basePath, currentToolKey)
            }
        } else {
            logger.info("Skipping sync: Could not acquire lock for $currentToolKey.")
        }
    }

    private fun detectCurrentTool(project: Project): String? {
        return AITool.instance.detectCurrentTool(project)
    }

    private fun getLockPath(rootPath: String, aiTool: String): File {
        val encodedPath = Base64.getEncoder().encodeToString(rootPath.toByteArray()).replace("/", "_").replace("+", "-")
        val homeDir = System.getProperty("user.home")
        val lockDir = File(homeDir, ".one-ide/locks")
        if (!lockDir.exists()) {
            lockDir.mkdirs()
        }
        return File(lockDir, "$encodedPath-$aiTool.lock")
    }

    private fun isLocked(rootPath: String, aiTool: String): Boolean {
        val lockFile = getLockPath(rootPath, aiTool)
        if (lockFile.exists()) {
            if (System.currentTimeMillis() - lockFile.lastModified() > 3 * 60 * 1000) {
                return false
            }
            return true
        }
        return false
    }

    private fun tryAcquireLock(rootPath: String, aiTool: String): Boolean {
        val lockFile = getLockPath(rootPath, aiTool)
        if (lockFile.exists()) {
            if (System.currentTimeMillis() - lockFile.lastModified() < 3 * 60 * 1000) {
                return false
            }
        }
        try {
            lockFile.writeText(System.currentTimeMillis().toString())
            return true
        } catch (e: Exception) {
            logger.error("Failed to acquire lock $lockFile", e)
            return false
        }
    }

    private fun releaseLock(rootPath: String, aiTool: String) {
        val lockFile = getLockPath(rootPath, aiTool)
        try {
            if (lockFile.exists()) {
                lockFile.delete()
            }
        } catch (e: Exception) {
            logger.error("Failed to release lock $lockFile", e)
        }
    }

    private fun getRuleBuilder(targetConfig: AIConfig): RuleBuilder {
        return if (targetConfig.strategy == "single-file") {
            SingleFileRuleBuilder(targetConfig.ruleRoot)
        } else {
            FolderRuleBuilder(targetConfig.ruleRoot, targetConfig.extension)
        }
    }

    private fun updateSyncStates(
        basePath: String,
        aiTools: Map<String, AIConfig>,
        latest: RuleStat,
        targetAi: String,
        aiMaxMtimes: Map<String, Long>,
        syncedMtime: Long
    ) {
        val newTargetState = SyncState()
        newTargetState.source = latest.ai
        newTargetState.lastModified = syncedMtime
        newTargetState.isFromSynced = true
        LocalStorage.setData(File(basePath, aiTools[targetAi]!!.ruleRoot).absolutePath, newTargetState)

        val sourceConfig = aiTools[latest.ai]!!
        val sourceRuleRoot = File(basePath, sourceConfig.ruleRoot)
        val sourceState = getSyncState(sourceRuleRoot, aiMaxMtimes[latest.ai] ?: 0L) ?: SyncState()
        if (!sourceState.syncedTo.contains(targetAi)) {
            sourceState.syncedTo.add(targetAi)
            LocalStorage.setData(sourceRuleRoot.absolutePath, sourceState)
        }
    }

    private fun syncRules(sourceAi: String, targetAi: String, rootPath: String): Long {
        try {
            val aiTools = AITool.instance.getAllAIConfigs()
            val sourceConfig = aiTools[sourceAi] ?: return 0L
            val targetConfig = aiTools[targetAi] ?: return 0L

            // Collect source contents
            if (!isStrategyCompatible(sourceConfig, targetConfig, rootPath)) return 0L

            val sources = readRuleFiles(rootPath, sourceConfig)

            if (sources.isEmpty()) return 0L

            // Build rules
            val builder = getRuleBuilder(targetConfig)

            val ruleFiles = builder.buildRules(sources, sourceAi)

            // Prepare revert map
            val previousContents = mutableMapOf<String, String?>()
            var maxWrittenMtime = 0L

            // We need to run write action on EDT
            var anyChanged = false
            ApplicationManager.getApplication().invokeAndWait {
                WriteAction.run<Throwable> {
                    var changed = false
                    for (ruleFile in ruleFiles) {
                        val targetFile = File(rootPath, ruleFile.path)

                        val currentContent = if (targetFile.exists()) targetFile.readText() else null
                        val newContent = ruleFile.content
                        val newMtime = ruleFile.lastModified

                        if (currentContent != newContent || targetFile.lastModified() != newMtime) {
                            previousContents[ruleFile.path] = currentContent

                            try {
                                // Using VFS to write
                                val parentDir = targetFile.parentFile
                                if (!parentDir.exists()) parentDir.mkdirs()

                                val vTargetDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parentDir)

                                if (vTargetDir != null) {
                                    val vFile = vTargetDir.findChild(targetFile.name) ?: vTargetDir.createChildData(
                                        this,
                                        targetFile.name
                                    )
                                    vFile.setBinaryContent(newContent.toByteArray())
                                    logger.info("Updated ${targetFile.path}")
                                    changed = true
                                } else {
                                    // Fallback to IO
                                    targetFile.writeText(newContent)
                                    targetFile.setLastModified(newMtime)
                                    logger.info("Updated ${targetFile.path} (IO)")
                                    changed = true
                                }
                                if (newMtime > maxWrittenMtime) maxWrittenMtime = newMtime
                            } catch (e: Exception) {
                                logger.error("Failed to write ${targetFile.path}", e)
                            }
                        } else {
                            // Content and mtime match, keep track of max time
                            if (newMtime > maxWrittenMtime) maxWrittenMtime = newMtime
                        }
                    }

                    if (changed) {
                        anyChanged = true
                        showRevertNotification(rootPath, previousContents, targetAi)
                    }
                }
            }
            return if (anyChanged) maxWrittenMtime else 0L
        } catch (e: Exception) {
            logger.error("Error during syncRules", e)
            return 0L
        }
    }

    private fun isStrategyCompatible(sourceConfig: AIConfig, targetConfig: AIConfig, rootPath: String): Boolean {
        if (sourceConfig.strategy == "single-file" && targetConfig.strategy == "folder") {
            val existing = listRuleFiles(rootPath, targetConfig)
            if (existing.size > 1) {
                logger.info("Skipping sync: ${targetConfig.name} already has multiple rule files.")
                return false
            }
        }
        return true
    }

    private fun showRevertNotification(rootPath: String, previousContents: Map<String, String?>, targetAi: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("OneIDE Notification Group")
            .createNotification("Rules synced to $targetAi", NotificationType.INFORMATION)

        notification.addAction(NotificationAction.createSimple("Revert") {
            WriteAction.run<Throwable> {
                for ((path, content) in previousContents) {
                    val file = File(rootPath, path)
                    if (content == null) {
                        if (file.exists()) file.delete()
                    } else {
                        file.writeText(content)
                    }
                }
                notification.expire()
                logger.info("Reverted changes for $targetAi")
            }
        })

        notification.notify(project)
    }
}
