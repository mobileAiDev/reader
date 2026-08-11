package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicBoundaryNavigationTest {
    @Test
    fun verticalSwipeThatEndsAtBottomOpensNext() {
        val target = ComicBoundaryNavigation.target(
            horizontal = false,
            controlsVisible = false,
            itemCount = 12,
            deltaX = 0f,
            deltaY = -180f,
            canScrollForward = false,
            canScrollBackward = true,
            distance = 96f
        )

        assertEquals(ComicBoundaryDirection.NEXT, target)
    }

    @Test
    fun loadingLastPageDoesNotOpenNextChapter() {
        val target = ComicBoundaryNavigation.target(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = -140f,
            canScrollForward = false,
            canScrollBackward = true,
            lastPageLoaded = false,
            distance = 96f
        )

        assertNull(target)
    }

    @Test
    fun successfullyLoadedLastPageOpensNextChapter() {
        val target = ComicBoundaryNavigation.target(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = -140f,
            canScrollForward = false,
            canScrollBackward = true,
            lastPageLoaded = true,
            distance = 96f
        )

        assertEquals(ComicBoundaryDirection.NEXT, target)
    }

    @Test
    fun failedLastPageStaysInChapterAndOffersRetry() {
        val blocked = ComicBoundaryNavigation.blockedByEdgeLoad(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = -140f,
            canScrollForward = false,
            canScrollBackward = true,
            firstPageLoaded = true,
            lastPageLoaded = false,
            distance = 96f
        )

        assertEquals(ComicBoundaryDirection.NEXT, blocked)
        assertEquals(
            "图片加载失败，请重试",
            ComicBoundaryNavigation.edgeLoadFeedback(blocked!!, failed = true)
        )
    }

    @Test
    fun loadingLastPageStaysInChapterAndAsksUserToWait() {
        val blocked = ComicBoundaryNavigation.blockedByEdgeLoad(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = -140f,
            canScrollForward = false,
            canScrollBackward = true,
            firstPageLoaded = true,
            lastPageLoaded = false,
            distance = 96f
        )

        assertEquals(ComicBoundaryDirection.NEXT, blocked)
        assertEquals(
            "最后一页仍在加载，请稍候",
            ComicBoundaryNavigation.edgeLoadFeedback(blocked!!, failed = false)
        )
    }

    @Test
    fun visibleEdgeButStillScrollableIsDiagnosticOnly() {
        val target = ComicBoundaryNavigation.target(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = -140f,
            canScrollForward = true,
            canScrollBackward = true,
            firstPageVisible = false,
            lastPageVisible = false,
            distance = 96f
        )
        val blocked = ComicBoundaryNavigation.blockedTarget(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = -140f,
            canScrollForward = true,
            canScrollBackward = true,
            firstPageVisible = false,
            lastPageVisible = true,
            distance = 96f
        )

        assertNull(target)
        assertEquals(ComicBoundaryDirection.NEXT, blocked)
    }

    @Test
    fun partiallyVisibleLastPageDoesNotOpenNextWhileRecyclerCanStillScroll() {
        val target = ComicBoundaryNavigation.target(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = -140f,
            canScrollForward = true,
            canScrollBackward = true,
            firstPageVisible = false,
            lastPageVisible = true,
            distance = 96f
        )

        assertNull(target)
    }

    @Test
    fun partiallyVisibleFirstPageDoesNotOpenPreviousWhileRecyclerCanStillScroll() {
        val target = ComicBoundaryNavigation.target(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = 140f,
            canScrollForward = true,
            canScrollBackward = true,
            firstPageVisible = true,
            lastPageVisible = false,
            distance = 96f
        )

        assertNull(target)
    }

    @Test
    fun horizontalPartiallyVisibleLastPageAlsoWaitsForTheRealBoundary() {
        val target = ComicBoundaryNavigation.target(
            horizontal = true,
            controlsVisible = false,
            itemCount = 3,
            deltaX = -140f,
            deltaY = 0f,
            canScrollForward = true,
            canScrollBackward = true,
            firstPageVisible = false,
            lastPageVisible = true,
            distance = 96f
        )

        assertNull(target)
    }
}
