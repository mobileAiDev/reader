package com.ldp.reader.ui.activity

internal sealed interface BookDetailLatestChapterUiState {
    data object Loading : BookDetailLatestChapterUiState

    data object Unavailable : BookDetailLatestChapterUiState

    data class Ready(val title: String) : BookDetailLatestChapterUiState
}

/**
 * Publishes a source-engine latest chapter only after the catalog has verified it.
 * Metadata can come from several routes and is useful for loading, but it is not
 * stable enough to replace a chapter title already shown to the reader.
 */
internal class BookDetailLatestChapterGate {
    private var activeRouteId: String? = null
    private var sourceRoute: Boolean = false
    private var state: BookDetailLatestChapterUiState = BookDetailLatestChapterUiState.Loading

    fun beginSession(routeId: String?, sourceRoute: Boolean): BookDetailLatestChapterUiState {
        activeRouteId = routeId
        this.sourceRoute = sourceRoute
        state = BookDetailLatestChapterUiState.Loading
        return state
    }

    fun acceptUnverifiedMetadata(
        routeId: String?,
        candidate: String?
    ): BookDetailLatestChapterUiState {
        if (routeId != activeRouteId || sourceRoute) return state
        val title = candidate?.trim().orEmpty()
        state = if (title.isNotEmpty()) {
            BookDetailLatestChapterUiState.Ready(title)
        } else {
            BookDetailLatestChapterUiState.Unavailable
        }
        return state
    }

    fun acceptVerifiedCatalog(
        routeId: String?,
        candidate: String?
    ): BookDetailLatestChapterUiState {
        if (routeId != activeRouteId) return state
        val title = candidate?.trim().orEmpty()
        state = if (title.isNotEmpty()) {
            BookDetailLatestChapterUiState.Ready(title)
        } else {
            BookDetailLatestChapterUiState.Unavailable
        }
        return state
    }
}
