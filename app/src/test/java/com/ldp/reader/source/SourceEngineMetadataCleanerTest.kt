package com.ldp.reader.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceEngineMetadataCleanerTest {
    @Test
    fun cleanIntroDecodesEntitiesAndDropsTags() {
        val intro = "&lt;p&gt;第一段&nbsp;&amp;nbsp;第二段&lt;/p&gt;&lgt;<br>尾声"

        assertEquals("第一段 第二段 尾声", SourceEngineMetadataCleaner.cleanIntro(intro))
    }

    @Test
    fun cleanContentKeepsReadableParagraphs() {
        val content = "第一段&nbsp;<br><p>第二段&amp;nbsp;</p><span>第三段</span>"

        assertEquals(
            listOf("第一段", "第二段", "第三段").joinToString("\n"),
            SourceEngineMetadataCleaner.cleanContent(content)
        )
    }
}
