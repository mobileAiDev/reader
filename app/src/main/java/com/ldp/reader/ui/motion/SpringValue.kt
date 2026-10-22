package com.ldp.reader.ui.motion

import android.content.Context
import android.os.Build
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * A value pulled towards a target by a damped spring and stepped on display frames. Changing
 * the target keeps the current velocity, so a motion can be reversed mid-flight without a jump.
 *
 * @param response seconds for one undamped swing; smaller is snappier
 * @param dampingRatio 1 settles without overshoot; lower values bounce
 * @param restDelta distance from the target, in value units, that counts as arrived
 *
 * Adapted from the duo host UI.
 */
class SpringValue(
    context: Context,
    response: Float,
    dampingRatio: Float,
    private val restDelta: Float
) : Choreographer.FrameCallback {
    fun interface Listener {
        /** Called every frame and once more when the value comes to rest ([isRunning] false). */
        fun onSpringUpdate(spring: SpringValue)
    }

    private val display: Display? = displayOf(context)
    private var frameInterval = 1f / 60f
    private var stiffness = 0f
    private var damping = 0f
    var listener: Listener? = null
    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f
        private set
    private var lastFrameNanos = 0L
    var isRunning = false
        private set

    init {
        setSpring(response, dampingRatio)
    }

    /** Changes how the spring moves from now on; the current value and velocity carry over. */
    fun setSpring(response: Float, dampingRatio: Float) {
        val omega = (2 * PI / response).toFloat()
        stiffness = omega * omega
        damping = 2f * dampingRatio * omega
    }

    /** Places the value without motion and reports it. */
    fun jumpTo(newValue: Float) {
        stop()
        value = newValue
        target = newValue
        velocity = 0f
        listener?.onSpringUpdate(this)
    }

    fun animateTo(newTarget: Float) = animateTo(newTarget, velocity)

    fun animateTo(newTarget: Float, startVelocity: Float) {
        target = newTarget
        velocity = startVelocity
        if (!Motion.enabled()) {
            jumpTo(newTarget)
            return
        }
        if (isRunning) return
        isRunning = true
        frameInterval = 1f / (display?.refreshRate ?: 60f)
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Stops where it is without a final report. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        var elapsed = if (lastFrameNanos == 0L) frameInterval else (frameTimeNanos - lastFrameNanos) / 1e9f
        lastFrameNanos = frameTimeNanos
        // A stalled frame must not throw the value far past its target.
        elapsed = min(elapsed, 1f / 15f)
        while (elapsed > 0f) {
            val dt = min(STEP, elapsed)
            val force = -stiffness * (value - target) - damping * velocity
            velocity += force * dt
            value += velocity * dt
            elapsed -= dt
        }
        if (abs(value - target) < restDelta && abs(velocity) < restDelta * 10f) {
            value = target
            velocity = 0f
            isRunning = false
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
        listener?.onSpringUpdate(this)
    }

    private companion object {
        const val STEP = 1f / 480f

        fun displayOf(context: Context): Display? {
            return if (Build.VERSION.SDK_INT >= 30) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            }
        }
    }
}
