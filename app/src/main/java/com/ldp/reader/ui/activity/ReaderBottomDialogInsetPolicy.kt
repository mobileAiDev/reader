package com.ldp.reader.ui.activity

import kotlin.math.max

internal object ReaderBottomDialogInsetPolicy {
    fun bottomPadding(
        initialBottomPaddingPx: Int,
        systemBarBottomPx: Int,
        mandatoryGestureBottomPx: Int,
        cutoutBottomPx: Int
    ): Int {
        val safeBottom = max(
            max(0, systemBarBottomPx),
            max(max(0, mandatoryGestureBottomPx), max(0, cutoutBottomPx))
        )
        return max(0, initialBottomPaddingPx) + safeBottom
    }
}
