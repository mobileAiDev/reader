package com.ldp.reader.ui.adapter

import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.ui.adapter.view.PageStyleHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.BaseViewHolder
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.widget.page.PageStyle

/**
 * Created by ldp on 17-5-19.
 */
class PageStyleAdapter : BaseListAdapter<Drawable>() {
    private var currentChecked = 0

    override fun createViewHolder(viewType: Int): IViewHolder<Drawable> {
        return PageStyleHolder()
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        @Suppress("UNCHECKED_CAST")
        val pageStyleHolder = (holder as BaseViewHolder<Drawable>).holder as PageStyleHolder
        if (currentChecked == position) {
            pageStyleHolder.setChecked()
        }
    }

    fun setPageStyleChecked(pageStyle: PageStyle) {
        currentChecked = pageStyle.ordinal
    }

    override fun onItemClick(v: View?, pos: Int) {
        super.onItemClick(v, pos)
        currentChecked = pos
        notifyDataSetChanged()
    }
}
