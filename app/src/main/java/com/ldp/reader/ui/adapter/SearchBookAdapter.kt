package com.ldp.reader.ui.adapter

import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.ui.adapter.view.SearchBookHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder

class SearchBookAdapter : BaseListAdapter<BookSearchResult>() {
    override fun createViewHolder(viewType: Int): IViewHolder<BookSearchResult> {
        return SearchBookHolder()
    }
}
