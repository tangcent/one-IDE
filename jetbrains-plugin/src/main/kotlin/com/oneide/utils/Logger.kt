package com.oneide.utils

import com.intellij.openapi.diagnostic.Logger as IdeaLogger
import com.oneide.models.IdeMetaData
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Logger {
    private val ideaLogger = IdeaLogger.getInstance("OneIDE")
    private val logFile: File by lazy {
        val path = Paths.get(System.getProperty("user.home"), ".one-ide", "one-ide.log")
        path.parent.toFile().mkdirs()
        path.toFile()
    }
    
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    init {
        rotateLogIfNeeded()
    }

    private fun rotateLogIfNeeded() {
        try {
            if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) { // 5MB
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"))
                val backup = File(logFile.parent, "${logFile.name}.$timestamp")
                logFile.renameTo(backup)
                
                // Clean up old logs (keep last 5)
                val files = logFile.parentFile.listFiles { f -> f.name.startsWith("one-ide.log.") }
                    ?.sortedByDescending { it.name }
                    ?: return
                
                if (files.size > 5) {
                    files.drop(5).forEach { it.delete() }
                }
            }
        } catch (e: Exception) {
            ideaLogger.error("Failed to rotate logs", e)
        }
    }

    private fun writeToFile(level: String, message: String) {
        try {
            val timestamp = LocalDateTime.now().format(dateFormatter)
            val metaStr = "${IdeMetaData.ide}-${IdeMetaData.id}"
            val line = "[$timestamp] [$level] [$metaStr] $message\n"
            logFile.appendText(line)
        } catch (e: Exception) {
            ideaLogger.error("Failed to write to log file", e)
        }
    }

    fun info(message: String) {
        ideaLogger.info(message)
        writeToFile("INFO", message)
    }

    fun error(message: String, e: Throwable? = null) {
        if (e != null) {
            ideaLogger.error(message, e)
            writeToFile("ERROR", "$message\n${e.stackTraceToString()}")
        } else {
            ideaLogger.error(message)
            writeToFile("ERROR", message)
        }
    }
}
