package com.ldp.reader.media

object MediaShelfChapterLink {
    fun resolve(kind: ReaderMediaKind, chapterRouteId: String): MediaShelfChapterTarget? {
        if (chapterRouteId.isBlank()) return null
        if (MediaRouteRegistry.kind(chapterRouteId) != kind) return null
        val chapter = MediaRouteRegistry.chapter(chapterRouteId) ?: return null
        val bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId) ?: return null
        return MediaShelfChapterTarget(
            bookRouteId = bookRouteId,
            chapterRouteId = chapterRouteId,
            chapterTitle = MediaDisplayTextCleaner.clean(chapter.name),
            chapterIndex = chapter.index
        )
    }
}

data class MediaShelfChapterTarget(
    val bookRouteId: String,
    val chapterRouteId: String,
    val chapterTitle: String,
    val chapterIndex: Int
)
