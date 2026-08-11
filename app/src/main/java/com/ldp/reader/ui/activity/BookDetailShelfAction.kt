package com.ldp.reader.ui.activity

internal enum class BookDetailShelfLabel {
    ADD_TO_SHELF,
    ALREADY_IN_SHELF
}

internal enum class BookDetailShelfTapAction {
    ADD_TO_SHELF,
    KEEP_ON_SHELF
}

internal data class BookDetailShelfState(
    val label: BookDetailShelfLabel,
    val tapAction: BookDetailShelfTapAction
)

internal object BookDetailShelfAction {
    fun forPresence(isInShelf: Boolean): BookDetailShelfState {
        return if (isInShelf) {
            BookDetailShelfState(
                label = BookDetailShelfLabel.ALREADY_IN_SHELF,
                tapAction = BookDetailShelfTapAction.KEEP_ON_SHELF
            )
        } else {
            BookDetailShelfState(
                label = BookDetailShelfLabel.ADD_TO_SHELF,
                tapAction = BookDetailShelfTapAction.ADD_TO_SHELF
            )
        }
    }
}

internal object BookDetailShelfFailureCopy {
    fun localPersistence(): String = "加入书架失败，请稍后重试"
}
