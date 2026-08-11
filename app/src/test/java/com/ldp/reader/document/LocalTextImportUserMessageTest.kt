package com.ldp.reader.document

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTextImportUserMessageTest {
    @Test
    fun emptyTextExplainsThatThereIsNoReadableContent() {
        assertEquals(
            "文件中没有可阅读内容",
            LocalTextImportUserMessage.forResult(LocalTextImportResult.Empty)
        )
    }

    @Test
    fun pathologicalSingleLineExplainsWhyTheTextCannotBeOpened() {
        assertEquals(
            "文件中有一段内容过长，暂时无法打开",
            LocalTextImportUserMessage.forResult(LocalTextImportResult.LineTooLong)
        )
    }
}
