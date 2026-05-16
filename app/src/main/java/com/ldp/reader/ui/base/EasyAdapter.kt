package com.ldp.reader.ui.base

import android.view.View
import android.view.ViewGroup
import com.ldp.reader.ui.base.adapter.IViewHolder
import java.util.Collections

abstract class EasyAdapter<T> : android.widget.BaseAdapter() {
    private val mList: MutableList<T> = ArrayList()

    val items: List<T>
        get() = Collections.unmodifiableList(mList)

    override fun getCount(): Int {
        return mList.size
    }

    override fun getItem(position: Int): T {
        return mList[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    fun addItem(value: T) {
        mList.add(value)
        notifyDataSetChanged()
    }

    fun addItem(index: Int, value: T) {
        mList.add(index, value)
        notifyDataSetChanged()
    }

    fun addItems(values: List<T>) {
        mList.addAll(values)
        notifyDataSetChanged()
    }

    fun removeItem(value: T) {
        mList.remove(value)
        notifyDataSetChanged()
    }

    fun getItemSize(): Int {
        return mList.size
    }

    fun refreshItems(list: List<T>) {
        mList.clear()
        mList.addAll(list)
        notifyDataSetChanged()
    }

    fun clear() {
        mList.clear()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View
        val holder: IViewHolder<T>
        if (convertView == null) {
            holder = onCreateViewHolder(getItemViewType(position))
            view = holder.createItemView(parent!!)
            view.tag = holder
            holder.initView()
        } else {
            view = convertView
            @Suppress("UNCHECKED_CAST")
            holder = convertView.tag as IViewHolder<T>
        }
        holder.onBind(getItem(position), position)
        return view
    }

    protected abstract fun onCreateViewHolder(viewType: Int): IViewHolder<T>
}
