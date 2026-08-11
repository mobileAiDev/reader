package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageGeometryTest {

    @Test
    fun gestureNavigationReservesOneFooterAndOneMandatoryGestureInset() {
        val safeInsets = ReaderPageGeometry.resolveSafeInsets(
            mandatoryGestureBottomPx = 72,
            tappableElementBottomPx = 0,
            cutoutLeftPx = 0,
            cutoutRightPx = 0,
            cutoutBottomPx = 0
        )
        val geometry = ReaderPageGeometry.resolveVertical(
            displayHeightPx = 2_992,
            contentTopPx = 168,
            contentBottomPaddingPx = 24,
            footerAreaHeightPx = 84,
            safeBottomInsetPx = safeInsets.bottom
        )

        assertEquals(72, safeInsets.bottom)
        assertEquals(2_920, geometry.footerBottom)
        assertEquals(2_836, geometry.footerTop)
        assertEquals(2_812, geometry.contentBottom)
        assertEquals(2_644, geometry.contentHeight)
    }

    @Test
    fun threeButtonNavigationDoesNotAddItsCompatSystemWindowFallbackToPageGeometry() {
        val safeInsets = ReaderPageGeometry.resolveSafeInsets(
            mandatoryGestureBottomPx = 144,
            tappableElementBottomPx = 144,
            cutoutLeftPx = 0,
            cutoutRightPx = 0,
            cutoutBottomPx = 0
        )
        val geometry = ReaderPageGeometry.resolveVertical(
            displayHeightPx = 1_920,
            contentTopPx = 72,
            contentBottomPaddingPx = 16,
            footerAreaHeightPx = 56,
            safeBottomInsetPx = safeInsets.bottom
        )

        assertEquals(0, safeInsets.bottom)
        assertEquals(1_920, geometry.footerBottom)
        assertEquals(1_848, geometry.contentBottom)
        assertEquals(1_776, geometry.contentHeight)
    }

    @Test
    fun landscapeCutoutUsesSymmetricReadableMarginAndLargestBottomSafeInset() {
        val safeInsets = ReaderPageGeometry.resolveSafeInsets(
            mandatoryGestureBottomPx = 18,
            tappableElementBottomPx = 0,
            cutoutLeftPx = 96,
            cutoutRightPx = 0,
            cutoutBottomPx = 32
        )

        assertEquals(96, safeInsets.left)
        assertEquals(0, safeInsets.right)
        assertEquals(32, safeInsets.bottom)
        assertEquals(111, ReaderPageGeometry.resolveHorizontalMargin(15, safeInsets))
    }

    @Test
    fun malformedDimensionsClampWithoutLettingBodyEnterFooter() {
        val geometry = ReaderPageGeometry.resolveVertical(
            displayHeightPx = 100,
            contentTopPx = 80,
            contentBottomPaddingPx = 30,
            footerAreaHeightPx = 40,
            safeBottomInsetPx = 20
        )

        assertEquals(40, geometry.footerTop)
        assertEquals(10, geometry.contentBottom)
        assertEquals(0, geometry.contentHeight)
    }

    @Test
    fun firstPageWaitsForItsStableInsetsButLaterBarVisibilityDoesNotGateDrawing() {
        assertEquals(
            false,
            ReaderPageGeometry.canPrepareDisplay(
                safeInsetBindingEnabled = true,
                safeInsetsResolved = false
            )
        )
        assertEquals(
            true,
            ReaderPageGeometry.canPrepareDisplay(
                safeInsetBindingEnabled = true,
                safeInsetsResolved = true
            )
        )
        assertEquals(
            true,
            ReaderPageGeometry.canPrepareDisplay(
                safeInsetBindingEnabled = false,
                safeInsetsResolved = false
            )
        )
    }

    @Test
    fun enlargedSystemTextReservesFooterFromMeasuredInkInsteadOfFixedDp() {
        val footerAreaHeight = ReaderPageGeometry.resolveFooterAreaHeight(
            minimumAreaHeightPx = 84,
            textMetricsTopPx = -72f,
            textMetricsBottomPx = 18f,
            batteryHeightPx = 72f,
            bottomMarginPx = 9
        )
        val geometry = ReaderPageGeometry.resolveVertical(
            displayHeightPx = 2_992,
            contentTopPx = 168,
            contentBottomPaddingPx = 24,
            footerAreaHeightPx = footerAreaHeight,
            safeBottomInsetPx = 72
        )
        val footerTextTop = geometry.footerBottom - 9 - (18 - -72)

        assertEquals(99, footerAreaHeight)
        assertTrue(footerTextTop >= geometry.footerTop)
        assertTrue(geometry.contentBottom < footerTextTop)
    }

    @Test
    fun pagedAndScrollRenderingShareTheSameScreenContentBand() {
        val contentTop = 168
        val pagedCanvasTop = ReaderPageGeometry.resolveContentCanvasTop(
            contentTopPx = contentTop,
            scrollMode = false
        )
        val scrollCanvasTop = ReaderPageGeometry.resolveContentCanvasTop(
            contentTopPx = contentTop,
            scrollMode = true
        )

        assertEquals(168, pagedCanvasTop)
        assertEquals(0, scrollCanvasTop)
        assertEquals(pagedCanvasTop, scrollCanvasTop + contentTop)
    }
}
