package com.ldp.reader.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaShelfChapterLinkTest {
    @Test
    fun resolveReturnsOwningBookAndChapterMetadata() {
        MediaRouteRegistry.clearForTest()
        val book = sampleBook()
        val bookRouteId = MediaRouteRegistry.registerBook(ReaderMediaKind.AUDIO, book)
        val chapterRouteId = MediaRouteRegistry.registerChapter(
            ReaderMediaKind.AUDIO,
            sampleChapter(book),
            bookRouteId
        )

        val target = MediaShelfChapterLink.resolve(ReaderMediaKind.AUDIO, chapterRouteId)

        assertEquals(bookRouteId, target?.bookRouteId)
        assertEquals(chapterRouteId, target?.chapterRouteId)
        assertEquals("第1集", target?.chapterTitle)
        assertEquals(0, target?.chapterIndex)
    }

    @Test
    fun resolveRejectsWrongKindOrMissingRoute() {
        MediaRouteRegistry.clearForTest()
        val book = sampleBook()
        val bookRouteId = MediaRouteRegistry.registerBook(ReaderMediaKind.AUDIO, book)
        val chapterRouteId = MediaRouteRegistry.registerChapter(
            ReaderMediaKind.AUDIO,
            sampleChapter(book),
            bookRouteId
        )

        assertNull(MediaShelfChapterLink.resolve(ReaderMediaKind.COMIC, chapterRouteId))
        assertNull(MediaShelfChapterLink.resolve(ReaderMediaKind.AUDIO, "missing"))
    }

    private fun sampleBook(): MediaSourceBook {
        return MediaSourceBook(
            source = sampleSource(),
            name = "庆余年",
            author = "猫腻",
            bookUrl = "https://audio.example/book/qyn",
            coverUrl = "https://audio.example/cover.jpg",
            intro = "权谋故事",
            kind = "audio",
            lastChapter = "第1集"
        )
    }

    private fun sampleChapter(book: MediaSourceBook): MediaSourceChapter {
        return MediaSourceChapter(
            source = book.source,
            book = book,
            index = 0,
            name = "第1集",
            chapterUrl = "https://audio.example/chapter/1"
        )
    }

    private fun sampleSource(): MediaSourceDefinition {
        val emptyRules = MediaLegadoRuleSet("", emptyMap())
        return MediaSourceDefinition(
            sourceName = "音频源",
            sourceUrl = "https://audio.example",
            sourceType = MediaSourceType.AUDIO,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = emptyRules,
            ruleBookInfo = emptyRules,
            ruleToc = emptyRules,
            ruleContent = emptyRules,
            diagnostics = emptyList()
        )
    }
}
