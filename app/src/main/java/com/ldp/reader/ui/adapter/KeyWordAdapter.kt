package com.ldp.reader.ui.adapter

import com.ldp.reader.ui.adapter.view.KeyWordHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder

class KeyWordAdapter : BaseListAdapter<String>() {
    override fun createViewHolder(viewType: Int): IViewHolder<String> {
        return KeyWordHolder()
    }
}
