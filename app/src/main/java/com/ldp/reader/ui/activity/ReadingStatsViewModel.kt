package com.ldp.reader.ui.activity

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.ldp.reader.utils.ReadingStatsUtils

class ReadingStatsViewModel : ViewModel() {
    private val _stats = MutableLiveData<ReadingStatsUiState>()
    val stats: LiveData<ReadingStatsUiState> = _stats

    fun refresh(nowMs: Long = System.currentTimeMillis()) {
        _stats.value = createState(
            ReadingStatsUtils.getTotalReadingMillis(),
            ReadingStatsUtils.getTodayReadingMillis(nowMs),
            ReadingStatsUtils.getWeeklyReadingMillis(nowMs)
        )
    }

    companion object {
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
