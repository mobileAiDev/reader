package com.ldp.reader.audio

import kotlin.math.abs

object AudioPlaybackSpeed {
    private val supportedSpeeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.8f)

    fun normalize(speed: Float?): Float {
        val candidate = speed?.takeIf { it.isFinite() } ?: DEFAULT_SPEED
        return supportedSpeeds.firstOrNull { abs(it - candidate) < SPEED_EPSILON } ?: DEFAULT_SPEED
    }

    fun next(speed: Float?): Float {
        val current = normalize(speed)
        val index = supportedSpeeds.indexOf(current)
        return supportedSpeeds[(index + 1) % supportedSpeeds.size]
    }

    fun label(speed: Float?): String {
        return when (val normalized = normalize(speed)) {
            DEFAULT_SPEED -> "倍速"
            else -> "${normalized.format()}x"
        }
    }

    private fun Float.format(): String {
        return if (this % 1f == 0f) toInt().toString() else toString()
    }

    const val DEFAULT_SPEED = 1.0f
    private const val SPEED_EPSILON = 0.001f
}

data class AudioPlaybackCarryOver(
    val playbackSpeed: Float,
    val sleepTimerMinutes: Int,
    val sleepTimerEndAtMs: Long
)

object AudioPlaybackSessionPolicy {
    fun carryOver(
        previous: AudioNowPlaying?,
        nowMs: Long = System.currentTimeMillis()
    ): AudioPlaybackCarryOver {
        val timerActive = previous != null && AudioSleepTimer.isActive(
            previous.sleepTimerMinutes,
            previous.sleepTimerEndAtMs,
            nowMs
        )
        return AudioPlaybackCarryOver(
            playbackSpeed = AudioPlaybackSpeed.normalize(previous?.playbackSpeed),
            sleepTimerMinutes = if (timerActive) previous?.sleepTimerMinutes ?: 0 else 0,
            sleepTimerEndAtMs = if (timerActive) previous?.sleepTimerEndAtMs ?: 0L else 0L
        )
    }

    fun afterServiceDestroyed(
        current: AudioNowPlaying?,
        nowMs: Long = System.currentTimeMillis()
    ): AudioNowPlaying? {
        return current?.copy(
            isPlaying = false,
            sleepTimerMinutes = 0,
            sleepTimerEndAtMs = 0L,
            playbackSpeed = AudioPlaybackSpeed.normalize(current.playbackSpeed),
            updatedAtMs = nowMs
        )
    }

    fun afterColdProcessRestore(
        persisted: AudioNowPlaying?,
        nowMs: Long = System.currentTimeMillis()
    ): AudioNowPlaying? {
        // The player service lives in this app process. After a cold restore there
        // is no live player or Handler callback that can justify persisted transient state.
        return afterServiceDestroyed(persisted, nowMs)
    }
}
