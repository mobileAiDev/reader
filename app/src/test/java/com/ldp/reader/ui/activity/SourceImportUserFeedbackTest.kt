package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceImportUserFeedbackTest {
    @Test
    fun networkFailureDoesNotExposeTransportDetails() {
        assertEquals(
            "导入失败，请检查地址或网络后重试",
            SourceImportUserFeedback.failureMessage(SourceImportInput.NETWORK)
        )
    }

    @Test
    fun contentFailureAndEmptyParseUseANonTechnicalMessage() {
        assertEquals(
            "没有识别到可用书源",
            SourceImportUserFeedback.failureMessage(SourceImportInput.CONTENT)
        )
        assertEquals(
            "没有识别到可用书源",
            SourceImportUserFeedback.noUsableSourcesMessage()
        )
    }
}
