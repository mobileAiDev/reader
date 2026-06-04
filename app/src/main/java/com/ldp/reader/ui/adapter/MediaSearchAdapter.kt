package com.ldp.reader.ui.adapter

import com.ldp.reader.media.MediaSearchBook
import com.ldp.reader.ui.adapter.view.MediaSearchHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder

class MediaSearchAdapter : BaseListAdapter<MediaSearchBook>() {
    override fun createViewHolder(viewType: Int): IViewHolder<MediaSearchBook> {
        return MediaSearchHolder()
    }
}
