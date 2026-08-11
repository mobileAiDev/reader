package com.ldp.reader.media

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MediaSearchRetryableException(
    val completedSourceCount: Int,
    val successfulSourceCount: Int,
    val totalSourceCount: Int,
    val timedOut: Boolean
) : RuntimeException(
    buildString {
        append("No media source completed successfully")
        append(" (completed=")
        append(completedSourceCount)
        append('/')
        append(totalSourceCount)
        append(", timedOut=")
        append(timedOut)
        append(')')
    }
)

internal class MediaSearchExecutionControl private constructor(
    val cancellationHandle: MediaSearchCancellationHandle,
    private val deadlineNanos: Long
) {
    fun throwIfStopped() {
        cancellationHandle.throwIfCancelled()
        if (remainingNanos() <= 0L) {
            throw MediaSearchRetryableException(
                completedSourceCount = 0,
                successfulSourceCount = 0,
                totalSourceCount = 0,
                timedOut = true
            )
        }
    }

    fun remainingMillis(): Long {
        val nanos = remainingNanos()
        if (nanos <= 0L) return 0L
        return ((nanos - 1L) / TimeUnit.MILLISECONDS.toNanos(1L)) + 1L
    }

    fun sourceDeadline(timeoutMs: Long): Long {
        val requested = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        return min(deadlineNanos, requested)
    }

    fun bounded(timeoutMs: Long): MediaSearchExecutionControl {
        cancellationHandle.throwIfCancelled()
        return create(
            cancellationHandle = cancellationHandle,
            timeoutMs = min(remainingMillis(), timeoutMs.coerceAtLeast(0L))
        )
    }

    private fun remainingNanos(): Long = deadlineNanos - System.nanoTime()

    companion object {
        fun create(
            cancellationHandle: MediaSearchCancellationHandle,
            timeoutMs: Long
        ): MediaSearchExecutionControl {
            return MediaSearchExecutionControl(
                cancellationHandle = cancellationHandle,
                deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
            )
        }
    }
}

internal class MediaSearchCancellationRegistration(
    private val unregisterAction: () -> Unit
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) unregisterAction()
    }
}

/**
 * Owns cancellation for one repository search. A cancelled handle is deliberately not reusable.
 */
class MediaSearchCancellationHandle {
    private val lock = Any()

    @Volatile
    private var cancelled = false
    private var session: MediaSearchExecutionSession? = null
    private val cancellationActions = LinkedHashMap<Any, () -> Unit>()

    val isCancelled: Boolean
        get() = cancelled

    fun cancel() {
        val resources = synchronized(lock) {
            cancelled = true
            val active = buildList {
                session?.let { activeSession -> add { activeSession.cancel() } }
                addAll(cancellationActions.values)
            }
            session = null
            cancellationActions.clear()
            active
        }
        resources.forEach { action -> runCatching(action) }
    }

    internal fun attach(candidate: MediaSearchExecutionSession) {
        val attached = synchronized(lock) {
            if (cancelled) {
                false
            } else {
                check(session == null) { "A media search cancellation handle may only own one active session" }
                session = candidate
                true
            }
        }
        if (!attached) {
            candidate.cancel()
            throw CancellationException("Media search was cancelled before it started")
        }
    }

    internal fun detach(candidate: MediaSearchExecutionSession) {
        synchronized(lock) {
            if (session === candidate) session = null
        }
    }

    internal fun throwIfCancelled() {
        if (cancelled) throw CancellationException("Media search was cancelled")
    }

    internal fun registerCancellation(action: () -> Unit): MediaSearchCancellationRegistration {
        val key = Any()
        val cancelImmediately = synchronized(lock) {
            if (cancelled) {
                true
            } else {
                cancellationActions[key] = action
                false
            }
        }
        if (cancelImmediately) action()
        return MediaSearchCancellationRegistration {
            synchronized(lock) { cancellationActions.remove(key) }
        }
    }
}

internal sealed class MediaSearchSourceResult<T> {
    data class Success<T>(val values: List<T>) : MediaSearchSourceResult<T>()
    data class Failure<T>(val cause: Throwable? = null) : MediaSearchSourceResult<T>()
}

