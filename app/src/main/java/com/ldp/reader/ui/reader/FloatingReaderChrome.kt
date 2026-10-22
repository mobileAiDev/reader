package com.ldp.reader.ui.reader

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ldp.reader.ui.motion.SpringValue
import kotlin.math.max

/** Placement shared by every reader's floating bars and sheets. */
object FloatingReaderChrome {
    /**
     * Keeps [view]'s XML margins and adds the system bars and cutout on the sides it touches:
     * the top edge for a top bar, the bottom edge otherwise, plus [clearancePx] on that edge for
     * page chrome the bar must not cover. The bars' place is measured as if the system bars were
     * shown, so revealing them with the menu never shifts a bar mid-motion.
     */
    fun keepClear(view: View, atTop: Boolean, clearancePx: Int = 0) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        val left = params.leftMargin
        val top = params.topMargin
        val right = params.rightMargin
        val bottom = params.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val lp = target.layoutParams as ViewGroup.MarginLayoutParams
            lp.leftMargin = left + max(bars.left, cutout.left)
            lp.rightMargin = right + max(bars.right, cutout.right)
            if (atTop) {
                lp.topMargin = top + max(bars.top, cutout.top) + clearancePx
            } else {
                lp.bottomMargin = bottom + max(bars.bottom, cutout.bottom) + clearancePx
            }
            target.layoutParams = lp
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}

/**
 * One floating bar that springs in from its own edge of [host] with a slight give and goes back
 * faster without bounce. Reversing mid-motion keeps the velocity, so taps never jump. The bar
 * stays laid out while hidden (invisible, not gone) so its travel is always known.
 */
class FloatingBar(
    private val bar: View,
    private val host: View,
    private val fromTop: Boolean
) {
    /** The intended state; the bar may still be moving towards it. */
    var isShown = false
        private set

    private val shadowRoom = SHADOW_ROOM_DP * bar.resources.displayMetrics.density
    private val spring = SpringValue(bar.context, IN_RESPONSE, IN_DAMPING, restDelta = 0.001f)

    init {
        spring.listener = SpringValue.Listener { render(it) }
        spring.jumpTo(0f)
    }

    fun setShown(shown: Boolean) {
        isShown = shown
        if (shown) {
            spring.setSpring(IN_RESPONSE, IN_DAMPING)
        } else {
            spring.setSpring(OUT_RESPONSE, 1f)
        }
        spring.animateTo(if (shown) 1f else 0f)
    }

    /** Puts the bar away at once, for states where motion would only distract (a failed load). */
    fun hideNow() {
        isShown = false
        spring.jumpTo(0f)
    }

    fun release() {
        spring.stop()
    }

    private fun render(spring: SpringValue) {
        val hidden = 1f - spring.value
        bar.translationY = if (fromTop) {
            -hidden * (bar.bottom + shadowRoom)
        } else {
            hidden * (host.height - bar.top + shadowRoom)
        }
        bar.alpha = (spring.value * 1.6f).coerceIn(0f, 1f)
        bar.visibility = if (!spring.isRunning && spring.value <= 0f) View.INVISIBLE else View.VISIBLE
    }

    private companion object {
        const val IN_RESPONSE = 0.4f
        const val IN_DAMPING = 0.8f
        const val OUT_RESPONSE = 0.26f
        const val SHADOW_ROOM_DP = 24f
    }
}
