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

    @Test
    fun activeValidationCanBeQueriedBySourceBookOrIdentity() {
        val tracker = SourceEngineV8ValidationTracker()
        val sourceBookKey = "https://source.example\n/book/1"
        val validationKey = "$sourceBookKey\n560\nfirst\nlast\ndigest"

        assertTrue(
            tracker.start(
                validationKey,
                Job(),
                SourceEngineV8ValidationTracker.ActiveBook(
                    sourceBookKey = sourceBookKey,
                    bookName = "苟在两界修仙",
                    author = "文抄公"
                )
            )
        )

        assertTrue(tracker.hasActiveBook(listOf(sourceBookKey), null, null))
        assertTrue(tracker.hasActiveBook(emptyList(), "苟在两界修仙", "文抄公"))
        assertFalse(tracker.hasActiveBook(emptyList(), "苟在两界修仙", "别人"))
    }

    @Test
    fun completedValidationIsNotReportedAsActiveBook() {
        val tracker = SourceEngineV8ValidationTracker()
        val sourceBookKey = "https://source.example\n/book/1"
        val job = Job()

        assertTrue(
            tracker.start(
                "$sourceBookKey\n560\nfirst\nlast\ndigest",
                job,
                SourceEngineV8ValidationTracker.ActiveBook(
                    sourceBookKey = sourceBookKey,
                    bookName = "苟在两界修仙",
                    author = "文抄公"
                )
            )
        )
        job.complete()

        assertFalse(tracker.hasActiveBook(listOf(sourceBookKey), "苟在两界修仙", "文抄公"))
    }
}
