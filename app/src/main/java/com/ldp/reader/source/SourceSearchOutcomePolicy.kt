package com.ldp.reader.source

import com.ldp.reader.sourceengine.model.SourceSearchReport

internal class SourceSearchRetryableException(
    val selectedSourceCount: Int,
    val startedRequestCount: Int,
    val completedRequestCount: Int
) : RuntimeException(
    "No novel source completed successfully " +
        "(selected=$selectedSourceCount, started=$startedRequestCount, completed=$completedRequestCount)"
)

internal object SourceSearchOutcomePolicy {
    fun requestSucceeded(report: SourceSearchReport): Boolean {
        return report.books.isNotEmpty() || report.attempts.any { attempt -> attempt.success }
    }

    fun requireSuccessfulSourceOrTrueEmpty(
        selectedSourceCount: Int,
        startedRequestCount: Int,
        completedRequestCount: Int,
        successfulRequestCount: Int
    ) {
        if (selectedSourceCount <= 0 || successfulRequestCount > 0) return
        throw SourceSearchRetryableException(
            selectedSourceCount = selectedSourceCount,
            startedRequestCount = startedRequestCount,
            completedRequestCount = completedRequestCount
        )
    }
}
