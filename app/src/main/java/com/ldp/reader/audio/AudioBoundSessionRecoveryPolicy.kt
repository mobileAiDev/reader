package com.ldp.reader.audio

object AudioBoundSessionRecoveryPolicy {
    enum class Action {
        KEEP_LIVE,
        RESTORE_PAUSED,
        SHOW_ENDED,
        RESOLVE_FRESH
    }

    @JvmStatic
    fun decide(
        hasLiveMediaItem: Boolean,
        hasLivePlayerError: Boolean,
        requestedRouteId: String?,
        persistedRouteId: String?,
        persistedUrl: String?,
        persistedReady: Boolean,
        persistedEnded: Boolean
    ): Action {
        val sameRoute = !requestedRouteId.isNullOrEmpty() && requestedRouteId == persistedRouteId
        if (hasLiveMediaItem) {
            return if (hasLivePlayerError || (sameRoute && !persistedReady)) {
                Action.RESOLVE_FRESH
            } else {
                Action.KEEP_LIVE
            }
        }
        val hasRestorableUrl = !persistedUrl.isNullOrEmpty()
        if (!sameRoute || !persistedReady || !hasRestorableUrl) return Action.RESOLVE_FRESH
        return if (persistedEnded) Action.SHOW_ENDED else Action.RESTORE_PAUSED
    }
}
