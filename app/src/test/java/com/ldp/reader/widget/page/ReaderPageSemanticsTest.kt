package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageSemanticsTest {

    @Test
    fun visiblePageExposesAHeadingAndSeparateReadableParagraphs() {
        val page = TxtPage().apply {
            title = "第五十一章 边界验证"
            titleLines = 1
            lines = listOf(
                "第五十一章 边界验证",
                "　　第一段第一行",
                "续行\n",
                "　　第二段\n"
            )
        }

        val snapshot = ReaderPageSemantics.fromPage(
            statusText = null,
            chapterTitle = "第五十一章 边界验证",
            page = page
        )

        assertEquals(
            listOf(
                ReaderPageSemanticRole.HEADING,
                ReaderPageSemanticRole.PARAGRAPH,
                ReaderPageSemanticRole.PARAGRAPH
            ),
            snapshot.nodes.map { it.role }
        )
        assertEquals("第五十一章 边界验证", snapshot.nodes[0].text)
        assertEquals("第一段第一行续行", snapshot.nodes[1].text)
        assertEquals("第二段", snapshot.nodes[2].text)
        assertFalse(snapshot.nodes.any { it.text.contains("第五十一章 边界验证第一段") })
    }

    @Test
    fun longVisibleParagraphIsSplitIntoNavigableCodePointSafeNodes() {
        val visibleLines = List(24) { "边界😀正文第${it}行继续阅读" }
        val page = TxtPage().apply {
            title = "长段落章节"
            titleLines = 0
            lines = visibleLines
        }

        val snapshot = ReaderPageSemantics.fromPage(
            statusText = null,
            chapterTitle = page.title,
            page = page
        )
        val bodyNodes = snapshot.nodes.filter { it.role == ReaderPageSemanticRole.PARAGRAPH }

        assertTrue(bodyNodes.size > 1)
        assertTrue(bodyNodes.all { it.text.codePointCount(0, it.text.length) <= 280 })
        assertTrue(bodyNodes.none { it.text.contains('\uFFFD') })
        assertEquals(visibleLines.joinToString(""), bodyNodes.joinToString("") { it.text })
    }

    @Test
    fun loadingOrFailureStatusReplacesStalePageTextWithOneStatusNode() {
        val stalePage = TxtPage().apply {
            title = "旧章节"
            titleLines = 0
            lines = listOf("旧正文\n")
        }

        val snapshot = ReaderPageSemantics.fromPage(
            statusText = "本章加载失败，轻点屏幕左右两侧重试",
            chapterTitle = stalePage.title,
            page = stalePage
        )

        assertEquals(1, snapshot.nodes.size)
        assertEquals(ReaderPageSemanticRole.STATUS, snapshot.nodes.single().role)
        assertEquals("本章加载失败，轻点屏幕左右两侧重试", snapshot.nodes.single().text)
    }
}