internal fun MediaSourceSearchReport.toMediaSearchSourceResult(): MediaSearchSourceResult<MediaSourceBook> {
    return if (books.isNotEmpty() || attempts.any { attempt -> attempt.success }) {
        MediaSearchSourceResult.Success(books)
    } else {
        MediaSearchSourceResult.Failure()
    }
}

internal object MediaSearchValidationOutcomePolicy {
    fun requireSuccessfulValidationOrTrueEmpty(
        attemptedValidationCount: Int,
        successfulValidationCount: Int
    ) {
        if (attemptedValidationCount <= 0 || successfulValidationCount > 0) return
        throw MediaSearchRetryableException(
            completedSourceCount = attemptedValidationCount,
            successfulSourceCount = 0,
            totalSourceCount = attemptedValidationCount,
            timedOut = false
        )
    }
}

/**
 * Only values that already passed the media display gate may be retained here.
 * A deadline is a soft stop once such a value exists; cancellation and ordinary
 * retryable failures remain hard stops.
 */
internal object MediaSearchValidatedResultPolicy {
    fun <T> retain(bestValidated: List<T>, candidate: List<T>): List<T> {
        return if (candidate.isEmpty()) bestValidated else candidate.toList()
    }

    fun <T> recoverDeadlineOrThrow(
        bestValidated: List<T>,
        error: MediaSearchRetryableException
    ): List<T> {
        if (!error.timedOut || bestValidated.isEmpty()) throw error
        return bestValidated.toList()
    }
}

/** Executes request windows on shared bounded pools so cancelled searches cannot accumulate pools. */
internal object MediaSearchBatchRunner {
    private const val DEFAULT_MAX_PARALLELISM = 8
    private const val MAX_GLOBAL_SEARCH_THREADS = DEFAULT_MAX_PARALLELISM * 2
    private const val MAX_GLOBAL_SEARCH_QUEUE = MAX_GLOBAL_SEARCH_THREADS * 4
    private const val MAX_GLOBAL_VALIDATION_QUEUE = DEFAULT_MAX_PARALLELISM * 4
    private val workerIds = AtomicInteger()
    private val searchExecutor = boundedExecutor(
        threadCount = MAX_GLOBAL_SEARCH_THREADS,
        queueCapacity = MAX_GLOBAL_SEARCH_QUEUE,
        threadPrefix = "media-source-search"
    )

    internal val validationExecutor: ThreadPoolExecutor = boundedExecutor(
        threadCount = DEFAULT_MAX_PARALLELISM,
        queueCapacity = MAX_GLOBAL_VALIDATION_QUEUE,
        threadPrefix = "media-search-validation"
    )

    internal fun queuedSourceTaskCount(): Int = searchExecutor.queue.size

    internal fun queuedValidationTaskCount(): Int = validationExecutor.queue.size

    internal fun sourceQueueCapacity(): Int = MAX_GLOBAL_SEARCH_QUEUE

    internal fun validationQueueCapacity(): Int = MAX_GLOBAL_VALIDATION_QUEUE

