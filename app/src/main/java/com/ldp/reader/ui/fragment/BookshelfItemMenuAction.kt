package com.ldp.reader.ui.fragment

internal enum class BookshelfItemMenuAction {
    PIN_TO_TOP,
    DELETE,
    BATCH_MANAGE;

    companion object {
        fun fromPosition(position: Int): BookshelfItemMenuAction = entries[position]
    }
}
