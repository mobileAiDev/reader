package com.ldp.reader.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSessionTrackerTest {
    @Test
    fun pauseAndStopCommitOneForegroundIntervalOnlyOnce() {
        val clock = FakeClock(1_000L)
        val recorded = mutableListOf<RecordedInterval>()
        val tracker = tracker(clock, recorded)

        tracker.onResume("book-1")
        clock.now = 4_000L
        tracker.onPause()
        clock.now = 5_000L
        tracker.onStop()

        assertEquals(listOf(RecordedInterval("book-1", 1_000L, 4_000L)), recorded)
    }

    @Test
    fun duplicateResumeDoesNotResetTheForegroundStart() {
        val clock = FakeClock(2_000L)
        val recorded = mutableListOf<RecordedInterval>()
        val tracker = tracker(clock, recorded)

        tracker.onResume("book-1")
        clock.now = 3_000L
        tracker.onResume("book-1")
        clock.now = 6_000L
        tracker.onPause()

        assertEquals(listOf(RecordedInterval("book-1", 2_000L, 6_000L)), recorded)
    }

    @Test
    fun pauseThenResumeCreatesAdjacentSessionsForActivityRecreation() {
        val clock = FakeClock(10_000L)
        val recorded = mutableListOf<RecordedInterval>()
        val oldActivity = tracker(clock, recorded)

        oldActivity.onResume("document:1")
        clock.now = 12_000L
        oldActivity.onPause()

        val recreatedActivity = tracker(clock, recorded)
        recreatedActivity.onResume("document:1")
        clock.now = 15_000L
        recreatedActivity.onPause()
        recreatedActivity.onStop()

        assertEquals(
            listOf(
                RecordedInterval("document:1", 10_000L, 12_000L),
                RecordedInterval("document:1", 12_000L, 15_000L)
            ),
            recorded
        )
    }

    private fun tracker(
        clock: FakeClock,
        recorded: MutableList<RecordedInterval>
    ): ReadingSessionTracker {
        return ReadingSessionTracker(
            nowMillis = { clock.now },
            recorder = { bookId, startMs, endMs ->
                recorded += RecordedInterval(bookId, startMs, endMs)
            }
        )
    }

    private data class FakeClock(var now: Long)

    private data class RecordedInterval(
        val bookId: String?,
        val startMs: Long,
        val endMs: Long
    )
}
