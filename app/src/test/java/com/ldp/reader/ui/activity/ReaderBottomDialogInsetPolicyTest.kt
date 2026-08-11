package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBottomDialogInsetPolicyTest {

    @Test
    fun gestureAreaExtendsPanelBackgroundWithoutMovingContentTwice() {
        assertEquals(
            108,
            ReaderBottomDialogInsetPolicy.bottomPadding(
                initialBottomPaddingPx = 36,
                systemBarBottomPx = 72,
                mandatoryGestureBottomPx = 72,
                cutoutBottomPx = 0
            )
        )
    }

    @Test
    fun threeButtonAndCutoutInsetsUseTheLargestSingleOwner() {
        assertEquals(
            156,
            ReaderBottomDialogInsetPolicy.bottomPadding(
                initialBottomPaddingPx = 12,
                systemBarBottomPx = 144,
                mandatoryGestureBottomPx = 144,
                cutoutBottomPx = 20
            )
        )
    }
}
