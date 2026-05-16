package com.ldp.reader.widget.itemdecoration

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Created by ldp on 2017/10/8.
 */
class DividerItemDecoration(context: Context?) : RecyclerView.ItemDecoration() {
    private var mDrawable: Drawable?

    init {
        val a = context!!.obtainStyledAttributes(ATTRS)
        mDrawable = a.getDrawable(0)
        a.recycle()
    }

    override fun onDraw(c: Canvas, parent: RecyclerView) {
        if (getLayoutManagerType(parent) == VERTICAL_LIST) {
            drawVertical(c, parent)
        } else {
            drawHorizontal(c, parent)
        }
    }

    private fun getLayoutManagerType(rv: RecyclerView): Int {
        val manager = rv.layoutManager

        if (manager !is LinearLayoutManager) {
            throw IllegalArgumentException("only supply linearLayoutManager")
        }
        return manager.orientation
    }

    fun drawVertical(c: Canvas, parent: RecyclerView) {
        val left = parent.paddingLeft
        val right = parent.width - parent.paddingRight

        val childCount = parent.childCount
        for (i in 0 until childCount) {
            val child: View = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val top = child.bottom + params.bottomMargin
            val bottom = top + mDrawable!!.intrinsicHeight
            mDrawable!!.setBounds(left, top, right, bottom)
            mDrawable!!.draw(c)
        }
    }

    fun drawHorizontal(c: Canvas, parent: RecyclerView) {
        val top = parent.paddingTop
        val bottom = parent.height - parent.paddingBottom

        val childCount = parent.childCount
        for (i in 0 until childCount) {
            val child: View = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val left = child.right + params.rightMargin
            val right = left + mDrawable!!.intrinsicHeight
            mDrawable!!.setBounds(left, top, right, bottom)
            mDrawable!!.draw(c)
        }
    }

    override fun getItemOffsets(outRect: Rect, itemPosition: Int, parent: RecyclerView) {
        if (getLayoutManagerType(parent) == VERTICAL_LIST) {
            outRect.set(0, 0, 0, mDrawable!!.intrinsicHeight)
        } else {
            outRect.set(0, 0, mDrawable!!.intrinsicWidth, 0)
        }
    }

    companion object {
        private const val TAG = "DividerItemDecoration"
        private val ATTRS = intArrayOf(android.R.attr.listDivider)

        const val HORIZONTAL_LIST = LinearLayoutManager.HORIZONTAL

        const val VERTICAL_LIST = LinearLayoutManager.VERTICAL
    }
}
