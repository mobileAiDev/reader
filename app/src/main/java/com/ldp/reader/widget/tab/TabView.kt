package com.ldp.reader.widget.tab

interface TabView {
    fun setText(text: String?)

    fun setPadding(padding: Int)

    fun setNumber(text: String?, visibility: Int)

    fun notifyData(focus: Boolean)

    fun onScroll(factor: Float)
}
