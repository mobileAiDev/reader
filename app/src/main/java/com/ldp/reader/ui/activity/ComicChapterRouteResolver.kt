package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.sourceengine.catalog.ChapterNormalizer
import java.util.Locale

internal object ComicChapterRouteResolver {
    private val normalizer = ChapterNormalizer()

    fun resolve(
        chapters: List<MediaChapterItem>,
        savedRouteId: String,
        savedTitle: String,
        savedSourceIndex: Int
    ): MediaChapterItem? {
        chapters.firstOrNull { it.routeId == savedRouteId }?.let { return it }

        if (savedTitle.isNotBlank()) {
            val normalizedSaved = normalizer.normalize(savedTitle)
            val titleKey = comparableTitle(normalizedSaved.displayTitle)
            unique(chapters.filter { chapter ->
                comparableTitle(normalizer.normalize(chapter.title).displayTitle) == titleKey
            })?.let { return it }

            normalizedSaved.ordinal?.let { ordinal ->
                return unique(chapters.filter { chapter ->
                    normalizer.normalize(chapter.title).ordinal == ordinal
                })
            }
            return null
        }

        return unique(chapters.filter { it.index == savedSourceIndex })
    }

    private fun unique(candidates: List<MediaChapterItem>): MediaChapterItem? {
        return candidates.singleOrNull()
    }

    private fun comparableTitle(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》]+"""), "")
    }
}
