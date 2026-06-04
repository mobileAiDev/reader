package com.ldp.reader.ui.adapter

import com.ldp.reader.media.MediaRequest
import com.ldp.reader.ui.adapter.view.ComicPageHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder

class ComicPageAdapter : BaseListAdapter<MediaRequest>() {
    var onPageLoadResult: ((position: Int, request: MediaRequest, success: Boolean, detail: String) -> Unit)? = null

    override fun createViewHolder(viewType: Int): IViewHolder<MediaRequest> {
        return ComicPageHolder { position, request, success, detail ->
            onPageLoadResult?.invoke(position, request, success, detail)
        }
    }
}
