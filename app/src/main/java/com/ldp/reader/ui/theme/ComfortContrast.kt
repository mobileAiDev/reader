package com.ldp.reader.ui.theme

import androidx.core.graphics.ColorUtils

/**
 * Keeps text/background contrast inside a comfortable band using the WCAG contrast ratio.
 *
 * Pure white on pure black (21:1) produces halation and eye strain in the dark, while too little
 * contrast makes text muddy. Text is moved along the straight line towards the background (to
 * soften) or towards black/white (to strengthen) until the ratio lands inside [min, max].
 */
object ComfortContrast {
    const val NIGHT_MIN = 6.5
    const val NIGHT_MAX = 9.0
    const val DAY_MIN = 7.5
    const val DAY_MAX = 14.0

    fun isDark(color: Int): Boolean = ColorUtils.calculateLuminance(color) < 0.18

    fun ratio(foreground: Int, background: Int): Double {
        return ColorUtils.calculateContrast(opaque(foreground), opaque(background))
    }

    fun textFor(text: Int, background: Int): Int {
        return if (isDark(background)) {
            clamp(text, background, NIGHT_MIN, NIGHT_MAX)
        } else {
            clamp(text, background, DAY_MIN, DAY_MAX)
        }
    }

    fun clamp(text: Int, background: Int, min: Double, max: Double): Int {
        val solidText = opaque(text)
        val solidBackground = opaque(background)
        val current = ratio(solidText, solidBackground)
        return when {
            current > max -> searchTowards(solidText, solidBackground, solidBackground, max)
            current < min -> {
                val pole = if (isDark(solidBackground)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                searchTowards(solidText, pole, solidBackground, min)
            }
            else -> solidText
        }
    }

    /** Binary search the blend amount whose contrast against [background] reaches [target]. */
    private fun searchTowards(from: Int, to: Int, background: Int, target: Double): Int {
        var low = 0f
        var high = 1f
        var best = from
        repeat(18) {
            val mid = (low + high) / 2f
            val candidate = ColorUtils.blendARGB(from, to, mid)
            val contrast = ratio(candidate, background)
            val movingTowardsBackground = to == background
            val overshoot = if (movingTowardsBackground) contrast < target else contrast > target
            if (overshoot) {
                high = mid
            } else {
                best = candidate
                low = mid
            }
        }
        return best
    }

    private fun opaque(color: Int): Int = ColorUtils.setAlphaComponent(color, 0xFF)
}
