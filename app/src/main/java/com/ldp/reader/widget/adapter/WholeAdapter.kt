package com.ldp.reader.widget.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.ui.base.adapter.BaseListAdapter

abstract class WholeAdapter<T> : BaseListAdapter<T> {
    private var mLoadDelegate: LoadMoreDelegate? = null
    private val mHeaderList = ArrayList<ItemView>(2)
    private val mFooterList = ArrayList<ItemView>(2)

    constructor()

    constructor(context: Context, options: Options?) {
        if (options != null) {
            mLoadDelegate = LoadMoreDelegate(context, options)
            mFooterList.add(mLoadDelegate!!)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_ITEM) {
            super.onCreateViewHolder(parent, viewType)
        } else {
            createOtherViewHolder(parent, viewType)
        }
    }

    private fun createOtherViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        var view: View? = null
        for (itemView in mHeaderList) {
            if (viewType == itemView.hashCode()) {
                view = itemView.onCreateView(parent)
            }
        }
        for (itemView in mFooterList) {
            if (viewType == itemView.hashCode()) {
                view = itemView.onCreateView(parent)
            }
        }
        return object : RecyclerView.ViewHolder(view!!) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position < mHeaderList.size) {
            mHeaderList[position].onBindView(holder.itemView)
        } else if (position < mHeaderList.size + getItemSize()) {
            super.onBindViewHolder(holder, position - mHeaderList.size)
        } else {
            val pos = position - mHeaderList.size - getItemSize()
            mFooterList[pos].onBindView(holder.itemView)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < mHeaderList.size) {
            mHeaderList[position].hashCode()
        } else if (position < mHeaderList.size + getItemSize()) {
            TYPE_ITEM
        } else {
            val pos = position - mHeaderList.size - getItemSize()
            mFooterList[pos].hashCode()
        }
    }

    final override fun getItemCount(): Int {
        return mHeaderList.size + getItemSize() + mFooterList.size
    }

    fun addHeaderView(itemView: ItemView) {
        mHeaderList.add(itemView)
    }

    fun addFooterView(itemView: ItemView) {
        if (mLoadDelegate != null) {
            val count = mFooterList.size - 1
            mFooterList.add(count, itemView)
        } else {
            mFooterList.add(itemView)
        }
    }

    override fun addItems(values: List<T>) {
        if (values.isEmpty() && mLoadDelegate != null) {
            mLoadDelegate!!.setLoadMoreStatus(LoadMoreView.TYPE_NO_MORE)
        }
        super.addItems(values)
    }

    fun setOnLoadMoreListener(listener: LoadMoreView.OnLoadMoreListener?) {
        checkLoadMoreExist()
        mLoadDelegate!!.setOnLoadMoreListener(listener)
    }

    private fun checkLoadMoreExist() {
        if (mLoadDelegate == null) {
            throw IllegalArgumentException("you must setting LoadMore Option")
        }
    }

    override fun refreshItems(list: List<T>) {
        if (mLoadDelegate != null) {
            mLoadDelegate!!.setLoadMoreStatus(LoadMoreView.TYPE_LOAD_MORE)
        }
        super.refreshItems(list)
    }

    fun showLoadError() {
        checkLoadMoreExist()
        mLoadDelegate!!.setLoadMoreStatus(LoadMoreView.TYPE_LOAD_ERROR)
        notifyDataSetChanged()
    }

    inner class WholeGridSpanSizeLookUp(var maxSize: Int = 1) : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            if (position < mHeaderList.size) {
                return maxSize
            }
            return if (position < mHeaderList.size + getItemSize()) 1 else maxSize
        }
    }

    class Options {
        @LayoutRes
        @JvmField
        var loadMoreId: Int = R.layout.view_load_more

        @LayoutRes
        @JvmField
        var errorId: Int = R.layout.view_error

        @LayoutRes
        @JvmField
        var noMoreId: Int = R.layout.view_nomore
    }

    interface ItemView {
        fun onCreateView(parent: ViewGroup): View
        fun onBindView(view: View)
    }

    companion object {
        private const val TYPE_ITEM = 0
    }
}
