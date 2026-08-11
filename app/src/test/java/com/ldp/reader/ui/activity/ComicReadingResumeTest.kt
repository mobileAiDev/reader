package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaChapterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicReadingResumeTest {
    private val chapters = listOf(
        MediaChapterItem("chapter-0", "第 1 话", 0),
        MediaChapterItem("chapter-1", "第 2 话", 1),
        MediaChapterItem("chapter-2", "第 3 话", 2)
    )

    @Test
    fun resumesExactSavedChapterInsteadOfFirstChapter() {
        val target = ComicReadingResume.chapter(chapters, "chapter-1", 1)

        assertEquals(chapters[1], target)
        assertTrue(ComicReadingResume.hasSavedChapter(chapters, "chapter-1", 1))
    }

    @Test
    fun fallsBackToSavedIndexWhenRouteChangedAfterCatalogRefresh() {
        val target = ComicReadingResume.chapter(chapters, "stale-route", 2)

        assertEquals(chapters[2], target)
        assertTrue(ComicReadingResume.hasSavedChapter(chapters, "stale-route", 2))
    }

    @Test
    fun freshBookStartsFromFirstChapter() {
        val target = ComicReadingResume.chapter(chapters, null, null)

        assertEquals(chapters.first(), target)
        assertFalse(ComicReadingResume.hasSavedChapter(chapters, null, null))
    }

    @Test
    fun `source switch resumes matching named chapter instead of unrelated raw index`() {
        val sourceB = listOf(
            MediaChapterItem("b-prologue", "序章", 50),
            MediaChapterItem("b-51", "第51话 决战", 51)
        )

        val target = ComicReadingResume.chapter(
            chapters = sourceB,
            savedChapterRouteId = "a-51",
            savedChapterIndex = 50,
            savedChapterTitle = "第五十一话：决战"
        )

        assertEquals("b-51", target?.routeId)
    }

    @Test
    fun `unmatched named chapter keeps persisted route instead of opening wrong source index`() {
        val sourceB = listOf(
            MediaChapterItem("b-prologue", "序章", 50),
            MediaChapterItem("b-first", "第1话", 51)
        )

        val target = ComicReadingResume.chapter(
            chapters = sourceB,
            savedChapterRouteId = "a-special",
            savedChapterIndex = 50,
            savedChapterTitle = "作者番外"
        )

        assertEquals("a-special", target?.routeId)
        assertEquals("作者番外", target?.title)
        assertTrue(
            ComicReadingResume.hasSavedChapter(
                chapters = sourceB,
                savedChapterRouteId = "a-special",
                savedChapterIndex = 50,
                savedChapterTitle = "作者番外"
            )
        )
    }
}
