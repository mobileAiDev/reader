package com.ldp.reader.ui.theme

import android.content.Context
import android.graphics.drawable.Drawable
import com.ldp.reader.R

/** How a theme draws book covers. */
enum class CoverStyle {
    /** A flat picture with a hairline. */
    FLAT,

    /** A bound book: a shade along the spine and a soft shadow. */
    BOOK,

    /** A volume standing on a shelf: hardcover hinge, sheen, page block and varied heights. */
    VOLUME
}

/** What the current InkTheme decides beyond colour, read from a themed [Context]. */
class InkThemeTraits private constructor(
    /** Drawn under each row of books; null when the theme has no shelves. */
    val shelfPlank: Drawable?,
    /** Whether titles and progress appear under the covers on the shelf. */
    val shelfCaptions: Boolean,
    val coverStyle: CoverStyle
) {
    /** Covers drawn as books of some kind, so generated covers get a paper title slip. */
    val bookCovers: Boolean get() = coverStyle != CoverStyle.FLAT

    companion object {
        fun of(context: Context): InkThemeTraits {
            val a = context.obtainStyledAttributes(R.styleable.InkThemeTraits)
            val traits = InkThemeTraits(
                shelfPlank = a.getDrawable(R.styleable.InkThemeTraits_inkShelfPlank),
                shelfCaptions = a.getBoolean(R.styleable.InkThemeTraits_inkShelfCaptions, true),
                coverStyle = CoverStyle.entries[a.getInt(R.styleable.InkThemeTraits_inkCoverStyle, 0)]
            )
            a.recycle()
            return traits
        }
    }
}
