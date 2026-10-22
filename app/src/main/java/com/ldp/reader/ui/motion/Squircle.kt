package com.ldp.reader.ui.motion

import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Continuous corners: a circular arc that eases into the straight edges instead of meeting them
 * abruptly (60% corner smoothing, two cubic curves around a shortened arc per corner). Shaders
 * draw the same corner as a superellipse of [EXPONENT] over [extent].
 *
 * Adapted from the duo host UI.
 */
object Squircle {
    const val SMOOTHING = 0.6f
    const val EXPONENT = 3.55f

    /** Length along each edge that a corner of [radius] occupies in [rect]. */
    fun extent(rect: RectF, radius: Float): Float {
        return min((1f + SMOOTHING) * radius, min(rect.width(), rect.height()) / 2f)
    }

    /** Superellipse exponent of the corner; a corner squeezed by a small rect turns plainly round. */
    fun exponent(rect: RectF, radius: Float): Float {
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        if (r <= 0f) return 2f
        val smoothing = (extent(rect, radius) / r - 1f).coerceAtLeast(0f)
        return 2f + (EXPONENT - 2f) * (smoothing / SMOOTHING)
    }

    /** Clipping follows paths from Android 13; before that a slightly rounder rounded rect. */
    fun outline(outline: Outline, rect: RectF, radius: Float, scratch: Path) {
        if (Build.VERSION.SDK_INT >= 33) {
            path(scratch, rect, radius)
            outline.setPath(scratch)
            return
        }
        val r = min(radius * 1.25f, min(rect.width(), rect.height()) / 2f)
        outline.setRoundRect(
            Math.round(rect.left), Math.round(rect.top),
            Math.round(rect.right), Math.round(rect.bottom), r
        )
    }

    /** Replaces [out] with the outline of [rect] with continuous corners of [radius]. */
    fun path(out: Path, rect: RectF, radius: Float) {
        out.rewind()
        val budget = min(rect.width(), rect.height()) / 2f
        val r = min(radius, budget)
        if (r <= 0f) {
            out.addRect(rect, Path.Direction.CW)
            return
        }
        val p = min((1f + SMOOTHING) * r, budget)
        val smoothing = p / r - 1f
        val arc = 90f * (1f - smoothing)
        val arcLength = (sin(Math.toRadians(arc / 2.0)) * r * sqrt(2.0)).toFloat()
        val alpha = (90f - arc) / 2f
        val p3p4 = (r * tan(Math.toRadians(alpha / 2.0))).toFloat()
        val beta = 45f * smoothing
        val c = (p3p4 * cos(Math.toRadians(beta.toDouble()))).toFloat()
        val d = (c * tan(Math.toRadians(beta.toDouble()))).toFloat()
        val b = (p - arcLength - c - d) / 3f
        val a = 2f * b
        val l = rect.left
        val t = rect.top
        val right = rect.right
        val bottom = rect.bottom
        val circle = RectF()

        out.moveTo(right - p, t)
        out.rCubicTo(a, 0f, a + b, 0f, a + b + c, d)
        circle.set(right - 2 * r, t, right, t + 2 * r)
        out.arcTo(circle, -45f - arc / 2f, arc, false)
        out.rCubicTo(d, c, d, b + c, d, a + b + c)

        out.lineTo(right, bottom - p)
        out.rCubicTo(0f, a, 0f, a + b, -d, a + b + c)
        circle.set(right - 2 * r, bottom - 2 * r, right, bottom)
        out.arcTo(circle, 45f - arc / 2f, arc, false)
        out.rCubicTo(-c, d, -(b + c), d, -(a + b + c), d)

        out.lineTo(l + p, bottom)
        out.rCubicTo(-a, 0f, -(a + b), 0f, -(a + b + c), -d)
        circle.set(l, bottom - 2 * r, l + 2 * r, bottom)
        out.arcTo(circle, 135f - arc / 2f, arc, false)
        out.rCubicTo(-d, -c, -d, -(b + c), -d, -(a + b + c))

        out.lineTo(l, t + p)
        out.rCubicTo(0f, -a, 0f, -(a + b), d, -(a + b + c))
        circle.set(l, t, l + 2 * r, t + 2 * r)
        out.arcTo(circle, 225f - arc / 2f, arc, false)
        out.rCubicTo(c, -d, b + c, -d, a + b + c, -d)
        out.close()
    }
}
