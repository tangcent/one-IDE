package com.oneide.services

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalStorageTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        val testFile = tempFolder.newFile("test-storage.json")
        LocalStorage.setStorageFile(testFile)
    }

    @Test
    fun `test setData and getData`() {
        val key = "testKey"
        val value = "testValue"
        
        LocalStorage.setData(key, value)
        val retrieved = LocalStorage.getData(key, String::class.java)
        
        assertEquals(value, retrieved)
    }

    @Test
    fun `test withLock modification`() {
        val key = "lockKey"
        val value = "lockValue"
        
        LocalStorage.setData(key, value)
        
        LocalStorage.withLock(false) { map ->
            assertEquals(value, map[key])
            map[key] = "newValue"
        }
        
        val retrieved = LocalStorage.getData(key, String::class.java)
        assertEquals("newValue", retrieved)
    }

    @Test
    fun `test withLock read-only`() {
        val key = "readKey"
        val value = "readValue"

        LocalStorage.setData(key, value)

        LocalStorage.withLock(true) { map ->
            assertEquals(value, map[key])
            map[key] = "ignoredValue" // Should not be persisted
        }

        val retrieved = LocalStorage.getData(key, String::class.java)
        assertEquals(value, retrieved)
    }
    
    @Test
    fun `test deleteData`() {
        val key = "deleteKey"
        val value = "deleteValue"
        
        LocalStorage.setData(key, value)
        assertNotNull(LocalStorage.getData(key, String::class.java))
        
        LocalStorage.deleteData(key)
        assertNull(LocalStorage.getData(key, String::class.java))
    }
    
    @Test
    fun `test updateData`() {
        val key = "updateKey"
        val value = "initial"
        
        LocalStorage.setData(key, value)
        
        LocalStorage.updateData(key, String::class.java) { old ->
            old + "Updated"
        }
        
        val retrieved = LocalStorage.getData(key, String::class.java)
        assertEquals("initialUpdated", retrieved)
    }
}
