package com.ldp.reader.widget.page

import kotlin.math.ceil
import kotlin.math.max

internal data class ReaderPageSafeInsets(
    val left: Int,
    val right: Int,
    val bottom: Int
) {
    companion object {
        val NONE = ReaderPageSafeInsets(left = 0, right = 0, bottom = 0)
    }
}

internal data class ReaderPageVerticalGeometry(
    val contentTop: Int,
    val contentBottom: Int,
    val contentHeight: Int,
    val footerTop: Int,
    val footerBottom: Int
)

internal data class ReaderTextLineLayout(
    val inkHeight: Int,
    val lineGap: Int,
    val paragraphGap: Int
)

/**
 * The page canvas owns three vertical bands exactly once: readable content, the app footer,
 * and the persistent gesture/cutout safe area. Transient system-bar visibility is deliberately
 * not an input, so showing reader chrome cannot repaginate or move the current line.
 */
internal object ReaderPageGeometry {
    fun resolveSafeInsets(
        mandatoryGestureBottomPx: Int,
        tappableElementBottomPx: Int,
        cutoutLeftPx: Int,
        cutoutRightPx: Int,
        cutoutBottomPx: Int
    ): ReaderPageSafeInsets {
        val mandatoryBottom = max(0, mandatoryGestureBottomPx)
        val tappableBottom = max(0, tappableElementBottomPx)
        val persistentGestureBottom = if (mandatoryBottom > tappableBottom) {
            mandatoryBottom
        } else {
            0
        }
        return ReaderPageSafeInsets(
            left = max(0, cutoutLeftPx),
            right = max(0, cutoutRightPx),
            bottom = max(persistentGestureBottom, max(0, cutoutBottomPx))
        )
    }

    fun canPrepareDisplay(
        safeInsetBindingEnabled: Boolean,
        safeInsetsResolved: Boolean
    ): Boolean {
        return !safeInsetBindingEnabled || safeInsetsResolved
    }

    fun resolveHorizontalMargin(
        baseMarginPx: Int,
        safeInsets: ReaderPageSafeInsets
    ): Int {
        return max(0, baseMarginPx) + max(safeInsets.left, safeInsets.right)
    }

    fun resolveFooterAreaHeight(
        minimumAreaHeightPx: Int,
        textMetricsTopPx: Float,
        textMetricsBottomPx: Float,
        batteryHeightPx: Float,
        bottomMarginPx: Int
    ): Int {
        val textInkHeight = max(0f, textMetricsBottomPx - textMetricsTopPx)
        val measuredContentHeight = max(textInkHeight, max(0f, batteryHeightPx))
        val measuredAreaHeight = ceil(measuredContentHeight).toInt() + max(0, bottomMarginPx)
        return max(max(0, minimumAreaHeightPx), measuredAreaHeight)
    }

    fun resolveTextLineLayout(
        textSizePx: Float,
        fontMetricsTopPx: Float,
        fontMetricsBottomPx: Float,
        configuredLineIntervalPx: Int,
        configuredParagraphIntervalPx: Int
    ): ReaderTextLineLayout {
        val inkHeight = max(
            1,
            ceil(max(0f, fontMetricsBottomPx - fontMetricsTopPx)).toInt()
        )
        val textSize = max(1, textSizePx.toInt())
        return ReaderTextLineLayout(
            inkHeight = inkHeight,
            lineGap = textSize + max(0, configuredLineIntervalPx) - inkHeight,
            paragraphGap = textSize + max(0, configuredParagraphIntervalPx) - inkHeight
        )
    }

    fun resolveContentCanvasTop(contentTopPx: Int, scrollMode: Boolean): Int {
        return if (scrollMode) 0 else max(0, contentTopPx)
    }

    fun resolveVertical(
        displayHeightPx: Int,
        contentTopPx: Int,
        contentBottomPaddingPx: Int,
        footerAreaHeightPx: Int,
        safeBottomInsetPx: Int
    ): ReaderPageVerticalGeometry {
        val displayBottom = max(0, displayHeightPx)
        val footerBottom = max(0, displayBottom - max(0, safeBottomInsetPx))
        val footerTop = max(0, footerBottom - max(0, footerAreaHeightPx))
        val contentBottom = max(0, footerTop - max(0, contentBottomPaddingPx))
        val contentTop = max(0, contentTopPx)
        return ReaderPageVerticalGeometry(
            contentTop = contentTop,
            contentBottom = contentBottom,
            contentHeight = max(0, contentBottom - contentTop),
            footerTop = footerTop,
            footerBottom = footerBottom
        )
    }
}
