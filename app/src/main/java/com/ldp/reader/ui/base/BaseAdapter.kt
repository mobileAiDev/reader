package com.ldp.reader.ui.base

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

abstract class BaseAdapter<E, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    protected val mItemList: MutableList<E> = ArrayList()
    private var mItemClickListener: OnItemClickListener? = null

    val items: List<E>
        get() = Collections.unmodifiableList(mItemList)

    override fun onBindViewHolder(holder: VH, position: Int) {
        bindData(holder, getItem(position), position)
        setUpClickListener(holder.itemView, position)
    }

    private fun setUpClickListener(view: View, position: Int) {
        view.setOnClickListener {
            onItemClick(view, position)
            mItemClickListener?.itemClick(view, position)
        }
    }

    override fun getItemCount(): Int {
        return mItemList.size
    }

    abstract fun bindData(holder: VH, data: E, position: Int)

    protected open fun onItemClick(v: View?, pos: Int) {
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        mItemClickListener = listener
    }

    fun addItems(item: E) {
        mItemList.add(item)
        notifyDataSetChanged()
    }

    fun addItems(items: List<E>) {
        mItemList.addAll(items)
        notifyDataSetChanged()
    }

    fun removeItems(item: E) {
        mItemList.remove(item)
    }

    fun removeItems() {
        mItemList.clear()
    }

    fun refreshItems(items: List<E>) {
        removeItems()
        addItems(items)
    }

    fun getItem(position: Int): E {
        return mItemList[position]
    }

    fun interface OnItemClickListener {
        fun itemClick(view: View?, pos: Int)
    }
}
