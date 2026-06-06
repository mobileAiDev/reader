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
    fun edgeImageStateDoesNotBlockNextChapter() {
        val target = ComicBoundaryNavigation.target(
            horizontal = false,
            controlsVisible = false,
            itemCount = 3,
            deltaX = 0f,
            deltaY = -140f,
            canScrollForward = false,
            canScrollBackward = true,
            distance = 96f
        )

        assertEquals(ComicBoundaryDirection.NEXT, target)
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
    fun visibleLastPageCanOpenNextEvenWhenRecyclerStillReportsScrollable() {
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

        assertEquals(ComicBoundaryDirection.NEXT, target)
    }
}
