package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRequest

internal const val DEFAULT_COMIC_BRIGHTNESS_PERCENT = 80
internal const val MIN_COMIC_BRIGHTNESS_PERCENT = 5
internal const val MAX_COMIC_BRIGHTNESS_PERCENT = 100

internal fun restoredComicBrightnessPercent(savedPercent: Int): Int {
    return savedPercent.takeIf {
        it in MIN_COMIC_BRIGHTNESS_PERCENT..MAX_COMIC_BRIGHTNESS_PERCENT
    } ?: DEFAULT_COMIC_BRIGHTNESS_PERCENT
}

internal data class ComicReaderModeState(
    val horizontalPages: Boolean,
    val night: Boolean,
    val eyeProtection: Boolean,
    val forcedLandscape: Boolean,
    val brightnessPercent: Int,
    val brightnessOverridden: Boolean
)

internal fun restoredComicReaderModeState(
    horizontalPages: Boolean,
    night: Boolean,
    eyeProtection: Boolean,
    forcedLandscape: Boolean,
    savedBrightnessPercent: Int,
    savedBrightnessOverridden: Boolean
): ComicReaderModeState {
    val brightnessIsValid = savedBrightnessPercent in
        MIN_COMIC_BRIGHTNESS_PERCENT..MAX_COMIC_BRIGHTNESS_PERCENT
    return ComicReaderModeState(
        horizontalPages = horizontalPages,
        night = night,
        eyeProtection = eyeProtection,
        forcedLandscape = forcedLandscape,
        brightnessPercent = restoredComicBrightnessPercent(savedBrightnessPercent),
        brightnessOverridden = savedBrightnessOverridden && brightnessIsValid
    )
}

internal data class ComicReaderConfirmedProgress(
    val chapterRouteId: String,
    val pageIndex: Int
) {
    companion object {
        val NONE = ComicReaderConfirmedProgress(chapterRouteId = "", pageIndex = -1)
    }
}

internal object ComicReaderProgressPolicy {
    fun confirmRenderedPage(
        previous: ComicReaderConfirmedProgress,
        chapterRouteId: String,
        pageIndex: Int,
        renderedPageCount: Int
    ): ComicReaderConfirmedProgress {
        if (chapterRouteId.isBlank() || renderedPageCount <= 0) {
            return previous
        }
        return ComicReaderConfirmedProgress(
            chapterRouteId = chapterRouteId,
            pageIndex = pageIndex.coerceIn(0, renderedPageCount - 1)
        )
    }

    fun pageToPersist(
        confirmed: ComicReaderConfirmedProgress,
        chapterRouteId: String
    ): Int? {
        return confirmed.pageIndex.takeIf {
            chapterRouteId.isNotBlank() &&
                confirmed.chapterRouteId == chapterRouteId &&
                it >= 0
        }
    }

    fun loadingVisiblePage(
        confirmed: ComicReaderConfirmedProgress,
        chapterRouteId: String
    ): Int {
        return pageToPersist(confirmed, chapterRouteId) ?: 0
    }

    fun pageAfterSuccessfulRender(
        confirmed: ComicReaderConfirmedProgress,
        chapterRouteId: String,
        persistedPage: Int,
        resetRequested: Boolean,
        renderedPageCount: Int
    ): Int {
        if (renderedPageCount <= 0) return 0
        val candidate = if (resetRequested) {
            0
        } else {
            pageToPersist(confirmed, chapterRouteId) ?: persistedPage
        }
        return candidate.coerceIn(0, renderedPageCount - 1)
    }
}

internal fun isCurrentComicPageRequest(
    requestGeneration: Int,
    currentGeneration: Int,
    position: Int,
    request: MediaRequest,
    currentItems: List<MediaRequest>
): Boolean {
    return requestGeneration == currentGeneration &&
        position in currentItems.indices &&
        currentItems[position] == request
}

internal data class ComicReaderRetryState(
    val visible: Boolean,
    val message: String
)

internal fun comicReaderRetryState(
    chapterResolved: Boolean,
    pageCount: Int,
    currentPageFailed: Boolean
): ComicReaderRetryState {
    return when {
        !chapterResolved -> ComicReaderRetryState(visible = false, message = "")
        pageCount <= 0 -> ComicReaderRetryState(
            visible = true,
            message = "漫画章节加载失败，点击重试"
        )
        currentPageFailed -> ComicReaderRetryState(
            visible = true,
            message = "当前图片加载失败，点击重试"
        )
        else -> ComicReaderRetryState(visible = false, message = "")
    }
}

internal data class ComicReaderChapterPresentation(
    val title: String,
    val catalogPosition: Int,
    val chapterCount: Int,
    val sourceIndex: Int
) {
    fun pageStatus(pagePosition: Int, pageCount: Int): String {
        val safePageCount = pageCount.coerceAtLeast(0)
        val safePage = if (safePageCount > 0) {
            pagePosition.coerceIn(1, safePageCount)
        } else {
            0
        }
        val page = if (safePageCount > 0) "第 $safePage/$safePageCount 页" else "第 0/0 页"
        return if (catalogPosition > 0 && chapterCount > 0) {
            "第 $catalogPosition/$chapterCount 话 · $page"
        } else {
            page
        }
    }

    companion object {
        val EMPTY = ComicReaderChapterPresentation(
            title = "",
            catalogPosition = 0,
            chapterCount = 0,
            sourceIndex = -1
        )
    }
}

internal fun comicReaderChapterPresentation(
    chronologicalChapters: List<MediaChapterItem>,
    chapterRouteId: String,
    fallbackTitle: String,
    fallbackSourceIndex: Int
): ComicReaderChapterPresentation {
    val routePosition = chronologicalChapters.indexOfFirst { chapter ->
        chapter.routeId == chapterRouteId
    }
    val sourceIndexPosition = chronologicalChapters.indexOfFirst { chapter ->
        chapter.index == fallbackSourceIndex
    }
    // A source-local numeric index is not a stable chapter identity. Once we have a
    // meaningful saved title, an unmatched route must remain unmatched instead of
    // silently borrowing an unrelated chapter from another source at the same index.
    val position = routePosition.takeIf { it >= 0 }
        ?: sourceIndexPosition.takeIf { it >= 0 && fallbackTitle.isBlank() }
    val chapter = position?.let(chronologicalChapters::get)
    val canonicalTitle = chapter?.title?.takeIf { it.isNotBlank() }
        ?: fallbackTitle.takeIf { it.isNotBlank() }
        ?: position?.let { "第 ${it + 1} 话" }
        .orEmpty()
    return ComicReaderChapterPresentation(
        title = canonicalTitle,
        catalogPosition = position?.plus(1) ?: 0,
        chapterCount = chronologicalChapters.size,
        sourceIndex = chapter?.index ?: fallbackSourceIndex
    )
}
