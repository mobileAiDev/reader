package com.ldp.reader.widget.page

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

internal data class ReaderPaginationViewportSpec(
    val visibleWidth: Int,
    val visibleHeight: Int
)

internal enum class ReaderPaginationReflowAction {
    NONE,
    RELOAD_CURRENT_SYNCHRONOUSLY,
    RELOAD_CURRENT_IN_BACKGROUND
}

internal data class ReaderPaginationReflowPlan(
    val invalidateStalePagination: Boolean,
    val action: ReaderPaginationReflowAction
)

internal typealias ReaderRelativePageProgress = LocalTxtPageProgress

@Parcelize
internal data class ReaderConfigurationReadingAnchor(
    val chapterOrdinal: Int,
    val chapterLink: String?,
    val chapterTitle: String?,
    val chapterTextOffset: Long?,
    val pageProgress: ReaderRelativePageProgress,
    val localTxtAnchor: LocalTxtReadingAnchor? = null
) : Parcelable

internal object ReaderPaginationReflowPolicy {
    fun hasChanged(
        previous: ReaderPaginationViewportSpec?,
        next: ReaderPaginationViewportSpec
    ): Boolean = previous != null && previous != next

    fun action(
        viewportChanged: Boolean,
        chapterOpen: Boolean,
        catalogPrepared: Boolean,
        backgroundPagination: Boolean,
        currentPageFinished: Boolean
    ): ReaderPaginationReflowAction {
        if (!viewportChanged || !chapterOpen || !catalogPrepared) {
            return ReaderPaginationReflowAction.NONE
        }
        if (backgroundPagination) {
            return ReaderPaginationReflowAction.RELOAD_CURRENT_IN_BACKGROUND
        }
        return if (currentPageFinished) {
            ReaderPaginationReflowAction.RELOAD_CURRENT_SYNCHRONOUSLY
        } else {
            ReaderPaginationReflowAction.NONE
        }
    }

    fun plan(
        viewportChanged: Boolean,
        chapterOpen: Boolean,
        catalogPrepared: Boolean,
        backgroundPagination: Boolean,
        currentPageFinished: Boolean
    ): ReaderPaginationReflowPlan = ReaderPaginationReflowPlan(
        invalidateStalePagination = viewportChanged,
        action = action(
            viewportChanged = viewportChanged,
            chapterOpen = chapterOpen,
            catalogPrepared = catalogPrepared,
            backgroundPagination = backgroundPagination,
            currentPageFinished = currentPageFinished
        )
    )

    fun captureRelativeProgress(
        pagePosition: Int,
        pageCount: Int
    ): ReaderRelativePageProgress = LocalTxtPageProgress.fromPage(pagePosition, pageCount)

    fun restorePagePosition(
        progress: ReaderRelativePageProgress,
        pageCount: Int
    ): Int = progress.resolvePageIndex(pageCount)

    fun captureConfigurationAnchor(
        chapterOrdinal: Int,
        chapterLink: String?,
        chapterTitle: String?,
        pages: List<TxtPage>,
        currentPagePosition: Int,
        localTxtAnchor: LocalTxtReadingAnchor? = null,
        retainedChapterTextOffset: Long? = null
    ): ReaderConfigurationReadingAnchor? {
        if (pages.isEmpty()) return null
        val pagePosition = currentPagePosition.coerceIn(0, pages.lastIndex)
        val pageStartOffset = pages.take(pagePosition).sumOf(::pageTextLength)
        val pageEndOffset = pageStartOffset + pageTextLength(pages[pagePosition])
        val textOffset = if (pages.any { page -> pageTextLength(page) > 0L }) {
            retainedChapterTextOffset
                ?.takeIf { offset -> offset >= pageStartOffset && offset < pageEndOffset }
                ?: pageStartOffset
        } else {
            null
        }
        return ReaderConfigurationReadingAnchor(
            chapterOrdinal = chapterOrdinal,
            chapterLink = chapterLink,
            chapterTitle = chapterTitle,
            chapterTextOffset = textOffset,
            pageProgress = captureRelativeProgress(pagePosition, pages.size),
            localTxtAnchor = localTxtAnchor
        )
    }

    fun restorePagePosition(
        anchor: ReaderConfigurationReadingAnchor,
        pages: List<TxtPage>
    ): Int {
        if (pages.isEmpty()) return 0
        anchor.chapterTextOffset?.let { textOffset ->
            var consumed = 0L
            pages.forEachIndexed { index, page ->
                val next = consumed + pageTextLength(page)
                if (textOffset < next) {
                    return index
                }
                consumed = next
            }
        }
        return restorePagePosition(anchor.pageProgress, pages.size)
    }

    private fun pageTextLength(page: TxtPage): Long = page.lines.orEmpty().sumOf { line ->
        line.length.toLong()
    }
}
