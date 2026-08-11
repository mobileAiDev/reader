package com.ldp.reader.ui.fragment

import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfItemMenuActionTest {
    @Test
    fun `menu positions map to implemented actions`() {
        assertEquals(
            BookshelfItemMenuAction.PIN_TO_TOP,
            BookshelfItemMenuAction.fromPosition(0)
        )
        assertEquals(
            BookshelfItemMenuAction.DELETE,
            BookshelfItemMenuAction.fromPosition(1)
        )
        assertEquals(
            BookshelfItemMenuAction.BATCH_MANAGE,
            BookshelfItemMenuAction.fromPosition(2)
        )
    }

}
