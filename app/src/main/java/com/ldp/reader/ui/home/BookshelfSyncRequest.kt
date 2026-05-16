package com.ldp.reader.ui.home

import android.content.Intent

object BookshelfSyncRequest {
    private const val EXTRA_REQUEST_BOOKSHELF_SYNC =
        "com.ldp.reader.extra.REQUEST_BOOKSHELF_SYNC"

    fun resultIntent(): Intent {
        return Intent().putExtra(EXTRA_REQUEST_BOOKSHELF_SYNC, true)
    }

    fun isRequested(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(EXTRA_REQUEST_BOOKSHELF_SYNC, false) == true
    }
}
