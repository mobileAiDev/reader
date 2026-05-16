package com.ldp.reader.ui.adapter

import android.view.View
import android.view.ViewGroup
import com.ldp.reader.ui.adapter.view.CategoryHolder
import com.ldp.reader.ui.base.EasyAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.widget.page.TxtChapter

/**
 * Created by ldp on 17-6-5.
 */
class CategoryAdapter : EasyAdapter<TxtChapter>() {
    private var currentSelected = 0

    override fun onCreateViewHolder(viewType: Int): IViewHolder<TxtChapter> {
        return CategoryHolder()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = super.getView(position, convertView, parent)
        val holder = view.tag as CategoryHolder
        if (position == currentSelected) {
            holder.setSelectedChapter()
        }
        return view
    }

    fun setChapter(pos: Int) {
        currentSelected = pos
        notifyDataSetChanged()
    }
}
