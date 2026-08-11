package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaChapterItem

internal object ComicReadingResume {
    fun chapter(
        chapters: List<MediaChapterItem>,
        savedChapterRouteId: String?,
        savedChapterIndex: Int?,
        savedChapterTitle: String? = null
    ): MediaChapterItem? {
        val routeId = savedChapterRouteId.orEmpty()
        val sourceIndex = savedChapterIndex ?: -1
        val title = savedChapterTitle.orEmpty()
        val hasSavedIdentity = routeId.isNotBlank() || sourceIndex >= 0
        if (!hasSavedIdentity) return chapters.firstOrNull()

        return ComicChapterRouteResolver.resolve(
            chapters = chapters,
            savedRouteId = routeId,
            savedTitle = title,
            savedSourceIndex = sourceIndex
        ) ?: routeId.takeIf { it.isNotBlank() && title.isNotBlank() }?.let {
            // The refreshed source has no equivalent chapter. The restored shelf snapshot still
            // owns this exact route, so continuing it is safer than opening an unrelated chapter.
            MediaChapterItem(routeId = it, title = title, index = sourceIndex)
        }
    }

    fun hasSavedChapter(
        chapters: List<MediaChapterItem>,
        savedChapterRouteId: String?,
        savedChapterIndex: Int?,
        savedChapterTitle: String? = null
    ): Boolean {
        val hasSavedIdentity = !savedChapterRouteId.isNullOrBlank() || (savedChapterIndex ?: -1) >= 0
        if (!hasSavedIdentity) return false
        return chapter(
            chapters = chapters,
            savedChapterRouteId = savedChapterRouteId,
            savedChapterIndex = savedChapterIndex,
            savedChapterTitle = savedChapterTitle
        ) != null
    }
}
