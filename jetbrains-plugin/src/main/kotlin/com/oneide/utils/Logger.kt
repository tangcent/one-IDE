package com.oneide.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger as IdeaLogger
import com.oneide.OneIde
import com.oneide.models.IdeMetaData
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Logger {
    private val ideaLogger = IdeaLogger.getInstance("OneIDE")
    private val logFile: File by lazy {
        resolveLogFile()
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

    private fun resolveLogFile(): File {
        val overrideDir = System.getProperty("oneide.log.dir")?.let { File(it) }
        val baseDir = overrideDir ?: OneIde.oneIdeDir.toFile()
        val resolvedDir = ensureWritableDir(baseDir)
            ?: ensureWritableDir(File(System.getProperty("java.io.tmpdir"), "one-ide"))
            ?: baseDir
        return File(resolvedDir, "one-ide.log")
    }

    private fun ensureWritableDir(dir: File): File? {
        return try {
            dir.mkdirs()
            if (dir.exists() && dir.canWrite()) dir else null
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToFile(level: String, message: String, metaData: IdeMetaData? = null) {
        try {
            val timestamp = LocalDateTime.now().format(dateFormatter)
            val metaStr = if (metaData != null) {
                "${metaData.ide}-${metaData.id}"
            } else {
                "Unknown"
            }
            val line = "[$timestamp] [$level] [$metaStr] $message\n"
            logFile.appendText(line)
        } catch (e: Exception) {
            ideaLogger.error("Failed to write to log file", e)
        }
    }

    fun info(message: String, metaData: IdeMetaData? = null) {
        writeToFile("INFO", message, metaData)
    }

    fun warn(message: String, metaData: IdeMetaData? = null) {
        writeToFile("WARN", message, metaData)
    }

    fun warn(message: String, e: Throwable? = null, metaData: IdeMetaData? = null) {
        if (e != null) {
            writeToFile("WARN", "$message\n${e.stackTraceToString()}", metaData)
        } else {
            writeToFile("WARN", message, metaData)
        }
    }

    fun error(message: String, metaData: IdeMetaData? = null) {
        writeToFile("ERROR", message, metaData)
    }

    fun error(message: String, e: Throwable? = null, metaData: IdeMetaData? = null) {
        if (e != null) {
            writeToFile("ERROR", "$message\n${e.stackTraceToString()}", metaData)
        } else {
            writeToFile("ERROR", message, metaData)
        }
    }

    fun withMetaData(metaData: IdeMetaData): MetaDataLogger = MetaDataLogger(metaData)

    fun withProject(project: Project): MetaDataLogger =
        withMetaData(IdeMetaData.getInstance(project))
}

class MetaDataLogger(private val metaData: IdeMetaData) {
    fun info(message: String) {
        Logger.info(message, metaData)
    }

    fun warn(message: String, e: Throwable? = null) {
        Logger.warn(message, e, metaData)
    }

    fun error(message: String, e: Throwable? = null) {
        Logger.error(message, e, metaData)
    }
}
