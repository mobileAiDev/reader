package com.ldp.reader.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSleepTimerTest {
    @Test
    fun nextOptionCyclesThroughSupportedSleepTimers() {
        assertEquals(15, AudioSleepTimer.nextMinutes(0))
        assertEquals(30, AudioSleepTimer.nextMinutes(15))
        assertEquals(60, AudioSleepTimer.nextMinutes(30))
        assertEquals(0, AudioSleepTimer.nextMinutes(60))
    }

    @Test
    fun labelUsesPlayerCopyForOffAndMinuteTimers() {
        assertEquals("定时", AudioSleepTimer.label(0))
        assertEquals("15分", AudioSleepTimer.label(15))
        assertEquals("30分", AudioSleepTimer.label(30))
        assertEquals("60分", AudioSleepTimer.label(60))
    }

    @Test
    fun activeTimerExpiresAtDeadline() {
        val now = 10_000L
        val deadline = AudioSleepTimer.deadlineMs(15, now)

        assertTrue(AudioSleepTimer.isActive(15, deadline, now + 1_000L))
        assertFalse(AudioSleepTimer.isActive(15, deadline, deadline))
        assertEquals(899_000L, AudioSleepTimer.delayMs(15, deadline, now + 1_000L))
    }
}
