package com.ldp.reader.ui.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * Spring press feedback: the view sinks a little under the finger and springs back, and a
 * quick tap still shows the full press. Never consumes the touch, so clicks and long-clicks work.
 */
object Press {
    const val CARD = 0.96f
    const val BUTTON = 0.94f

    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View, pressedScale: Float = CARD) {
        val spring = SpringValue(view.context, response = 0.28f, dampingRatio = 0.62f, restDelta = 0.001f)
        spring.listener = SpringValue.Listener { value ->
            val scale = 1f - (1f - pressedScale) * value.value
            view.scaleX = scale
            view.scaleY = scale
        }
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> spring.animateTo(1f)
                MotionEvent.ACTION_UP -> {
                    // Let a quick tap reach its pressed depth before springing back.
                    touched.postDelayed({ spring.animateTo(0f) }, QUICK_TAP_HOLD_MS)
                }
                MotionEvent.ACTION_CANCEL -> spring.animateTo(0f)
            }
            false
        }
    }

    private const val QUICK_TAP_HOLD_MS = 70L
}
