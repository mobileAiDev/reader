package com.ldp.reader.model.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextSizeTest {
    @Test
    fun defaultSizeAlwaysUsesCanonicalSpValue() {
        assertEquals(28, ReaderTextSize.DEFAULT_SP)
        assertEquals(28, ReaderTextSize.displaySp(storedPx = 16, scaledDensity = 3f, isDefault = true))
        assertEquals(84, ReaderTextSize.toStoredPx(ReaderTextSize.DEFAULT_SP, scaledDensity = 3f))
    }

    @Test
    fun customSizeRoundTripsBetweenDisplaySpAndStoredPx() {
        val storedPx = ReaderTextSize.toStoredPx(displaySp = 24, scaledDensity = 2.75f)

        assertEquals(66, storedPx)
        assertEquals(
            24,
            ReaderTextSize.displaySp(storedPx, scaledDensity = 2.75f, isDefault = false)
        )
    }

    @Test
    fun invalidDensityAndNonPositiveSizesStaySafe() {
        assertEquals(ReaderTextSize.DEFAULT_SP, ReaderTextSize.displaySp(60, 0f, isDefault = false))
        assertEquals(1, ReaderTextSize.toStoredPx(0, scaledDensity = 0f))
    }
}