    fun <T> run(
        tasks: List<Callable<MediaSearchSourceResult<T>>>,
        timeoutMs: Long,
        cancellationHandle: MediaSearchCancellationHandle = MediaSearchCancellationHandle(),
        maxParallelism: Int = DEFAULT_MAX_PARALLELISM,
        partialResultIntervalMs: Long = 650L,
        retryWhenNoSourceSucceeded: Boolean = true,
        executionControl: MediaSearchExecutionControl = MediaSearchExecutionControl.create(
            cancellationHandle,
            timeoutMs
        ),
        onPartialResults: ((List<T>, Boolean) -> Unit)? = null
    ): List<T> {
        executionControl.throwIfStopped()
        if (tasks.isEmpty()) return emptyList()

        val workerCount = maxParallelism.coerceAtLeast(1).coerceAtMost(tasks.size)
        val session = MediaSearchExecutionSession(searchExecutor)
        val completionService = ExecutorCompletionService<MediaSearchSourceResult<T>>(searchExecutor)
        val values = ArrayList<T>()
        var completedSourceCount = 0
        var submittedSourceCount = 0
        var successfulSourceCount = 0
        var timedOut = false
        var lastPartialAtNanos = 0L

        try {
            cancellationHandle.attach(session)
            fun submitNext(): Boolean {
                if (submittedSourceCount >= tasks.size) return false
                cancellationHandle.throwIfCancelled()
                val task = tasks[submittedSourceCount]
                val future = try {
                    completionService.submit(task)
                } catch (error: RejectedExecutionException) {
                    cancellationHandle.throwIfCancelled()
                    throw MediaSearchRetryableException(
                        completedSourceCount = completedSourceCount,
                        successfulSourceCount = successfulSourceCount,
                        totalSourceCount = tasks.size,
                        timedOut = false
                    ).also { it.initCause(error) }
                }
                session.track(future)
                submittedSourceCount += 1
                return true
            }
            repeat(workerCount) { submitNext() }

            val deadlineNanos = executionControl.sourceDeadline(timeoutMs)
            while (completedSourceCount < tasks.size) {
                cancellationHandle.throwIfCancelled()
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    timedOut = true
                    break
                }
                val future = completionService.poll(
                    min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MS)),
                    TimeUnit.NANOSECONDS
                ) ?: continue
                completedSourceCount += 1
                submitNext()
                val result = try {
                    future.get()
                } catch (error: CancellationException) {
                    cancellationHandle.throwIfCancelled()
                    MediaSearchSourceResult.Failure<T>(error)
                } catch (error: ExecutionException) {
                    MediaSearchSourceResult.Failure<T>(error.cause ?: error)
                }
                when (result) {
                    is MediaSearchSourceResult.Success -> {
                        successfulSourceCount += 1
                        if (result.values.isNotEmpty()) {
                            values.addAll(result.values)
                            val nowNanos = System.nanoTime()
                            if (
                                onPartialResults != null &&
                                nowNanos - lastPartialAtNanos >= TimeUnit.MILLISECONDS.toNanos(partialResultIntervalMs)
                            ) {
                                onPartialResults(values.toList(), false)
                                executionControl.throwIfStopped()
                                lastPartialAtNanos = nowNanos
                            }
                        }
                    }

                    is MediaSearchSourceResult.Failure -> Unit
                }
            }

            cancellationHandle.throwIfCancelled()
            if (retryWhenNoSourceSucceeded && successfulSourceCount == 0) {
                throw MediaSearchRetryableException(
                    completedSourceCount = completedSourceCount,
                    successfulSourceCount = successfulSourceCount,
                    totalSourceCount = tasks.size,
                    timedOut = timedOut
                )
            }
            onPartialResults?.invoke(values.toList(), true)
            if (retryWhenNoSourceSucceeded) {
                executionControl.throwIfStopped()
            } else {
                cancellationHandle.throwIfCancelled()
            }
            return values.toList()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            cancellationHandle.cancel()
            throw CancellationException("Media search caller was interrupted").also {
                it.initCause(error)
            }
        } finally {
            session.cancel()
            cancellationHandle.detach(session)
        }
    }

    private const val CANCELLATION_POLL_MS = 50L

    private fun boundedExecutor(
        threadCount: Int,
        queueCapacity: Int,
        threadPrefix: String
    ): ThreadPoolExecutor {
        return ThreadPoolExecutor(
            threadCount,
            threadCount,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(queueCapacity),
            { runnable ->
                Thread(runnable, "$threadPrefix-${workerIds.incrementAndGet()}").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy()
        )
    }
}

internal class MediaSearchExecutionSession(
    private val executor: ExecutorService
) {
    private val lock = Any()
    private val futures = ArrayList<Future<*>>()
    private var cancelled = false

    fun track(future: Future<*>) {
        val cancelImmediately = synchronized(lock) {
            if (cancelled) {
                true
            } else {
                futures += future
                false
            }
        }
        if (cancelImmediately) {
            future.cancel(true)
            (executor as? ThreadPoolExecutor)?.purge()
        }
    }

    fun cancel() {
        val tracked = synchronized(lock) {
            if (cancelled) {
                emptyList()
            } else {
                cancelled = true
                futures.toList().also { futures.clear() }
            }
        }
        tracked.forEach { future -> future.cancel(true) }
        (executor as? ThreadPoolExecutor)?.purge()
    }
}
