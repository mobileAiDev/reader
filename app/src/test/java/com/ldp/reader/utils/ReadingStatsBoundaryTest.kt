package com.ldp.reader.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ReadingStatsBoundaryTest {
    @Test
    fun `reading across local midnight is credited to both local days`() {
        val storage = InMemoryReadingStatsStorage()
        val recorder = ReadingStatsRecorder(storage, SHANGHAI)
        val startMs = localTimeMs(SHANGHAI, 2026, Calendar.AUGUST, 6, 23, 59)
        val endMs = localTimeMs(SHANGHAI, 2026, Calendar.AUGUST, 7, 0, 1)

        recorder.recordReading("book-1", startMs, endMs)

        assertEquals(2 * MINUTE_MS, recorder.getTotalReadingMillis(endMs))
        assertEquals(MINUTE_MS, recorder.getTodayReadingMillis(startMs))
        assertEquals(MINUTE_MS, recorder.getTodayReadingMillis(endMs))
        assertEquals(2 * MINUTE_MS, recorder.getWeeklyReadingMillis(endMs))
    }

    @Test
    fun `Sunday to Monday reading is split between local ISO weeks`() {
        val recorder = ReadingStatsRecorder(InMemoryReadingStatsStorage(), SHANGHAI)
        val sundayMs = localTimeMs(SHANGHAI, 2026, Calendar.AUGUST, 9, 23, 59)
        val mondayMs = localTimeMs(SHANGHAI, 2026, Calendar.AUGUST, 10, 0, 1)

        recorder.recordReading("book-1", sundayMs, mondayMs)

        assertEquals(2 * MINUTE_MS, recorder.getTotalReadingMillis(mondayMs))
        assertEquals(MINUTE_MS, recorder.getWeeklyReadingMillis(sundayMs))
        assertEquals(MINUTE_MS, recorder.getWeeklyReadingMillis(mondayMs))
        assertEquals(MINUTE_MS, recorder.getTodayReadingMillis(sundayMs))
        assertEquals(MINUTE_MS, recorder.getTodayReadingMillis(mondayMs))
    }

    @Test
    fun `same ISO week remains one weekly bucket when the calendar year changes`() {
        val recorder = ReadingStatsRecorder(InMemoryReadingStatsStorage(), SHANGHAI)
        val previousYearMs = localTimeMs(SHANGHAI, 2024, Calendar.DECEMBER, 31, 23, 59)
        val nextYearMs = localTimeMs(SHANGHAI, 2025, Calendar.JANUARY, 1, 0, 1)

        recorder.recordReading("book-1", previousYearMs, nextYearMs)

        assertEquals(MINUTE_MS, recorder.getTodayReadingMillis(previousYearMs))
        assertEquals(MINUTE_MS, recorder.getTodayReadingMillis(nextYearMs))
        assertEquals(2 * MINUTE_MS, recorder.getWeeklyReadingMillis(previousYearMs))
        assertEquals(2 * MINUTE_MS, recorder.getWeeklyReadingMillis(nextYearMs))
    }

    @Test
    fun `overlapping pause and resume commits count every instant only once`() {
        val recorder = ReadingStatsRecorder(InMemoryReadingStatsStorage(), SHANGHAI)
        val tenOClockMs = localTimeMs(SHANGHAI, 2026, Calendar.AUGUST, 7, 10, 0)
        val tenTenMs = tenOClockMs + 10 * MINUTE_MS
        val overlappingResumeMs = tenTenMs - MINUTE_MS
        val tenTwentyMs = tenOClockMs + 20 * MINUTE_MS

        recorder.recordReading("book-1", tenOClockMs, tenTenMs)
        recorder.recordReading("book-1", overlappingResumeMs, tenTwentyMs)
        recorder.recordReading("book-1", overlappingResumeMs, tenTwentyMs)

        assertEquals(20 * MINUTE_MS, recorder.getTotalReadingMillis(tenTwentyMs))
        assertEquals(20 * MINUTE_MS, recorder.getTodayReadingMillis(tenTwentyMs))
        assertEquals(20 * MINUTE_MS, recorder.getWeeklyReadingMillis(tenTwentyMs))
    }

    @Test
    fun `the same instants follow the recorder local time zone`() {
        val startMs = localTimeMs(UTC, 2026, Calendar.AUGUST, 6, 15, 59)
        val endMs = startMs + 2 * MINUTE_MS
        val shanghaiRecorder = ReadingStatsRecorder(InMemoryReadingStatsStorage(), SHANGHAI)
        val utcRecorder = ReadingStatsRecorder(InMemoryReadingStatsStorage(), UTC)

        shanghaiRecorder.recordReading("book-1", startMs, endMs)
        utcRecorder.recordReading("book-1", startMs, endMs)

        assertEquals(MINUTE_MS, shanghaiRecorder.getTodayReadingMillis(startMs))
        assertEquals(MINUTE_MS, shanghaiRecorder.getTodayReadingMillis(endMs))
        assertEquals(2 * MINUTE_MS, utcRecorder.getTodayReadingMillis(startMs))
        assertEquals(2 * MINUTE_MS, utcRecorder.getTodayReadingMillis(endMs))
    }

    @Test
    fun `invalid timestamp ranges do not change stored totals`() {
        val recorder = ReadingStatsRecorder(InMemoryReadingStatsStorage(), SHANGHAI)
        val nowMs = localTimeMs(SHANGHAI, 2026, Calendar.AUGUST, 7, 12, 0)

        recorder.recordReading("book-1", -1L, nowMs)
        recorder.recordReading("book-1", nowMs, nowMs)
        recorder.recordReading("book-1", nowMs + MINUTE_MS, nowMs)
        recorder.recordReading("book-1", Long.MIN_VALUE, Long.MAX_VALUE)

        assertEquals(0L, recorder.getTotalReadingMillis(nowMs))
        assertEquals(0L, recorder.getTodayReadingMillis(nowMs))
        assertEquals(0L, recorder.getWeeklyReadingMillis(nowMs))
    }

    @Test
    fun `pathological positive timestamp span is rejected without iterating every day`() {
        val recorder = ReadingStatsRecorder(InMemoryReadingStatsStorage(), SHANGHAI)
        val nowMs = localTimeMs(SHANGHAI, 2026, Calendar.AUGUST, 7, 12, 0)

        recorder.recordReading("book-1", 0L, Long.MAX_VALUE)
        recorder.recordReading("book-1", nowMs, nowMs + 8L * 24L * HOUR_MS)

        assertEquals(0L, recorder.getTotalReadingMillis(nowMs))
        assertEquals(0L, recorder.getTodayReadingMillis(nowMs))
        assertEquals(0L, recorder.getWeeklyReadingMillis(nowMs))
    }

    @Test
    fun `DST spring forward and fall back days keep their real elapsed durations`() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        val recorder = ReadingStatsRecorder(InMemoryReadingStatsStorage(), newYork)
        val springStartMs = localTimeMs(newYork, 2026, Calendar.MARCH, 8, 0, 0)
        val springEndMs = localTimeMs(newYork, 2026, Calendar.MARCH, 9, 0, 0)
        val fallStartMs = localTimeMs(newYork, 2026, Calendar.NOVEMBER, 1, 0, 0)
        val fallEndMs = localTimeMs(newYork, 2026, Calendar.NOVEMBER, 2, 0, 0)

        recorder.recordReading("spring-book", springStartMs, springEndMs)
        recorder.recordReading("fall-book", fallStartMs, fallEndMs)

        assertEquals(23 * HOUR_MS, recorder.getTodayReadingMillis(springStartMs))
        assertEquals(25 * HOUR_MS, recorder.getTodayReadingMillis(fallStartMs))
        assertEquals(48 * HOUR_MS, recorder.getTotalReadingMillis(fallEndMs))
    }

    private class InMemoryReadingStatsStorage : ReadingStatsStorage {
        private val values = mutableMapOf<String, Long>()

        override fun getLong(key: String, defaultValue: Long): Long = values[key] ?: defaultValue

        override fun putLong(key: String, value: Long) {
            values[key] = value
        }
    }

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private val SHANGHAI: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        private fun localTimeMs(
            timeZone: TimeZone,
            year: Int,
            month: Int,
            day: Int,
            hour: Int,
            minute: Int,
        ): Long {
            return Calendar.getInstance(timeZone, Locale.CHINA).apply {
                clear()
                set(year, month, day, hour, minute, 0)
            }.timeInMillis
        }
    }
}
