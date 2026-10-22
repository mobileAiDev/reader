package com.ldp.reader.ui.adapter

import android.view.View
import android.view.ViewGroup
import com.ldp.reader.ui.adapter.view.CategoryHolder
import com.ldp.reader.ui.base.EasyAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.ui.theme.ReaderPalette
import com.ldp.reader.widget.page.TxtChapter

/**
 * Created by ldp on 17-6-5.
 */
class CategoryAdapter : EasyAdapter<TxtChapter>() {
    private var currentSelected = 0
    private var palette: ReaderPalette? = null

    override fun onCreateViewHolder(viewType: Int): IViewHolder<TxtChapter> {
        return CategoryHolder { checkNotNull(palette) }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = super.getView(position, convertView, parent)
        val holder = view.tag as CategoryHolder
        val item = getItem(position)
        val catalogIndex = item.catalogIndex.takeIf { index -> index >= 0 } ?: position
        if (catalogIndex == currentSelected) {
            holder.setSelectedChapter()
        }
        return view
    }

    fun setChapter(pos: Int) {
        currentSelected = pos
        notifyDataSetChanged()
    }

    fun applyPalette(newPalette: ReaderPalette) {
        palette = newPalette
        notifyDataSetChanged()
    }
}
