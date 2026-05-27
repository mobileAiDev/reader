package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkState
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceEnginePersistedCatalogMarksTest {
    @Test
    fun restoresPersistedMarkByExactChapterLink() {
        val source = source("https://exact.example")
        val book = book(source, "https://exact.example/book/1")
        val incoming = chapterBean(chapter(book, 3))
        val persisted = chapterBean(chapter(book, 3)).applyMark(V5ChapterMarkState.WRONG)

        val result = SourceEnginePersistedCatalogMarks.mergeInto(listOf(incoming), listOf(persisted))

        assertEquals(1, result.restored)
        assertEquals(1, result.exactRestored)
        assertEquals(0, result.identityRestored)
        assertEquals(V5ChapterMarkState.WRONG.name, incoming.sourceIntegrityState)
        assertEquals(0.8, incoming.sourceIntegrityConfidence, 0.0)
        assertEquals(reason("cached"), incoming.sourceIntegrityReason)
        assertEquals(1, SourceEnginePersistedCatalogMarks.countHidden(listOf(incoming)))
    }

    @Test
    fun preservesIncomingMarkWhenItAlreadyExists() {
        val source = source("https://incoming.example")
        val book = book(source, "https://incoming.example/book/1")
        val incoming = chapterBean(chapter(book, 4)).applyMark(V5ChapterMarkState.NORMAL)
        val persisted = chapterBean(chapter(book, 4)).applyMark(V5ChapterMarkState.BAD_EXTRACTION)

        val result = SourceEnginePersistedCatalogMarks.mergeInto(listOf(incoming), listOf(persisted))

        assertEquals(0, result.restored)
        assertEquals(V5ChapterMarkState.NORMAL.name, incoming.sourceIntegrityState)
    }

    @Test
    fun restoresBySameSourceBookIdentityIndexAndTitleWhenBookUrlDiffers() {
        val source = source("https://identity.example")
        val persistedBook = book(source, "https://identity.example/book/original")
        val incomingBook = book(source, "https://identity.example/book/current")
        val incoming = chapterBean(chapter(incomingBook, 8, "第八章 山中旧事"))
        val persisted = chapterBean(chapter(persistedBook, 8, "第八章 山中旧事"))
            .applyMark(V5ChapterMarkState.NON_STORY)

        val result = SourceEnginePersistedCatalogMarks.mergeInto(listOf(incoming), listOf(persisted))

        assertEquals(1, result.restored)
        assertEquals(0, result.exactRestored)
        assertEquals(1, result.identityRestored)
        assertEquals(V5ChapterMarkState.NON_STORY.name, incoming.sourceIntegrityState)
    }

    @Test
    fun doesNotRestoreAcrossDifferentSources() {
        val persistedSource = source("https://persisted.example")
        val incomingSource = source("https://incoming.example")
        val persistedBook = book(persistedSource, "https://persisted.example/book/1")
        val incomingBook = book(incomingSource, "https://incoming.example/book/1")
        val incoming = chapterBean(chapter(incomingBook, 5, "第五章 同名"))
        val persisted = chapterBean(chapter(persistedBook, 5, "第五章 同名"))
            .applyMark(V5ChapterMarkState.WRONG)

        val result = SourceEnginePersistedCatalogMarks.mergeInto(listOf(incoming), listOf(persisted))

        assertEquals(0, result.restored)
        assertNull(incoming.sourceIntegrityState)
    }

    @Test
    fun doesNotRestoreWhenChapterTitleDiffers() {
        val source = source("https://title.example")
        val persistedBook = book(source, "https://title.example/book/original")
        val incomingBook = book(source, "https://title.example/book/current")
        val incoming = chapterBean(chapter(incomingBook, 6, "第六章 新标题"))
        val persisted = chapterBean(chapter(persistedBook, 6, "第六章 旧标题"))
            .applyMark(V5ChapterMarkState.WRONG)

        val result = SourceEnginePersistedCatalogMarks.mergeInto(listOf(incoming), listOf(persisted))

        assertEquals(0, result.restored)
        assertNull(incoming.sourceIntegrityState)
    }

    @Test
    fun ignoresAndClearsPersistedMarksWithoutCurrentSchema() {
        val source = source("https://schema.example")
        val book = book(source, "https://schema.example/book/1")
        val incoming = chapterBean(chapter(book, 7)).apply {
            sourceIntegrityState = V5ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = "cached"
        }
        val persisted = chapterBean(chapter(book, 7)).apply {
            sourceIntegrityState = V5ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = "cached"
        }

        val result = SourceEnginePersistedCatalogMarks.mergeInto(listOf(incoming), listOf(persisted))

        assertEquals(0, result.restored)
        assertNull(incoming.sourceIntegrityState)
        assertEquals(0.0, incoming.sourceIntegrityConfidence, 0.0)
        assertNull(incoming.sourceIntegrityReason)
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

    private fun BookChapterBean.applyMark(state: V5ChapterMarkState): BookChapterBean {
        sourceIntegrityState = state.name
        sourceIntegrityConfidence = 0.8
        sourceIntegrityReason = reason("cached")
        return this
    }

    private fun reason(value: String): String {
        return sourceIntegrityPersistedReason(listOf(value))
    }
}
