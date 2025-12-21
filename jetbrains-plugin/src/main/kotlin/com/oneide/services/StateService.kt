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
    private val metaData: IdeMetaData,
    private val onStateChanged: (State) -> Unit,
    private val getCurrentProjectPath: () -> String?
) {
    private val mapper = jacksonObjectMapper()
    private val stateFile = oneIdeDir.resolve("state.json").toFile()
    private val oneIdePath = oneIdeDir
    private var isProcessing = AtomicInteger(0)
    private val isRunning = AtomicBoolean(true)
    private var watchService: java.nio.file.WatchService? = null

    init {
        initStateFile()
        startWatching()
        // Initialize lastCheckPoint
        readLatestState()
    }

    fun dispose() {
        isRunning.set(false)
        try {
            watchService?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun initStateFile() {
        if (!stateFile.exists()) {
            stateFile.parentFile?.mkdirs()
            try {
                stateFile.createNewFile()
            } catch (e: Exception) {
                Logger.error("Failed to create state file", e, metaData)
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

    fun getLastCheckPoint(): Long {
        return metaData.lastCheckPoint
    }

    fun appendState(state: State) {
        if (isSyncing()) return

        Logger.info("Appending state from ${state.source} with timestamp ${state.timestamp}", metaData)

        try {
            // Check for conflict
            val (currentState, _) = readStateFromFile()
            if (currentState != null && currentState.timestamp > metaData.lastCheckPoint) {
                Logger.info("Conflict detected. Local: ${metaData.lastCheckPoint}, Remote: ${currentState.timestamp}", metaData)
                metaData.lastCheckPoint = currentState.timestamp
                onStateChanged(currentState)
                return
            }

            val json = mapper.writeValueAsString(state)
            Logger.info("State content: $json", metaData)
            
            // Check truncation
            if (stateFile.length() > 1024 * 1024) { // 1MB
                stateFile.writeText("$json\n")
            } else {
                stateFile.appendText("$json\n")
            }
            metaData.lastCheckPoint = state.timestamp
        } catch (e: Exception) {
            Logger.error("Failed to append state", e, metaData)
        }
    }

    private fun startWatching() {
        val thread = Thread {
            try {
                watchService = FileSystems.getDefault().newWatchService()
                oneIdePath.register(
                    watchService, 
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE
                )

                while (isRunning.get()) {
                    val key = watchService?.take() ?: break // Blocks until event
                    
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
                Logger.error("Error watching state file", e, metaData)
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    private fun readStateFromFile(): Pair<State?, Long> {
        if (!stateFile.exists()) return null to 0L
        
        var maxTimestamp = 0L
        var bestState: State? = null

        try {
            val lines = stateFile.readLines()
            if (lines.isEmpty()) return null to 0L

            val currentPath = getCurrentProjectPath()
            
            // Iterate backwards
            for (i in lines.indices.reversed()) {
                val line = lines[i]
                if (line.isBlank()) continue

                try {
                    val state = mapper.readValue<State>(line)
                    
                    if (state.timestamp > maxTimestamp) {
                        maxTimestamp = state.timestamp
                    }

                    if (state.timestamp <= metaData.lastCheckPoint) {
                        // Assuming append-only and ordered, we can stop if we reach old states
                        break
                    }
                    
                    // We only want the *latest* matching state. 
                    // If we already found a bestState (which is newer because we iterate backwards), we skip checking older ones for match.
                    if (bestState != null) continue

                    val rootPath = state.root.path
                    if (currentPath == null) {
                        Logger.info("Discard state: current project path is null. State root: $rootPath", metaData)
                        continue
                    }

                    if (rootPath == currentPath || rootPath.contains(currentPath) || currentPath.contains(rootPath)) {
                        bestState = state
                    } else {
                        Logger.info("Discard state: path mismatch. State root: $rootPath, Current: $currentPath", metaData)
                    }
                } catch (e: Exception) {
                    // Ignore parse error
                }
            }
        } catch (e: Exception) {
            Logger.error("Failed to parse state", e, metaData)
        }
        return bestState to maxTimestamp
    }

    private fun readLatestState() {
        try {
            // Slight delay to ensure file write is complete (optional but helpful for some OS/FS)
             Thread.sleep(50)
             
            val (state, maxTimestamp) = readStateFromFile()
            
            // Always update lastCheckPoint if we saw a newer timestamp
            if (maxTimestamp > metaData.lastCheckPoint) {
                metaData.lastCheckPoint = maxTimestamp
            }

            if (state != null) {
                onStateChanged(state)
            }
        } catch (e: Exception) {
            Logger.error("Failed to read state file", e, metaData)
        }
    }
}
