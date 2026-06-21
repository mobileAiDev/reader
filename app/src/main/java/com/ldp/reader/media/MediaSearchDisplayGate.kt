package com.ldp.reader.media

import com.ldp.reader.media.MediaSourceBook
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

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
        readableExecutor: Executor? = null,
        readableTimeoutMs: Long? = null,
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
            val candidateBooks = group.distinctBy { sourceKey(it) }
                .take(maxSourcesPerGroup)
            val readableBooks = readableBooksForGroup(kind, candidateBooks, readable, readableExecutor, readableTimeoutMs)
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
        readableExecutor: Executor?,
        readableTimeoutMs: Long?
    ): List<MediaSourceBook> {
        if (kind != ReaderMediaKind.COMIC || readableExecutor == null || candidateBooks.size <= 1) {
            return candidateBooks.filter { book -> readable(kind, book) }
        }
        val futures = candidateBooks.map { book ->
            CompletableFuture.supplyAsync(
                Supplier {
                    if (readable(kind, book)) book else null
                },
                readableExecutor
            )
        }
        val deadline = readableTimeoutMs?.let { System.currentTimeMillis() + it }
        return futures.mapNotNull { future ->
            runCatching {
                if (deadline == null) {
                    future.get()
                } else {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) null else future.get(remaining, TimeUnit.MILLISECONDS)
                }
            }.getOrNull()
        }.also {
            futures.filterNot { future -> future.isDone }.forEach { future -> future.cancel(true) }
        }
    }

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
