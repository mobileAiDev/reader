package com.ldp.reader.widget.refresh

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.ui.widget.TopFadingRecyclerView

class ScrollRefreshRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollRefreshLayout(context, attrs) {
    private lateinit var mRecyclerView: RecyclerView

    fun setLayoutManager(manager: RecyclerView.LayoutManager?) {
        mRecyclerView.layoutManager = manager
    }

    fun addItemDecoration(decoration: RecyclerView.ItemDecoration) {
        mRecyclerView.addItemDecoration(decoration)
    }

    fun setAdapter(adapter: RecyclerView.Adapter<*>?) {
        mRecyclerView.adapter = adapter
        adapter!!.registerAdapterDataObserver(MyAdapterDataObserver())
    }

    fun startRefresh() {
        mRecyclerView.post { isRefreshing = true }
    }

    fun finishRefresh() {
        mRecyclerView.post {
            this@ScrollRefreshRecyclerView.isRefreshing = false
        }
    }

    override fun getContentView(parent: ViewGroup): View {
        mRecyclerView = TopFadingRecyclerView(context)
        return mRecyclerView
    }

    fun getReyclerView(): RecyclerView {
        return mRecyclerView
    }

    internal inner class MyAdapterDataObserver : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            super.onChanged()
            update()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            super.onItemRangeChanged(positionStart, itemCount)
            update()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            super.onItemRangeChanged(positionStart, itemCount, payload)
            update()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            update()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            super.onItemRangeRemoved(positionStart, itemCount)
            update()
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            super.onItemRangeMoved(fromPosition, toPosition, itemCount)
            update()
        }

        private fun update() {
            val count = mRecyclerView.adapter!!.itemCount
            if (count == 0) {
                showEmptyView()
                mRecyclerView.visibility = GONE
            } else if (mRecyclerView.visibility == GONE) {
                hideEmptyView()
                mRecyclerView.visibility = VISIBLE
            }
        }
    }

    companion object {
        private const val TAG = "ScrollRefreshRecyclerView"
        private var showLog = true

        @JvmStatic
        fun log(str: String?) {
            if (showLog) {
                Log.d(TAG, str!!)
            }
        }
    }
}
