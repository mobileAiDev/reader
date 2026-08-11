package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfReadRestoredPageTest {

    @Test
    fun pageJumpPinsTheTargetPageToTheViewportTopAndReplaysAfterLayout() {
        val alignments = mutableListOf<Pair<Int, Int>>()
        var afterViewportReady: (() -> Unit)? = null

        val targetIndex = applyPdfPageTarget(
            targetPageIndex = 2,
            pageCount = 3,
            alignPageTop = { index, offset -> alignments += index to offset },
            whenViewportReady = { action -> afterViewportReady = action }
        )

        assertEquals(2, targetIndex)
        assertEquals(listOf(2 to 0), alignments)
        requireNotNull(afterViewportReady).invoke()
        assertEquals(listOf(2 to 0, 2 to 0), alignments)
    }

    @Test
    fun savedPageIsRestoredAfterDocumentPageCountIsKnown() {
        assertEquals(37, restoredPdfPageIndex(savedPageIndex = 37, pageCount = 80))
    }

    @Test
    fun restoredPageIsAppliedAgainAfterTheViewportBecomesReady() {
        var viewportReady = false
        var visiblePageIndex = 0
        var afterViewportReady: (() -> Unit)? = null

        val restoredIndex = applyPdfPageRestore(
            savedPageIndex = 2,
            pageCount = 3,
            alignPageTop = { target, offset ->
                assertEquals(0, offset)
                if (viewportReady) visiblePageIndex = target
            },
            whenViewportReady = { action -> afterViewportReady = action }
        )

        assertEquals(2, restoredIndex)
        assertEquals(0, visiblePageIndex)
        viewportReady = true
        requireNotNull(afterViewportReady).invoke()
        assertEquals(2, visiblePageIndex)
    }

    @Test
    fun missingOrOutOfRangeSavedPageUsesAValidDocumentPage() {
        assertEquals(0, restoredPdfPageIndex(savedPageIndex = null, pageCount = 80))
        assertEquals(79, restoredPdfPageIndex(savedPageIndex = 120, pageCount = 80))
        assertEquals(0, restoredPdfPageIndex(savedPageIndex = 10, pageCount = 0))
    }

    @Test
    fun pageIndicatorUsesThePageOccupyingMostOfTheViewport() {
        assertEquals(
            2,
            dominantPdfPageIndex(
                visiblePages = listOf(
                    PdfVisiblePage(index = 1, visiblePixels = 620),
                    PdfVisiblePage(index = 2, visiblePixels = 2_160)
                ),
                fallbackIndex = 1,
                pageCount = 3
            )
        )
    }

    @Test
    fun pageIndicatorKeepsTheEarlierPageOnAnExactVisibilityTie() {
        assertEquals(
            4,
            dominantPdfPageIndex(
                visiblePages = listOf(
                    PdfVisiblePage(index = 4, visiblePixels = 1_000),
                    PdfVisiblePage(index = 5, visiblePixels = 1_000)
                ),
                fallbackIndex = 4,
                pageCount = 10
            )
        )
    }

    @Test
    fun pageIndicatorFallsBackToAClampedValidPage() {
        assertEquals(
            2,
            dominantPdfPageIndex(
                visiblePages = listOf(PdfVisiblePage(index = -1, visiblePixels = 500)),
                fallbackIndex = 20,
                pageCount = 3
            )
        )
    }
}
