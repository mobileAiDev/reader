package com.ldp.reader.ui.motion

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** One vocabulary of touch feedback, mapped to the platform's constants. */
object Haptics {
    /** A discrete step: a segment, a swatch, a stepper. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A toggle flipping on or off. */
    fun toggle(view: View, on: Boolean) {
        val constant = if (Build.VERSION.SDK_INT >= 34) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    /** A decision completed: a sheet option chosen, an item pinned. */
    fun confirm(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }
}
