package com.ldp.reader.ui.activity

import com.ldp.reader.audio.AudioRepositoryCallResult
import com.ldp.reader.audio.AudioRepositoryCallRunner
import com.ldp.reader.media.MediaSearchRetryableException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class MediaDetailFailureBoundaryTest {
    @Test
    fun retryableSourcePreparationFailureBecomesDetailFailureInsteadOfEscaping() = runBlocking {
        val sourceFailure = MediaSearchRetryableException(
            completedSourceCount = 0,
            successfulSourceCount = 0,
            totalSourceCount = 0,
            timedOut = true
        )

        val outcome = runMediaDetailCallSafely<String> {
            throw sourceFailure
        }

        assertTrue(outcome is MediaDetailCallOutcome.Failed)
        assertSame(sourceFailure, (outcome as MediaDetailCallOutcome.Failed).error)
    }

    @Test
    fun timedOutDetailCallsDoNotStarveForegroundPlayerOrBackgroundService() = runBlocking {
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(3)
        try {
            val timedOutDetails = (0 until 3).map {
                async(Dispatchers.Default) {
                    withTimeoutOrNull(500L) {
                        runMediaDetailCallSafely {
                            started.countDown()
                            try {
                                awaitIgnoringInterrupts(release)
                                "late-detail"
                            } finally {
                                finished.countDown()
                            }
                        }
                    }
                }
            }

            assertTrue("all detail workers should start", started.await(2, TimeUnit.SECONDS))
            withTimeout(2_000L) { timedOutDetails.awaitAll() }
            assertTrue("detail cancellation must not rotate replacement threads", finished.count == 3L)

            lateinit var followUpDetail: MediaDetailCallOutcome<String>
            val busyElapsedMs = measureTimeMillis {
                followUpDetail = runMediaDetailCallSafely { "must-not-run" }
            }
            assertTrue(
                "the saturated detail lane must stay bounded",
                followUpDetail is MediaDetailCallOutcome.Failed &&
                    (followUpDetail as MediaDetailCallOutcome.Failed).error == null
            )
            assertTrue(
                "the detail lane must reject instead of queueing, elapsed=$busyElapsedMs",
                busyElapsedMs < 500L
            )

            val playerLoad = AudioRepositoryCallRunner.runForegroundPlayerResult { "player-ready" }
            val autoAdvance = AudioRepositoryCallRunner.runBackgroundServiceResult { "auto-advance-ready" }

            assertTrue(
                "timed-out detail work must not starve the player",
                playerLoad is AudioRepositoryCallResult.Success && playerLoad.value == "player-ready"
            )
            assertTrue(
                "timed-out detail work must not starve automatic advance",
                autoAdvance is AudioRepositoryCallResult.Success && autoAdvance.value == "auto-advance-ready"
            )
        } finally {
            release.countDown()
            assertTrue("stuck test workers should be released", finished.await(2, TimeUnit.SECONDS))
        }
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Models a synchronous source runtime that ignores Future.cancel(true).
            }
        }
    }
}
