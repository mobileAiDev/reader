package com.ldp.reader.media

import com.ldp.reader.media.MediaLegadoRuleSet
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceType
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceBookDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogCompletenessTest {
    @Test
    fun readsExpectedCountFromLatestChapterText() {
        assertEquals(316, MediaCatalogCompleteness.expectedCount(detail("更新至316集")))
        assertEquals(700, MediaCatalogCompleteness.expectedCount(detail("第700话")))
    }

    @Test
    fun prefersFullerCatalogWhenReadableCandidateIsClearlyPartial() {
        assertTrue(
            MediaCatalogCompleteness.shouldPreferFullerCatalog(
                expectedCount = 316,
                readableChapterCount = 9,
                fullerChapterCount = 300
            )
        )
        assertFalse(
            MediaCatalogCompleteness.shouldPreferFullerCatalog(
                expectedCount = 316,
                readableChapterCount = 280,
                fullerChapterCount = 300
            )
        )
        assertTrue(
            MediaCatalogCompleteness.shouldPreferFullerCatalog(
                expectedCount = 316,
                readableChapterCount = 9,
                fullerChapterCount = 24
            )
        )
    }

    private fun detail(lastChapter: String): MediaSourceBookDetail {
        val source = MediaSourceDefinition(
            sourceName = "fixture",
            sourceUrl = "https://example.com",
            sourceType = MediaSourceType.AUDIO,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "/search",
            ruleSearch = MediaLegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = MediaLegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = MediaLegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = MediaLegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
        val book = MediaSourceBook(source, "fixture", "", "https://example.com/book", "", "", "", lastChapter)
        return MediaSourceBookDetail(book, book.name, book.author, "", "", "", lastChapter, book.bookUrl)
    }
}
