package com.ldp.reader.audio

object AudioSleepTimer {
    private val supportedMinutes = listOf(0, 15, 30, 60)

    fun isSupported(minutes: Int): Boolean {
        return supportedMinutes.contains(minutes)
    }

    fun nextMinutes(currentMinutes: Int): Int {
        val index = supportedMinutes.indexOf(currentMinutes)
        if (index < 0) return 0
        return supportedMinutes[(index + 1) % supportedMinutes.size]
    }

    fun label(minutes: Int): String {
        return if (minutes <= 0) "定时" else "${minutes}分"
    }

    fun deadlineMs(minutes: Int, nowMs: Long = System.currentTimeMillis()): Long {
        if (minutes <= 0) return 0L
        return nowMs + minutes * 60_000L
    }

    fun isActive(minutes: Int, deadlineMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        return minutes > 0 && deadlineMs > nowMs
    }

    fun delayMs(minutes: Int, deadlineMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
        if (!isActive(minutes, deadlineMs, nowMs)) return 0L
        return deadlineMs - nowMs
    }
}
