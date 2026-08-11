package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCatalogTerminalCopyTest {
    @Test
    fun `load failure offers retry while confirmed empty states that no chapters exist`() {
        assertEquals(
            "暂时无法加载目录，轻点屏幕左右两侧重试",
            ReaderCatalogTerminalCopy.messageFor(ReaderCatalogTerminalState.LOAD_FAILED)
        )
        assertEquals(
            "这本书暂时没有可阅读章节",
            ReaderCatalogTerminalCopy.messageFor(ReaderCatalogTerminalState.CONFIRMED_EMPTY)
        )
    }
}
