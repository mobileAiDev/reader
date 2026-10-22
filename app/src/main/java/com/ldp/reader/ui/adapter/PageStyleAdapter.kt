package com.ldp.reader.ui.adapter

import android.view.View
import com.ldp.reader.ui.adapter.view.PageStyleHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.ui.theme.ReaderPalette
import com.ldp.reader.widget.page.PageStyle

/**
 * Created by ldp on 17-5-19.
 */
class PageStyleAdapter(
    private val paletteProvider: () -> ReaderPalette
) : BaseListAdapter<PageStyle>() {
    private var currentChecked = PageStyle.BG_0

    override fun createViewHolder(viewType: Int): IViewHolder<PageStyle> {
        return PageStyleHolder(paletteProvider) { style -> style == currentChecked }
    }

    fun setPageStyleChecked(pageStyle: PageStyle) {
        currentChecked = pageStyle
        notifyDataSetChanged()
    }

    override fun onItemClick(v: View?, pos: Int) {
        super.onItemClick(v, pos)
        currentChecked = mList[pos]
        notifyDataSetChanged()
    }
}
