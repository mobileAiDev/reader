package com.ldp.reader.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlayerUiReadinessPolicyTest {
    @Test
    fun `slow resolution disables controls until a real session exists`() {
        assertEquals(
            AudioPlayerUiReadiness(primaryEnabled = false, sessionControlsEnabled = false),
            AudioPlayerUiReadinessPolicy.decide(
                controllerConnected = false,
                hasLiveMediaItem = false,
                recoveryActionAvailable = false,
                recoveryDispatchPending = false,
                retryAvailable = false
            )
        )
    }

    @Test
    fun `connected live media enables playback speed and timer controls`() {
        assertEquals(
            AudioPlayerUiReadiness(primaryEnabled = true, sessionControlsEnabled = true),
            AudioPlayerUiReadinessPolicy.decide(
                controllerConnected = true,
                hasLiveMediaItem = true,
                recoveryActionAvailable = false,
                recoveryDispatchPending = false,
                retryAvailable = false
            )
        )
    }

    @Test
    fun `ended or fresh recovery enables only the primary action`() {
        assertEquals(
            AudioPlayerUiReadiness(primaryEnabled = true, sessionControlsEnabled = false),
            AudioPlayerUiReadinessPolicy.decide(
                controllerConnected = true,
                hasLiveMediaItem = false,
                recoveryActionAvailable = true,
                recoveryDispatchPending = false,
                retryAvailable = false
            )
        )
    }

    @Test
    fun `automatic recovery dispatch cannot be double tapped`() {
        assertEquals(
            AudioPlayerUiReadiness(primaryEnabled = false, sessionControlsEnabled = false),
            AudioPlayerUiReadinessPolicy.decide(
                controllerConnected = true,
                hasLiveMediaItem = false,
                recoveryActionAvailable = true,
                recoveryDispatchPending = true,
                retryAvailable = false
            )
        )
    }

    @Test
    fun `failed resolution exposes primary retry without phantom session controls`() {
        assertEquals(
            AudioPlayerUiReadiness(primaryEnabled = true, sessionControlsEnabled = false),
            AudioPlayerUiReadinessPolicy.decide(
                controllerConnected = false,
                hasLiveMediaItem = false,
                recoveryActionAvailable = false,
                recoveryDispatchPending = false,
                retryAvailable = true
            )
        )
    }
}
