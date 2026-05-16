package com.ldp.reader.widget.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

class LoadMoreDelegate(context: Context, options: WholeAdapter.Options) : WholeAdapter.ItemView {
    private val mLoadMoreView: LoadMoreView

    init {
        val view = LoadMoreView(context, options.loadMoreId, options.errorId, options.noMoreId)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        view.layoutParams = params
        mLoadMoreView = view
    }

    override fun onCreateView(parent: ViewGroup): View {
        return mLoadMoreView
    }

    override fun onBindView(view: View) {
        val loadMoreView = view as LoadMoreView
        loadMoreView.refreshView()
    }

    fun setLoadMoreStatus(status: Int) {
        mLoadMoreView.setLoadMoreStatus(status)
    }

    fun setOnLoadMoreListener(listener: LoadMoreView.OnLoadMoreListener?) {
        mLoadMoreView.setOnLoadMoreListener(listener)
    }
}
