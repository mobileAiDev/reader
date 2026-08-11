package com.ldp.reader.media

import com.ldp.reader.media.MediaSourceBook
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal data class MediaSearchDisplayGateTrace(
    val key: String,
    val title: String,
    val rawCount: Int,
    val uniqueSources: Int,
    val hasCover: Boolean,
    val matchesQuery: Boolean,
    val validated: Boolean,
    val readableCount: Int,
    val readableSources: Int,
    val readableHasCover: Boolean,
    val displayed: Boolean
)

internal object MediaSearchDisplayGate {
    fun displayableBooks(
        kind: ReaderMediaKind,
        keyword: String,
        books: List<MediaSourceBook>,
        maxGroups: Int,
        maxSourcesPerGroup: Int,
        onGroupEvaluated: ((MediaSearchDisplayGateTrace) -> Unit)? = null,
        readableExecutor: ExecutorService? = null,
        readableTimeoutMs: Long? = null,
        cancellationHandle: MediaSearchCancellationHandle? = null,
        readable: (ReaderMediaKind, MediaSourceBook) -> Boolean
    ): List<MediaSourceBook> {
        if (kind == ReaderMediaKind.NOVEL) return books
        val limit = maxGroups * maxSourcesPerGroup
        val groups = books.groupBy { MediaTitleKey.consensusKey(kind, keyword, it.name) }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<MediaSourceBook>>> { entry ->
                    if (isExactQueryGroup(kind, keyword, entry.value)) 0 else 1
                }
            )
        val displayableBooks = ArrayList<MediaSourceBook>()
        var evaluatedGroupCount = 0
        for ((key, group) in groups) {
            val exactQueryGroup = isExactQueryGroup(kind, keyword, group)
            val matches = group.firstOrNull()?.let { matchesQuery(kind, keyword, it) } == true
            val uniqueSources = group.uniqueSourceCount()
            val hasCover = group.any { it.hasUsableCover() }
            val minReadableSources = minReadableSources(kind)
            if (!matches || uniqueSources < minReadableSources) {
                onGroupEvaluated?.invoke(
                    MediaSearchDisplayGateTrace(
                        key = key,
                        title = group.firstOrNull()?.name.orEmpty(),
                        rawCount = group.size,
                        uniqueSources = uniqueSources,
                        hasCover = hasCover,
                        matchesQuery = matches,
                        validated = false,
                        readableCount = 0,
                        readableSources = 0,
                        readableHasCover = false,
                        displayed = false
                    )
                )
                continue
            }
            if (evaluatedGroupCount >= maxGroups.coerceAtLeast(0)) break
            evaluatedGroupCount += 1
            val candidateBooks = group.distinctBy { sourceKey(it) }
                .take(maxSourcesPerGroup)
            val readableBooks = try {
                readableBooksForGroup(
                    kind,
                    candidateBooks,
                    readable,
                    readableExecutor,
                    readableTimeoutMs,
                    cancellationHandle
                )
            } catch (error: MediaSearchRetryableException) {
                return MediaSearchValidatedResultPolicy.recoverDeadlineOrThrow(displayableBooks, error)
            }
            val readableSources = readableBooks.uniqueSourceCount()
            val readableHasCover = readableBooks.any { it.hasUsableCover() }
            val displayed = readableBooks.isNotEmpty()
            onGroupEvaluated?.invoke(
                MediaSearchDisplayGateTrace(
                    key = key,
                    title = group.firstOrNull()?.name.orEmpty(),
                    rawCount = group.size,
                    uniqueSources = uniqueSources,
                    hasCover = hasCover,
                    matchesQuery = matches,
                    validated = true,
                    readableCount = readableBooks.size,
                    readableSources = readableSources,
                    readableHasCover = readableHasCover,
                    displayed = displayed
                )
            )
            if (displayed) {
                displayableBooks += readableBooks
                if (kind == ReaderMediaKind.COMIC && exactQueryGroup) break
                if (displayableBooks.size >= limit) break
            }
        }
        return displayableBooks.take(limit)
    }

    private fun readableBooksForGroup(
        kind: ReaderMediaKind,
        candidateBooks: List<MediaSourceBook>,
        readable: (ReaderMediaKind, MediaSourceBook) -> Boolean,
        readableExecutor: ExecutorService?,
        readableTimeoutMs: Long?,
        cancellationHandle: MediaSearchCancellationHandle?
    ): List<MediaSourceBook> {
        if (kind != ReaderMediaKind.COMIC || readableExecutor == null || candidateBooks.size <= 1) {
            val readableBooks = ArrayList<MediaSourceBook>(candidateBooks.size)
            candidateBooks.forEach { book ->
                val isReadable = try {
                    readable(kind, book)
                } catch (error: MediaSearchRetryableException) {
                    return MediaSearchValidatedResultPolicy.recoverDeadlineOrThrow(readableBooks, error)
                }
                if (isReadable) readableBooks += book
            }
            return readableBooks
        }
        val completionService = ExecutorCompletionService<CompletedReadableBook>(readableExecutor)
        val futures = ArrayList<Future<CompletedReadableBook>>(candidateBooks.size)
        val cancellationRegistrations = ArrayList<MediaSearchCancellationRegistration>(candidateBooks.size)
        return try {
            candidateBooks.forEachIndexed { index, book ->
                val future = try {
                    completionService.submit(
                        Callable {
                            CompletedReadableBook(
                                candidateIndex = index,
                                book = if (readable(kind, book)) book else null
                            )
                        }
                    )
                } catch (error: RejectedExecutionException) {
                    throw MediaSearchRetryableException(
                        completedSourceCount = 0,
                        successfulSourceCount = 0,
                        totalSourceCount = candidateBooks.size,
                        timedOut = false
                    ).also { it.initCause(error) }
                }
                futures += future
                cancellationHandle?.registerCancellation { future.cancel(true) }
                    ?.let(cancellationRegistrations::add)
            }
            val deadline = readableTimeoutMs?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) }
            val readableBooks = ArrayList<CompletedReadableBook>(candidateBooks.size)
            var completedSourceCount = 0
            var successfulSourceCount = 0
            var timedOut = false
            var retryableFailure = false
            while (completedSourceCount < candidateBooks.size) {
                cancellationHandle?.throwIfCancelled()
                val completedFuture = if (deadline == null) {
                    completionService.poll(CANCELLATION_POLL_MS, TimeUnit.MILLISECONDS)
                } else {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) {
                        completionService.poll()
                    } else {
                        completionService.poll(
                            min(remaining, TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MS)),
                            TimeUnit.NANOSECONDS
                        )
                    }
                }
                if (completedFuture == null) {
                    if (deadline != null && deadline - System.nanoTime() <= 0L) {
                        timedOut = true
                        break
                    }
                    continue
                }
                completedSourceCount += 1
                val result = getReadableResult(completedFuture)
                if (result.completedSuccessfully) successfulSourceCount += 1
                retryableFailure = retryableFailure || result.retryable
                timedOut = timedOut || result.timedOut
                result.book?.takeIf { completed -> completed.book != null }
                    ?.let(readableBooks::add)
            }
            cancellationHandle?.throwIfCancelled()
            if (successfulSourceCount == 0 && (timedOut || retryableFailure)) {
                throw MediaSearchRetryableException(
                    completedSourceCount = completedSourceCount,
                    successfulSourceCount = successfulSourceCount,
                    totalSourceCount = candidateBooks.size,
                    timedOut = timedOut
                )
            }
            readableBooks.sortedBy { completed -> completed.candidateIndex }
                .mapNotNull { completed -> completed.book }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Media catalog validation was interrupted").also {
                it.initCause(error)
            }
        } finally {
            cancellationRegistrations.forEach { registration -> registration.close() }
            futures.filterNot { future -> future.isDone }.forEach { future -> future.cancel(true) }
            (readableExecutor as? ThreadPoolExecutor)?.purge()
        }
    }

    private fun getReadableResult(
        future: Future<CompletedReadableBook>
    ): ReadableResult {
        return try {
            ReadableResult(completedSuccessfully = true, book = future.get())
        } catch (error: ExecutionException) {
            when (val cause = error.cause ?: error) {
                is CancellationException -> throw cause
                is MediaSearchRetryableException -> ReadableResult(
                    completedSuccessfully = false,
                    book = null,
                    retryable = true,
                    timedOut = cause.timedOut
                )

                else -> ReadableResult(completedSuccessfully = false, book = null)
            }
        }
    }

    private data class ReadableResult(
        val completedSuccessfully: Boolean,
        val book: CompletedReadableBook?,
        val retryable: Boolean = false,
        val timedOut: Boolean = false
    )

    private data class CompletedReadableBook(
        val candidateIndex: Int,
        val book: MediaSourceBook?
    )

    private const val CANCELLATION_POLL_MS = 50L

    private fun List<MediaSourceBook>.uniqueSourceCount(): Int {
        return map { sourceKey(it) }.toSet().size
    }

    private fun MediaSourceBook.hasUsableCover(): Boolean {
        val cover = coverUrl.trim()
        return cover.startsWith("http://", ignoreCase = true) ||
            cover.startsWith("https://", ignoreCase = true)
    }

    private fun matchesQuery(kind: ReaderMediaKind, keyword: String, book: MediaSourceBook): Boolean {
        return MediaTitleKey.matchesQuery(kind, keyword, book.name)
    }

    private fun isExactQueryGroup(
        kind: ReaderMediaKind,
        keyword: String,
        group: List<MediaSourceBook>
    ): Boolean {
        if (kind != ReaderMediaKind.COMIC) return false
        val queryKey = MediaTitleKey.normalized(keyword)
        return queryKey.isNotBlank() &&
            group.any { book -> MediaTitleKey.normalizedForQuery(book.name, keyword) == queryKey }
    }

    private fun sourceKey(book: MediaSourceBook): String {
        return MediaSourceIdentity.sourceKey(book.source)
    }

    private fun minReadableSources(kind: ReaderMediaKind): Int {
        return when (kind) {
            ReaderMediaKind.AUDIO -> 1
            ReaderMediaKind.COMIC -> 1
            ReaderMediaKind.NOVEL -> 1
        }
    }

}
