package com.ldp.reader.widget.page

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.concurrent.atomic.AtomicLong

/**
 * The live reading position captured before a local TXT is decoded again.
 *
 * Chapter titles are the strongest human-readable anchor. The byte position is the stable fallback
 * when changing the charset also changes which lines are recognized as chapter boundaries.
 */
@Parcelize
internal data class LocalTxtReadingAnchor(
    val chapterOrdinal: Int,
    val chapterTitle: String?,
    val chapterStart: Long,
    val chapterEnd: Long,
    val currentPageIndex: Int,
    val currentPageCount: Int,
    val approximateByteOffset: Long?
) : Parcelable {
    val pageProgress: LocalTxtPageProgress
        get() = LocalTxtPageProgress.fromPage(currentPageIndex, currentPageCount)
}

internal data class LocalTxtResolvedReadingAnchor(
    val chapterOrdinal: Int,
    val pageProgress: LocalTxtPageProgress
)

@Parcelize
internal data class LocalTxtPageProgress(
    val numerator: Long,
    val denominator: Long
) : Parcelable {
    init {
        require(denominator > 0L) { "denominator must be positive" }
    }

    fun resolvePageIndex(pageCount: Int): Int {
        if (pageCount <= 1) return 0
        val boundedNumerator = numerator.coerceIn(0L, denominator - 1L)
        return scaleFloor(pageCount.toLong(), boundedNumerator, denominator)
            .toInt()
            .coerceIn(0, pageCount - 1)
    }

    companion object {
        fun fromPage(pageIndex: Int, pageCount: Int): LocalTxtPageProgress {
            if (pageCount <= 0) return LocalTxtPageProgress(0L, 1L)
            return LocalTxtPageProgress(
                numerator = pageIndex.coerceIn(0, pageCount - 1).toLong(),
                denominator = pageCount.toLong()
            )
        }
    }
}

internal object LocalTxtReadingAnchorResolver {
    fun capture(
        chapters: List<TxtChapter>,
        currentChapterOrdinal: Int,
        currentPageIndex: Int,
        currentPageCount: Int
    ): LocalTxtReadingAnchor? {
        val chapter = chapters.getOrNull(currentChapterOrdinal) ?: return null
        val progress = LocalTxtPageProgress.fromPage(currentPageIndex, currentPageCount)
        val byteLength = (chapter.end - chapter.start).coerceAtLeast(0L)
        val byteOffset = if (byteLength > 0L) {
            chapter.start + scaleFloor(byteLength, progress.numerator, progress.denominator)
        } else {
            chapter.start.takeIf { it >= 0L }
        }
        return LocalTxtReadingAnchor(
            chapterOrdinal = currentChapterOrdinal,
            chapterTitle = chapter.title,
            chapterStart = chapter.start,
            chapterEnd = chapter.end,
            currentPageIndex = currentPageIndex.coerceAtLeast(0),
            currentPageCount = currentPageCount.coerceAtLeast(0),
            approximateByteOffset = byteOffset
        )
    }

    fun resolve(
        anchor: LocalTxtReadingAnchor,
        chapters: List<TxtChapter>
    ): LocalTxtResolvedReadingAnchor? {
        if (chapters.isEmpty()) return null
        val titleMatch = BookRecordChapterResolver.resolve(
            previousOrdinal = anchor.chapterOrdinal,
            savedLink = null,
            savedTitle = anchor.chapterTitle,
            chapters = chapters
        )
        val byteMatch = if (titleMatch == null) {
            anchor.approximateByteOffset?.let { offset ->
                chapters.indexOfFirst { chapter ->
                    chapter.end > chapter.start && offset >= chapter.start && offset < chapter.end
                }.takeIf { it >= 0 }
            }
        } else {
            null
        }
        val targetOrdinal = titleMatch
            ?: byteMatch
            ?: anchor.chapterOrdinal.coerceIn(0, chapters.lastIndex)
        val targetProgress = if (byteMatch != null) {
            val chapter = chapters[targetOrdinal]
            val length = chapter.end - chapter.start
            val offsetWithinChapter = (
                (anchor.approximateByteOffset ?: chapter.start) - chapter.start
                ).coerceIn(0L, (length - 1L).coerceAtLeast(0L))
            LocalTxtPageProgress(offsetWithinChapter, length.coerceAtLeast(1L))
        } else {
            anchor.pageProgress
        }
        return LocalTxtResolvedReadingAnchor(targetOrdinal, targetProgress)
    }
}

internal data class LocalTxtChapterRefreshRequest(
    val generation: Long,
    val readingAnchor: LocalTxtReadingAnchor?
)

/** Keeps cancelled charset reparses from publishing and from replacing a readable live anchor. */
internal class LocalTxtChapterRefreshRequests {
    private val latestGeneration = AtomicLong(0L)
    private var lastReadableEncodingAnchor: LocalTxtReadingAnchor? = null

    @Synchronized
    fun beginRegularRefresh(): LocalTxtChapterRefreshRequest {
        return LocalTxtChapterRefreshRequest(
            generation = latestGeneration.incrementAndGet(),
            readingAnchor = null
        )
    }

    @Synchronized
    fun beginEncodingReload(
        liveAnchor: LocalTxtReadingAnchor?,
        currentPageReadable: Boolean
    ): LocalTxtChapterRefreshRequest {
        if (currentPageReadable || lastReadableEncodingAnchor == null) {
            lastReadableEncodingAnchor = liveAnchor
        }
        return LocalTxtChapterRefreshRequest(
            generation = latestGeneration.incrementAndGet(),
            readingAnchor = lastReadableEncodingAnchor
        )
    }

    fun isLatest(request: LocalTxtChapterRefreshRequest): Boolean {
        return latestGeneration.get() == request.generation
    }

    @Synchronized
    fun invalidate() {
        latestGeneration.incrementAndGet()
        lastReadableEncodingAnchor = null
    }
}

private fun scaleFloor(total: Long, numerator: Long, denominator: Long): Long {
    if (total <= 0L || numerator <= 0L) return 0L
    val boundedNumerator = numerator.coerceAtMost(denominator)
    val whole = total / denominator
    val remainder = total % denominator
    val wholeContribution = whole * boundedNumerator
    val remainderContribution = if (
        boundedNumerator == 0L || remainder <= Long.MAX_VALUE / boundedNumerator
    ) {
        remainder * boundedNumerator / denominator
    } else {
        (remainder.toDouble() * boundedNumerator.toDouble() / denominator.toDouble()).toLong()
    }
    return wholeContribution + remainderContribution
}
