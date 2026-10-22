package com.ldp.reader.ui.theme

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ldp.reader.R
import com.ldp.reader.widget.page.PageStyle

/**
 * Colours for one reading page style and the reader chrome laid over it.
 *
 * Menus, the settings sheet and the catalog are tinted from the page itself, so opening a menu
 * on parchment feels like parchment and the night page never flashes a bright panel. Body text
 * runs through [ComfortContrast] so night pages avoid pure-white halation.
 */
data class ReaderPalette(
    val isDark: Boolean,
    val page: Int,
    val text: Int,
    val tip: Int,
    val panel: Int,
    val panelText: Int,
    val panelTextSecondary: Int,
    val panelControl: Int,
    val panelDivider: Int,
    val accent: Int,
    val onAccent: Int
) {
    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()

        fun of(context: Context, style: PageStyle): ReaderPalette {
            val page = ContextCompat.getColor(context, style.bgColor)
            val dark = ComfortContrast.isDark(page)
            val text = ComfortContrast.textFor(ContextCompat.getColor(context, style.fontColor), page)
            val panel = ColorUtils.blendARGB(page, WHITE, if (dark) 0.06f else 0.55f)
            val panelText = ComfortContrast.clamp(
                ContextCompat.getColor(
                    context,
                    if (dark) R.color.ink_reader_ink_dark else R.color.ink_reader_ink_light
                ),
                panel,
                if (dark) ComfortContrast.NIGHT_MIN else ComfortContrast.DAY_MIN,
                if (dark) 11.0 else 16.0
            )
            return ReaderPalette(
                isDark = dark,
                page = page,
                text = text,
                tip = ColorUtils.blendARGB(text, page, 0.42f),
                panel = panel,
                panelText = panelText,
                panelTextSecondary = ColorUtils.blendARGB(panelText, panel, 0.42f),
                panelControl = ColorUtils.blendARGB(panel, panelText, if (dark) 0.08f else 0.06f),
                panelDivider = ColorUtils.blendARGB(panel, panelText, 0.1f),
                accent = ContextCompat.getColor(
                    context,
                    if (dark) R.color.ink_reader_accent_dark else R.color.ink_reader_accent_light
                ),
                onAccent = ContextCompat.getColor(
                    context,
                    if (dark) R.color.ink_reader_on_accent_dark else R.color.ink_reader_on_accent_light
                )
            )
        }
    }
}
