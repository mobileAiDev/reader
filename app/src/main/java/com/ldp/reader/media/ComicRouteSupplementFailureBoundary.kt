package com.ldp.reader.media

internal enum class ComicRouteSupplementFallbackReason(val traceValue: String) {
    DEADLINE("deadline"),
    RETRY_LATER("retry_later")
}

internal data class ComicRouteSupplementOutcome(
    val books: List<MediaSourceBook>,
    val fallbackReason: ComicRouteSupplementFallbackReason? = null
)

internal object ComicRouteSupplementFailureBoundary {
    fun loadOrFallback(
        initialCandidates: List<MediaSourceBook>,
        load: () -> List<MediaSourceBook>
    ): ComicRouteSupplementOutcome {
        return try {
            ComicRouteSupplementOutcome(books = load())
        } catch (error: MediaSearchRetryableException) {
            ComicRouteSupplementOutcome(
                books = initialCandidates,
                fallbackReason = if (error.timedOut) {
                    ComicRouteSupplementFallbackReason.DEADLINE
                } else {
                    ComicRouteSupplementFallbackReason.RETRY_LATER
                }
            )
        }
    }
}
