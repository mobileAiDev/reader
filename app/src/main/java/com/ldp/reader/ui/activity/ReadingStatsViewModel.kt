package com.ldp.reader.ui.activity

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.ldp.reader.utils.ReadingStatsUtils

class ReadingStatsViewModel : ViewModel() {
    private val _stats = MutableLiveData<ReadingStatsUiState>()
    val stats: LiveData<ReadingStatsUiState> = _stats
    private val _recentDays = MutableLiveData<List<Long>>()

    /** Reading time per day for the last week, oldest first, ending today. */
    val recentDays: LiveData<List<Long>> = _recentDays

    fun refresh(nowMs: Long = System.currentTimeMillis()) {
        _stats.value = createState(
            ReadingStatsUtils.getTotalReadingMillis(),
            ReadingStatsUtils.getTodayReadingMillis(nowMs),
            ReadingStatsUtils.getWeeklyReadingMillis(nowMs)
        )
        _recentDays.value = ReadingStatsUtils.getRecentDailyReadingMillis(nowMs, RECENT_DAYS)
    }

    companion object {
        const val RECENT_DAYS = 7

        @JvmStatic
        fun createState(totalMs: Long, todayMs: Long, weekMs: Long): ReadingStatsUiState {
            return ReadingStatsUiState(
                ReadingStatsUtils.formatDurationLabel(totalMs),
                ReadingStatsUtils.formatDurationLabel(todayMs),
                ReadingStatsUtils.formatDurationLabel(weekMs)
            )
        }
    }
}
