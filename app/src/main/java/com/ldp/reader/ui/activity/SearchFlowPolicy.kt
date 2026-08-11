package com.ldp.reader.ui.activity

import com.ldp.reader.media.ReaderMediaKind

internal data class SearchInputChangePlan(
    val normalizedQuery: String,
    val activeSubmittedQuery: String,
    val keywordLookupQuery: String?
)

internal data class SearchRestorationPlan(
    val kind: ReaderMediaKind,
    val inputQuery: String,
    val submittedQuery: String,
    val shouldRestartSearch: Boolean
)

internal data class SearchLoadingPresentation(
    val message: String,
    val percent: Int?
)

internal data class SearchKindTabState(
    val kind: ReaderMediaKind,
    val selected: Boolean
)

internal enum class NovelResultClickAction {
    OPEN,
    WAIT_FOR_READING_CONTENT
}

internal object SearchFlowPolicy {
    fun tabStates(activeKind: ReaderMediaKind): List<SearchKindTabState> {
        return ReaderMediaKind.entries.map { kind ->
            SearchKindTabState(kind = kind, selected = kind == activeKind)
        }
    }

    fun novelResultClickAction(readingReady: Boolean): NovelResultClickAction {
        return if (readingReady) {
            NovelResultClickAction.OPEN
        } else {
            NovelResultClickAction.WAIT_FOR_READING_CONTENT
        }
    }

    fun novelProgressPhase(
        resultCount: Int,
        final: Boolean,
        hasReadableResult: Boolean,
        tierPreparing: Boolean,
        tierReady: Boolean,
        phaseOverride: BookSearchProgressPhase?
    ): BookSearchProgressPhase {
        return when {
            resultCount <= 0 && final -> BookSearchProgressPhase.EMPTY
            resultCount <= 0 -> BookSearchProgressPhase.SEARCHING
            hasReadableResult && final -> BookSearchProgressPhase.FINISHED
            hasReadableResult -> BookSearchProgressPhase.READY
            phaseOverride != null -> phaseOverride
            tierPreparing && !tierReady -> BookSearchProgressPhase.PREPARING_READING
            final -> BookSearchProgressPhase.FINISHED
            tierReady -> BookSearchProgressPhase.READY
            else -> BookSearchProgressPhase.FOUND_SEARCHING
        }
    }

    fun novelProgressMessage(progress: BookSearchProgressState): String {
        return when (progress.phase) {
            BookSearchProgressPhase.FOUND_SEARCHING ->
                "已找到 ${progress.resultCount} 本，仍在查找更多结果"
            BookSearchProgressPhase.PREPARING_READING,
            BookSearchProgressPhase.CHECKING_READING_SOURCE ->
                "已找到 ${progress.resultCount} 本，正在确认是否可以阅读"
            BookSearchProgressPhase.WAITING_READING_SOURCE ->
                "已找到 ${progress.resultCount} 本，仍在查找可阅读内容"
            BookSearchProgressPhase.READY ->
                "已找到可阅读内容，仍在整理更多结果"
            BookSearchProgressPhase.FINISHED -> "搜索完成"
            BookSearchProgressPhase.SEARCHING,
            BookSearchProgressPhase.EMPTY,
            BookSearchProgressPhase.ERROR -> ""
        }
    }

    fun loadingPresentation(
        kind: ReaderMediaKind,
        elapsedMs: Long,
        resultCount: Int,
        finished: Boolean
    ): SearchLoadingPresentation {
        val safeResultCount = resultCount.coerceAtLeast(0)
        if (finished) {
            val message = if (safeResultCount > 0) {
                "搜索完成，已找到 ${resultCountText(kind, safeResultCount)}"
            } else {
                "搜索完成，暂未找到可用结果"
            }
            return SearchLoadingPresentation(message = message, percent = 100)
        }
        if (safeResultCount > 0) {
            return SearchLoadingPresentation(
                message = "已找到 ${resultCountText(kind, safeResultCount)}，正在继续搜索和整理",
                percent = null
            )
        }
        val stage = if (elapsedMs < 4_000) "搜索已开始" else "搜索仍在进行"
        val message = "$stage，正在查找${searchTarget(kind)}"
        return SearchLoadingPresentation(message = message, percent = null)
    }

    private fun searchTarget(kind: ReaderMediaKind): String {
        return when (kind) {
            ReaderMediaKind.NOVEL -> "可阅读内容"
            ReaderMediaKind.COMIC -> "可阅读漫画"
            ReaderMediaKind.AUDIO -> "可播放内容"
        }
    }

    fun resultCountText(kind: ReaderMediaKind, resultCount: Int): String {
        val unit = when (kind) {
            ReaderMediaKind.NOVEL -> "本"
            ReaderMediaKind.COMIC -> "部"
            ReaderMediaKind.AUDIO -> "个"
        }
        return "$resultCount $unit"
    }

    fun restorationPlan(
        savedKind: String?,
        rawInputQuery: String?,
        rawSubmittedQuery: String?,
        searchWasSubmitted: Boolean,
        hasRetainedNovelSession: Boolean
    ): SearchRestorationPlan {
        val kind = savedKind
            ?.let { ReaderMediaKind.fromSeedKey(it) }
            ?: ReaderMediaKind.NOVEL
        val inputQuery = rawInputQuery?.trim().orEmpty()
        val submittedQuery = rawSubmittedQuery
            ?.trim()
            .orEmpty()
            .takeIf { searchWasSubmitted && it.isNotEmpty() }
            .orEmpty()
        return SearchRestorationPlan(
            kind = kind,
            inputQuery = inputQuery,
            submittedQuery = submittedQuery,
            shouldRestartSearch = submittedQuery.isNotEmpty() &&
                (kind != ReaderMediaKind.NOVEL || !hasRetainedNovelSession)
        )
    }

    fun inputChange(kind: ReaderMediaKind, rawQuery: String?): SearchInputChangePlan {
        val query = rawQuery?.trim().orEmpty()
        if (query.isEmpty()) {
            return SearchInputChangePlan(
                normalizedQuery = "",
                activeSubmittedQuery = "",
                keywordLookupQuery = null
            )
        }
        return if (kind == ReaderMediaKind.NOVEL) {
            SearchInputChangePlan(
                normalizedQuery = query,
                activeSubmittedQuery = "",
                keywordLookupQuery = query
            )
        } else {
            SearchInputChangePlan(
                normalizedQuery = query,
                activeSubmittedQuery = query,
                keywordLookupQuery = null
            )
        }
    }

    fun queryToRestart(
        currentKind: ReaderMediaKind,
        selectedKind: ReaderMediaKind,
        rawQuery: String?
    ): String? {
        if (currentKind == selectedKind) return null
        return rawQuery?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun isCurrentMediaResponse(
        activeKind: ReaderMediaKind,
        responseKind: ReaderMediaKind,
        activeToken: Int,
        responseToken: Int
    ): Boolean {
        return activeKind == responseKind && activeToken == responseToken
    }

    fun isCurrentKeywordResponse(
        activeKind: ReaderMediaKind,
        activeSubmittedQuery: String,
        rawInputQuery: String?,
        responseQuery: String
    ): Boolean {
        return activeKind == ReaderMediaKind.NOVEL &&
            activeSubmittedQuery.isBlank() &&
            rawInputQuery?.trim().orEmpty() == responseQuery.trim()
    }
}
