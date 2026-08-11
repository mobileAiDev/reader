package com.ldp.reader.source

enum class SourceContentTierPrepareResult {
    READY,
    EXHAUSTED,
    RETRY_LATER;

    val isReady: Boolean
        get() = this == READY

    val shouldRetry: Boolean
        get() = this == RETRY_LATER
}

enum class SourceContentTierMode {
    FULL,
    READING_LIGHT,
    CATALOG_FULL_SCAN;

    val usesLightweightTier: Boolean
        get() = this != FULL
}
