package com.ldp.reader.widget.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.LayoutRes

class LoadMoreView(
    context: Context,
    @LayoutRes loadMoreId: Int,
    @LayoutRes errorId: Int,
    @LayoutRes noMoreId: Int
) : FrameLayout(context) {
    private lateinit var mLoadMoreView: View
    private lateinit var mErrorView: View
    private lateinit var mNoMoreView: View

    private var mListener: OnLoadMoreListener? = null
    private var mStatus = TYPE_HIDE

    init {
        initView(loadMoreId, errorId, noMoreId)
    }

    private fun initView(loadMoreId: Int, errorId: Int, noMoreId: Int) {
        mLoadMoreView = inflateId(loadMoreId)
        mErrorView = inflateId(errorId)
        mNoMoreView = inflateId(noMoreId)

        addView(mLoadMoreView)
        addView(mErrorView)
        addView(mNoMoreView)

        refreshView()

        mErrorView.setOnClickListener {
            setLoadMore()
        }
    }

    private fun inflateId(id: Int): View {
        return LayoutInflater.from(context)
            .inflate(id, this, false)
    }

    fun setOnLoadMoreListener(listener: OnLoadMoreListener?) {
        mListener = listener
    }

    fun refreshView() {
        when (mStatus) {
            TYPE_HIDE -> setHide()
            TYPE_LOAD_MORE -> setLoadMore()
            TYPE_NO_MORE -> setLoadNoMore()
            TYPE_LOAD_ERROR -> setLoadError()
        }
    }

    fun setLoadMoreStatus(status: Int) {
        mStatus = status
        refreshView()
    }

    private fun setHide() {
        mLoadMoreView.visibility = GONE
        mErrorView.visibility = GONE
        mNoMoreView.visibility = GONE
    }

    private fun setLoadMore() {
        mLoadMoreView.visibility = VISIBLE
        mErrorView.visibility = GONE
        mNoMoreView.visibility = GONE
        mListener?.onLoadMore()
    }

    private fun setLoadError() {
        mLoadMoreView.visibility = GONE
        mErrorView.visibility = VISIBLE
        mNoMoreView.visibility = GONE
    }

    private fun setLoadNoMore() {
        mLoadMoreView.visibility = GONE
        mErrorView.visibility = GONE
        mNoMoreView.visibility = VISIBLE
    }

    fun interface OnLoadMoreListener {
        fun onLoadMore()
    }

    companion object {
        const val TYPE_HIDE = 0
        const val TYPE_LOAD_MORE = 1
        const val TYPE_NO_MORE = 2
        const val TYPE_LOAD_ERROR = 3
    }
}
