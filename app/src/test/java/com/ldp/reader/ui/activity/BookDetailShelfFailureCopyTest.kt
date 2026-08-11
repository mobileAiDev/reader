package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class BookDetailShelfFailureCopyTest {
    @Test
    fun `local persistence failure asks for a retry instead of blaming the network`() {
        assertEquals(
            "加入书架失败，请稍后重试",
            BookDetailShelfFailureCopy.localPersistence()
        )
    }
}
