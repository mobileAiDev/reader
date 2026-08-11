package com.ldp.reader.ui.adapter.view

import com.ldp.reader.media.MediaSearchBook
import com.ldp.reader.media.ReaderMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaSearchResultSummaryTest {

    @Test
    fun `comic search summary shows a user count without source maintenance label`() {
        val summary = MediaSearchResultSummary.text(
            book = result(chapterCount = 12),
            kind = ReaderMediaKind.COMIC
        )

        assertEquals("共 12 话 | 作者甲 | 第 12 话 | 简介", summary)
        assertFalse(summary.contains("内部维护源"))
    }

    @Test
    fun `audio search summary uses episode units`() {
        assertEquals(
            "共 8 集 | 作者甲 | 第 8 集 | 简介",
            MediaSearchResultSummary.text(
                book = result(chapterCount = 8, latest = "第 8 集"),
                kind = ReaderMediaKind.AUDIO
            )
        )
    }

    @Test
    fun `audio search summary omits latest when it only repeats the episode count`() {
        assertEquals(
            "共 6 集 | Reader QA | 简介",
            MediaSearchResultSummary.text(
                book = result(
                    chapterCount = 6,
                    latest = "共6集",
                    author = "Reader QA"
                ),
                kind = ReaderMediaKind.AUDIO
            )
        )
    }

    @Test
    fun `audio search summary omits a bare count-only latest value`() {
        assertEquals(
            "共 6 集 | 作者甲 | 简介",
            MediaSearchResultSummary.text(
                book = result(chapterCount = 6, latest = "6 集"),
                kind = ReaderMediaKind.AUDIO
            )
        )
    }

    @Test
    fun `comic search summary omits latest when it only repeats the chapter count`() {
        assertEquals(
            "共 12 话 | 作者甲 | 简介",
            MediaSearchResultSummary.text(
                book = result(chapterCount = 12, latest = "共 12 话"),
                kind = ReaderMediaKind.COMIC
            )
        )
    }

    @Test
    fun `search summary keeps a real latest title`() {
        assertEquals(
            "共 6 集 | 作者甲 | Reader QA 完结篇 | 简介",
            MediaSearchResultSummary.text(
                book = result(chapterCount = 6, latest = "Reader QA 完结篇"),
                kind = ReaderMediaKind.AUDIO
            )
        )
    }

    @Test
    fun `search summary keeps a count-like latest value when the count differs`() {
        assertEquals(
            "共 6 集 | 作者甲 | 共 7 集 | 简介",
            MediaSearchResultSummary.text(
                book = result(chapterCount = 6, latest = "共 7 集"),
                kind = ReaderMediaKind.AUDIO
            )
        )
    }

    private fun result(
        chapterCount: Int,
        latest: String = "第 12 话",
        author: String = "作者甲"
    ) = MediaSearchBook(
        routeId = "route",
        title = "作品",
        author = author,
        coverUrl = "",
        intro = "简介",
        latest = latest,
        sourceName = "内部维护源",
        chapterCount = chapterCount
    )
}
