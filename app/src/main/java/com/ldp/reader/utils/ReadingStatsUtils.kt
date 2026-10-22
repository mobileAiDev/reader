package com.ldp.reader.utils

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60L * MINUTE_MS
private const val MAX_READING_SESSION_MS = 7L * 24L * HOUR_MS
private const val KEY_TOTAL = "reading_total"
private const val KEY_DAY_PREFIX = "reading_day_"
private const val KEY_WEEK_PREFIX = "reading_week_"
private const val KEY_BOOK_PREFIX = "reading_book_"
private const val KEY_LAST_END_PREFIX = "reading_last_end_"

internal interface ReadingStatsStorage {
    fun getLong(key: String, defaultValue: Long): Long

    fun putLong(key: String, value: Long)
}

private class SharedPreReadingStatsStorage(
    private val prefs: SharedPreUtils = SharedPreUtils.getInstance(),
) : ReadingStatsStorage {
    override fun getLong(key: String, defaultValue: Long): Long {
        return prefs.getLong(key, defaultValue)
    }

    override fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
    }
}

internal class ReadingStatsRecorder(
    private val storage: ReadingStatsStorage,
    timeZone: TimeZone,
) {
    private val timeZone = timeZone.clone() as TimeZone

    fun recordReading(bookId: String?, startMs: Long, endMs: Long) {
        if (
            startMs < 0L ||
            endMs <= startMs ||
            endMs - startMs > MAX_READING_SESSION_MS
        ) {
            return
        }
        val sessionKey = KEY_LAST_END_PREFIX + Integer.toHexString(bookId.orEmpty().hashCode())
        val lastRecordedEndMs = storage.getLong(sessionKey, Long.MIN_VALUE)
        val effectiveStartMs = maxOf(startMs, lastRecordedEndMs)
        if (endMs <= effectiveStartMs) {
            return
        }

        val durationMs = endMs - effectiveStartMs
        addDuration(KEY_TOTAL, durationMs)
        if (!bookId.isNullOrEmpty()) {
            val bookKey = KEY_BOOK_PREFIX + Integer.toHexString(bookId.hashCode())
            addDuration(bookKey, durationMs)
        }
        forEachLocalDaySegment(effectiveStartMs, endMs) { calendar, segmentDurationMs ->
            addDuration(KEY_DAY_PREFIX + dayKey(calendar), segmentDurationMs)
            addDuration(KEY_WEEK_PREFIX + weekKey(calendar), segmentDurationMs)
        }
        storage.putLong(sessionKey, endMs)
    }

    fun getTotalReadingMillis(nowMs: Long): Long {
        val todayMs = getStoredTodayReadingMillis(nowMs)
        val weeklyMs = getStoredWeeklyReadingMillis(nowMs)
        return ReadingStatsUtils.normalizedTotalReadingMillis(
            storage.getLong(KEY_TOTAL, 0L),
            todayMs,
            weeklyMs,
        )
    }

    fun getTodayReadingMillis(nowMs: Long): Long {
        val todayMs = getStoredTodayReadingMillis(nowMs)
        val weeklyMs = getStoredWeeklyReadingMillis(nowMs)
        val totalMs = ReadingStatsUtils.normalizedTotalReadingMillis(
            storage.getLong(KEY_TOTAL, 0L),
            todayMs,
            weeklyMs,
        )
        val normalizedWeeklyMs = ReadingStatsUtils.normalizedWeeklyReadingMillis(
            totalMs,
            todayMs,
            weeklyMs,
        )
        return ReadingStatsUtils.normalizedTodayReadingMillis(totalMs, normalizedWeeklyMs, todayMs)
    }

    fun getWeeklyReadingMillis(nowMs: Long): Long {
        val todayMs = getStoredTodayReadingMillis(nowMs)
        val weeklyMs = getStoredWeeklyReadingMillis(nowMs)
        val totalMs = ReadingStatsUtils.normalizedTotalReadingMillis(
            storage.getLong(KEY_TOTAL, 0L),
            todayMs,
            weeklyMs,
        )
        return ReadingStatsUtils.normalizedWeeklyReadingMillis(totalMs, todayMs, weeklyMs)
    }

    /** Reading time on each of the last [days] local days, oldest first, ending today. */
    fun getRecentDailyReadingMillis(nowMs: Long, days: Int): List<Long> {
        val calendar = calendarAt(nowMs)
        calendar.add(Calendar.DAY_OF_MONTH, 1 - days)
        return List(days) {
            val dayMs = storage.getLong(KEY_DAY_PREFIX + dayKey(calendar), 0L)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            dayMs
        }
    }

    private fun getStoredTodayReadingMillis(nowMs: Long): Long {
        return storage.getLong(KEY_DAY_PREFIX + dayKey(calendarAt(nowMs)), 0L)
    }

    private fun getStoredWeeklyReadingMillis(nowMs: Long): Long {
        return storage.getLong(KEY_WEEK_PREFIX + weekKey(calendarAt(nowMs)), 0L)
    }

    private fun addDuration(key: String, durationMs: Long) {
        val currentMs = maxOf(0L, storage.getLong(key, 0L))
        val updatedMs = if (Long.MAX_VALUE - currentMs < durationMs) {
            Long.MAX_VALUE
        } else {
            currentMs + durationMs
        }
        storage.putLong(key, updatedMs)
    }

    private inline fun forEachLocalDaySegment(
        startMs: Long,
        endMs: Long,
        consume: (Calendar, Long) -> Unit,
    ) {
        var cursorMs = startMs
        while (cursorMs < endMs) {
            val calendar = calendarAt(cursorMs)
            val nextDayStartMs = (calendar.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            val segmentEndMs = minOf(endMs, nextDayStartMs)
            if (segmentEndMs <= cursorMs) {
                return
            }
            consume(calendar, segmentEndMs - cursorMs)
            cursorMs = segmentEndMs
        }
    }

    private fun calendarAt(nowMs: Long): Calendar {
        return Calendar.getInstance(timeZone, Locale.CHINA).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = nowMs
        }
    }

    private fun dayKey(calendar: Calendar): String {
        return calendar[Calendar.YEAR].toString() + "_" + calendar[Calendar.DAY_OF_YEAR]
    }

    private fun weekKey(calendar: Calendar): String {
        return calendar.weekYear.toString() + "_" + calendar[Calendar.WEEK_OF_YEAR]
    }
}

