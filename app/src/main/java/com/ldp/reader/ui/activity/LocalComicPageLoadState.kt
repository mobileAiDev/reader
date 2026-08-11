package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaRequest

internal data class LocalComicPageLoadState(
    val loadedPagePositions: Set<Int>,
    val failedPagePositions: Set<Int>
) {
    val hasReadablePage: Boolean
        get() = loadedPagePositions.isNotEmpty()

    fun record(
        requestGeneration: Int,
        currentGeneration: Int,
        position: Int,
        request: MediaRequest,
        currentItems: List<MediaRequest>,
        success: Boolean
    ): LocalComicPageLoadState {
        if (
            !isCurrentComicPageRequest(
                requestGeneration = requestGeneration,
                currentGeneration = currentGeneration,
                position = position,
                request = request,
                currentItems = currentItems
            )
        ) {
            return this
        }
        return if (success) {
            copy(
                loadedPagePositions = loadedPagePositions + position,
                failedPagePositions = failedPagePositions - position
            )
        } else {
            copy(
                loadedPagePositions = loadedPagePositions - position,
                failedPagePositions = failedPagePositions + position
            )
        }
    }

    fun canPersist(pageIndex: Int, pageCount: Int): Boolean {
        return pageIndex in 0 until pageCount && loadedPagePositions.contains(pageIndex)
    }

    fun retryState(visiblePageIndex: Int, pageCount: Int): LocalComicPageRetryState {
        if (pageCount <= 0) return LocalComicPageRetryState.HIDDEN
        val allPagesFailed = (0 until pageCount).all(failedPagePositions::contains)
        return when {
            allPagesFailed -> LocalComicPageRetryState(
                visible = true,
                allPagesFailed = true,
                message = "漫画图片均无法显示，点击重试"
            )
            failedPagePositions.contains(visiblePageIndex) -> LocalComicPageRetryState(
                visible = true,
                allPagesFailed = false,
                message = "当前图片无法显示，点击重试"
            )
            else -> LocalComicPageRetryState.HIDDEN
        }
    }

    companion object {
        val EMPTY = LocalComicPageLoadState(
            loadedPagePositions = emptySet(),
            failedPagePositions = emptySet()
        )
    }
}

internal data class LocalComicPageRetryState(
    val visible: Boolean,
    val allPagesFailed: Boolean,
    val message: String
) {
    companion object {
        val HIDDEN = LocalComicPageRetryState(
            visible = false,
            allPagesFailed = false,
            message = ""
        )
    }
}
