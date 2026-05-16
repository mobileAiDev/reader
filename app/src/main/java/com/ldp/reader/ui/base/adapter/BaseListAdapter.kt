package com.ldp.reader.ui.base.adapter

import android.os.Handler
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

abstract class BaseListAdapter<T> : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    protected val mList: MutableList<T> = ArrayList()
    protected var mClickListener: OnItemClickListener? = null
    protected var mLongClickListener: OnItemLongClickListener? = null

    val items: List<T>
        get() = Collections.unmodifiableList(mList)

    protected abstract fun createViewHolder(viewType: Int): IViewHolder<T>

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val viewHolder = createViewHolder(viewType)
        val view = viewHolder.createItemView(parent)
        return BaseViewHolder(view, viewHolder)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is BaseViewHolder<*>) {
            throw IllegalArgumentException("The ViewHolder item must extend BaseViewHolder")
        }

        @Suppress("UNCHECKED_CAST")
        val itemHolder = holder.holder as IViewHolder<T>
        itemHolder.onBind(getItem(position), position)

        holder.itemView.setOnClickListener { view ->
            mClickListener?.onItemClick(view, position)
            itemHolder.onClick()
            onItemClick(view, position)
        }
        holder.itemView.setOnLongClickListener { view ->
            var isClicked = false
            mLongClickListener?.let {
                isClicked = it.onItemLongClick(view, position)
            }
            onItemLongClick(view, position)
            isClicked
        }
    }

    override fun getItemCount(): Int {
        return mList.size
    }

    protected open fun onItemClick(v: View?, pos: Int) {
    }

    protected open fun onItemLongClick(v: View?, pos: Int) {
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        mClickListener = listener
    }

    fun setOnItemLongClickListener(listener: OnItemLongClickListener?) {
        mLongClickListener = listener
    }

    open fun addItem(value: T) {
        mList.add(value)
        notifyDataSetChanged()
    }

    open fun addItem(index: Int, value: T) {
        mList.add(index, value)
        notifyDataSetChanged()
    }

    open fun addItems(values: List<T>) {
        mList.addAll(values)
        Handler().post {
            notifyDataSetChanged()
        }
    }

    open fun removeItem(value: T) {
        mList.remove(value)
        notifyDataSetChanged()
    }

    open fun removeItems(value: List<T>) {
        mList.removeAll(value.toSet())
        notifyDataSetChanged()
    }

    fun getItem(position: Int): T {
        return mList[position]
    }

    fun getItemSize(): Int {
        return mList.size
    }

    open fun refreshItems(list: List<T>) {
        mList.clear()
        mList.addAll(list)
        notifyDataSetChanged()
    }

    fun clear() {
        mList.clear()
    }

    fun interface OnItemClickListener {
        fun onItemClick(view: View?, pos: Int)
    }

    fun interface OnItemLongClickListener {
        fun onItemLongClick(view: View?, pos: Int): Boolean
    }
}
