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

    @Test
    fun fullCatalogProgressRemainsMonotonicAndAvailableAfterFinish() {
        val tracker = SourceEngineV8ValidationTracker()
        val sourceBookKey = "https://source.example\n/book/1"
        val validationKey = "$sourceBookKey\n560\nfirst\nlast\ndigest"
        val activeBook = SourceEngineV8ValidationTracker.ActiveBook(
            sourceBookKey = sourceBookKey,
            bookName = "苟在两界修仙",
            author = "文抄公"
        )
        assertTrue(
            tracker.start(
                validationKey,
                Job(),
                activeBook,
                SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, processed = 0, target = 127)
            )
        )

        tracker.updateFullCatalogProgress(
            validationKey,
            SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, processed = 32, target = 127)
        )
        tracker.updateFullCatalogProgress(
            validationKey,
            SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, processed = 16, target = 127)
        )
        tracker.updateFullCatalogProgress(
            validationKey,
            SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.COMPLETED, processed = 127, target = 127)
        )
        tracker.finish(validationKey)

        val latest = tracker.latestFullCatalogProgress(listOf(sourceBookKey), "苟在两界修仙", "文抄公")
        assertEquals(127, latest?.processed)
        assertEquals(127, latest?.target)
        assertTrue(latest?.completed == true)
    }

    @Test
    fun cacheHitProgressNeverRegistersAnActiveScan() {
        val tracker = SourceEngineV8ValidationTracker()
        val sourceBookKey = "https://source.example\n/book/1"
        val activeBook = SourceEngineV8ValidationTracker.ActiveBook(
            sourceBookKey = sourceBookKey,
            bookName = "苟在两界修仙",
            author = "文抄公"
        )

        tracker.recordFullCatalogCacheHit(
            key = "$sourceBookKey\nshape",
            activeBook = activeBook,
            progress = SourceEngineV8ScanProgress(
                SourceEngineV8ScanPhase.CACHE_HIT,
                processed = 127,
                target = 127,
                wrong = 2
            )
        )

        assertFalse(tracker.hasActiveBook(listOf(sourceBookKey), "苟在两界修仙", "文抄公"))
        val latest = tracker.latestFullCatalogProgress(listOf(sourceBookKey), "苟在两界修仙", "文抄公")
        assertTrue(latest?.cacheHit == true)
        assertEquals(2, latest?.wrong)
    }

    @Test
    fun fullCatalogProgressCanFollowFallbackSourceWhenAuthorIsMissing() {
        val tracker = SourceEngineV8ValidationTracker()
        val fallbackSourceBookKey = "https://fallback.example\n/book/9"
        val activeBook = SourceEngineV8ValidationTracker.ActiveBook(
            sourceBookKey = fallbackSourceBookKey,
            bookName = "只有书名的小说",
            author = ""
        )
        val validationKey = "$fallbackSourceBookKey\nshape\nscope=full-catalog"
        assertTrue(
            tracker.start(
                validationKey,
                Job(),
                activeBook,
                SourceEngineV8ScanProgress(
                    SourceEngineV8ScanPhase.RUNNING,
                    processed = 16,
                    target = 127
                )
            )
        )

        val latest = tracker.latestFullCatalogProgress(
            sourceBookKeys = listOf("https://original.example\n/book/1"),
            bookName = "只有书名的小说",
            author = ""
        )

        assertEquals(16, latest?.processed)
        assertEquals(127, latest?.target)
    }

    @Test
    fun timeoutTerminalUpdateDoesNotResetProcessedCheckpoint() {
        val tracker = SourceEngineV8ValidationTracker()
        val sourceBookKey = "https://timeout.example\n/book/1"
        val key = "$sourceBookKey\nshape\nscope=full-catalog"
        val activeBook = SourceEngineV8ValidationTracker.ActiveBook(sourceBookKey, "超时测试", "作者")
        assertTrue(
            tracker.start(
                key,
                Job(),
                activeBook,
                SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, 0, 127)
            )
        )
        tracker.updateFullCatalogProgress(
            key,
            SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, 64, 127, wrong = 1)
        )

        tracker.updateFullCatalogProgress(
            key,
            SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.FAILED, 0, 127)
        )

        val terminal = tracker.latestFullCatalogProgress(listOf(sourceBookKey), "超时测试", "作者")
        assertEquals(SourceEngineV8ScanPhase.FAILED, terminal?.phase)
        assertEquals(64, terminal?.processed)
        assertEquals(1, terminal?.wrong)
    }

    @Test
    fun cancellationDoesNotResetProcessedCheckpoint() {
        val tracker = SourceEngineV8ValidationTracker()
        val sourceBookKey = "https://cancel.example\n/book/1"
        val key = "$sourceBookKey\nshape\nscope=full-catalog"
        val activeBook = SourceEngineV8ValidationTracker.ActiveBook(sourceBookKey, "取消测试", "作者")
        assertTrue(
            tracker.start(
                key,
                Job(),
                activeBook,
                SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, 0, 127)
            )
        )
        tracker.updateFullCatalogProgress(
            key,
            SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, 80, 127, wrong = 2)
        )

        tracker.cancelBook(
            sourceBookKeys = listOf(sourceBookKey),
            bookName = "取消测试",
            author = "作者",
            cause = CancellationException("test")
        )

        val terminal = tracker.latestFullCatalogProgress(listOf(sourceBookKey), "取消测试", "作者")
        assertEquals(SourceEngineV8ScanPhase.CANCELLED, terminal?.phase)
        assertEquals(80, terminal?.processed)
        assertEquals(2, terminal?.wrong)
    }

    @Test
    fun cancelledOwnerCannotFinishReplacementJobWithTheSameKey() {
        val tracker = SourceEngineV8ValidationTracker()
        val sourceBookKey = "https://replace.example\n/book/1"
        val key = "$sourceBookKey\nshape\nscope=full-catalog"
        val activeBook = SourceEngineV8ValidationTracker.ActiveBook(sourceBookKey, "替换测试", "作者")
        val oldJob = Job()
        assertTrue(
            tracker.start(
                key,
                oldJob,
                activeBook,
                SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, 32, 127)
            )
        )
        tracker.cancelBook(listOf(sourceBookKey), "替换测试", "作者", CancellationException("replace"))
        val replacementJob = Job()
        assertTrue(
            tracker.start(
                key,
                replacementJob,
                activeBook,
                SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, 32, 127)
            )
        )

        tracker.finish(key, expectedJob = oldJob)

        assertEquals(replacementJob, tracker.activeJob(key))
    }

    @Test
    fun cancelledOwnerCannotOverwriteReplacementProgressWithTheSameKey() {
        val tracker = SourceEngineV8ValidationTracker()
        val sourceBookKey = "https://replace-progress.example\n/book/1"
        val key = "$sourceBookKey\nshape\nscope=full-catalog"
        val activeBook = SourceEngineV8ValidationTracker.ActiveBook(sourceBookKey, "替换进度测试", "作者")
        val oldJob = Job()
        assertTrue(
            tracker.start(
                key,
                oldJob,
                activeBook,
                SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, 16, 127)
            )
        )
        tracker.cancelBook(listOf(sourceBookKey), "替换进度测试", "作者", CancellationException("replace"))
        val replacementJob = Job()
        assertTrue(
            tracker.start(
                key,
                replacementJob,
                activeBook,
                SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.RUNNING, 32, 127)
            )
        )

        tracker.updateFullCatalogProgress(
            key,
            SourceEngineV8ScanProgress(SourceEngineV8ScanPhase.CANCELLED, 16, 127),
            expectedJob = oldJob
        )

        val latest = tracker.latestFullCatalogProgress(listOf(sourceBookKey), "替换进度测试", "作者")
        assertEquals(SourceEngineV8ScanPhase.RUNNING, latest?.phase)
        assertEquals(32, latest?.processed)
    }
}
