package com.oneide.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.oneide.models.State
import com.oneide.utils.Logger
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import com.oneide.models.IdeMetaData

class StateService(
    oneIdeDir: Path,
    private val onStateChanged: (State) -> Unit,
    private val getCurrentProjectPath: () -> String?
) {
    private val mapper = jacksonObjectMapper()
    private val stateFile = oneIdeDir.resolve("state.json").toFile()
    private val oneIdePath = oneIdeDir
    private var isProcessing = AtomicInteger(0)
    private val isRunning = AtomicBoolean(true)

    init {
        initStateFile()
        startWatching()
        // Initialize lastKnownTimestamp
        readStateFromFile()?.let { IdeMetaData.lastKnownTimestamp = it.timestamp }
    }

    private fun initStateFile() {
        if (!stateFile.exists()) {
            try {
                stateFile.createNewFile()
            } catch (e: Exception) {
                Logger.error("Failed to create state file", e)
            }
        }
    }

    fun startSync() {
        isProcessing.incrementAndGet()
    }

    fun endSync() {
        isProcessing.decrementAndGet()
    }

    fun isSyncing(): Boolean {
        return isProcessing.get() > 0
    }

    fun getLastKnownTimestamp(): Long {
        return IdeMetaData.lastKnownTimestamp
    }

    fun appendState(state: State) {
        if (isSyncing()) return

        Logger.info("Appending state from ${state.source} with timestamp ${state.timestamp}")

        try {
            // Check for conflict
            val currentState = readStateFromFile()
            if (currentState != null && currentState.timestamp > IdeMetaData.lastKnownTimestamp) {
                Logger.info("Conflict detected. Local: ${IdeMetaData.lastKnownTimestamp}, Remote: ${currentState.timestamp}")
                IdeMetaData.lastKnownTimestamp = currentState.timestamp
                onStateChanged(currentState)
                return
            }

            val json = mapper.writeValueAsString(state)
            Logger.info("State content: $json")
            
            // Check truncation
            if (stateFile.length() > 1024 * 1024) { // 1MB
                stateFile.writeText("$json\n")
            } else {
                stateFile.appendText("$json\n")
            }
            IdeMetaData.lastKnownTimestamp = state.timestamp
        } catch (e: Exception) {
            Logger.error("Failed to append state", e)
        }
    }

    private fun startWatching() {
        val thread = Thread {
            try {
                val watchService = FileSystems.getDefault().newWatchService()
                oneIdePath.register(
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
                        if (filename.toString() == "state.json") {
                            readLatestState()
                        }
                    }

                    if (!key.reset()) {
                        break
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error watching state file", e)
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    private fun readStateFromFile(): State? {
        if (!stateFile.exists()) return null
        try {
            val lines = stateFile.readLines()
            if (lines.isEmpty()) return null

            val currentPath = getCurrentProjectPath()
            
            // Iterate backwards
            for (i in lines.indices.reversed()) {
                val line = lines[i]
                if (line.isBlank()) continue

                try {
                    val state = mapper.readValue<State>(line)
                    
                    if (state.timestamp <= IdeMetaData.lastKnownTimestamp) {
                        return null
                    }
                    
                    val rootPath = state.root.path
                    if (currentPath == null) {
                        // If no current project path, we cannot verify path matching. 
                        // However, to ensure we don't miss updates during initialization (when path might be null),
                        // we might return the latest state. 
                        // But given the strict requirements, if we can't match, we shouldn't return.
                        // Wait, if init calls this and path is null, we get null. lastKnownTimestamp stays 0.
                        // Then when path becomes available, we might process old events.
                        // Let's assume path is available or we return latest if null to be safe?
                        // User requirement: "find out last state that match any condition"
                        // If currentPath is null, condition fails.
                        continue
                    }

                    if (rootPath == currentPath || rootPath.contains(currentPath) || currentPath.contains(rootPath)) {
                        return state
                    }
                } catch (e: Exception) {
                    // Ignore parse error
                }
            }
        } catch (e: Exception) {
            Logger.error("Failed to parse state", e)
        }
        return null
    }

    private fun readLatestState() {
        try {
            // Slight delay to ensure file write is complete (optional but helpful for some OS/FS)
             Thread.sleep(50)
             
            val state = readStateFromFile()
            if (state != null && state.timestamp > IdeMetaData.lastKnownTimestamp) {
                IdeMetaData.lastKnownTimestamp = state.timestamp
                onStateChanged(state)
            }
        } catch (e: Exception) {
            Logger.error("Failed to read state file", e)
        }
    }
}
