package com.ldp.reader.model.local

import kotlin.math.roundToInt

/**
 * Keeps the reader setting UI in sp while the page renderer continues to store pixels.
 */
object ReaderTextSize {
    const val DEFAULT_SP = 28
    private const val MIN_SP = 1

    fun displaySp(storedPx: Int, scaledDensity: Float, isDefault: Boolean): Int {
        if (isDefault) return DEFAULT_SP
        if (!scaledDensity.isFinite() || scaledDensity <= 0f) return DEFAULT_SP
        return (storedPx / scaledDensity).roundToInt().coerceAtLeast(MIN_SP)
    }

    fun toStoredPx(displaySp: Int, scaledDensity: Float): Int {
        val safeSp = displaySp.coerceAtLeast(MIN_SP)
        if (!scaledDensity.isFinite() || scaledDensity <= 0f) return safeSp
        return (safeSp * scaledDensity).roundToInt().coerceAtLeast(1)
    }

    fun defaultStoredPx(scaledDensity: Float): Int = toStoredPx(DEFAULT_SP, scaledDensity)
}
