package com.ldp.reader.audio

data class AudioPlayerUiReadiness(
    val primaryEnabled: Boolean,
    val sessionControlsEnabled: Boolean
)

object AudioPlayerUiReadinessPolicy {
    fun decide(
        controllerConnected: Boolean,
        hasLiveMediaItem: Boolean,
        recoveryActionAvailable: Boolean,
        recoveryDispatchPending: Boolean,
        retryAvailable: Boolean
    ): AudioPlayerUiReadiness {
        val liveSession = controllerConnected && hasLiveMediaItem
        val recoveryReady = controllerConnected && recoveryActionAvailable && !recoveryDispatchPending
        return AudioPlayerUiReadiness(
            primaryEnabled = retryAvailable || liveSession || recoveryReady,
            sessionControlsEnabled = liveSession
        )
    }
}
