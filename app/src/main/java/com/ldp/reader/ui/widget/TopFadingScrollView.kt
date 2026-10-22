package com.ldp.reader.ui.widget

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * Scrolling content that fades only where it passes under the page header. The bottom runs on
 * under the navigation bar: with a bottom padding for that bar, the platform would draw the
 * bottom fade inside the padding, a dark band with a hard edge across the content.
 */
class TopFadingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NestedScrollView(context, attrs) {
    override fun getBottomFadingEdgeStrength(): Float = 0f
}

/** The list form of [TopFadingScrollView]. */
class TopFadingRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {
    override fun getBottomFadingEdgeStrength(): Float = 0f
}
