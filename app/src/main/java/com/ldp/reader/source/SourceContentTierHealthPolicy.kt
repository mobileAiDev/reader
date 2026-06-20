package com.ldp.reader.source

internal object SourceContentTierHealthPolicy {
    private const val ABSOLUTE_FAILED_ROUTE_THRESHOLD = 2

    data class Decision(
        val tierSize: Int,
        val failedRoutes: Int,
        val threshold: Int,
        val cullFailedRoutes: Boolean
    )

    fun evaluate(tierSize: Int, failedRoutes: Int): Decision {
        val safeTierSize = tierSize.coerceAtLeast(0)
        val safeFailedRoutes = failedRoutes.coerceAtLeast(0)
        val threshold = failureThreshold(safeTierSize)
        return Decision(
            tierSize = safeTierSize,
            failedRoutes = safeFailedRoutes,
            threshold = threshold,
            cullFailedRoutes = safeFailedRoutes > 0 && safeFailedRoutes >= threshold
        )
    }

    fun failureThreshold(tierSize: Int): Int {
        if (tierSize <= 0) return Int.MAX_VALUE
        val ratioThreshold = (tierSize + 2) / 3
        return minOf(ABSOLUTE_FAILED_ROUTE_THRESHOLD, ratioThreshold.coerceAtLeast(1))
    }
}
