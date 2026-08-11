package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBottomDialogWindowPolicyTest {

    @Test
    fun navigationBarIsRemovedFromTheFloatingWindowFrameWithoutRemovingOtherInsets() {
        val statusBars = 0b0001
        val navigationBars = 0b0010
        val displayCutout = 0b0100

        assertEquals(
            statusBars or displayCutout,
            ReaderBottomDialogWindowPolicy.fitInsetsTypesForEdgeToEdgeBottom(
                currentFitInsetsTypes = statusBars or navigationBars or displayCutout,
                navigationBarInsetsType = navigationBars
            )
        )
    }

    @Test
    fun anAlreadyEdgeToEdgeBottomWindowKeepsItsExistingFitInsets() {
        assertEquals(
            0b0101,
            ReaderBottomDialogWindowPolicy.fitInsetsTypesForEdgeToEdgeBottom(
                currentFitInsetsTypes = 0b0101,
                navigationBarInsetsType = 0b0010
            )
        )
    }
}
