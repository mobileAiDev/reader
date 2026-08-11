package com.ldp.reader.ui.activity

internal object ComicColdResumePolicy {
    fun canPublishFastPages(requestIsCurrent: Boolean, pageCount: Int): Boolean {
        return requestIsCurrent && pageCount > 0
    }

    fun displayRouteAfterCatalog(
        savedRouteId: String,
        resolvedRouteId: String,
        fastPagesPublished: Boolean
    ): String {
        return if (fastPagesPublished) savedRouteId else resolvedRouteId
    }
}
