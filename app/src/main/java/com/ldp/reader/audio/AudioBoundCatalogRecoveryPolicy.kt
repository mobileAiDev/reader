package com.ldp.reader.audio

enum class AudioBoundCatalogRecoveryPolicy {
    KEEP_REGISTERED,
    LOAD_FULL_CATALOG,
    UNAVAILABLE;

    companion object {
        fun decide(bookRouteId: String, registeredEpisodeCount: Int): AudioBoundCatalogRecoveryPolicy {
            if (bookRouteId.isBlank()) return UNAVAILABLE
            return if (registeredEpisodeCount > 1) KEEP_REGISTERED else LOAD_FULL_CATALOG
        }
    }
}
