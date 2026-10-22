package com.ldp.reader.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.ldp.reader.ui.glass.GlassLinearLayout
import com.ldp.reader.ui.motion.SpringValue
import kotlin.math.abs

/**
 * A glass sheet resting on the bottom edge that follows the finger down and is let go either
 * back into place or off the screen. It opens and closes on the same spring, so a drag, a fling
 * and a tap outside all hand over without a jump.
 *
 * The sheet takes a drag only when it is clearly vertical and [scrollContent] is at its top, so
 * sliders, segments and the content's own scrolling keep working.
 */
class ReaderSheetLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GlassLinearLayout(context, attrs) {
    /** Called once the sheet has come to rest off screen. */
    var onHidden: (() -> Unit)? = null

    /** Scrolling content inside the sheet; a downward drag scrolls it first. */
    var scrollContent: View? = null

    /** True from [open] until [close] starts, whatever the sheet is currently doing. */
    var isOpen = false
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val offset = SpringValue(context, OPEN_RESPONSE, OPEN_DAMPING, restDelta = 0.5f)
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragStartOffset = 0f
    private var dragging = false

    init {
        offset.listener = SpringValue.Listener { spring ->
            translationY = spring.value
            if (!spring.isRunning && !isOpen && spring.value >= hiddenOffset() - 1f) {
                visibility = INVISIBLE
                onHidden?.invoke()
            }
        }
        visibility = INVISIBLE
    }

    fun open() {
        isOpen = true
        visibility = VISIBLE
        post {
            if (!offset.isRunning && offset.value == 0f) offset.jumpTo(hiddenOffset())
            offset.setSpring(OPEN_RESPONSE, OPEN_DAMPING)
            offset.animateTo(0f)
        }
    }

    fun close() {
        close(offset.velocity)
    }

    private fun close(velocity: Float) {
        if (!isOpen) return
        isOpen = false
        offset.setSpring(CLOSE_RESPONSE, CLOSE_DAMPING)
        offset.animateTo(hiddenOffset(), velocity)
    }

    /** Distance that takes the sheet, its bottom margin and its shadow fully below the screen. */
    private fun hiddenOffset(): Float {
        val margin = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        return height + margin + SHADOW_ROOM_DP * density
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                // A finger on a sheet still settling catches it where it is.
                if (offset.isRunning && isOpen) {
                    startDrag(event)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (isDownwardPull(event)) {
                startDrag(event)
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) {
            // A touch that began on the sheet itself, not on a control, never passes through
            // onInterceptTouchEvent again, so the pull is recognised here too.
            if (event.actionMasked == MotionEvent.ACTION_MOVE && isDownwardPull(event)) {
                startDrag(event)
            }
            return super.onTouchEvent(event) || dragging
        }
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val pulled = dragStartOffset + event.rawY - downY
                // Past the resting place the sheet resists, like pulling paper off a stack.
                val next = if (pulled < 0f) pulled * UPWARD_RESISTANCE else pulled
                offset.jumpTo(next)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = checkNotNull(velocityTracker)
                tracker.computeCurrentVelocity(1000)
                val velocity = tracker.yVelocity
                tracker.recycle()
                velocityTracker = null
                dragging = false
                val flungDown = velocity > FLING_DP_PER_S * density
                val pulledFar = offset.value > height * DISMISS_FRACTION && velocity > -FLING_DP_PER_S * density
                if (flungDown || pulledFar) {
                    close(velocity)
                } else {
                    offset.setSpring(OPEN_RESPONSE, OPEN_DAMPING)
                    offset.animateTo(0f, velocity)
                }
            }
        }
        return true
    }

    private fun isDownwardPull(event: MotionEvent): Boolean {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        val atTop = scrollContent?.canScrollVertically(-1) != true
        return dy > touchSlop && dy > abs(dx) * 1.5f && atTop
    }

    private fun startDrag(event: MotionEvent) {
        dragging = true
        offset.stop()
        dragStartOffset = offset.value
        downY = event.rawY
        parent?.requestDisallowInterceptTouchEvent(true)
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    override fun onDetachedFromWindow() {
        offset.stop()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val OPEN_RESPONSE = 0.42f
        const val OPEN_DAMPING = 0.84f
        const val CLOSE_RESPONSE = 0.3f
        const val CLOSE_DAMPING = 1f
        const val UPWARD_RESISTANCE = 0.18f
        const val DISMISS_FRACTION = 0.3f
        const val FLING_DP_PER_S = 900f
        const val SHADOW_ROOM_DP = 24f
    }
}
