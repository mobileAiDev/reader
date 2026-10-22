package com.ldp.reader.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import com.ldp.reader.ui.motion.Motion

/**
 * The outline of content that is on its way: placeholder blocks that breathe slowly while the
 * layout is on screen, instead of a spinner. The pulse runs only while the view is visible.
 */
class SkeletonPulseLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val pulse = ValueAnimator.ofFloat(1f, DIM_ALPHA).apply {
        duration = PULSE_MS
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = PathInterpolator(0.45f, 0f, 0.55f, 1f)
        addUpdateListener { alpha = it.animatedValue as Float }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && Motion.enabled()) {
            if (!pulse.isStarted) pulse.start()
        } else {
            pulse.cancel()
            alpha = 1f
        }
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DIM_ALPHA = 0.45f
        const val PULSE_MS = 900L
    }
}
