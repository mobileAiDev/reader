package com.ldp.reader.media

/** Keeps a persisted chapter attached to the source that actually owns its route. */
internal object MediaShelfRouteSnapshotPolicy {
    fun forChapter(
        currentSnapshot: MediaRouteSnapshot?,
        persistedSnapshot: MediaRouteSnapshot,
        chapterRouteId: String
    ): MediaRouteSnapshot {
        val owner = when {
            currentSnapshot.ownsChapter(chapterRouteId) -> currentSnapshot
            persistedSnapshot.ownsChapter(chapterRouteId) -> persistedSnapshot
            else -> null
        }
        return owner?.let { MediaRouteRegistry.compactSnapshot(it, chapterRouteId) }
            ?: persistedSnapshot
    }

    private fun MediaRouteSnapshot?.ownsChapter(chapterRouteId: String): Boolean {
        if (this == null) return false
        val chapter = chapters.firstOrNull { it.routeId == chapterRouteId }?.chapter ?: return false
        return chapter.source.sourceType == book.source.sourceType &&
            chapter.source.sourceUrl == book.source.sourceUrl &&
            chapter.source.sourceName == book.source.sourceName &&
            chapter.book.bookUrl == book.bookUrl
    }
}
