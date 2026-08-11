package com.ldp.reader.audio

import com.ldp.reader.media.MediaRouteChapterSnapshot

object AudioAutoAdvance {
    fun nextEpisode(
        chapters: List<MediaRouteChapterSnapshot>,
        currentChapterRouteId: String
    ): MediaRouteChapterSnapshot? {
        return remainingEpisodes(
            chapters = chapters,
            currentChapterRouteId = currentChapterRouteId,
            maxCandidates = 1
        ).firstOrNull()
    }

    fun remainingEpisodes(
        chapters: List<MediaRouteChapterSnapshot>,
        currentChapterRouteId: String,
        maxCandidates: Int
    ): List<MediaRouteChapterSnapshot> {
        if (currentChapterRouteId.isBlank() || maxCandidates <= 0) return emptyList()
        val chronological = AudioChapterOrder.chronologicalSnapshots(chapters)
        val currentIndex = chronological.indexOfFirst { it.routeId == currentChapterRouteId }
        if (currentIndex < 0) return emptyList()
        return chronological
            .asSequence()
            .drop(currentIndex + 1)
            .filter { it.routeId.isNotBlank() }
            .distinctBy { it.routeId }
            .take(maxCandidates)
            .toList()
    }
}

object AudioAutoAdvanceResolver {
    fun nextEpisode(
        chapters: List<MediaRouteChapterSnapshot>,
        currentChapterRouteId: String,
        refreshCatalog: () -> List<MediaRouteChapterSnapshot>
    ): MediaRouteChapterSnapshot? {
        AudioAutoAdvance.nextEpisode(chapters, currentChapterRouteId)?.let { return it }
        return AudioAutoAdvance.nextEpisode(refreshCatalog(), currentChapterRouteId)
    }

    fun remainingEpisodes(
        chapters: List<MediaRouteChapterSnapshot>,
        currentChapterRouteId: String,
        maxCandidates: Int,
        refreshCatalog: () -> List<MediaRouteChapterSnapshot>
    ): List<MediaRouteChapterSnapshot> {
        val available = AudioAutoAdvance.remainingEpisodes(
            chapters = chapters,
            currentChapterRouteId = currentChapterRouteId,
            maxCandidates = maxCandidates
        )
        if (available.isNotEmpty()) return available
        return AudioAutoAdvance.remainingEpisodes(
            chapters = refreshCatalog(),
            currentChapterRouteId = currentChapterRouteId,
            maxCandidates = maxCandidates
        )
    }
}
