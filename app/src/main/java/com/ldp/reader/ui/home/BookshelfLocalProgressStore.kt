package com.ldp.reader.ui.home

import com.ldp.reader.utils.SharedPreUtils

object BookshelfLocalProgressStore {
    private const val PREFIX = "bookshelf_local_progress_tenths_"
    private const val UNKNOWN_PROGRESS = -1

    @JvmStatic
    fun saveProgressTenths(bookId: String?, progressTenths: Int) {
        if (bookId == null || bookId.trim().isEmpty()) {
            return
        }
        val safeProgress = progressTenths.coerceAtLeast(0).coerceAtMost(999)
        SharedPreUtils.getInstance().putInt(PREFIX + bookId, safeProgress)
    }

    @JvmStatic
    fun getProgressTenths(bookId: String?): Int {
        if (bookId == null || bookId.trim().isEmpty()) {
            return UNKNOWN_PROGRESS
        }
        return SharedPreUtils.getInstance().getInt(PREFIX + bookId, UNKNOWN_PROGRESS)
    }

    @JvmStatic
    fun clear(bookId: String?) {
        if (bookId == null || bookId.trim().isEmpty()) {
            return
        }
        SharedPreUtils.getInstance().putInt(PREFIX + bookId, UNKNOWN_PROGRESS)
    }
}