object ReadingStatsUtils {
    @Synchronized
    @JvmStatic
    fun recordReading(bookId: String?, startMs: Long, endMs: Long) {
        createRecorder().recordReading(bookId, startMs, endMs)
    }

    @JvmStatic
    fun getWeeklyReadingLabel(): String {
        return formatWeeklyReadingLabel(getWeeklyReadingMillis(System.currentTimeMillis()))
    }

    @JvmStatic
    fun getMineReadingLabel(): String {
        val nowMs = System.currentTimeMillis()
        return formatMineReadingLabel(getTotalReadingMillis(), getTodayReadingMillis(nowMs))
    }

    @JvmStatic
    fun getTotalReadingMillis(): Long {
        val nowMs = System.currentTimeMillis()
        return createRecorder().getTotalReadingMillis(nowMs)
    }

    @JvmStatic
    fun getTodayReadingMillis(nowMs: Long): Long {
        return createRecorder().getTodayReadingMillis(nowMs)
    }

    @JvmStatic
    fun getWeeklyReadingMillis(nowMs: Long): Long {
        return createRecorder().getWeeklyReadingMillis(nowMs)
    }

    @JvmStatic
    fun getRecentDailyReadingMillis(nowMs: Long, days: Int): List<Long> {
        return createRecorder().getRecentDailyReadingMillis(nowMs, days)
    }

    @JvmStatic
    fun normalizedTotalReadingMillis(totalMs: Long, todayMs: Long, weeklyMs: Long): Long {
        return Math.max(0L, Math.max(totalMs, Math.max(todayMs, weeklyMs)))
    }

    @JvmStatic
    fun normalizedWeeklyReadingMillis(totalMs: Long, todayMs: Long, weeklyMs: Long): Long {
        val normalizedWeek = Math.max(0L, Math.max(todayMs, weeklyMs))
        return Math.min(Math.max(0L, totalMs), normalizedWeek)
    }

    @JvmStatic
    fun normalizedTodayReadingMillis(totalMs: Long, weeklyMs: Long, todayMs: Long): Long {
        val normalizedToday = Math.max(0L, todayMs)
        return Math.min(Math.min(Math.max(0L, totalMs), Math.max(0L, weeklyMs)), normalizedToday)
    }

    @JvmStatic
    fun formatWeeklyReadingLabel(durationMs: Long): String {
        return "本周读" + formatDurationLabel(durationMs)
    }

    @JvmStatic
    fun formatDurationLabel(durationMs: Long): String {
        val totalMinutes = Math.max(0L, durationMs / MINUTE_MS)
        if (totalMinutes < 60L) {
            return totalMinutes.toString() + "分钟"
        }
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        if (minutes == 0L) {
            return hours.toString() + "小时"
        }
        return hours.toString() + "小时" + minutes + "分钟"
    }

    /** Number and unit shown separately in stat tiles: minutes under an hour, else hours. */
    @JvmStatic
    fun formatDurationParts(durationMs: Long): Pair<String, String> {
        val totalMinutes = Math.max(0L, durationMs / MINUTE_MS)
        if (totalMinutes < 60L) {
            return totalMinutes.toString() to "分钟"
        }
        val tenthsOfHour = totalMinutes * 10L / 60L
        val whole = tenthsOfHour / 10L
        val tenth = tenthsOfHour % 10L
        return (if (tenth == 0L) "$whole" else "$whole.$tenth") to "小时"
    }

    @JvmStatic
    fun formatMineReadingLabel(totalDurationMs: Long, todayDurationMs: Long): String {
        val totalHours = Math.max(0L, totalDurationMs / HOUR_MS)
        val todayMinutes = Math.max(0L, todayDurationMs / MINUTE_MS)
        return "累计读" + totalHours + "小时 | 今日读" + todayMinutes + "分钟"
    }

    private fun createRecorder(): ReadingStatsRecorder {
        return ReadingStatsRecorder(SharedPreReadingStatsStorage(), TimeZone.getDefault())
    }
}
