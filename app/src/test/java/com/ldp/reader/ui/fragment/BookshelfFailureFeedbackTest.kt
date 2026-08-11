package com.ldp.reader.ui.fragment

import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfFailureFeedbackTest {
    @Test
    fun initialLoadAndRefreshUseAReaderFacingUpdateMessage() {
        assertEquals(
            "书架更新失败，请稍后重试",
            BookshelfFailureFeedback.messageFor(BookshelfFailureContext.INITIAL_LOAD)
        )
        assertEquals(
            "书架更新失败，请稍后重试",
            BookshelfFailureFeedback.messageFor(BookshelfFailureContext.UPDATE)
        )
    }

    @Test
    fun accountSyncUsesANetworkActionableMessage() {
        assertEquals(
            "同步失败，请检查网络后重试",
            BookshelfFailureFeedback.messageFor(BookshelfFailureContext.SYNC)
        )
    }
}
