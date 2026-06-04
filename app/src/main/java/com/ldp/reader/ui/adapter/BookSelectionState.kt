package com.ldp.reader.ui.adapter

import com.ldp.reader.model.bean.CollBookBean

interface BookSelectionState {
    val isEditMode: Boolean
    fun isSelected(book: CollBookBean?): Boolean
}
