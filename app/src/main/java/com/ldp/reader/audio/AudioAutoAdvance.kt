package com.ldp.reader.audio

import com.ldp.reader.media.MediaRouteChapterSnapshot

object AudioAutoAdvance {
    fun nextEpisode(
        chapters: List<MediaRouteChapterSnapshot>,
        currentChapterRouteId: String
    ): MediaRouteChapterSnapshot? {
        if (currentChapterRouteId.isBlank()) return null
        val currentIndex = chapters.indexOfFirst { it.routeId == currentChapterRouteId }
        if (currentIndex < 0) return null
        return chapters.getOrNull(currentIndex + 1)
    }
}
