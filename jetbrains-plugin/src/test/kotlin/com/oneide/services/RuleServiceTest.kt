package com.oneide.services

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleServiceTest {

    @Test
    fun `test FolderRuleBuilder maintains file structure but ensures markdown extension`() {
        val builder = FolderRuleBuilder(".trae/rules")
        val sourceFiles = listOf(
            RuleFile("some/path/rule1.md", "content1"),
            RuleFile("rule2", "content2") // Missing extension
        )

        val rules = builder.buildRules(sourceFiles, "Cursor")

        assertEquals(2, rules.size)
        assertEquals(".trae/rules/rule1.md", rules[0].path)
        assertEquals("content1", rules[0].content)
        
        assertEquals(".trae/rules/rule2.md", rules[1].path)
        assertEquals("content2", rules[1].content)
    }

    @Test
    fun `test SingleFileRuleBuilder concatenates files`() {
        val builder = SingleFileRuleBuilder(".codiumai.toml")
        val sourceFiles = listOf(
            RuleFile("rule1.md", "content1", 100),
            RuleFile("rule2.md", "content2", 200)
        )

        val rules = builder.buildRules(sourceFiles, "Cursor")

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals(".codiumai.toml", rule.path)
        assertEquals(200, rule.lastModified)
        
        // Check content format
        val expectedContent = "content1\n\ncontent2\n\n"
        assertEquals(expectedContent, rule.content)
    }
}
