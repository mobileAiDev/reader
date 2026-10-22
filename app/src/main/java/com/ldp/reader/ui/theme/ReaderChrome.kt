package com.ldp.reader.ui.theme

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Drawables and colour lists built from a [ReaderPalette] for runtime-tinted reader chrome. */
object ReaderChrome {
    fun rounded(color: Int, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }
    }

    /** Rounded on the right only, for a panel sliding in from the left edge. */
    fun endRounded(color: Int, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(0f, 0f, radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f)
        }
    }

    fun pressable(palette: ReaderPalette, color: Int, radiusPx: Float): Drawable {
        val shape = rounded(color, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(palette.panelDivider), shape, rounded(0xFFFFFFFF.toInt(), radiusPx))
    }

    /** Background that fills with [checkedColor] when checked and [normalColor] otherwise. */
    fun checkable(checkedColor: Int, normalColor: Int, radiusPx: Float): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), rounded(checkedColor, radiusPx))
            addState(intArrayOf(), rounded(normalColor, radiusPx))
        }
    }

    fun checkableText(checkedColor: Int, normalColor: Int): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(checkedColor, normalColor)
        )
    }

    /** Accent at low opacity over the panel, for the chapter being read in the catalog. */
    fun accentWash(palette: ReaderPalette): Int {
        return ColorUtils.blendARGB(palette.panel, palette.accent, if (palette.isDark) 0.22f else 0.14f)
    }

    /** Ink at low opacity over the panel, for selected chips: chosen, not shouting. */
    fun inkWash(palette: ReaderPalette): Int {
        return ColorUtils.blendARGB(palette.panel, palette.panelText, if (palette.isDark) 0.16f else 0.1f)
    }

    /** A segment or card lifted slightly above the panel. */
    fun raised(palette: ReaderPalette): Int {
        return if (palette.isDark) {
            ColorUtils.blendARGB(palette.panel, 0xFFFFFFFF.toInt(), 0.12f)
        } else {
            0xFFFFFFFF.toInt()
        }
    }

    fun tintText(view: TextView, color: Int) {
        view.setTextColor(color)
        view.compoundDrawableTintList = ColorStateList.valueOf(color)
    }

    /** Sliders are drawn in the panel's ink; the accent is kept for the reading position. */
    fun tintSeekBar(seekBar: SeekBar, palette: ReaderPalette) {
        seekBar.progressTintList = ColorStateList.valueOf(palette.panelText)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(palette.panelDivider)
        seekBar.thumbTintList = ColorStateList.valueOf(palette.panelText)
    }
}
