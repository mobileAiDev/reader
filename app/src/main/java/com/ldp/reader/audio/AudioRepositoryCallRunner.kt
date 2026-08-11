package com.ldp.reader.audio

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed class AudioRepositoryCallResult<out T> {
    data class Success<T>(val value: T) : AudioRepositoryCallResult<T>()
    data class Failure(val error: Throwable) : AudioRepositoryCallResult<Nothing>()
    object Busy : AudioRepositoryCallResult<Nothing>()
}

/**
 * Keeps synchronous source-engine calls behind a cancellable coroutine seam.
 *
 * Cancellation stops waiting immediately and interrupts the worker as a best effort. A source
 * implementation that ignores interruption may finish in the background, so callers must still
 * protect state application with their own request generation. The synchronous hand-off queue
 * rejects excess work as [AudioRepositoryCallResult.Busy] instead of hiding it behind stuck calls.
 */
internal class AudioRepositoryCallExecutor(
    maxConcurrentCalls: Int,
    threadNamePrefix: String = "audio-repository"
) {
    init {
        require(maxConcurrentCalls > 0) { "maxConcurrentCalls must be positive" }
    }

    private val threadNumber = AtomicInteger(0)
    private val executor = ThreadPoolExecutor(
        maxConcurrentCalls,
        maxConcurrentCalls,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue<Runnable>(),
        { runnable ->
            Thread(runnable, "$threadNamePrefix-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    suspend fun <T> runResult(block: () -> T): AudioRepositoryCallResult<T> {
        return suspendCancellableCoroutine { continuation ->
            val future = try {
                executor.submit {
                    val result = try {
                        AudioRepositoryCallResult.Success(block())
                    } catch (error: Throwable) {
                        AudioRepositoryCallResult.Failure(error)
                    }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
            } catch (_: RejectedExecutionException) {
                continuation.resume(AudioRepositoryCallResult.Busy)
                return@suspendCancellableCoroutine
            } catch (error: Throwable) {
                continuation.resume(AudioRepositoryCallResult.Failure(error))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                future.cancel(true)
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}

/**
 * Owns separate, process-scoped lanes for media detail, foreground player, and background service
 * work.
 *
 * Every lane stays deliberately small and rejects instead of queueing. Keeping the three failure
 * domains separate means a cancelled request whose source ignores interruption cannot consume the
 * workers needed by either of the other user-visible playback paths.
 */
object AudioRepositoryCallRunner {
    private val mediaDetailExecutor = AudioRepositoryCallExecutor(
        maxConcurrentCalls = MAX_CONCURRENT_CALLS_PER_LANE,
        threadNamePrefix = "media-detail-repository"
    )
    private val foregroundPlayerExecutor = AudioRepositoryCallExecutor(
        maxConcurrentCalls = MAX_CONCURRENT_CALLS_PER_LANE,
        threadNamePrefix = "audio-player-repository"
    )
    private val backgroundServiceExecutor = AudioRepositoryCallExecutor(
        maxConcurrentCalls = MAX_CONCURRENT_CALLS_PER_LANE,
        threadNamePrefix = "audio-service-repository"
    )

    suspend fun <T> runMediaDetailResult(block: () -> T): AudioRepositoryCallResult<T> {
        return mediaDetailExecutor.runResult(block)
    }

    suspend fun <T> runForegroundPlayerResult(block: () -> T): AudioRepositoryCallResult<T> {
        return foregroundPlayerExecutor.runResult(block)
    }

    suspend fun <T> runBackgroundServiceResult(block: () -> T): AudioRepositoryCallResult<T> {
        return backgroundServiceExecutor.runResult(block)
    }

    suspend fun <T> runForegroundPlayer(block: () -> T): T? {
        return when (val result = runForegroundPlayerResult(block)) {
            is AudioRepositoryCallResult.Success -> result.value
            is AudioRepositoryCallResult.Failure,
            AudioRepositoryCallResult.Busy -> null
        }
    }

    private const val MAX_CONCURRENT_CALLS_PER_LANE = 3
}
