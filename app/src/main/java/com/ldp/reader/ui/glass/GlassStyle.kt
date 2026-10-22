package com.ldp.reader.ui.glass

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ldp.reader.R
import com.ldp.reader.ui.theme.AppearanceMode

/**
 * How one kind of glass looks. Sizes are dp. Every glass surface takes one of these presets,
 * so the material reads the same everywhere.
 *
 * @param blur blur of what lies behind, before it is bent
 * @param bezel width of the rim band where light bends
 * @param depth how far content is pulled in at the very rim
 * @param dispersion colour split at the rim, 0 for none
 * @param saturation colour intensity of what shows through; 1 leaves it as it is
 * @param tint colour laid over, straight alpha
 * @param fallback opaque-ish fill where live glass is unavailable (before Android 12)
 * @param highlight strength of the rim light
 */
data class GlassStyle(
    val blur: Float,
    val bezel: Float,
    val depth: Float,
    val dispersion: Float,
    val saturation: Float,
    val tint: Int,
    val fallback: Int,
    val highlight: Float
) {
    companion object {
        /** Floating capsules: the bottom bar, the edit bar. */
        fun bar(context: Context): GlassStyle = GlassStyle(
            blur = 22f, bezel = 14f, depth = 8f, dispersion = 0.25f, saturation = 1.35f,
            tint = color(context, R.color.ink_glass_tint),
            fallback = color(context, R.color.ink_glass_fallback),
            highlight = rimLight(context)
        )

        /** Popup menus and cards that float over a page. */
        fun popup(context: Context): GlassStyle = GlassStyle(
            blur = 28f, bezel = 16f, depth = 10f, dispersion = 0.2f, saturation = 1.3f,
            tint = ColorUtils.setAlphaComponent(color(context, R.color.ink_glass_tint), 0xD6),
            fallback = color(context, R.color.ink_glass_fallback),
            highlight = rimLight(context) * 0.85f
        )

        /** Reader menus tinted by the page under them; [panel] is that page's panel colour. */
        fun readerPanel(panel: Int, dark: Boolean): GlassStyle = GlassStyle(
            blur = 30f, bezel = 18f, depth = 10f, dispersion = 0.2f, saturation = 1.2f,
            tint = ColorUtils.setAlphaComponent(panel, if (dark) 0xC2 else 0xB3),
            fallback = ColorUtils.setAlphaComponent(panel, 0xFF),
            highlight = if (dark) 0.45f else 0.85f
        )

        /** Comic, PDF and EPUB chrome, which stays dark over any page. */
        fun mediaReader(context: Context): GlassStyle =
            readerPanel(color(context, R.color.ink_reader_dark), dark = true)

        private fun rimLight(context: Context): Float = if (AppearanceMode.isNight(context)) 0.5f else 0.95f

        private fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)
    }
}
