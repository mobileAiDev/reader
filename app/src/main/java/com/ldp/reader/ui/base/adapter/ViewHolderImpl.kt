package com.ldp.reader.ui.base.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

abstract class ViewHolderImpl<T> : IViewHolder<T> {
    private var view: View? = null
    private var context: Context? = null

    protected abstract fun getItemLayoutId(): Int

    override fun createItemView(parent: ViewGroup): View {
        view = LayoutInflater.from(parent.context)
            .inflate(getItemLayoutId(), parent, false)
        context = parent.context
        return view!!
    }

    protected fun getContext(): Context = context!!

    protected fun getItemView(): View = view!!

    override fun onClick() {
    }
}
