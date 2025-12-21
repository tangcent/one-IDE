package com.oneide.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.oneide.utils.Logger
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException

object LocalStorage {
    private var testFile: File? = null

    fun setStorageFile(file: File) {
        testFile = file
    }

    private val storageFile: File
        get() {
            testFile?.let { return it }
            val home = System.getProperty("user.home")
            val dir = File(home, ".one-ide")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "local-storage.json")
        }

    private val gson = Gson()

    /**
     * Executes the given block within a file lock.
     *
     * @param shared If true, acquires a shared lock (read-only) and does not write back to the file.
     *               If false, acquires an exclusive lock (read-write) and writes changes back to the file.
     * @param block The code block to execute with the map representation of the storage.
     * @return The result of the block execution.
     */
    fun <R> withLock(shared: Boolean, block: (MutableMap<String, Any>) -> R): R? {
        return synchronized(this) {
            executeWithFileLock(shared) { raf ->
                val content = readFile(raf)
                val map: MutableMap<String, Any> = if (content.isNotEmpty()) {
                    try {
                        gson.fromJson(content, object : TypeToken<MutableMap<String, Any>>() {}.type)
                    } catch (e: Exception) {
                        mutableMapOf()
                    }
                } else {
                    mutableMapOf()
                }

                val result = block(map)
                
                // Only write back if not shared (exclusive lock implies write intent)
                if (!shared) {
                    writeFile(raf, gson.toJson(map))
                }
                
                result
            }
        }
    }

    /**
     * Retrieves data associated with the given key.
     *
     * @param key The key to retrieve.
     * @param classOfT The class of the data type.
     * @return The data if found, null otherwise.
     */
    fun <T> getData(key: String, classOfT: Class<T>): T? {
        return synchronized(this) {
            executeWithFileLock(true) { raf ->
                val content = readFile(raf)
                if (content.isEmpty()) return@executeWithFileLock null
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(content, type) ?: return@executeWithFileLock null
                val rawValue = map[key] ?: return@executeWithFileLock null
                val jsonString = gson.toJson(rawValue)
                gson.fromJson(jsonString, classOfT)
            }
        }
    }

    fun <T> setData(key: String, data: T) {
        synchronized(this) {
            executeWithFileLock(false) { raf ->
                val content = readFile(raf)
                val map: MutableMap<String, Any> = if (content.isNotEmpty()) {
                    try {
                        gson.fromJson(content, object : TypeToken<MutableMap<String, Any>>() {}.type)
                    } catch (e: Exception) {
                        mutableMapOf()
                    }
                } else {
                    mutableMapOf()
                }
                if (data != null) {
                    map[key] = data
                }
                writeFile(raf, gson.toJson(map))
            }
        }
    }

    fun deleteData(key: String) {
        synchronized(this) {
            executeWithFileLock(false) { raf ->
                val content = readFile(raf)
                if (content.isNotEmpty()) {
                    val map: MutableMap<String, Any> = try {
                        gson.fromJson(content, object : TypeToken<MutableMap<String, Any>>() {}.type)
                    } catch (e: Exception) {
                        return@executeWithFileLock
                    }
                    if (map.containsKey(key)) {
                        map.remove(key)
                        writeFile(raf, gson.toJson(map))
                    }
                }
            }
        }
    }

    fun <T> updateData(key: String, classOfT: Class<T>, transform: (T) -> T) {
        synchronized(this) {
            executeWithFileLock(false) { raf ->
                val content = readFile(raf)
                val map: MutableMap<String, Any> = if (content.isNotEmpty()) {
                    try {
                        gson.fromJson(content, object : TypeToken<MutableMap<String, Any>>() {}.type)
                    } catch (e: Exception) {
                        mutableMapOf()
                    }
                } else {
                    mutableMapOf()
                }

                val rawValue = map[key]
                val currentVal: T? = if (rawValue != null) {
                    val jsonString = gson.toJson(rawValue)
                    gson.fromJson(jsonString, classOfT)
                } else {
                    null
                }

                // If currentVal is null, we can't really update it unless transform handles null?
                // The interface says (T->T), implying T is not null. 
                // But let's assume if it's missing, we can't update.
                if (currentVal != null) {
                    val newVal = transform(currentVal)
                    map[key] = newVal as Any
                    writeFile(raf, gson.toJson(map))
                }
            }
        }
    }

    private fun readFile(raf: RandomAccessFile): String {
        raf.seek(0)
        val length = raf.length()
        if (length == 0L) return ""
        val bytes = ByteArray(length.toInt())
        raf.readFully(bytes)
        return String(bytes)
    }

    private fun writeFile(raf: RandomAccessFile, content: String) {
        raf.seek(0)
        raf.setLength(0)
        raf.write(content.toByteArray())
    }

    private fun <R> executeWithFileLock(shared: Boolean, block: (RandomAccessFile) -> R): R? {
        val lockDir = File(storageFile.parentFile, "local-storage.lock")
        val maxWait = 5000L
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < maxWait) {
            if (lockDir.mkdir()) {
                try {
                    return RandomAccessFile(storageFile, "rw").use { raf ->
                        block(raf)
                    }
                } finally {
                    lockDir.delete()
                }
            } else {
                if (System.currentTimeMillis() - lockDir.lastModified() > 10000) {
                    lockDir.delete()
                }
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }
}
