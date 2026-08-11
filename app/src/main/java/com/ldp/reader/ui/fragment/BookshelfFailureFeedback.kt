package com.ldp.reader.ui.fragment

internal enum class BookshelfFailureContext {
    INITIAL_LOAD,
    UPDATE,
    SYNC
}

internal object BookshelfFailureFeedback {
    fun messageFor(context: BookshelfFailureContext): String {
        return when (context) {
            BookshelfFailureContext.INITIAL_LOAD,
            BookshelfFailureContext.UPDATE -> "书架更新失败，请稍后重试"

            BookshelfFailureContext.SYNC -> "同步失败，请检查网络后重试"
        }
    }
}
