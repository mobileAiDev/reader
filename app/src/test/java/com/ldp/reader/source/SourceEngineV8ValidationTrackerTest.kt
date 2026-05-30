package com.ldp.reader.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineV8ValidationTrackerTest {
    @Test
    fun completedValidationDoesNotBlockNextValidationForSameKey() {
        val tracker = SourceEngineV8ValidationTracker()
        val key = "book\nsource\nshape"

        val firstJob = Job()
        assertTrue(tracker.start(key, firstJob))
        assertEquals(firstJob, tracker.activeJob(key))
        tracker.finish(key)
        assertEquals(null, tracker.activeJob(key))

        assertTrue(tracker.start(key, Job()))
    }

    @Test
    fun staleCancellationClearsOnlyOtherActiveJobs() {
        val tracker = SourceEngineV8ValidationTracker()
        val currentKey = "current"
        val staleKey = "stale"
        val currentJob = Job()
        val staleJob = Job()

        assertTrue(tracker.start(currentKey, currentJob))
        assertTrue(tracker.start(staleKey, staleJob))

        val cancelled = tracker.cancelStaleExcept(
            currentKey,
            CancellationException("test")
        )

        assertEquals(1, cancelled)
        assertTrue(staleJob.isCancelled)
        assertFalse(currentJob.isCancelled)
        assertTrue(tracker.isActive(currentKey))
        assertFalse(tracker.isActive(staleKey))
        assertFalse(tracker.start(currentKey, Job()))
        assertTrue(tracker.start(staleKey, Job()))
    }

    @Test
    fun finishedCurrentKeyCanStartAfterStaleJobsAreCancelled() {
        val tracker = SourceEngineV8ValidationTracker()
        val currentKey = "current"
        val staleKey = "stale"

        assertTrue(tracker.start(currentKey, Job()))
        tracker.finish(currentKey)
        assertTrue(tracker.start(staleKey, Job()))

        val cancelled = tracker.cancelStaleExcept(
            currentKey,
            CancellationException("test")
        )

        assertEquals(1, cancelled)
        assertTrue(tracker.start(currentKey, Job()))
    }
}
