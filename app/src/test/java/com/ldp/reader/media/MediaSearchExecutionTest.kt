package com.ldp.reader.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaSearchExecutionTest {

    @Test
    fun failedEngineAttemptIsAFailedSourceEvenWhenTheEngineReturnsAReport() {
        val result = MediaSourceSearchReport(
            books = emptyList(),
            attempts = listOf(
                MediaSourceSearchAttempt(
                    sourceName = "offline-source",
                    success = false,
                    resultCount = 0,
                    message = "timeout"
                )
            )
        ).toMediaSearchSourceResult()

        assertTrue(result is MediaSearchSourceResult.Failure<*>)
    }

    @Test
    fun successfulEngineAttemptWithNoBooksIsARealEmptySourceResult() {
        val result = MediaSourceSearchReport(
            books = emptyList(),
            attempts = listOf(
                MediaSourceSearchAttempt(
                    sourceName = "healthy-source",
                    success = true,
                    resultCount = 0,
                    message = "request-url"
                )
            )
        ).toMediaSearchSourceResult()

        assertTrue(result is MediaSearchSourceResult.Success<*>)
        assertTrue((result as MediaSearchSourceResult.Success<MediaSourceBook>).values.isEmpty())
    }

    @Test
    fun allFailedSourcesBecomeRetryableInsteadOfAnEmptyResult() {
        val error = expectRetryable {
            MediaSearchBatchRunner.run(
                tasks = listOf(
                    Callable { MediaSearchSourceResult.Failure<String>() },
                    Callable { MediaSearchSourceResult.Failure<String>() }
                ),
                timeoutMs = 1_000L
            )
        }

        assertEquals(0, error.successfulSourceCount)
        assertEquals(2, error.completedSourceCount)
        assertTrue(!error.timedOut)
    }

    @Test
    fun aSuccessfulSourceWithNoBooksRemainsARealEmptyResult() {
        val result = MediaSearchBatchRunner.run(
            tasks = listOf(
                Callable { MediaSearchSourceResult.Success(emptyList<String>()) },
                Callable { MediaSearchSourceResult.Failure<String>() }
            ),
            timeoutMs = 1_000L
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun allFailedCatalogValidationsAreRetryableButNoCandidatesIsTrueEmpty() {
        MediaSearchValidationOutcomePolicy.requireSuccessfulValidationOrTrueEmpty(
            attemptedValidationCount = 0,
            successfulValidationCount = 0
        )
        MediaSearchValidationOutcomePolicy.requireSuccessfulValidationOrTrueEmpty(
            attemptedValidationCount = 3,
            successfulValidationCount = 1
        )

        expectRetryable {
            MediaSearchValidationOutcomePolicy.requireSuccessfulValidationOrTrueEmpty(
                attemptedValidationCount = 3,
                successfulValidationCount = 0
            )
        }
    }

    @Test
    fun deadlineUsesBestValidatedSnapshotInsteadOfTurningSuccessIntoAnError() {
        val best = listOf("validated-result")
        val timeout = MediaSearchRetryableException(
            completedSourceCount = 4,
            successfulSourceCount = 3,
            totalSourceCount = 8,
            timedOut = true
        )

        assertEquals(
            best,
            MediaSearchValidatedResultPolicy.recoverDeadlineOrThrow(best, timeout)
        )
        assertEquals(
            best,
            MediaSearchValidatedResultPolicy.retain(best, emptyList())
        )
        assertEquals(
            listOf("newer-validated-result"),
            MediaSearchValidatedResultPolicy.retain(best, listOf("newer-validated-result"))
        )
    }

    @Test
    fun deadlineWithoutValidatedResultsAndNonDeadlineFailuresRemainRetryable() {
        val timeout = MediaSearchRetryableException(
            completedSourceCount = 0,
            successfulSourceCount = 0,
            totalSourceCount = 8,
            timedOut = true
        )
        val ordinaryFailure = MediaSearchRetryableException(
            completedSourceCount = 8,
            successfulSourceCount = 0,
            totalSourceCount = 8,
            timedOut = false
        )

        assertEquals(
            timeout,
            expectRetryable {
                MediaSearchValidatedResultPolicy.recoverDeadlineOrThrow(emptyList<String>(), timeout)
            }
        )
        assertEquals(
            ordinaryFailure,
            expectRetryable {
                MediaSearchValidatedResultPolicy.recoverDeadlineOrThrow(
                    listOf("validated-result"),
                    ordinaryFailure
                )
            }
        )
    }

    @Test
    fun boundedPartialDeadlineDoesNotExpireTheParentSearchBudget() {
        val handle = MediaSearchCancellationHandle()
        val parent = MediaSearchExecutionControl.create(handle, timeoutMs = 1_000L)
        val partial = parent.bounded(timeoutMs = 0L)

        val error = expectRetryable { partial.throwIfStopped() }

        assertTrue(error.timedOut)
        parent.throwIfStopped()
    }

    @Test
    fun cancellationInvokesRegisteredBlockingResourceCancellation() {
        val handle = MediaSearchCancellationHandle()
        val cancelled = CountDownLatch(1)
        val registration = handle.registerCancellation { cancelled.countDown() }

        handle.cancel()

        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        registration.close()
    }

    @Test
    fun overallDeadlineAlsoCoversPartialResultProcessing() {
        val handle = MediaSearchCancellationHandle()
        val control = MediaSearchExecutionControl.create(handle, timeoutMs = 40L)

        val error = expectRetryable {
            MediaSearchBatchRunner.run(
                tasks = listOf(Callable { MediaSearchSourceResult.Success(listOf("book")) }),
                timeoutMs = 1_000L,
                cancellationHandle = handle,
                executionControl = control,
                onPartialResults = { _, final ->
                    if (!final) Thread.sleep(80L)
                }
            )
        }

        assertTrue(error.timedOut)
    }

    @Test
    fun timeoutWithoutAnySuccessfulSourceBecomesRetryableAndInterruptsWork() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)

        val error = expectRetryable {
            MediaSearchBatchRunner.run(
                tasks = listOf(
                    Callable {
                        started.countDown()
                        try {
                            CountDownLatch(1).await()
                            MediaSearchSourceResult.Failure<String>()
                        } catch (error: InterruptedException) {
                            interrupted.countDown()
                            throw error
                        }
                    }
                ),
                timeoutMs = 100L
            )
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(error.timedOut)
        assertEquals(0, error.successfulSourceCount)
        assertTrue(interrupted.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun bestEffortTimeoutReturnsCompletedValuesInsteadOfTurningDeadlineIntoFailure() {
        val blockedStarted = CountDownLatch(1)
        val blockedInterrupted = CountDownLatch(1)
        val releaseBlocked = CountDownLatch(1)

        try {
            val result = MediaSearchBatchRunner.run(
                tasks = listOf(
                    Callable {
                        blockedStarted.countDown()
                        try {
                            releaseBlocked.await()
                            MediaSearchSourceResult.Success(emptyList<String>())
                        } catch (error: InterruptedException) {
                            blockedInterrupted.countDown()
                            throw error
                        }
                    },
                    Callable {
                        if (!blockedStarted.await(1, TimeUnit.SECONDS)) {
                            throw AssertionError("Blocked supplement source did not start")
                        }
                        MediaSearchSourceResult.Success(listOf("validated-primary"))
                    }
                ),
                timeoutMs = 250L,
                maxParallelism = 2,
                retryWhenNoSourceSucceeded = false
            )

            assertEquals(listOf("validated-primary"), result)
            assertTrue(blockedInterrupted.await(1, TimeUnit.SECONDS))
        } finally {
            releaseBlocked.countDown()
        }
    }

    @Test
    fun explicitCancellationInterruptsSubmittedWorkAndCancelsTheCaller() {
        val handle = MediaSearchCancellationHandle()
        val taskStarted = CountDownLatch(1)
        val taskInterrupted = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()

        try {
            val result = caller.submit<List<String>> {
                MediaSearchBatchRunner.run(
                    tasks = listOf(
                        Callable {
                            taskStarted.countDown()
                            try {
                                CountDownLatch(1).await()
                                MediaSearchSourceResult.Success(listOf("stale"))
                            } catch (error: InterruptedException) {
                                taskInterrupted.countDown()
                                throw error
                            }
                        }
                    ),
                    timeoutMs = 5_000L,
                    cancellationHandle = handle
                )
            }

            assertTrue(taskStarted.await(1, TimeUnit.SECONDS))
            handle.cancel()

            try {
                result.get(1, TimeUnit.SECONDS)
                fail("Expected the media search caller to be cancelled")
            } catch (expected: ExecutionException) {
                assertTrue(expected.cause is CancellationException)
            }
            assertTrue(taskInterrupted.await(1, TimeUnit.SECONDS))
        } finally {
            handle.cancel()
            caller.shutdownNow()
        }
    }

    @Test
    fun aNewRequestIsNotQueuedBehindBlockedWorkersFromAnOldRequest() {
        val oldHandle = MediaSearchCancellationHandle()
        val oldWorkersStarted = CountDownLatch(2)
        val releaseOldWorkers = CountDownLatch(1)
        val oldCaller = Executors.newSingleThreadExecutor()

        try {
            oldCaller.submit<List<String>> {
                MediaSearchBatchRunner.run(
                    tasks = List(4) {
                        Callable {
                            oldWorkersStarted.countDown()
                            releaseOldWorkers.await()
                            MediaSearchSourceResult.Success(emptyList<String>())
                        }
                    },
                    timeoutMs = 5_000L,
                    cancellationHandle = oldHandle,
                    maxParallelism = 2
                )
            }
            assertTrue(oldWorkersStarted.await(1, TimeUnit.SECONDS))

            val freshResult = MediaSearchBatchRunner.run(
                tasks = listOf(
                    Callable { MediaSearchSourceResult.Success(listOf("fresh")) }
                ),
                timeoutMs = 500L,
                maxParallelism = 2
            )

            assertEquals(listOf("fresh"), freshResult)
        } finally {
            oldHandle.cancel()
            releaseOldWorkers.countDown()
            oldCaller.shutdownNow()
        }
    }

    @Test
    fun repeatedCancellationKeepsTheSharedQueueBoundedAndFreshSearchUsable() {
        val caller = Executors.newSingleThreadExecutor()
        try {
            repeat(24) {
                val handle = MediaSearchCancellationHandle()
                val started = CountDownLatch(1)
                val result = caller.submit<List<String>> {
                    MediaSearchBatchRunner.run(
                        tasks = listOf(
                            Callable {
                                started.countDown()
                                CountDownLatch(1).await()
                                MediaSearchSourceResult.Success(listOf("stale"))
                            }
                        ),
                        timeoutMs = 5_000L,
                        cancellationHandle = handle
                    )
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                handle.cancel()
                try {
                    result.get(1, TimeUnit.SECONDS)
                    fail("Expected cancellation")
                } catch (_: ExecutionException) {
                    // The caller receives the search CancellationException through its Future.
                }
                assertEquals(0, MediaSearchBatchRunner.queuedSourceTaskCount())
                assertTrue(MediaSearchBatchRunner.queuedSourceTaskCount() <= MediaSearchBatchRunner.sourceQueueCapacity())
            }

            val fresh = MediaSearchBatchRunner.run(
                tasks = listOf(Callable { MediaSearchSourceResult.Success(listOf("fresh")) }),
                timeoutMs = 500L
            )
            assertEquals(listOf("fresh"), fresh)
        } finally {
            caller.shutdownNow()
        }
    }

    private fun expectRetryable(block: () -> Unit): MediaSearchRetryableException {
        return try {
            block()
            throw AssertionError("Expected MediaSearchRetryableException")
        } catch (expected: MediaSearchRetryableException) {
            expected
        }
    }
}
