package com.ldp.reader.source

import com.ldp.reader.utils.BookIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SourceEngineChapterContentCacheKeyTest {
    @Test
    fun keepsTitleForBackendChapters() {
        assertEquals(
            "第一章 正文",
            SourceEngineChapterContentCacheKey.fileName(
                bookId = "backend_book",
                title = "第一章 正文",
                link = "https://example.com/chapter/1"
            )
        )
    }

    @Test
    fun usesChapterRouteForSourceEngineShelfBooks() {
        val shelfBookId = BookIdentity.sourceEngineShelfId("叩问仙道", "雨打青石")
        val chapterRoute = "source_engine_chapter_abc123"

        assertEquals(
            chapterRoute,
            SourceEngineChapterContentCacheKey.fileName(
                bookId = shelfBookId,
                title = "第二千六百九十七章 西绝剑城",
                link = chapterRoute
            )
        )
    }

    @Test
    fun usesChapterRouteWhenOnlyLinkIdentifiesSourceEngine() {
        val chapterRoute = "source_engine_chapter_abc123"

        assertEquals(
            chapterRoute,
            SourceEngineChapterContentCacheKey.fileName(
                bookId = "legacy-book-id",
                title = "第二千六百九十七章 西绝剑城",
                link = chapterRoute
            )
        )
    }

    @Test
    fun sourceEngineShelfBookRequiresChapterRoute() {
        val shelfBookId = BookIdentity.sourceEngineShelfId("叩问仙道", "雨打青石")

        try {
            SourceEngineChapterContentCacheKey.fileName(
                bookId = shelfBookId,
                title = "第二千六百九十七章 西绝剑城",
                link = null
            )
            fail("Expected source-engine cache key creation to reject missing chapter routes.")
        } catch (_: IllegalArgumentException) {
        }
    }
}
