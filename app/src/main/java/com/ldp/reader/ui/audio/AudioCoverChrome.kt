package com.ldp.reader.ui.audio

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator

internal object AudioCoverChrome {
    private const val ROTATION_DURATION_MS = 18_000L

    fun configureCircularCover(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        view.clipToOutline = true
    }

    fun updateRotation(view: View, playing: Boolean, current: ObjectAnimator?): ObjectAnimator? {
        if (!playing && current == null) return null
        val animator = current ?: ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = ROTATION_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        if (playing) {
            if (!animator.isStarted) {
                animator.start()
            } else if (!animator.isRunning) {
                animator.resume()
            }
        } else if (animator.isRunning) {
            animator.pause()
        }
        return animator
    }
}
