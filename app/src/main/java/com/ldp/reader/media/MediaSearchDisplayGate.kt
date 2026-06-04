package com.ldp.reader.media

import com.ldp.reader.media.MediaSourceBook

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
        readable: (ReaderMediaKind, MediaSourceBook) -> Boolean
    ): List<MediaSourceBook> {
        if (kind == ReaderMediaKind.NOVEL) return books
        return books.groupBy { MediaTitleKey.consensusKey(kind, keyword, it.name) }
            .asSequence()
            .flatMap { (key, group) ->
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
                    return@flatMap emptySequence()
                }
                val candidateBooks = group.distinctBy { sourceKey(it) }
                    .take(maxSourcesPerGroup)
                val readableBooks = candidateBooks.filter { book -> readable(kind, book) }
                val readableSources = readableBooks.uniqueSourceCount()
                val readableHasCover = readableBooks.any { it.hasUsableCover() }
                val displayBooks = readableBooks
                val displayed = displayBooks.isNotEmpty()
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
                displayBooks.takeIf { displayed }.orEmpty().asSequence()
            }
            .take(maxGroups * maxSourcesPerGroup)
            .toList()
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
