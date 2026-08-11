package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class BookDetailShelfActionTest {
    @Test
    fun `detail action adds an absent book and keeps an existing shelf book intact`() {
        assertEquals(
            BookDetailShelfState(
                label = BookDetailShelfLabel.ADD_TO_SHELF,
                tapAction = BookDetailShelfTapAction.ADD_TO_SHELF
            ),
            BookDetailShelfAction.forPresence(isInShelf = false)
        )

        assertEquals(
            BookDetailShelfState(
                label = BookDetailShelfLabel.ALREADY_IN_SHELF,
                tapAction = BookDetailShelfTapAction.KEEP_ON_SHELF
            ),
            BookDetailShelfAction.forPresence(isInShelf = true)
        )
    }
}
