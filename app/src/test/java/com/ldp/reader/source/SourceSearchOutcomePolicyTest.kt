package com.ldp.reader.source

import com.ldp.reader.sourceengine.model.SourceSearchAttempt
import com.ldp.reader.sourceengine.model.SourceSearchReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceSearchOutcomePolicyTest {
    @Test
    fun failedAttemptInsideSuccessfulEngineEnvelopeIsStillAFailedRequest() {
        val report = SourceSearchReport(
            books = emptyList(),
            attempts = listOf(SourceSearchAttempt("offline", false, 0, "timeout"))
        )

        assertFalse(SourceSearchOutcomePolicy.requestSucceeded(report))
    }

    @Test
    fun successfulEmptyAttemptIsATrueEmptyRequest() {
        val report = SourceSearchReport(
            books = emptyList(),
            attempts = listOf(SourceSearchAttempt("healthy", true, 0, "request-url"))
        )

        assertTrue(SourceSearchOutcomePolicy.requestSucceeded(report))
        SourceSearchOutcomePolicy.requireSuccessfulSourceOrTrueEmpty(
            selectedSourceCount = 1,
            startedRequestCount = 1,
            completedRequestCount = 1,
            successfulRequestCount = 1
        )
    }

    @Test
    fun allSelectedSourcesFailedIsRetryableInsteadOfEmpty() {
        try {
            SourceSearchOutcomePolicy.requireSuccessfulSourceOrTrueEmpty(
                selectedSourceCount = 12,
                startedRequestCount = 12,
                completedRequestCount = 12,
                successfulRequestCount = 0
            )
            fail("Expected SourceSearchRetryableException")
        } catch (expected: SourceSearchRetryableException) {
            assertTrue(expected.selectedSourceCount == 12)
        }
    }

    @Test
    fun noConfiguredSourceRemainsARealEmptyResult() {
        SourceSearchOutcomePolicy.requireSuccessfulSourceOrTrueEmpty(
            selectedSourceCount = 0,
            startedRequestCount = 0,
            completedRequestCount = 0,
            successfulRequestCount = 0
        )
    }
}
