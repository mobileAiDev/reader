package com.ldp.reader.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaShelfRouteSnapshotPolicyTest {
    @Test
    fun `refresh from another source cannot replace the saved chapter with its first chapter`() {
        val savedSourceA = snapshot("book", "source-a", "chapter-a")
        val refreshedSourceB = snapshot("book", "source-b", "chapter-b")

        val selected = MediaShelfRouteSnapshotPolicy.forChapter(
            currentSnapshot = refreshedSourceB,
            persistedSnapshot = savedSourceA,
            chapterRouteId = "chapter-a"
        )

        assertEquals("source-a", selected.book.source.sourceName)
        assertEquals(listOf("chapter-a"), selected.chapters.map { it.routeId })
    }

    @Test
    fun `stale old route retained in refreshed registry is not owned by the new book source`() {
        val savedSourceA = snapshot("book", "source-a", "chapter-a")
        val refreshedSourceB = snapshot("book", "source-b", "chapter-b").let { sourceB ->
            sourceB.copy(chapters = savedSourceA.chapters + sourceB.chapters)
        }

        val selected = MediaShelfRouteSnapshotPolicy.forChapter(
            currentSnapshot = refreshedSourceB,
            persistedSnapshot = savedSourceA,
            chapterRouteId = "chapter-a"
        )

        assertEquals("source-a", selected.book.source.sourceName)
        assertEquals(listOf("chapter-a"), selected.chapters.map { it.routeId })
    }

    @Test
    fun `resolved route from refreshed source becomes the focused saved chapter`() {
        val savedSourceA = snapshot("book", "source-a", "chapter-a")
        val refreshedSourceB = snapshot("book", "source-b", "chapter-b")

        val selected = MediaShelfRouteSnapshotPolicy.forChapter(
            currentSnapshot = refreshedSourceB,
            persistedSnapshot = savedSourceA,
            chapterRouteId = "chapter-b"
        )

        assertEquals("source-b", selected.book.source.sourceName)
        assertEquals(listOf("chapter-b"), selected.chapters.map { it.routeId })
    }

    private fun snapshot(bookRouteId: String, sourceName: String, chapterRouteId: String): MediaRouteSnapshot {
        val source = MediaSourceDefinition(
            sourceName = sourceName,
            sourceUrl = "https://$sourceName.example",
            sourceType = MediaSourceType.COMIC,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = MediaLegadoRuleSet("", emptyMap()),
            ruleBookInfo = MediaLegadoRuleSet("", emptyMap()),
            ruleToc = MediaLegadoRuleSet("", emptyMap()),
            ruleContent = MediaLegadoRuleSet("", emptyMap()),
            diagnostics = emptyList()
        )
        val book = MediaSourceBook(
            source = source,
            name = "同一本漫画",
            author = "作者",
            bookUrl = "https://$sourceName.example/book",
            coverUrl = "",
            intro = "",
            kind = "comic",
            lastChapter = "第1话"
        )
        return MediaRouteSnapshot(
            kind = ReaderMediaKind.COMIC,
            bookRouteId = bookRouteId,
            book = book,
            chapters = listOf(
                MediaRouteChapterSnapshot(
                    routeId = chapterRouteId,
                    chapter = MediaSourceChapter(
                        source = source,
                        book = book,
                        index = 0,
                        name = "第1话",
                        chapterUrl = "https://$sourceName.example/chapter/1"
                    )
                )
            )
        )
    }
}
