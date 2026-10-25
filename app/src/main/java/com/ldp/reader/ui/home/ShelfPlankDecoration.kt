package com.ldp.reader.ui.home

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * A wooden shelf under every row of books, running the full width of the wall. Books stand on
 * its top face: the plank is drawn beneath the items, starting a little above the cover bottoms.
 * Full-width items (the continue-reading card) sit above the shelves and get none.
 *
 * Each shelf item is a vertical layout whose first child is the cover.
 */
class ShelfPlankDecoration(
    private val plank: Drawable,
    private val isFullSpan: (Int) -> Boolean,
    private val sinkPx: Int,
    private val roomPx: Int
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || isFullSpan(position)) return
        outRect.bottom = roomPx
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        plank.setBounds(0, 0, parent.width, plank.intrinsicHeight)
        var lastShelfTop = Int.MIN_VALUE
        for (index in 0 until parent.childCount) {
            val item = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(item)
            if (position == RecyclerView.NO_POSITION || isFullSpan(position)) continue
            val cover = (item as ViewGroup).getChildAt(0)
            val shelfTop = item.top + item.translationY.toInt() + cover.bottom - sinkPx
            // Items of one row share their shelf; draw it once.
            if (shelfTop == lastShelfTop) continue
            lastShelfTop = shelfTop
            // Tiled bitmaps need local coordinates: moving only the bounds leaves the grain
            // stationary behind a moving shelf-shaped window.
            val saveCount = canvas.save()
            canvas.translate(0f, shelfTop.toFloat())
            plank.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
    }
}
