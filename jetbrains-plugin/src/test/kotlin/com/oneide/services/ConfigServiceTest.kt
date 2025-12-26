package com.oneide.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.oneide.OneIde
import com.oneide.models.Config
import org.junit.Test
import java.nio.file.Path

class ConfigServiceTest : BasePlatformTestCase() {

    private lateinit var oneIdeDir: Path
    private lateinit var configService: ConfigService

    override fun setUp() {
        super.setUp()
        oneIdeDir = createTempDir("one-ide-test").toPath()
        OneIde.oneIdeDir = oneIdeDir
        configService = ConfigService(project)
        // Wait a bit for file watcher to initialize
        Thread.sleep(100)
    }

    @Test
    fun `test initial config creation`() {
        val configFile = oneIdeDir.resolve("config.json").toFile()
        assertTrue("Config file should be created", configFile.exists())
        
        val config = configService.getConfig()
        assertNotNull("Config should not be null", config)
        assertTrue("Default excludeFiles should be empty", config.excludeFiles.isEmpty())
        assertFalse("Default excludeGitIgnore should be false", config.excludeGitIgnore)
    }

    @Test
    fun `test config persistence`() {
        val newConfig = Config(
            excludeFiles = listOf("*.log", "*.tmp", "build"),
            excludeGitIgnore = true
        )
        
        configService.updateConfig(newConfig)
        // Wait for async write
        Thread.sleep(200)
        
        // Create new service instance to test loading
        val configService2 = ConfigService(project)
        Thread.sleep(100)
        
        val loadedConfig = configService2.getConfig()
        assertEquals("excludeFiles should be persisted", newConfig.excludeFiles, loadedConfig.excludeFiles)
        assertEquals("excludeGitIgnore should be persisted", newConfig.excludeGitIgnore, loadedConfig.excludeGitIgnore)
    }

    @Test
    fun `test shouldSyncFile with no exclusions`() {
        val config = Config(excludeFiles = emptyList(), excludeGitIgnore = false)
        configService.updateConfig(config)
        Thread.sleep(100)
        
        assertTrue("Should sync regular file", configService.shouldSyncFile("/path/to/file.kt"))
        assertTrue("Should sync any file when no exclusions", configService.shouldSyncFile("/path/to/anything.log"))
    }

    @Test
    fun `test shouldSyncFile with excludeFiles patterns`() {
        val config = Config(
            excludeFiles = listOf("*.log", "*.tmp", "build"),
            excludeGitIgnore = false
        )
        configService.updateConfig(config)
        Thread.sleep(100)
        
        assertTrue("Should sync regular file", configService.shouldSyncFile("/path/to/file.kt"))
        assertFalse("Should not sync .log file", configService.shouldSyncFile("/path/to/app.log"))
        assertFalse("Should not sync .tmp file", configService.shouldSyncFile("/path/to/temp.tmp"))
        assertFalse("Should not sync build file", configService.shouldSyncFile("/path/to/build"))
        assertTrue("Should sync file with build in path but not name", configService.shouldSyncFile("/path/to/build/file.kt"))
    }

    @Test
    fun `test shouldSyncFile with complex glob patterns`() {
        val config = Config(
            excludeFiles = listOf("test_*.kt", "*_backup.*", "temp/*"),
            excludeGitIgnore = false
        )
        configService.updateConfig(config)
        Thread.sleep(100)
        
        assertFalse("Should not sync test_ prefixed files", configService.shouldSyncFile("test_file.kt"))
        assertTrue("Should sync regular .kt files", configService.shouldSyncFile("regular.kt"))
        assertFalse("Should not sync backup files", configService.shouldSyncFile("file_backup.txt"))
        assertFalse("Should not sync files in temp directory", configService.shouldSyncFile("temp/file.txt"))
    }

    @Test
    fun `test invalid glob pattern handling`() {
        val config = Config(
            excludeFiles = listOf("[invalid", "*.log"),
            excludeGitIgnore = false
        )
        configService.updateConfig(config)
        Thread.sleep(100)
        
        // Invalid pattern should not cause exceptions, should be ignored
        assertTrue("Should sync file when pattern is invalid", configService.shouldSyncFile("[invalid"))
        assertFalse("Should still apply valid patterns", configService.shouldSyncFile("test.log"))
    }

    @Test
    fun `test config file watcher`() {
        val configFile = oneIdeDir.resolve("config.json").toFile()
        
        // Initial config
        val initialConfig = Config(excludeFiles = listOf("*.log"), excludeGitIgnore = false)
        configService.updateConfig(initialConfig)
        Thread.sleep(200)
        
        // Modify file directly (simulating external change)
        val newConfig = Config(excludeFiles = listOf("*.tmp", "*.bak"), excludeGitIgnore = true)
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        configFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(newConfig))
        
        // Wait for file watcher to detect change
        Thread.sleep(1500)
        
        val currentConfig = configService.getConfig()
        assertEquals("Config should be reloaded from file", newConfig.excludeFiles, currentConfig.excludeFiles)
        assertEquals("Config should be reloaded from file", newConfig.excludeGitIgnore, currentConfig.excludeGitIgnore)
    }

    @Test
    fun `test multiple config updates`() {
        val configs = listOf(
            Config(excludeFiles = listOf("*.log"), excludeGitIgnore = false),
            Config(excludeFiles = listOf("*.tmp"), excludeGitIgnore = true),
            Config(excludeFiles = listOf("*.bak", "*.swp"), excludeGitIgnore = false)
        )
        
        configs.forEach { config ->
            configService.updateConfig(config)
            Thread.sleep(150) // Wait for async write
            
            val currentConfig = configService.getConfig()
            assertEquals("Config should match last update", config.excludeFiles, currentConfig.excludeFiles)
            assertEquals("Config should match last update", config.excludeGitIgnore, currentConfig.excludeGitIgnore)
        }
    }

    @Test
    fun `test empty config file handling`() {
        val configFile = oneIdeDir.resolve("config.json").toFile()
        configFile.writeText("")
        
        // Create new service with empty config file
        val configService2 = ConfigService(project)
        Thread.sleep(100)
        
        val config = configService2.getConfig()
        assertNotNull("Should handle empty config file", config)
        assertTrue("Should use default empty excludeFiles", config.excludeFiles.isEmpty())
        assertFalse("Should use default excludeGitIgnore false", config.excludeGitIgnore)
    }

    @Test
    fun `test malformed config file handling`() {
        val configFile = oneIdeDir.resolve("config.json").toFile()
        configFile.writeText("{ invalid json")
        
        // Create new service with malformed config file
        val configService2 = ConfigService(project)
        Thread.sleep(100)
        
        val config = configService2.getConfig()
        assertNotNull("Should handle malformed config file", config)
        assertTrue("Should use default values", config.excludeFiles.isEmpty())
        assertFalse("Should use default values", config.excludeGitIgnore)
    }
}