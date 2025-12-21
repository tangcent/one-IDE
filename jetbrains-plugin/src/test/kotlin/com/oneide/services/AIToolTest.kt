package com.oneide.services

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AIToolTest {

    private lateinit var aiTool: AITool

    @Before
    fun setUp() {
        aiTool = AITool()
    }

    @Test
    fun `test loadAIConfig loads tools correctly`() {
        val configs = aiTool.getAllAIConfigs()
        
        assertFalse("Configs should not be empty", configs.isEmpty())
        
        // Verify standard tools exist
        assertTrue("Should contain Cursor", configs.containsKey("Cursor"))
        assertTrue("Should contain Trae", configs.containsKey("Trae"))
        assertTrue("Should contain Windsurf", configs.containsKey("Windsurf"))
        assertTrue("Should contain Claude", configs.containsKey("Claude"))
        assertTrue("Should contain Copilot", configs.containsKey("Copilot"))
        assertTrue("Should contain JetBrains", configs.containsKey("JetBrains"))
        assertTrue("Should contain Qodo", configs.containsKey("Qodo"))
        
        // Verify specific details for one tool (e.g., Trae)
        val traeConfig = configs["Trae"]
        assertNotNull(traeConfig)
        assertEquals("Trae", traeConfig!!.name)
        assertTrue("Should contain Trae plugin ID", traeConfig.plugins.contains("com.marscode"))
        assertEquals(".trae/rules", traeConfig.ruleRoot)
    }

    @Test
    fun `test resolveTool returns configured tool`() {
        // Should return configured tool regardless of plugins
        val tool = aiTool.resolveTool("Cursor") { false }
        assertEquals("Cursor", tool)
        
        val tool2 = aiTool.resolveTool("Windsurf") { true }
        assertEquals("Windsurf", tool2)
    }

    @Test
    fun `test resolveTool returns Auto detected tool`() {
        // Mock plugin installed for Trae
        val tool = aiTool.resolveTool("Auto") { id -> 
            id == "com.marscode" 
        }
        assertEquals("Trae", tool)
    }

    @Test
    fun `test resolveTool returns null when no plugin detected`() {
        val tool = aiTool.resolveTool("Auto") { false }
        assertNull(tool)
    }

    @Test
    fun `test resolveTool prioritizes based on order in JSON`() {
        // Based on JSON order: Cursor, Trae, Windsurf...
        // If both Trae and Windsurf are installed, Trae should be picked because it comes first in JSON
        
        val tool = aiTool.resolveTool("Auto") { id -> 
            id == "com.marscode" || id == "com.codeium.intellij"
        }
        assertEquals("Trae", tool)
    }
}
