package com.ldp.reader.audio

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRouteChapterSnapshot
import com.ldp.reader.sourceengine.catalog.ChapterNormalizer

object AudioChapterOrder {
    private val chapterNormalizer = ChapterNormalizer()

    fun chronological(episodes: List<MediaChapterItem>): List<MediaChapterItem> {
        return chronologicalByTitle(episodes) { it.title }
    }

    fun chronologicalSnapshots(
        chapters: List<MediaRouteChapterSnapshot>
    ): List<MediaRouteChapterSnapshot> {
        return chronologicalByTitle(chapters) { it.chapter.name }
    }

    private fun <T> chronologicalByTitle(
        values: List<T>,
        title: (T) -> String
    ): List<T> {
        if (values.size < 2) return values
        val orderedEvidence = values.mapIndexedNotNull { position, value ->
            episodeOrdinal(title(value))?.let { ordinal ->
                OrdinalEvidence(position, ordinal, value)
            }
        }
        if (orderedEvidence.size < 2) return values
        if (orderedEvidence.map { it.ordinal }.distinct().size != orderedEvidence.size) {
            return values
        }

        var ascending = 0
        var descending = 0
        orderedEvidence.zipWithNext().forEach { (current, next) ->
            when {
                next.ordinal > current.ordinal -> ascending += 1
                next.ordinal < current.ordinal -> descending += 1
            }
        }
        if (descending == 0) return values
        if (ascending == 0) return values.asReversed()

        val chronologicalValues = orderedEvidence
            .sortedWith(compareBy<OrdinalEvidence<T>> { it.ordinal }.thenBy { it.position })
            .map { it.value }
        val result = values.toMutableList()
        orderedEvidence.map { it.position }.zip(chronologicalValues).forEach { (position, value) ->
            result[position] = value
        }
        return result
    }

    private fun episodeOrdinal(title: String): Int? {
        val audioMatch = AUDIO_ORDINAL_PATTERNS
            .asSequence()
            .mapNotNull { pattern -> pattern.find(title) }
            .firstOrNull()
        val normalizedAudioUnit = audioMatch?.let { match ->
            title.replaceRange(match.range, "第${match.groupValues[1]}章")
        } ?: title
        return chapterNormalizer.normalize(normalizedAudioUnit).ordinal
    }

    private data class OrdinalEvidence<T>(
        val position: Int,
        val ordinal: Int,
        val value: T
    )

    private const val ORDINAL_TOKEN =
        "[0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+"
    private val AUDIO_ORDINAL_PATTERNS = listOf(
        Regex("""第\s*($ORDINAL_TOKEN)\s*[集期]"""),
        Regex("""^\s*($ORDINAL_TOKEN)\s*[集期]""")
    )
}
