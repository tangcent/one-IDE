package com.oneide.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.oneide.models.ClusterState
import com.oneide.models.State
import com.oneide.models.IdeMetaData
import com.oneide.utils.Logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds

/**
 * Handles the serialization, publication, and observation of IDE state.
 *
 * The state is exchanged via a shared JSON file (~/.one-ide/cluster/state.json).
 * - The LEADER writes to this file when the user's context (open files, cursor) changes.
 * - FOLLOWERS watch this file for changes and apply the new state.
 */
class StateService(
    oneIdeDir: Path,
    private val metaData: IdeMetaData
) {
    private val stateFile: File = oneIdeDir.resolve("cluster").resolve("state.json").toFile()
    private val mapper = jacksonObjectMapper()
    private var onStateReceivedCallback: ((State) -> Unit)? = null
    private var watchThread: Thread? = null
    private var isWatching = false

    fun setOnStateReceived(callback: (State) -> Unit) {
        onStateReceivedCallback = callback
    }

    fun publishState(state: State, leaderId: String) {
        Logger.info("Publishing state from ${state.source} with timestamp ${state.timestamp}", metaData)

        val clusterState = ClusterState(
            timestamp = System.currentTimeMillis(),
            leaderId = leaderId,
            state = state
        )

        try {
            if (!stateFile.parentFile.exists()) stateFile.parentFile.mkdirs()
            stateFile.writeText(mapper.writeValueAsString(clusterState))
            metaData.lastCheckPoint = state.timestamp
        } catch (e: Exception) {
            Logger.error("Failed to publish state", e, metaData)
        }
    }

    fun startWatching() {
        if (isWatching) return
        isWatching = true

        val dir = stateFile.parentFile.toPath()
        if (!stateFile.parentFile.exists()) stateFile.parentFile.mkdirs()

        // Initial read
        readLatestState()

        watchThread = Thread {
            try {
                val watchService = FileSystems.getDefault().newWatchService()
                dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)

                while (isWatching) {
                    val key = watchService.take()
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue

                        val filename = event.context() as Path
                        if (filename.toString() == "state.json") {
                            // Give a small delay for file write to complete
                            Thread.sleep(50)
                            readLatestState()
                        }
                    }
                    if (!key.reset()) break
                }
            } catch (e: InterruptedException) {
                // Stopped
            } catch (e: Exception) {
                Logger.error("Error watching state file", e, metaData)
            }
        }
        watchThread?.start()
    }

    fun stopWatching() {
        isWatching = false
        watchThread?.interrupt()
        watchThread = null
    }

    private fun readLatestState() {
        if (!stateFile.exists()) return

        try {
            val clusterState: ClusterState = mapper.readValue(stateFile)
            if (clusterState.timestamp > metaData.lastCheckPoint) {
                metaData.lastCheckPoint = clusterState.timestamp
                onStateReceivedCallback?.invoke(clusterState.state)
            }
        } catch (e: Exception) {
            // Logger.error("Error reading state file", e, metaData)
        }
    }

    fun dispose() {
        stopWatching()
    }
}
