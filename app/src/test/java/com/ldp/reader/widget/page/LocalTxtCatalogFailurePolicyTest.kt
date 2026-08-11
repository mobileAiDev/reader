package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTxtCatalogFailurePolicyTest {
    @Test
    fun deterministicLineLimitFailurePublishesItsUserMessageWithoutRetryingPreparation() {
        val error = LocalTxtParseException(
            message = "TXT line exceeds 1048576 bytes",
            failure = LocalTxtParseFailure.LINE_TOO_LONG
        )

        val decision = LocalTxtCatalogFailurePolicy.after(error)

        assertFalse(decision.retryPreparation)
        assertEquals("文件中有一段内容过长，暂时无法打开", decision.userMessage)
    }

    @Test
    fun sourceMutationCanRetryAndKeepsTheGenericCatalogFallback() {
        val error = LocalTxtParseException("TXT file changed while chapters were being parsed")

        val decision = LocalTxtCatalogFailurePolicy.after(error)

        assertTrue(decision.retryPreparation)
        assertNull(decision.userMessage)
    }
}
