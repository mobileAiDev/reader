package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkResult
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkState
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.widget.page.TxtChapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceEngineCatalogMarkRegistryTest {
    @After
    fun tearDown() {
        SourceEngineCatalogMarkRegistry.clearForTest()
    }

    @Test
    fun appliesMarksByExactSourceBookAndChapterIndex() {
        val source = source("https://exact.example")
        val book = book(source, "https://exact.example/book/1")
        val chapter = chapter(book, 7)
        val bean = chapterBean(chapter)

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "exact",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(7, V5ChapterMarkState.WRONG))
        )

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(listOf(bean)))
        assertEquals(V5ChapterMarkState.WRONG.name, bean.sourceIntegrityState)
        assertEquals(1, SourceEngineCatalogMarkRegistry.countMatchingBookChapters(listOf(bean)))
    }

    @Test
    fun fallsBackToSameSourceBookIdentityWhenBookUrlDiffers() {
        val source = source("https://identity.example")
        val validatedBook = book(source, "https://identity.example/book/original")
        val displayedBook = book(source, "https://identity.example/book/displayed")
        val bean = chapterBean(chapter(displayedBook, 12))

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, validatedBook.bookUrl),
            "identity",
            source.sourceUrl,
            validatedBook.name,
            validatedBook.author,
            listOf(mark(12, V5ChapterMarkState.BAD_EXTRACTION))
        )

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(listOf(bean)))
        assertEquals(V5ChapterMarkState.BAD_EXTRACTION.name, bean.sourceIntegrityState)
        assertEquals(1, SourceEngineCatalogMarkRegistry.countMatchingBookChapters(listOf(bean)))
    }

    @Test
    fun fallsBackAcrossDifferentSourcesOnlyByChapterTitle() {
        val validatedSource = source("https://validated.example")
        val displayedSource = source("https://displayed.example")
        val validatedBook = book(validatedSource, "https://validated.example/book/1")
        val displayedBook = book(displayedSource, "https://displayed.example/book/1")
        val bean = chapterBean(chapter(displayedBook, 9, "Shared Chapter"))

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(validatedSource.sourceUrl, validatedBook.bookUrl),
            "validated",
            validatedSource.sourceUrl,
            validatedBook.name,
            validatedBook.author,
            listOf(mark(3, "Shared Chapter", V5ChapterMarkState.WRONG))
        )

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(listOf(bean)))
        assertEquals(V5ChapterMarkState.WRONG.name, bean.sourceIntegrityState)
        assertEquals(1, SourceEngineCatalogMarkRegistry.countMatchingBookChapters(listOf(bean)))
    }

    @Test
    fun doesNotApplyCrossSourceMarksByIndexWhenTitleDiffers() {
        val validatedSource = source("https://validated.example")
        val displayedSource = source("https://displayed.example")
        val validatedBook = book(validatedSource, "https://validated.example/book/1")
        val displayedBook = book(displayedSource, "https://displayed.example/book/1")
        val bean = chapterBean(chapter(displayedBook, 3, "Displayed Chapter"))

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(validatedSource.sourceUrl, validatedBook.bookUrl),
            "validated",
            validatedSource.sourceUrl,
            validatedBook.name,
            validatedBook.author,
            listOf(mark(3, "Validated Chapter", V5ChapterMarkState.WRONG))
        )

        assertEquals(0, SourceEngineCatalogMarkRegistry.applyToBookChapters(listOf(bean)))
        assertEquals(null, bean.sourceIntegrityState)
        assertEquals(0, SourceEngineCatalogMarkRegistry.countMatchingBookChapters(listOf(bean)))
    }

    @Test
    fun clearsStaleBookChapterMarkWhenRegistryHasNoMatchingMark() {
        val source = source("https://empty-registry.example")
        val book = book(source, "https://empty-registry.example/book/1")
        val bean = chapterBean(chapter(book, 5)).apply {
            sourceIntegrityState = V5ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = "cached"
        }

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(listOf(bean)))
        assertEquals(null, bean.sourceIntegrityState)
        assertEquals(0.0, bean.sourceIntegrityConfidence, 0.0)
        assertEquals(null, bean.sourceIntegrityReason)
    }

    @Test
    fun clearsStaleTxtChapterMarkWhenRegistryHasNoMatchingMark() {
        val source = source("https://empty-txt-registry.example")
        val book = book(source, "https://empty-txt-registry.example/book/1")
        val txtChapter = txtChapter(chapter(book, 6)).apply {
            sourceIntegrityState = V5ChapterMarkState.BAD_EXTRACTION.name
            sourceIntegrityConfidence = 0.7
            sourceIntegrityReason = "cached"
        }

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyTo(listOf(txtChapter)))
        assertEquals(null, txtChapter.sourceIntegrityState)
        assertEquals(0.0, txtChapter.sourceIntegrityConfidence, 0.0)
        assertEquals(null, txtChapter.sourceIntegrityReason)
    }

    @Test
    fun clearsExistingMarkWhenSameSourceBookV5RunOmitsChapter() {
        val source = source("https://clear-stale.example")
        val book = book(source, "https://clear-stale.example/book/1")
        val bean = chapterBean(chapter(book, 8)).apply {
            sourceIntegrityState = V5ChapterMarkState.NON_STORY.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = "old"
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "clear",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(9, V5ChapterMarkState.WRONG))
        )

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(listOf(bean)))
        assertEquals(null, bean.sourceIntegrityState)
        assertEquals(0.0, bean.sourceIntegrityConfidence, 0.0)
        assertEquals(null, bean.sourceIntegrityReason)
    }

    @Test
    fun clearsExactSourceIndexMarksWhenCatalogIdentityChanged() {
        val source = source("https://catalog-change.example")
        val book = book(source, "https://catalog-change.example/book/1")
        val chapters = (1..13).map { index -> chapterBean(chapter(book, index)) }
        val target = chapters[7].apply {
            sourceIntegrityState = V5ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = "old"
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "old",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(8, V5ChapterMarkState.WRONG)),
            catalogIdentity = SourceEngineCatalogMarkRegistry.catalogIdentity((1..10).map { index -> "Chapter $index" })
        )

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(chapters))
        assertEquals(null, target.sourceIntegrityState)
        assertEquals(0.0, target.sourceIntegrityConfidence, 0.0)
        assertEquals(null, target.sourceIntegrityReason)
        assertEquals(0, SourceEngineCatalogMarkRegistry.countMatchingBookChapters(chapters))
    }

    @Test
    fun usesCrossSourceTitleMarkWhenExactSourceCatalogIdentityChanged() {
        val staleSource = source("https://stale-exact.example")
        val freshSource = source("https://fresh-title.example")
        val staleBook = book(staleSource, "https://stale-exact.example/book/1")
        val freshBook = book(freshSource, "https://fresh-title.example/book/1")
        val displayedChapters = (1..13).map { index ->
            val title = if (index == 8) "Shared Chapter" else "Chapter $index"
            chapterBean(chapter(staleBook, index, title))
        }
        val target = displayedChapters[7]

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(staleSource.sourceUrl, staleBook.bookUrl),
            "stale",
            staleSource.sourceUrl,
            staleBook.name,
            staleBook.author,
            listOf(mark(8, "Shared Chapter", V5ChapterMarkState.WRONG)),
            catalogIdentity = SourceEngineCatalogMarkRegistry.catalogIdentity((1..10).map { index -> "Chapter $index" })
        )
        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(freshSource.sourceUrl, freshBook.bookUrl),
            "fresh",
            freshSource.sourceUrl,
            freshBook.name,
            freshBook.author,
            listOf(mark(8, "Shared Chapter", V5ChapterMarkState.NORMAL)),
            catalogIdentity = SourceEngineCatalogMarkRegistry.catalogIdentity(
                (1..13).map { index -> if (index == 8) "Shared Chapter" else "Chapter $index" }
            )
        )

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(displayedChapters))
        assertEquals(V5ChapterMarkState.NORMAL.name, target.sourceIntegrityState)
        assertEquals(1, SourceEngineCatalogMarkRegistry.countMatchingBookChapters(displayedChapters))
    }

    @Test
    fun replacesExistingBadMarkWhenV5ReturnsNormalForSameChapter() {
        val source = source("https://normal.example")
        val book = book(source, "https://normal.example/book/1")
        val bean = chapterBean(chapter(book, 8)).apply {
            sourceIntegrityState = V5ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = "cached"
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "normal",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(8, V5ChapterMarkState.NORMAL))
        )

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(listOf(bean)))
        assertEquals(V5ChapterMarkState.NORMAL.name, bean.sourceIntegrityState)
        assertEquals(0.9, bean.sourceIntegrityConfidence, 0.0)
        assertEquals(reason("test"), bean.sourceIntegrityReason)
    }

    @Test
    fun v5WrongMarkOverridesReadableContentRuntimeMarkForExactDisplayedChapter() {
        val source = source("https://runtime-readable.example")
        val book = book(source, "https://runtime-readable.example/book/1")
        val txtChapter = txtChapter(chapter(book, 8)).apply {
            sourceIntegrityState = V5ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = "v5"
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "v5",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(8, V5ChapterMarkState.WRONG))
        )

        assertEquals(true, SourceEngineCatalogMarkRegistry.recordReadableContent(txtChapter))
        assertEquals(V5ChapterMarkState.NORMAL.name, txtChapter.sourceIntegrityState)
        assertEquals(1.0, txtChapter.sourceIntegrityConfidence, 0.0)
        assertEquals(reason("runtime readable content v2"), txtChapter.sourceIntegrityReason)

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyTo(listOf(txtChapter)))
        assertEquals(V5ChapterMarkState.WRONG.name, txtChapter.sourceIntegrityState)
        assertEquals(reason("test"), txtChapter.sourceIntegrityReason)
    }

    @Test
    fun readableContentOverrideDoesNotClearNeighborChapterWithSameSourceAndIndex() {
        val source = source("https://runtime-neighbor.example")
        val book = book(source, "https://runtime-neighbor.example/book/1")
        val readChapter = txtChapter(chapter(book, 8, "Chapter 8"))
        val neighbor = txtChapter(chapter(book, 9, "Chapter 9"))

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "v5",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(8, V5ChapterMarkState.WRONG), mark(9, V5ChapterMarkState.WRONG))
        )
        SourceEngineCatalogMarkRegistry.recordReadableContent(readChapter)

        assertEquals(2, SourceEngineCatalogMarkRegistry.applyTo(listOf(readChapter, neighbor)))
        assertEquals(V5ChapterMarkState.WRONG.name, readChapter.sourceIntegrityState)
        assertEquals(V5ChapterMarkState.WRONG.name, neighbor.sourceIntegrityState)
    }

    @Test
    fun persistedReadableContentOverrideIsReplacedByCachedV5WrongAfterRestart() {
        val source = source("https://runtime-persisted.example")
        val book = book(source, "https://runtime-persisted.example/book/1")
        val bean = chapterBean(chapter(book, 8)).apply {
            sourceIntegrityState = V5ChapterMarkState.NORMAL.name
            sourceIntegrityConfidence = 1.0
            sourceIntegrityReason = "runtime readable content v2"
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "v5",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(8, V5ChapterMarkState.WRONG))
        )

        val stats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(listOf(bean))

        assertEquals(1, stats.changed)
        assertEquals(1, stats.hidden)
        assertEquals(V5ChapterMarkState.WRONG.name, bean.sourceIntegrityState)
        assertEquals(0.9, bean.sourceIntegrityConfidence, 0.0)
        assertEquals(reason("test"), bean.sourceIntegrityReason)
    }

    @Test
    fun oldRuntimeReadableContentOverrideIsReplacedByV5Wrong() {
        val source = source("https://runtime-readable-v1.example")
        val book = book(source, "https://runtime-readable-v1.example/book/1")
        val bean = chapterBean(chapter(book, 8)).apply {
            sourceIntegrityState = V5ChapterMarkState.NORMAL.name
            sourceIntegrityConfidence = 1.0
            sourceIntegrityReason = "runtime readable content"
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "v5",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(8, V5ChapterMarkState.WRONG))
        )

        val stats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(listOf(bean))

        assertEquals(1, stats.changed)
        assertEquals(1, stats.hidden)
        assertEquals(V5ChapterMarkState.WRONG.name, bean.sourceIntegrityState)
        assertEquals(reason("test"), bean.sourceIntegrityReason)
    }

    @Test
    fun oldRuntimeContentMismatchIsReplacedByCachedV5Normal() {
        val source = source("https://runtime-mismatch-persisted.example")
        val book = book(source, "https://runtime-mismatch-persisted.example/book/1")
        val bean = chapterBean(chapter(book, 8)).apply {
            sourceIntegrityState = V5ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 1.0
            sourceIntegrityReason = "runtime conflicting chapter heading"
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "v5",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(8, V5ChapterMarkState.NORMAL))
        )

        val stats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(listOf(bean))

        assertEquals(1, stats.changed)
        assertEquals(0, stats.hidden)
        assertEquals(V5ChapterMarkState.NORMAL.name, bean.sourceIntegrityState)
        assertEquals(reason("test"), bean.sourceIntegrityReason)
    }

    @Test
    fun genericNormalMarkCanStillBeReplacedByV5Wrong() {
        val source = source("https://generic-normal.example")
        val book = book(source, "https://generic-normal.example/book/1")
        val bean = chapterBean(chapter(book, 8)).apply {
            sourceIntegrityState = V5ChapterMarkState.NORMAL.name
            sourceIntegrityConfidence = 0.9
            sourceIntegrityReason = "test"
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "v5",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(8, V5ChapterMarkState.WRONG))
        )

        assertEquals(1, SourceEngineCatalogMarkRegistry.applyToBookChapters(listOf(bean)))
        assertEquals(V5ChapterMarkState.WRONG.name, bean.sourceIntegrityState)
        assertEquals(reason("test"), bean.sourceIntegrityReason)
    }

    @Test
    fun applyStatsReportMatchedAndHiddenMarksEvenWhenValuesAlreadyMatch() {
        val source = source("https://stats.example")
        val book = book(source, "https://stats.example/book/1")
        val bean = chapterBean(chapter(book, 10)).apply {
            sourceIntegrityState = V5ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.9
            sourceIntegrityReason = reason("test")
        }

        SourceEngineCatalogMarkRegistry.record(
            SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            "stats",
            source.sourceUrl,
            book.name,
            book.author,
            listOf(mark(10, V5ChapterMarkState.WRONG))
        )

        val stats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(listOf(bean))

        assertEquals(0, stats.changed)
        assertEquals(1, stats.matched)
        assertEquals(1, stats.hidden)
    }

    private fun source(url: String): BookSource {
        val emptyRules = LegadoRuleSet("empty", emptyMap())
        return BookSource(
            sourceName = "source",
            sourceUrl = url,
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

    private fun book(source: BookSource, url: String): SourceBook {
        return SourceBook(
            source = source,
            name = "Target Book",
            author = "Target Author",
            bookUrl = url,
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }

    private fun chapter(book: SourceBook, index: Int, title: String = "Chapter $index"): SourceChapter {
        return SourceChapter(
            source = book.source,
            book = book,
            index = index,
            name = title,
            chapterUrl = "${book.bookUrl}/$index.html"
        )
    }

    private fun chapterBean(chapter: SourceChapter): BookChapterBean {
        return BookChapterBean().apply {
            link = SourceEngineBookRoute.chapterId(chapter)
            title = chapter.name
            start = chapter.index.toLong()
        }
    }

    private fun txtChapter(chapter: SourceChapter): TxtChapter {
        return TxtChapter().apply {
            link = SourceEngineBookRoute.chapterId(chapter)
            title = chapter.name
            catalogIndex = chapter.index
        }
    }

    private fun mark(index: Int, state: V5ChapterMarkState): V5ChapterMarkResult {
        return mark(index, "Chapter $index", state)
    }

    private fun mark(index: Int, title: String, state: V5ChapterMarkState): V5ChapterMarkResult {
        return V5ChapterMarkResult(
            chapterIndex = index,
            chapterTitle = title,
            state = state,
            confidence = 0.9,
            qualityType = null,
            suggestionState = null,
            action = null,
            reasons = listOf("test")
        )
    }

    private fun reason(value: String): String {
        return sourceIntegrityPersistedReason(listOf(value))
    }
}
