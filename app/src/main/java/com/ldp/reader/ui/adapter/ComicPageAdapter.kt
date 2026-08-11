package com.ldp.reader.ui.adapter

import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.ui.adapter.view.ComicPageHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.BaseViewHolder
import com.ldp.reader.ui.base.adapter.IViewHolder
import java.util.Collections
import java.util.WeakHashMap

class ComicPageAdapter : BaseListAdapter<MediaRequest>() {
    private val pageHolders = Collections.newSetFromMap(
        WeakHashMap<ComicPageHolder, Boolean>()
    )
    private var pageLayoutMode = ComicPageLayoutMode.VERTICAL_CONTINUOUS
    var loadGeneration: Int = 0
        private set
    var onPageLoadResult: ((generation: Int, position: Int, request: MediaRequest, success: Boolean, detail: String) -> Unit)? = null

    override fun createViewHolder(viewType: Int): IViewHolder<MediaRequest> {
        return ComicPageHolder(
            generationProvider = { loadGeneration },
            layoutModeProvider = { pageLayoutMode }
        ) { generation, position, request, success, detail ->
            onPageLoadResult?.invoke(generation, position, request, success, detail)
        }.also(pageHolders::add)
    }

    fun refreshItemsForGeneration(generation: Int, pages: List<MediaRequest>) {
        loadGeneration = generation
        pageHolders.toList().forEach(ComicPageHolder::cancelPendingRetries)
        super.refreshItems(pages)
    }

    internal fun updatePageLayoutMode(mode: ComicPageLayoutMode) {
        if (pageLayoutMode == mode) return
        pageLayoutMode = mode
        pageHolders.toList().forEach(ComicPageHolder::cancelPendingRetries)
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        val comicHolder = (holder as? BaseViewHolder<*>)?.holder as? ComicPageHolder
        comicHolder?.cancelPendingRetries()
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        pageHolders.toList().forEach(ComicPageHolder::cancelPendingRetries)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun cancelPendingRetries() {
        pageHolders.toList().forEach(ComicPageHolder::cancelPendingRetries)
        loadGeneration += 1
        if (itemCount > 0) {
            super.refreshItems(emptyList())
        }
    }
}
