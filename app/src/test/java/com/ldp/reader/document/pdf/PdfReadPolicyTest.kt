package com.ldp.reader.document.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PdfReadPolicyTest {

    @Test
    fun documentOpenProblemsUseShortReaderFriendlyMessages() {
        assertTrue(PdfReadPolicy.isDefinitelyEmpty(0L))
        assertFalse(PdfReadPolicy.isDefinitelyEmpty(-1L))
        assertFalse(PdfReadPolicy.isDefinitelyEmpty(24L))
        assertEquals("此 PDF 无法打开", PdfReadPolicy.EMPTY_DOCUMENT_MESSAGE)
        assertEquals("此 PDF 无法打开", PdfReadPolicy.NO_PAGES_MESSAGE)
        assertEquals("文件页数过多，暂时无法打开", PdfReadPolicy.TOO_MANY_PAGES_MESSAGE)
        assertEquals(
            "此 PDF 无法打开",
            PdfReadPolicy.documentOpenFailure(IOException("bad xref"))
        )
    }

    @Test
    fun pageCountHasAnExplicitDocumentComplexityBoundary() {
        assertFalse(PdfReadPolicy.isSupportedPageCount(0))
        assertTrue(PdfReadPolicy.isSupportedPageCount(1))
        assertTrue(PdfReadPolicy.isSupportedPageCount(PdfReadPolicy.MAX_PAGE_COUNT))
        assertFalse(PdfReadPolicy.isSupportedPageCount(PdfReadPolicy.MAX_PAGE_COUNT + 1))
        assertFalse(PdfReadPolicy.isSupportedPageCount(Int.MAX_VALUE))
    }

    @Test
    fun internalOpenFailureTypesDoNotLeakTechnicalDetails() {
        assertEquals(
            "此 PDF 无法打开",
            PdfReadPolicy.documentOpenFailure(SecurityException("password required"))
        )
        assertEquals(
            "此 PDF 无法打开",
            PdfReadPolicy.documentOpenFailure(IllegalArgumentException("not seekable"))
        )
        assertEquals(
            "此 PDF 无法打开",
            PdfReadPolicy.sourceOpenFailure(SecurityException("permission denied"))
        )
    }

    @Test
    fun normalPortraitPageHasStableRenderSize() {
        val result = PdfReadPolicy.renderSize(
            targetWidth = 1080,
            pageWidth = 612,
            pageHeight = 792
        )

        assertEquals(PdfRenderSize.Ready(width = 1080, height = 1398), result)
    }

    @Test
    fun highResolutionDisplayStaysWithinBitmapBudget() {
        val result = PdfReadPolicy.renderSize(
            targetWidth = 4096,
            pageWidth = 612,
            pageHeight = 792
        ) as PdfRenderSize.Ready

        assertTrue(result.width <= PdfReadPolicy.MAX_BITMAP_DIMENSION)
        assertTrue(result.height <= PdfReadPolicy.MAX_BITMAP_DIMENSION)
        assertTrue(result.width.toLong() * result.height <= PdfReadPolicy.MAX_BITMAP_PIXELS)
        assertTrue(result.width.toLong() * result.height * 4L <= 16_000_000L)
    }

    @Test
    fun invalidOrPathologicallyTallPageIsRejectedBeforeBitmapAllocation() {
        assertEquals(
            PdfRenderSize.Rejected("页面尺寸无效，无法渲染"),
            PdfReadPolicy.renderSize(targetWidth = 1080, pageWidth = 0, pageHeight = 792)
        )
        assertEquals(
            PdfRenderSize.Rejected("页面尺寸过大，无法安全渲染"),
            PdfReadPolicy.renderSize(targetWidth = 1080, pageWidth = 1, pageHeight = 100_000)
        )
        assertEquals(
            PdfRenderSize.Rejected("页面尺寸过大，无法安全渲染"),
            PdfReadPolicy.renderSize(targetWidth = 10_000, pageWidth = 612, pageHeight = 792)
        )
    }

    @Test
    fun veryLargePageMetadataCannotOverflowDimensionMath() {
        assertEquals(
            PdfRenderSize.Ready(width = 1080, height = 1080),
            PdfReadPolicy.renderSize(
                targetWidth = 1080,
                pageWidth = Int.MAX_VALUE,
                pageHeight = Int.MAX_VALUE
            )
        )
    }

    @Test
    fun pageNavigationClampsBothBoundaries() {
        assertEquals(null, PdfReadPolicy.pageState(requestedIndex = 0, pageCount = 0))
        assertEquals(
            PdfPageState(index = 0, number = 1, count = 3, hasPrevious = false, hasNext = true),
            PdfReadPolicy.pageState(requestedIndex = -10, pageCount = 3)
        )
        assertEquals(
            PdfPageState(index = 2, number = 3, count = 3, hasPrevious = true, hasNext = false),
            PdfReadPolicy.pageState(requestedIndex = Int.MAX_VALUE, pageCount = 3)
        )
        assertEquals(
            PdfPageState(index = 0, number = 1, count = 1, hasPrevious = false, hasNext = false),
            PdfReadPolicy.move(currentIndex = 0, pageCount = 1, delta = 1)
        )
        assertEquals(0, PdfReadPolicy.move(currentIndex = 0, pageCount = 3, delta = -1)?.index)
        assertEquals(0, PdfReadPolicy.move(currentIndex = 1, pageCount = 3, delta = -1)?.index)
        assertEquals(2, PdfReadPolicy.move(currentIndex = 1, pageCount = 3, delta = 1)?.index)
        assertEquals(2, PdfReadPolicy.move(currentIndex = 2, pageCount = 3, delta = 1)?.index)
    }

    @Test
    fun pageFailuresNameThePageWithoutExposingRenderingDetails() {
        assertEquals(
            "第4页无法显示",
            PdfReadPolicy.pageRenderFailure(pageIndex = 3, OutOfMemoryError("bitmap"))
        )
        assertEquals(
            "第4页无法显示",
            PdfReadPolicy.pageRenderFailure(pageIndex = 3, IllegalStateException("render"))
        )
    }
}
