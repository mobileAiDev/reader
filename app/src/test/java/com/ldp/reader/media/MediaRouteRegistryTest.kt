package com.ldp.reader.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRouteRegistryTest {
    @Test
    fun registerChapterReusesRouteForSameBookChapter() {
        MediaRouteRegistry.clearForTest()
        val bookRouteId = MediaRouteRegistry.registerBook(ReaderMediaKind.AUDIO, sampleBook())
        val chapter = sampleChapter(0, "第1集", "https://audio.example/chapter/1")

        val firstRouteId = MediaRouteRegistry.registerChapter(ReaderMediaKind.AUDIO, chapter, bookRouteId)
        val secondRouteId = MediaRouteRegistry.registerChapter(ReaderMediaKind.AUDIO, chapter, bookRouteId)

        assertEquals(firstRouteId, secondRouteId)
        assertEquals(bookRouteId, MediaRouteRegistry.bookRouteForChapter(firstRouteId))
        assertEquals(1, MediaRouteRegistry.chaptersForBookRoute(bookRouteId).size)
    }

    @Test
    fun snapshotRestoresBookDetailAlternatesAndChapterRoutes() {
        MediaRouteRegistry.clearForTest()
        val primary = sampleBook(name = "凡人修仙传")
        val alternate = sampleBook(name = "凡人修仙传", sourceName = "备用源", sourceUrl = "https://backup.example")
        val bookRouteId = MediaRouteRegistry.registerBook(ReaderMediaKind.AUDIO, primary, listOf(alternate))
        val detail = sampleDetail(primary)
        MediaRouteRegistry.registerDetail(bookRouteId, detail)
        val firstChapterRoute = MediaRouteRegistry.registerChapter(
            ReaderMediaKind.AUDIO,
            sampleChapter(0, "第1集", "https://audio.example/chapter/1", primary),
            bookRouteId
        )
        val secondChapterRoute = MediaRouteRegistry.registerChapter(
            ReaderMediaKind.AUDIO,
            sampleChapter(1, "第2集", "https://audio.example/chapter/2", primary),
            bookRouteId
        )
        val snapshot = MediaRouteRegistry.snapshotBookRoute(bookRouteId)

        MediaRouteRegistry.clearForTest()
        MediaRouteRegistry.restore(requireNotNull(snapshot))

        assertNotNull(MediaRouteRegistry.book(bookRouteId))
        assertEquals(detail, MediaRouteRegistry.detail(bookRouteId))
        assertEquals(listOf(alternate), MediaRouteRegistry.alternates(bookRouteId))
        assertEquals(bookRouteId, MediaRouteRegistry.bookRouteForChapter(firstChapterRoute))
        assertEquals(bookRouteId, MediaRouteRegistry.bookRouteForChapter(secondChapterRoute))
        val restoredChapters = MediaRouteRegistry.chaptersForBookRoute(bookRouteId)
        assertEquals(listOf(firstChapterRoute, secondChapterRoute), restoredChapters.map { it.routeId })
        assertTrue(restoredChapters.all { MediaRouteRegistry.kind(it.routeId) == ReaderMediaKind.AUDIO })
    }

    private fun sampleSource(
        sourceName: String = "主源",
        sourceUrl: String = "https://audio.example"
    ): MediaSourceDefinition {
        val emptyRules = MediaLegadoRuleSet("", emptyMap())
        return MediaSourceDefinition(
            sourceName = sourceName,
            sourceUrl = sourceUrl,
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

    private fun sampleBook(
        name: String = "凡人修仙传",
        sourceName: String = "主源",
        sourceUrl: String = "https://audio.example"
    ): MediaSourceBook {
        return MediaSourceBook(
            source = sampleSource(sourceName, sourceUrl),
            name = name,
            author = "忘语",
            bookUrl = "$sourceUrl/book/fanren",
            coverUrl = "$sourceUrl/cover.jpg",
            intro = "修仙故事",
            kind = "audio",
            lastChapter = "第2集"
        )
    }

    private fun sampleDetail(book: MediaSourceBook): MediaSourceBookDetail {
        return MediaSourceBookDetail(
            book = book,
            name = book.name,
            author = book.author,
            coverUrl = book.coverUrl,
            intro = book.intro,
            kind = book.kind,
            lastChapter = book.lastChapter,
            tocUrl = book.bookUrl + "/toc"
        )
    }

    private fun sampleChapter(
        index: Int,
        name: String,
        chapterUrl: String,
        book: MediaSourceBook = sampleBook()
    ): MediaSourceChapter {
        return MediaSourceChapter(
            source = book.source,
            book = book,
            index = index,
            name = name,
            chapterUrl = chapterUrl
        )
    }
}
