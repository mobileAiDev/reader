package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class ReaderCatalogPageRefreshPolicyTest {

    @Test
    fun `same route safe title completion repaginates the opened cached chapter`() {
        assertTrue(
            ReaderCatalogPageRefreshPolicy.shouldRepage(
                previousChapter = chapter(
                    link = "source-engine-chapter://wensang/591",
                    title = "第五百九十一章 融合..."
                ),
                refreshedChapter = chapter(
                    link = "source-engine-chapter://wensang/591",
                    title = "第五百九十一章 融合成功！"
                ),
                chapterOpen = true,
                currentPageFinished = true,
                refreshedChapterCached = true
            )
        )
    }

    @Test
    fun `route changes and unsafe title rewrites never repaginate the current chapter`() {
        val previous = chapter(
            link = "source-engine-chapter://wensang/591",
            title = "第五百九十一章 融合..."
        )

        assertFalse(
            ReaderCatalogPageRefreshPolicy.shouldRepage(
                previousChapter = previous,
                refreshedChapter = chapter(
                    link = "source-engine-chapter://other/591",
                    title = "第五百九十一章 融合成功！"
                ),
                chapterOpen = true,
                currentPageFinished = true,
                refreshedChapterCached = true
            )
        )
        assertFalse(
            ReaderCatalogPageRefreshPolicy.shouldRepage(
                previousChapter = previous,
                refreshedChapter = chapter(
                    link = previous.link,
                    title = "第五百九十一章 完全不同"
                ),
                chapterOpen = true,
                currentPageFinished = true,
                refreshedChapterCached = true
            )
        )
    }

    @Test
    fun `unopened unfinished or uncached chapters do not enter the cached repagination path`() {
        val previous = chapter("same-link", "第五百九十一章 融合...")
        val refreshed = chapter("same-link", "第五百九十一章 融合成功！")

        assertFalse(
            ReaderCatalogPageRefreshPolicy.shouldRepage(
                previous,
                refreshed,
                chapterOpen = false,
                currentPageFinished = true,
                refreshedChapterCached = true
            )
        )
        assertFalse(
            ReaderCatalogPageRefreshPolicy.shouldRepage(
                previous,
                refreshed,
                chapterOpen = true,
                currentPageFinished = false,
                refreshedChapterCached = true
            )
        )
        assertFalse(
            ReaderCatalogPageRefreshPolicy.shouldRepage(
                previous,
                refreshed,
                chapterOpen = true,
                currentPageFinished = true,
                refreshedChapterCached = false
            )
        )
    }

    @Test
    fun `cached repagination regenerates both title fields consumed by the canvas`() {
        val cachedBody = "正文第一段\n正文第二段"
        val oldPage = paginate("第五百九十一章 融合...", cachedBody).single()
        val refreshedPage = paginate("第五百九十一章 融合成功！", cachedBody).single()

        assertEquals("第五百九十一章 融合...", oldPage.title)
        assertEquals("第五百九十一章 融合成功！", refreshedPage.title)
        assertEquals("第五百九十一章 融合成功！", refreshedPage.lines!!.first())
        assertEquals(
            oldPage.lines!!.drop(oldPage.titleLines),
            refreshedPage.lines!!.drop(refreshedPage.titleLines)
        )
    }

    private fun paginate(title: String, cachedBody: String): MutableList<TxtPage> {
        return ReaderChapterPaginator.paginate(
            chapter = chapter("same-link", title),
            reader = BufferedReader(StringReader(cachedBody)),
            spec = ReaderChapterPaginationSpec(
                visibleHeight = 1_000,
                titleLineHeight = 10,
                bodyLineHeight = 10,
                titleInterval = 0,
                bodyInterval = 0,
                titleParagraphSpacing = 0,
                bodyParagraphSpacing = 0,
                breakTitle = String::length,
                breakBody = String::length,
                formatBodyParagraph = ReaderBodyParagraphFormatter::format
            )
        )
    }

    private fun chapter(link: String?, title: String?) = TxtChapter().apply {
        this.link = link
        this.title = title
    }
}
