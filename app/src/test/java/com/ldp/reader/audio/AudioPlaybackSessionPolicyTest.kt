package com.ldp.reader.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioPlaybackSessionPolicyTest {
    @Test
    fun speedCyclesAndInvalidPersistedValuesFallBackToNormal() {
        assertEquals(1.25f, AudioPlaybackSpeed.next(1.0f))
        assertEquals(1.5f, AudioPlaybackSpeed.next(1.25f))
        assertEquals(2.0f, AudioPlaybackSpeed.next(1.5f))
        assertEquals(0.8f, AudioPlaybackSpeed.next(2.0f))
        assertEquals(1.0f, AudioPlaybackSpeed.next(0.8f))
        assertEquals(1.0f, AudioPlaybackSpeed.normalize(0f))
        assertEquals("倍速", AudioPlaybackSpeed.label(Float.NaN))
        assertEquals("1.25x", AudioPlaybackSpeed.label(1.25f))
    }

    @Test
    fun activeTimerAndSpeedCarryAcrossEpisodeChanges() {
        val previous = nowPlaying(
            isPlaying = true,
            speed = 1.5f,
            timerMinutes = 30,
            timerEndAtMs = 1_900_000L
        )

        val carryOver = AudioPlaybackSessionPolicy.carryOver(previous, nowMs = 100_000L)

        assertEquals(1.5f, carryOver.playbackSpeed)
        assertEquals(30, carryOver.sleepTimerMinutes)
        assertEquals(1_900_000L, carryOver.sleepTimerEndAtMs)
    }

    @Test
    fun expiredTimerDoesNotCarryIntoAnotherEpisode() {
        val previous = nowPlaying(
            isPlaying = true,
            speed = 1.25f,
            timerMinutes = 15,
            timerEndAtMs = 100_000L
        )

        val carryOver = AudioPlaybackSessionPolicy.carryOver(previous, nowMs = 100_000L)

        assertEquals(1.25f, carryOver.playbackSpeed)
        assertEquals(0, carryOver.sleepTimerMinutes)
        assertEquals(0L, carryOver.sleepTimerEndAtMs)
    }

    @Test
    fun serviceDestructionClearsOnlyTransientPlaybackState() {
        val current = nowPlaying(
            isPlaying = true,
            speed = 2.0f,
            timerMinutes = 60,
            timerEndAtMs = 3_700_000L
        ).copy(
            audioUrl = "https://audio.example/chapter-1.mp3",
            positionMs = 42_000L,
            durationMs = 180_000L,
            playbackReady = true,
            playbackEnded = false
        )

        val stopped = requireNotNull(AudioPlaybackSessionPolicy.afterServiceDestroyed(current, nowMs = 123L))

        assertFalse(stopped.isPlaying)
        assertEquals(0, stopped.sleepTimerMinutes)
        assertEquals(0L, stopped.sleepTimerEndAtMs)
        assertEquals(2.0f, stopped.playbackSpeed)
        assertEquals(42_000L, stopped.positionMs)
        assertEquals(180_000L, stopped.durationMs)
        assertEquals("https://audio.example/chapter-1.mp3", stopped.audioUrl)
        assertEquals(true, stopped.playbackReady)
        assertEquals(false, stopped.playbackEnded)
        assertEquals(123L, stopped.updatedAtMs)
    }

    @Test
    fun coldProcessRestoreNeverClaimsThatADeadServiceIsStillPlaying() {
        val persisted = nowPlaying(
            isPlaying = true,
            speed = 1.25f,
            timerMinutes = 15,
            timerEndAtMs = 1_000_000L
        ).copy(
            audioUrl = "https://audio.example/chapter-1.mp3",
            playbackReady = true,
            playbackEnded = true
        )

        val restored = requireNotNull(
            AudioPlaybackSessionPolicy.afterColdProcessRestore(persisted, nowMs = 200L)
        )

        assertFalse(restored.isPlaying)
        assertEquals(0, restored.sleepTimerMinutes)
        assertEquals(0L, restored.sleepTimerEndAtMs)
        assertEquals(1.25f, restored.playbackSpeed)
        assertEquals("https://audio.example/chapter-1.mp3", restored.audioUrl)
        assertEquals(true, restored.playbackReady)
        assertEquals(true, restored.playbackEnded)
    }

    private fun nowPlaying(
        isPlaying: Boolean,
        speed: Float,
        timerMinutes: Int,
        timerEndAtMs: Long
    ): AudioNowPlaying {
        return AudioNowPlaying(
            chapterRouteId = "chapter-1",
            title = "第1集",
            isPlaying = isPlaying,
            playbackSpeed = speed,
            sleepTimerMinutes = timerMinutes,
            sleepTimerEndAtMs = timerEndAtMs
        )
    }
}
