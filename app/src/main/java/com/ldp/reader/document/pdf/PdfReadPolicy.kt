package com.ldp.reader.document.pdf

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PdfPageState(
    val index: Int,
    val number: Int,
    val count: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean
)

sealed interface PdfRenderSize {
    data class Ready(val width: Int, val height: Int) : PdfRenderSize
    data class Rejected(val message: String) : PdfRenderSize
}

object PdfReadPolicy {
    const val MAX_BITMAP_PIXELS = 4_000_000L
    const val MAX_BITMAP_DIMENSION = 8_192
    const val MAX_PAGE_COUNT = 20_000
    const val UNREADABLE_DOCUMENT_MESSAGE = "此 PDF 无法打开"
    const val EMPTY_DOCUMENT_MESSAGE = UNREADABLE_DOCUMENT_MESSAGE
    const val NO_PAGES_MESSAGE = UNREADABLE_DOCUMENT_MESSAGE
    const val TOO_MANY_PAGES_MESSAGE = "文件页数过多，暂时无法打开"

    fun isDefinitelyEmpty(descriptorSize: Long): Boolean = descriptorSize == 0L

    fun isSupportedPageCount(pageCount: Int): Boolean = pageCount in 1..MAX_PAGE_COUNT

    fun sourceOpenFailure(@Suppress("UNUSED_PARAMETER") error: Throwable): String =
        UNREADABLE_DOCUMENT_MESSAGE

    fun documentOpenFailure(@Suppress("UNUSED_PARAMETER") error: Throwable): String =
        UNREADABLE_DOCUMENT_MESSAGE

    fun pageRenderFailure(
        pageIndex: Int,
        @Suppress("UNUSED_PARAMETER") error: Throwable
    ): String = pageDisplayFailure(pageIndex)

    fun pageDisplayFailure(pageIndex: Int): String = "第${pageIndex + 1}页无法显示"

    fun renderSize(targetWidth: Int, pageWidth: Int, pageHeight: Int): PdfRenderSize {
        if (targetWidth <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return PdfRenderSize.Rejected("页面尺寸无效，无法渲染")
        }
        if (targetWidth > MAX_BITMAP_DIMENSION) {
            return PdfRenderSize.Rejected("页面尺寸过大，无法安全渲染")
        }
        val displayHeightValue = targetWidth.toDouble() * pageHeight.toDouble() / pageWidth.toDouble()
        if (!displayHeightValue.isFinite() || displayHeightValue > MAX_BITMAP_DIMENSION) {
            return PdfRenderSize.Rejected("页面尺寸过大，无法安全渲染")
        }
        val displayHeight = displayHeightValue.roundToInt().coerceAtLeast(1)
        val displayPixels = targetWidth.toLong() * displayHeight
        if (displayPixels <= MAX_BITMAP_PIXELS) {
            return PdfRenderSize.Ready(targetWidth, displayHeight)
        }
        val scale = sqrt(MAX_BITMAP_PIXELS.toDouble() / displayPixels.toDouble())
        val renderWidth = floor(targetWidth * scale).toInt().coerceAtLeast(1)
        val renderHeight = floor(displayHeight * scale).toInt().coerceAtLeast(1)
        return PdfRenderSize.Ready(
            width = min(renderWidth, MAX_BITMAP_DIMENSION),
            height = min(renderHeight, MAX_BITMAP_DIMENSION)
        )
    }

    fun pageState(requestedIndex: Int, pageCount: Int): PdfPageState? {
        if (pageCount <= 0) return null
        val index = requestedIndex.coerceIn(0, pageCount - 1)
        return PdfPageState(
            index = index,
            number = index + 1,
            count = pageCount,
            hasPrevious = index > 0,
            hasNext = index < pageCount - 1
        )
    }

    fun move(currentIndex: Int, pageCount: Int, delta: Int): PdfPageState? {
        if (pageCount <= 0) return null
        val requestedIndex = (currentIndex.toLong() + delta).coerceIn(0L, (pageCount - 1).toLong()).toInt()
        return pageState(requestedIndex, pageCount)
    }
}
