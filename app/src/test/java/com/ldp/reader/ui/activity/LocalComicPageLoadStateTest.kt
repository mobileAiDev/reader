package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalComicPageLoadStateTest {
    @Test
    fun `failed current page is retryable and cannot be persisted as readable`() {
        val pages = listOf(
            MediaRequest("/cache/comic/001.jpg"),
            MediaRequest("/cache/comic/002.jpg")
        )

        val state = LocalComicPageLoadState.EMPTY.record(
            requestGeneration = 7,
            currentGeneration = 7,
            position = 0,
            request = pages[0],
            currentItems = pages,
            success = false
        )

        assertFalse(state.canPersist(pageIndex = 0, pageCount = pages.size))
        assertFalse(state.hasReadablePage)
        assertEquals(setOf(0), state.failedPagePositions)
        val retry = state.retryState(visiblePageIndex = 0, pageCount = pages.size)
        assertTrue(retry.visible)
        assertFalse(retry.allPagesFailed)
        assertEquals("当前图片无法显示，点击重试", retry.message)
    }

    @Test
    fun `stale generation and mismatched request cannot change the current load`() {
        val pages = listOf(MediaRequest("/cache/comic/current.jpg"))

        val staleGeneration = LocalComicPageLoadState.EMPTY.record(
            requestGeneration = 6,
            currentGeneration = 7,
            position = 0,
            request = pages[0],
            currentItems = pages,
            success = true
        )
        val staleRequest = LocalComicPageLoadState.EMPTY.record(
            requestGeneration = 7,
            currentGeneration = 7,
            position = 0,
            request = MediaRequest("/cache/comic/old.jpg"),
            currentItems = pages,
            success = false
        )

        assertEquals(LocalComicPageLoadState.EMPTY, staleGeneration)
        assertEquals(LocalComicPageLoadState.EMPTY, staleRequest)
    }

    @Test
    fun `all failed pages expose the all page retry state`() {
        val pages = listOf(
            MediaRequest("/cache/comic/001.jpg"),
            MediaRequest("/cache/comic/002.jpg")
        )
        val state = pages.indices.fold(LocalComicPageLoadState.EMPTY) { current, position ->
            current.record(
                requestGeneration = 9,
                currentGeneration = 9,
                position = position,
                request = pages[position],
                currentItems = pages,
                success = false
            )
        }

        val retry = state.retryState(visiblePageIndex = 0, pageCount = pages.size)

        assertTrue(retry.visible)
        assertTrue(retry.allPagesFailed)
        assertEquals("漫画图片均无法显示，点击重试", retry.message)
        assertFalse(state.hasReadablePage)
    }

    @Test
    fun `only successfully decoded pages can be persisted`() {
        val pages = listOf(
            MediaRequest("/cache/comic/001.jpg"),
            MediaRequest("/cache/comic/002.jpg")
        )
        val state = LocalComicPageLoadState.EMPTY.record(
            requestGeneration = 11,
            currentGeneration = 11,
            position = 1,
            request = pages[1],
            currentItems = pages,
            success = true
        )

        assertTrue(state.hasReadablePage)
        assertFalse(state.canPersist(pageIndex = 0, pageCount = pages.size))
        assertTrue(state.canPersist(pageIndex = 1, pageCount = pages.size))
        assertFalse(state.retryState(visiblePageIndex = 1, pageCount = pages.size).visible)
    }
}
