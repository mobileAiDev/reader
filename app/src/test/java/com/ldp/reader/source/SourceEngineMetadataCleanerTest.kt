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
    fun cleanIntroRemovesReaderWrapperAndActionTail() {
        val intro = "手机阅读《剑烛大荒》无弹窗纯文字全文免费阅读 “烛龙不是龙，却是世间最后一根烛。” 下载地址 加入书架 投推荐票 直达底部"

        assertEquals(
            "“烛龙不是龙，却是世间最后一根烛。”",
            SourceEngineMetadataCleaner.cleanIntro(intro)
        )
    }

    @Test
    fun cleanIntroRemovesTrailingAuthorBookPromo() {
        val intro = "“烛龙不是龙，却是世间最后一根烛。” 爱潜水的乌贼所写的《剑烛大荒》"

        assertEquals(
            "“烛龙不是龙，却是世间最后一根烛。”",
            SourceEngineMetadataCleaner.cleanIntro(intro)
        )
    }

    @Test
    fun cleanContentKeepsReadableParagraphs() {
        val content = "第一段&nbsp;<br><p>第二段&amp;nbsp;</p><span>第三段</span>"

        assertEquals(
            listOf("第一段", "第二段", "第三段").joinToString("\n"),
            SourceEngineMetadataCleaner.cleanContent(content)
        )
    }

    @Test
    fun cleanContentRemovesLeadingEntityEqualsArtifacts() {
        val content = "&nbsp;=&nbsp;=&nbsp;=&nbsp;正文第一段<br>&nbsp;=&nbsp;=&nbsp;第二段"

        assertEquals(
            listOf("正文第一段", "第二段").joinToString("\n"),
            SourceEngineMetadataCleaner.cleanContent(content)
        )
    }
}
