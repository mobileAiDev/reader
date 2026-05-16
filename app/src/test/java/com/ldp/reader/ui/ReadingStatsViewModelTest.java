package com.ldp.reader.ui;

import static org.junit.Assert.assertEquals;

import com.ldp.reader.ui.activity.ReadingStatsUiState;
import com.ldp.reader.ui.activity.ReadingStatsViewModel;

import org.junit.Test;

public class ReadingStatsViewModelTest {

    @Test
    public void createsReadingStatsUiStateFromDurations() {
        ReadingStatsUiState state = ReadingStatsViewModel.createState(
                65L * 60_000L,
                3L * 60_000L,
                120L * 60_000L
        );

        assertEquals("1小时5分钟", state.getTotalLabel());
        assertEquals("3分钟", state.getTodayLabel());
        assertEquals("2小时", state.getWeekLabel());
    }
}
