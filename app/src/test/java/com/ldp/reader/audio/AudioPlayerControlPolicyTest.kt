package com.ldp.reader.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayerControlPolicyTest {
    @Test
    fun completedPlaybackShowsPlayAndRestartsFromBeginning() {
        val decision = AudioPlayerControlPolicy.resolve(
            playbackEnded = true,
            isPlaying = false,
            playWhenReady = true
        )

        assertEquals(AudioPlayerPrimaryAction.REPLAY_FROM_START, decision.action)
        assertFalse(decision.showsPause)
        assertEquals("播放", decision.contentDescription)
    }

    @Test
    fun pausedPlaybackKeepsPlayAction() {
        val decision = AudioPlayerControlPolicy.resolve(
            playbackEnded = false,
            isPlaying = false,
            playWhenReady = false
        )

        assertEquals(AudioPlayerPrimaryAction.PLAY, decision.action)
        assertFalse(decision.showsPause)
        assertEquals("播放", decision.contentDescription)
    }

    @Test
    fun bufferingPlaybackWithPendingPlayKeepsPauseAction() {
        val decision = AudioPlayerControlPolicy.resolve(
            playbackEnded = false,
            isPlaying = false,
            playWhenReady = true
        )

        assertEquals(AudioPlayerPrimaryAction.PAUSE, decision.action)
        assertTrue(decision.showsPause)
        assertEquals("暂停", decision.contentDescription)
    }
}
