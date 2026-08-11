package com.ldp.reader.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRepositoryCallRunnerTest {
    @Test
    fun timeoutStopsWaitingForASynchronousBlockingRepositoryCall() = runBlocking {
        var result: String? = "not-started"

        val elapsedMs = measureTimeMillis {
            result = withTimeoutOrNull(100L) {
                AudioRepositoryCallRunner.runForegroundPlayer {
                    Thread.sleep(10_000L)
                    "late-result"
                }
            }
        }

        assertNull(result)
        assertTrue("timeout should release the caller promptly, elapsed=$elapsedMs", elapsedMs < 2_000L)
    }

    @Test
    fun resultDistinguishesSuccessfulNullFromFailure() = runBlocking {
        val executor = AudioRepositoryCallExecutor(maxConcurrentCalls = 1, threadNamePrefix = "audio-result-test")
        try {
            val successfulNull = executor.runResult<String?> { null }
            val failure = executor.runResult<String> { error("source failed") }

            assertTrue(successfulNull is AudioRepositoryCallResult.Success && successfulNull.value == null)
            assertTrue(failure is AudioRepositoryCallResult.Failure)
        } finally {
            executor.close()
        }
    }

    @Test
    fun saturatedExecutorRejectsFourthCallImmediatelyInsteadOfQueueing() {
        runBlocking {
            val executor = AudioRepositoryCallExecutor(maxConcurrentCalls = 3, threadNamePrefix = "audio-busy-test")
            val started = CountDownLatch(3)
            val release = CountDownLatch(1)
            try {
                val blockers = (0 until 3).map {
                    async(Dispatchers.Default) {
                        executor.runResult {
                            started.countDown()
                            awaitIgnoringInterrupts(release)
                            "released"
                        }
                    }
                }
                assertTrue("all repository workers should be occupied", started.await(2, TimeUnit.SECONDS))

                lateinit var fourth: AudioRepositoryCallResult<String>
                val elapsedMs = measureTimeMillis {
                    fourth = executor.runResult { "must-not-run" }
                }

                assertTrue(fourth === AudioRepositoryCallResult.Busy)
                assertTrue("busy result should be immediate, elapsed=$elapsedMs", elapsedMs < 500L)
                release.countDown()
                withTimeout(2_000L) { blockers.awaitAll() }
            } finally {
                release.countDown()
                executor.close()
            }
        }
    }

    @Test
    fun saturatedForegroundPlayerLaneDoesNotStarveDetailOrBackgroundService() = runBlocking {
        assertFailureDomainIsolation(
            saturatedLaneName = "foreground player",
            saturatedLane = { block -> AudioRepositoryCallRunner.runForegroundPlayerResult(block) },
            firstAvailableLaneName = "media detail",
            firstAvailableLane = { block -> AudioRepositoryCallRunner.runMediaDetailResult(block) },
            secondAvailableLaneName = "background service",
            secondAvailableLane = { block -> AudioRepositoryCallRunner.runBackgroundServiceResult(block) }
        )
    }

    @Test
    fun saturatedBackgroundServiceLaneDoesNotStarveDetailOrForegroundPlayer() = runBlocking {
        assertFailureDomainIsolation(
            saturatedLaneName = "background service",
            saturatedLane = { block -> AudioRepositoryCallRunner.runBackgroundServiceResult(block) },
            firstAvailableLaneName = "media detail",
            firstAvailableLane = { block -> AudioRepositoryCallRunner.runMediaDetailResult(block) },
            secondAvailableLaneName = "foreground player",
            secondAvailableLane = { block -> AudioRepositoryCallRunner.runForegroundPlayerResult(block) }
        )
    }

    @Test
    fun processScopedFailureDomainWorkersAreDaemonThreads() = runBlocking {
        val detailWorker = AudioRepositoryCallRunner.runMediaDetailResult {
            Thread.currentThread().name to Thread.currentThread().isDaemon
        }
        val playerWorker = AudioRepositoryCallRunner.runForegroundPlayerResult {
            Thread.currentThread().name to Thread.currentThread().isDaemon
        }
        val serviceWorker = AudioRepositoryCallRunner.runBackgroundServiceResult {
            Thread.currentThread().name to Thread.currentThread().isDaemon
        }

        assertDaemonWorker(detailWorker, "media-detail-repository-")
        assertDaemonWorker(playerWorker, "audio-player-repository-")
        assertDaemonWorker(serviceWorker, "audio-service-repository-")
    }

    private suspend fun assertFailureDomainIsolation(
        saturatedLaneName: String,
        saturatedLane: suspend (() -> String) -> AudioRepositoryCallResult<String>,
        firstAvailableLaneName: String,
        firstAvailableLane: suspend (() -> String) -> AudioRepositoryCallResult<String>,
        secondAvailableLaneName: String,
        secondAvailableLane: suspend (() -> String) -> AudioRepositoryCallResult<String>
    ) = coroutineScope {
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(3)
        try {
            val timedOutCalls = (0 until 3).map {
                async(Dispatchers.Default) {
                    withTimeoutOrNull(500L) {
                        saturatedLane {
                            started.countDown()
                            try {
                                awaitIgnoringInterrupts(release)
                                "late-result"
                            } finally {
                                finished.countDown()
                            }
                        }
                    }
                }
            }

            assertTrue("all $saturatedLaneName workers should start", started.await(2, TimeUnit.SECONDS))
            withTimeout(2_000L) { timedOutCalls.awaitAll() }
            assertTrue(
                "$saturatedLaneName cancellation must not rotate replacement threads",
                finished.count == 3L
            )

            lateinit var sameLaneRetry: AudioRepositoryCallResult<String>
            val busyElapsedMs = measureTimeMillis {
                sameLaneRetry = saturatedLane { "must-not-run" }
            }
            assertSame(AudioRepositoryCallResult.Busy, sameLaneRetry)
            assertTrue(
                "$saturatedLaneName must reject instead of queueing, elapsed=$busyElapsedMs",
                busyElapsedMs < 500L
            )

            val firstAvailable = firstAvailableLane { "first-ready" }
            val secondAvailable = secondAvailableLane { "second-ready" }
            assertTrue(
                "$saturatedLaneName must not starve $firstAvailableLaneName",
                firstAvailable is AudioRepositoryCallResult.Success && firstAvailable.value == "first-ready"
            )
            assertTrue(
                "$saturatedLaneName must not starve $secondAvailableLaneName",
                secondAvailable is AudioRepositoryCallResult.Success && secondAvailable.value == "second-ready"
            )
        } finally {
            release.countDown()
            assertTrue("$saturatedLaneName workers should be released", finished.await(2, TimeUnit.SECONDS))
        }
    }

    private fun assertDaemonWorker(
        result: AudioRepositoryCallResult<Pair<String, Boolean>>,
        expectedNamePrefix: String
    ) {
        assertTrue("worker should complete", result is AudioRepositoryCallResult.Success)
        val identity = (result as AudioRepositoryCallResult.Success).value
        assertTrue(
            "worker should use $expectedNamePrefix, actual=${identity.first}",
            identity.first.startsWith(expectedNamePrefix)
        )
        assertTrue("$expectedNamePrefix worker must be daemon", identity.second)
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Models a source runtime that does not cooperate with Future.cancel(true).
            }
        }
    }
}
