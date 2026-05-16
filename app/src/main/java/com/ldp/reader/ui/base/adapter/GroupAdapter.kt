package com.ldp.reader.ui.base.adapter

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

abstract class GroupAdapter<T, R>(recyclerView: RecyclerView, spanSize: Int) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var mGroupListener: OnGroupClickListener? = null
    private var mChildClickListener: OnChildClickListener? = null

    abstract fun getGroupCount(): Int
    abstract fun getChildCount(groupPos: Int): Int

    abstract fun getGroupItem(groupPos: Int): T
    abstract fun getChildItem(groupPos: Int, childPos: Int): R

    protected abstract fun createGroupViewHolder(): IViewHolder<T>
    protected abstract fun createChildViewHolder(): IViewHolder<R>

    init {
        val manager = GridLayoutManager(recyclerView.context, spanSize)
        manager.spanSizeLookup = GroupSpanSizeLookup(spanSize)
        recyclerView.layoutManager = manager
    }

    fun setOnGroupItemListener(listener: OnGroupClickListener?) {
        mGroupListener = listener
    }

    fun setOnChildItemListener(listener: OnChildClickListener?) {
        mChildClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val itemHolder: IViewHolder<*>
        val view: View
        if (viewType == TYPE_GROUP) {
            itemHolder = createGroupViewHolder()
            view = itemHolder.createItemView(parent)
        } else {
            itemHolder = createChildViewHolder()
            view = itemHolder.createItemView(parent)
        }
        return BaseViewHolder(view, itemHolder)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is BaseViewHolder<*>) {
            throw IllegalArgumentException("The ViewHolder item must extend BaseViewHolder")
        }

        val itemHolder = holder.holder
        val type = getItemViewType(position)
        if (type == TYPE_GROUP) {
            val groupPos = calculateGroup(position)
            holder.itemView.setOnClickListener { view ->
                itemHolder.onClick()
                mGroupListener?.onGroupClick(view, groupPos)
            }
            @Suppress("UNCHECKED_CAST")
            (itemHolder as IViewHolder<T>).onBind(getGroupItem(groupPos), groupPos)
        } else {
            val groupPos = calculateGroup(position)
            val childPos = calculateChild(position)
            holder.itemView.setOnClickListener { view ->
                itemHolder.onClick()
                mChildClickListener?.onChildClick(view, groupPos, childPos)
            }
            @Suppress("UNCHECKED_CAST")
            (itemHolder as IViewHolder<R>).onBind(getChildItem(groupPos, childPos), childPos)
        }
    }

    private fun calculateGroup(position: Int): Int {
        var total = 0
        for (i in 0 until getGroupCount()) {
            total += getChildCount(i) + 1
            if (total > position) {
                return i
            }
        }
        return -1
    }

    protected fun calculateChild(position: Int): Int {
        var currentPosition = position
        for (i in 0 until getGroupCount()) {
            val total = getChildCount(i) + 1
            val loc = currentPosition - total
            if (loc < 0) {
                return currentPosition - 1
            }
            currentPosition = loc
        }
        return -1
    }

    override fun getItemCount(): Int {
        val groupCount = getGroupCount()
        var totalCount = groupCount
        for (i in 0 until groupCount) {
            totalCount += getChildCount(i)
        }
        return totalCount
    }

    override fun getItemViewType(position: Int): Int {
        var currentPosition = position
        if (currentPosition == 0) {
            return TYPE_GROUP
        }

        for (i in 0 until getGroupCount()) {
            val total = getChildCount(i) + 1
            if (currentPosition == 0) {
                return TYPE_GROUP
            } else if (currentPosition < 0) {
                return TYPE_CHILD
            }
            currentPosition -= total
        }
        return TYPE_CHILD
    }

    inner class GroupSpanSizeLookup(private val maxSize: Int) : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            return if (getItemViewType(position) == TYPE_GROUP) maxSize else 1
        }
    }

    fun getGroupToPosition(groupPos: Int): Int {
        var position = 0
        for (i in 0 until groupPos) {
            position += getChildCount(groupPos) + 1
        }
        return position
    }

    fun getChildToPosition(groupPos: Int, childPos: Int): Int {
        var position = 0
        for (i in 0 until groupPos) {
            position += getChildCount(i) + 1
        }
        position += childPos + 1
        return position
    }

    fun interface OnGroupClickListener {
        fun onGroupClick(view: View?, pos: Int)
    }

    fun interface OnChildClickListener {
        fun onChildClick(view: View?, groupPos: Int, childPos: Int)
    }

    companion object {
        private const val TYPE_GROUP = 1
        private const val TYPE_CHILD = 2
    }
}
