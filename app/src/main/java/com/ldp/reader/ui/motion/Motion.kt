package com.ldp.reader.ui.motion

import android.animation.ValueAnimator

/** Shared motion rules. */
object Motion {
    /** False when the user turned animations off in the system settings. */
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun lerp(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

    /** Smooth step of [value] across [from]..[to]. */
    fun window(value: Float, from: Float, to: Float): Float {
        if (value <= from) return 0f
        if (value >= to) return 1f
        val t = (value - from) / (to - from)
        return t * t * (3f - 2f * t)
    }
}
