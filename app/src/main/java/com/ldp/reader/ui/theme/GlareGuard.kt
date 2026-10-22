package com.ldp.reader.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Adaptive anti-glare for images shown on dark surfaces (covers, comic pages, album art).
 *
 * The bitmap is sampled on a coarse grid and converted to CIE lightness (L*). Bright images —
 * white-background covers, paper-white comic pages — get their sRGB channels scaled down until
 * their 90th-percentile lightness and mean lightness sit under comfortable ceilings for a dark
 * room. Dark images are left untouched. Bright, saturated art is also slightly desaturated,
 * which removes the "neon" sting without washing colours out.
 */
object GlareGuard {
    private const val GRID = 24
    private const val HIGHLIGHT_CEILING_L = 76.0
    private const val MEAN_CEILING_L = 50.0
    private const val MIN_SCALE = 0.6f
    private const val SRGB_GAMMA = 2.2

    /** Active whenever the surrounding UI is dark. */
    fun isActive(context: Context): Boolean = AppearanceMode.isNight(context)

    fun apply(imageView: ImageView, drawable: Drawable?) {
        if (!isActive(imageView.context)) {
            imageView.clearColorFilter()
            return
        }
        // Vector placeholders are already tuned for dark surfaces; only decoded images are sampled.
        // Image loaders request software bitmaps while dark mode is active (see [isActive]).
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap == null || bitmap.config == Bitmap.Config.HARDWARE) {
            imageView.clearColorFilter()
            return
        }
        imageView.colorFilter = filterFor(scaleFor(bitmap))
    }

    /** Same as [apply] for a bitmap rendered by the app itself (e.g. a PDF page). */
    fun apply(imageView: ImageView, bitmap: Bitmap) {
        imageView.colorFilter = if (isActive(imageView.context)) filterFor(scaleFor(bitmap)) else null
    }

    fun clear(imageView: ImageView) {
        imageView.clearColorFilter()
    }

    /** sRGB channel multiplier that brings the image's bright areas under the ceilings. */
    fun scaleFor(bitmap: Bitmap): Float {
        if (bitmap.width <= 0 || bitmap.height <= 0) return 1f
        val samples = ArrayList<Double>(GRID * GRID)
        var sum = 0.0
        for (row in 0 until GRID) {
            val y = ((row + 0.5f) * bitmap.height / GRID).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until GRID) {
                val x = ((column + 0.5f) * bitmap.width / GRID).toInt().coerceIn(0, bitmap.width - 1)
                val lightness = lightness(bitmap.getPixel(x, y))
                samples.add(lightness)
                sum += lightness
            }
        }
        samples.sort()
        val highlight = samples[(samples.size * 0.9).toInt().coerceAtMost(samples.size - 1)]
        val mean = sum / samples.size
        val scale = minOf(
            channelScale(highlight, HIGHLIGHT_CEILING_L),
            channelScale(mean, MEAN_CEILING_L)
        )
        return scale.toFloat().coerceIn(MIN_SCALE, 1f)
    }

    fun filterFor(scale: Float): ColorMatrixColorFilter? {
        if (scale >= 0.999f) return null
        val matrix = ColorMatrix()
        // Up to 18% desaturation for the brightest images, proportional to how much was dimmed.
        matrix.setSaturation(1f - (1f - scale) / (1f - MIN_SCALE) * 0.18f)
        matrix.postConcat(ColorMatrix().apply { setScale(scale, scale, scale, 1f) })
        return ColorMatrixColorFilter(matrix)
    }

    /** Ratio of sRGB values that moves lightness [current] down to [ceiling]. */
    private fun channelScale(current: Double, ceiling: Double): Double {
        if (current <= ceiling) return 1.0
        val ratio = relativeLuminance(ceiling) / relativeLuminance(current)
        return ratio.pow(1.0 / SRGB_GAMMA)
    }

    private fun lightness(color: Int): Double {
        val r = linear((color shr 16) and 0xFF)
        val g = linear((color shr 8) and 0xFF)
        val b = linear(color and 0xFF)
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (y > 0.008856) 116.0 * cbrt(y) - 16.0 else 903.3 * y
    }

    private fun relativeLuminance(lightness: Double): Double {
        return if (lightness > 8.0) ((lightness + 16.0) / 116.0).pow(3.0) else lightness / 903.3
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
