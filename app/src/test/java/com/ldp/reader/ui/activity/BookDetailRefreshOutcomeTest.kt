package com.ldp.reader.ui.activity

import com.ldp.reader.model.bean.BookDetailBeanInOwn
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BookDetailRefreshOutcomeTest {
    @Test
    fun failedRemoteRefreshRetainsPublishedPreliminaryContentAndOffersRetry() = runBlocking {
        val failure = IOException("network unavailable")

        val outcome = loadBookDetailWithPreliminaryFallback(preliminaryPublished = true) {
            throw failure
        }

        val failed = outcome as BookDetailRefreshOutcome.Failed
        assertSame(failure, failed.error)
        assertTrue(failed.state.keepsContent)
        assertTrue(failed.state.retryable)
        assertEquals(BookDetailRefreshPhase.PARTIAL_FAILURE, failed.state.phase)
    }

    @Test
    fun failedInitialRefreshUsesFullErrorState() = runBlocking {
        val outcome = loadBookDetailWithPreliminaryFallback(preliminaryPublished = false) {
            throw IOException("network unavailable")
        }

        val failed = outcome as BookDetailRefreshOutcome.Failed
        assertFalse(failed.state.keepsContent)
        assertTrue(failed.state.retryable)
        assertEquals(BookDetailRefreshPhase.FAILURE, failed.state.phase)
    }

    @Test
    fun cancellationIsRethrownInsteadOfBecomingFailure() = runBlocking {
        val cancellation = CancellationException("screen left")

        try {
            loadBookDetailWithPreliminaryFallback(preliminaryPublished = true) {
                throw cancellation
            }
            fail("CancellationException must be rethrown")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun successfulRemoteRefreshPublishesReadyState() = runBlocking {
        val detail = BookDetailBeanInOwn().apply { title = "测试书籍" }

        val outcome = loadBookDetailWithPreliminaryFallback(preliminaryPublished = true) {
            detail
        }

        val ready = outcome as BookDetailRefreshOutcome.Ready
        assertSame(detail, ready.detail)
        assertEquals(BookDetailRefreshPhase.READY, ready.state.phase)
        assertTrue(ready.state.keepsContent)
        assertFalse(ready.state.retryable)
    }
}
