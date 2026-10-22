package com.ldp.reader.ui.image

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import com.ldp.reader.R

/**
 * The cover of a book that has none: bound in a cloth whose colour follows from the title, so a
 * book always wears the same cover. Themes that draw covers as books paste the title on a paper
 * slip, set top to bottom; flat themes set it across the cloth like a modern jacket.
 */
object BookClothCover {
    /** 绛红, 藏青, 赭石, 檀褐, 墨灰, 绀紫: muted cloths that sit well on paper and in the dark. */
    private val CLOTHS = intArrayOf(
        0xFF7B3A33.toInt(),
        0xFF2F3D52.toInt(),
        0xFF8C6239.toInt(),
        0xFF6B4A3A.toInt(),
        0xFF45423E.toInt(),
        0xFF4A3B52.toInt()
    )
    private const val MAX_TITLE_CHARS = 8

    /** The plain cloth; the cover frame around it gives it its shape and light. */
    fun background(title: String): Drawable {
        return ColorDrawable(CLOTHS[Math.floorMod(title.hashCode(), CLOTHS.size)])
    }

    /** The title one character per line, for the vertical slip. */
    fun verticalTitle(title: String): String {
        return title.take(MAX_TITLE_CHARS).toList().joinToString("\n")
    }

    /** The cover text for [title]: one character per line on a slip, or the plain title on a jacket. */
    fun titleText(title: String, bookCovers: Boolean): String {
        return if (bookCovers) verticalTitle(title) else title
    }

    /**
     * Restyles a title laid out as a slip (Ink.BookTitleSlip in XML) as a modern jacket's type.
     * Call once per view, when the theme draws flat covers.
     */
    fun styleAsJacket(titleView: TextView) {
        titleView.background = null
        titleView.gravity = Gravity.START
        titleView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        titleView.setTextColor(titleView.context.getColor(R.color.book_cloth_title))
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleView.textSize * MODERN_TITLE_SCALE)
        titleView.setLineSpacing(titleView.textSize * 0.2f, 1f)
        titleView.layoutParams = titleView.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        (titleView.layoutParams as ViewGroup.MarginLayoutParams).rightMargin =
            (titleView.layoutParams as ViewGroup.MarginLayoutParams).leftMargin
    }

    private const val MODERN_TITLE_SCALE = 1.15f
}
