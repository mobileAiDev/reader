package com.ldp.reader.ui.adapter

import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.ui.adapter.view.CollBookHolder
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.widget.adapter.WholeAdapter

/**
 * Created by ldp on 17-5-8.
 */
class CollBookAdapter : WholeAdapter<CollBookBean>(), BookSelectionState {
    override var isEditMode = false
        private set
    private val selectedBookKeys = HashSet<String>()

    override fun createViewHolder(viewType: Int): IViewHolder<CollBookBean> {
        return CollBookHolder(this)
    }

    fun setEditMode(editMode: Boolean) {
        isEditMode = editMode
        if (!editMode) {
            selectedBookKeys.clear()
        }
        notifyDataSetChanged()
    }

    fun toggleSelection(book: CollBookBean?) {
        val key = selectionKey(book)
        if (selectedBookKeys.contains(key)) {
            selectedBookKeys.remove(key)
        } else {
            selectedBookKeys.add(key)
        }
        notifyDataSetChanged()
    }

    override fun isSelected(book: CollBookBean?): Boolean {
        return selectedBookKeys.contains(selectionKey(book))
    }

    fun selectAllVisible() {
        for (book in mList) {
            selectedBookKeys.add(selectionKey(book))
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedBookKeys.clear()
        notifyDataSetChanged()
    }

    val isAllVisibleSelected: Boolean
        get() {
            if (mList.isEmpty()) {
                return false
            }
            for (book in mList) {
                if (!selectedBookKeys.contains(selectionKey(book))) {
                    return false
                }
            }
            return true
        }

    val selectedCount: Int
        get() = selectedBooks.size

    val selectedBooks: List<CollBookBean>
        get() {
            val selectedBooks = ArrayList<CollBookBean>()
            for (book in mList) {
                if (selectedBookKeys.contains(selectionKey(book))) {
                    selectedBooks.add(book)
                }
            }
            return selectedBooks
        }

    override fun removeItem(value: CollBookBean) {
        selectedBookKeys.remove(selectionKey(value))
        super.removeItem(value)
    }

    override fun removeItems(value: List<CollBookBean>) {
        for (book in value) {
            selectedBookKeys.remove(selectionKey(book))
        }
        super.removeItems(value)
    }

    companion object {
        @JvmStatic
        fun selectionKey(book: CollBookBean?): String {
            if (book == null) {
                return ""
            }
            val id = clean(book.get_id())
            if (book.isLocal()) {
                return "local:" + clean(book.cover) + ":" + id
            }
            return "online:$id"
        }

        private fun clean(value: String?): String {
            return value?.trim() ?: ""
        }
    }
}
