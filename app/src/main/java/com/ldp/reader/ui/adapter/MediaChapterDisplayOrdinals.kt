package com.ldp.reader.ui.adapter

import com.ldp.reader.media.MediaChapterItem

/**
 * Separates the number shown to readers from the source-owned chapter index.
 * Source indexes and route ids remain untouched for persistence and routing.
 */
internal class MediaChapterDisplayOrdinals private constructor(
    private val chronologicalPositionByRouteId: Map<String, Int>
) {
    fun forChapter(chapter: MediaChapterItem): Int {
        return chronologicalPositionByRouteId[chapter.routeId] ?: (chapter.index + 1)
    }

    companion object {
        fun sourceIndexes(): MediaChapterDisplayOrdinals {
            return MediaChapterDisplayOrdinals(emptyMap())
        }

        fun chronological(chapters: List<MediaChapterItem>): MediaChapterDisplayOrdinals {
            val positions = LinkedHashMap<String, Int>(chapters.size)
            chapters.forEachIndexed { position, chapter ->
                if (chapter.routeId.isNotBlank() && chapter.routeId !in positions) {
                    positions[chapter.routeId] = position + 1
                }
            }
            return MediaChapterDisplayOrdinals(positions)
        }
    }
}
