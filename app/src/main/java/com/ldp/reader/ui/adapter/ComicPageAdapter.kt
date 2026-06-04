package com.ldp.reader.ui.adapter

import com.ldp.reader.media.MediaRequest
import com.ldp.reader.ui.adapter.view.ComicPageHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder

class ComicPageAdapter : BaseListAdapter<MediaRequest>() {
    override fun createViewHolder(viewType: Int): IViewHolder<MediaRequest> {
        return ComicPageHolder()
    }
}
