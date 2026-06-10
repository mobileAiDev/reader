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
