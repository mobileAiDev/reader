package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginationReflowPolicyTest {

    @Test
    fun onlyAnEstablishedViewportSizeChangeRequiresReflow() {
        val portrait = ReaderPaginationViewportSpec(visibleWidth = 1_200, visibleHeight = 2_644)
        val landscape = ReaderPaginationViewportSpec(visibleWidth = 2_300, visibleHeight = 1_100)

        assertFalse(ReaderPaginationReflowPolicy.hasChanged(previous = null, next = portrait))
        assertFalse(ReaderPaginationReflowPolicy.hasChanged(previous = portrait, next = portrait))
        assertTrue(ReaderPaginationReflowPolicy.hasChanged(previous = portrait, next = landscape))
    }

    @Test
    fun localAndNetworkLoadersChooseExplicitReflowPaths() {
        assertEquals(
            ReaderPaginationReflowAction.RELOAD_CURRENT_SYNCHRONOUSLY,
            ReaderPaginationReflowPolicy.action(
                viewportChanged = true,
                chapterOpen = true,
                catalogPrepared = true,
                backgroundPagination = false,
                currentPageFinished = true
            )
        )
        assertEquals(
            ReaderPaginationReflowAction.RELOAD_CURRENT_IN_BACKGROUND,
            ReaderPaginationReflowPolicy.action(
                viewportChanged = true,
                chapterOpen = true,
                catalogPrepared = true,
                backgroundPagination = true,
                currentPageFinished = true
            )
        )
        assertEquals(
            ReaderPaginationReflowAction.NONE,
            ReaderPaginationReflowPolicy.action(
                viewportChanged = false,
                chapterOpen = true,
                catalogPrepared = true,
                backgroundPagination = true,
                currentPageFinished = true
            )
        )
    }

    @Test
    fun changedViewportAlwaysInvalidatesEvenWhenCurrentChapterCannotReloadYet() {
        val plan = ReaderPaginationReflowPolicy.plan(
            viewportChanged = true,
            chapterOpen = true,
            catalogPrepared = true,
            backgroundPagination = false,
            currentPageFinished = false
        )

        assertTrue(plan.invalidateStalePagination)
        assertEquals(ReaderPaginationReflowAction.NONE, plan.action)

        val sameViewportPlan = ReaderPaginationReflowPolicy.plan(
            viewportChanged = false,
            chapterOpen = true,
            catalogPrepared = true,
            backgroundPagination = true,
            currentPageFinished = true
        )
        assertFalse(sameViewportPlan.invalidateStalePagination)
        assertEquals(ReaderPaginationReflowAction.NONE, sameViewportPlan.action)
    }

    @Test
    fun relativeProgressClampsAcrossLargePageCountChanges() {
        val progress = ReaderPaginationReflowPolicy.captureRelativeProgress(
            pagePosition = 8,
            pageCount = 10
        )

        assertEquals(3, ReaderPaginationReflowPolicy.restorePagePosition(progress, 4))
        assertEquals(
            2,
            ReaderPaginationReflowPolicy.restorePagePosition(
                ReaderPaginationReflowPolicy.captureRelativeProgress(1, 2),
                4
            )
        )
        assertEquals(0, ReaderPaginationReflowPolicy.restorePagePosition(progress, 1))
        assertEquals(0, ReaderPaginationReflowPolicy.restorePagePosition(progress, 0))
        assertEquals(
            4,
            ReaderPaginationReflowPolicy.restorePagePosition(
                ReaderPaginationReflowPolicy.captureRelativeProgress(99, 5),
                5
            )
        )
    }
}
