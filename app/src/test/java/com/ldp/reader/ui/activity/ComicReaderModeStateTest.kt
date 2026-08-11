package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicReaderModeStateTest {
    @Test
    fun `resolved chapter without pages offers an explicit retry`() {
        val state = comicReaderRetryState(
            chapterResolved = true,
            pageCount = 0,
            currentPageFailed = false
        )

        assertTrue(state.visible)
        assertEquals("漫画章节加载失败，点击重试", state.message)
    }

    @Test
    fun `failed visible image offers an explicit retry`() {
        val state = comicReaderRetryState(
            chapterResolved = true,
            pageCount = 12,
            currentPageFailed = true
        )

        assertTrue(state.visible)
        assertEquals("当前图片加载失败，点击重试", state.message)
    }

    @Test
    fun `reader mode restoration keeps forced landscape selected`() {
        val state = restoredComicReaderModeState(
            horizontalPages = true,
            night = true,
            eyeProtection = true,
            forcedLandscape = true,
            savedBrightnessPercent = 67,
            savedBrightnessOverridden = true
        )

        assertTrue(state.horizontalPages)
        assertTrue(state.night)
        assertTrue(state.eyeProtection)
        assertTrue(state.forcedLandscape)
        assertEquals(67, state.brightnessPercent)
        assertTrue(state.brightnessOverridden)
    }

    @Test
    fun `reader follows system brightness until the user overrides it`() {
        val state = restoredComicReaderModeState(
            horizontalPages = false,
            night = false,
            eyeProtection = false,
            forcedLandscape = false,
            savedBrightnessPercent = DEFAULT_COMIC_BRIGHTNESS_PERCENT,
            savedBrightnessOverridden = false
        )

        assertFalse(state.brightnessOverridden)
    }

    @Test
    fun `invalid saved brightness cannot restore a manual override`() {
        val state = restoredComicReaderModeState(
            horizontalPages = false,
            night = false,
            eyeProtection = false,
            forcedLandscape = false,
            savedBrightnessPercent = 101,
            savedBrightnessOverridden = true
        )

        assertEquals(DEFAULT_COMIC_BRIGHTNESS_PERCENT, state.brightnessPercent)
        assertFalse(state.brightnessOverridden)
    }

    @Test
    fun `latest first source presents canonical title with chronological catalog position`() {
        val sourceOrder = listOf(
            MediaChapterItem("latest", "第 3 话 终幕", 0),
            MediaChapterItem("middle", "第 2 话", 1),
            MediaChapterItem("first", "第 1 话 开始", 2)
        )
        val chronological = ComicChapterOrder.resolve(sourceOrder, "第 3 话 终幕").chronological

        val presentation = comicReaderChapterPresentation(
            chronologicalChapters = chronological,
            chapterRouteId = "latest",
            fallbackTitle = "旧标题",
            fallbackSourceIndex = 0
        )

        assertEquals("第 3 话 终幕", presentation.title)
        assertEquals(3, presentation.catalogPosition)
        assertEquals(0, presentation.sourceIndex)
        assertEquals("第 3/3 话 · 第 1/12 页", presentation.pageStatus(pagePosition = 1, pageCount = 12))
    }

    @Test
    fun `named chapter missing after source switch is not relabeled by an unrelated raw index`() {
        val sourceB = listOf(
            MediaChapterItem("b-prologue", "序章", 50),
            MediaChapterItem("b-first", "第1话", 51)
        )

        val presentation = comicReaderChapterPresentation(
            chronologicalChapters = sourceB,
            chapterRouteId = "a-special",
            fallbackTitle = "作者番外",
            fallbackSourceIndex = 50
        )

        assertEquals("作者番外", presentation.title)
        assertEquals(0, presentation.catalogPosition)
        assertEquals(50, presentation.sourceIndex)
    }

    @Test
    fun `valid saved brightness is restored`() {
        assertEquals(5, restoredComicBrightnessPercent(5))
        assertEquals(67, restoredComicBrightnessPercent(67))
        assertEquals(100, restoredComicBrightnessPercent(100))
    }

    @Test
    fun `invalid saved brightness safely falls back`() {
        assertEquals(DEFAULT_COMIC_BRIGHTNESS_PERCENT, restoredComicBrightnessPercent(-1))
        assertEquals(DEFAULT_COMIC_BRIGHTNESS_PERCENT, restoredComicBrightnessPercent(101))
    }

    @Test
    fun `loading placeholder never replaces the last confirmed page`() {
        val confirmed = ComicReaderProgressPolicy.confirmRenderedPage(
            previous = ComicReaderConfirmedProgress.NONE,
            chapterRouteId = "chapter-8",
            pageIndex = 7,
            renderedPageCount = 12
        )

        val afterEmptyRetry = ComicReaderProgressPolicy.confirmRenderedPage(
            previous = confirmed,
            chapterRouteId = "chapter-8",
            pageIndex = 0,
            renderedPageCount = 0
        )

        assertEquals(confirmed, afterEmptyRetry)
        assertEquals(7, ComicReaderProgressPolicy.loadingVisiblePage(confirmed, "chapter-8"))
        assertEquals(7, ComicReaderProgressPolicy.pageToPersist(confirmed, "chapter-8"))
    }

    @Test
    fun `initial empty load has no progress to persist`() {
        assertEquals(
            0,
            ComicReaderProgressPolicy.loadingVisiblePage(
                ComicReaderConfirmedProgress.NONE,
                "chapter-1"
            )
        )
        assertNull(
            ComicReaderProgressPolicy.pageToPersist(
                ComicReaderConfirmedProgress.NONE,
                "chapter-1"
            )
        )
    }

    @Test
    fun `successful retry prefers unsaved confirmed page over stale storage`() {
        val confirmed = ComicReaderConfirmedProgress("chapter-8", 7)

        assertEquals(
            7,
            ComicReaderProgressPolicy.pageAfterSuccessfulRender(
                confirmed = confirmed,
                chapterRouteId = "chapter-8",
                persistedPage = 3,
                resetRequested = false,
                renderedPageCount = 12
            )
        )
    }

    @Test
    fun `explicit chapter reset still starts from first page after render`() {
        val oldProgress = ComicReaderConfirmedProgress("chapter-8", 7)

        assertEquals(
            0,
            ComicReaderProgressPolicy.pageAfterSuccessfulRender(
                confirmed = oldProgress,
                chapterRouteId = "chapter-8",
                persistedPage = 7,
                resetRequested = true,
                renderedPageCount = 12
            )
        )
        assertNull(ComicReaderProgressPolicy.pageToPersist(oldProgress, "chapter-9"))
    }

    @Test
    fun `same url callback from an older load generation is stale`() {
        val request = MediaRequest("https://img.example/same-page.jpg")
        val currentItems = listOf(request)

        assertFalse(
            isCurrentComicPageRequest(
                requestGeneration = 4,
                currentGeneration = 5,
                position = 0,
                request = request,
                currentItems = currentItems
            )
        )
        assertTrue(
            isCurrentComicPageRequest(
                requestGeneration = 5,
                currentGeneration = 5,
                position = 0,
                request = request,
                currentItems = currentItems
            )
        )
    }
}
