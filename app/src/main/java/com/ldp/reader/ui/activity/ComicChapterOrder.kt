package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.sourceengine.catalog.ChapterNormalizer
import java.util.Locale

internal object ComicChapterOrder {
    private val normalizer = ChapterNormalizer()

    enum class Direction {
        ASCENDING,
        DESCENDING,
        UNKNOWN
    }

    data class Result(
        val chronological: List<MediaChapterItem>,
        val first: MediaChapterItem?,
        val latest: MediaChapterItem?,
        val sourceDirection: Direction,
        val evidence: String
    )

    fun resolve(chapters: List<MediaChapterItem>, latestTitle: String?): Result {
        if (chapters.isEmpty()) {
            return Result(emptyList(), null, null, Direction.UNKNOWN, "empty")
        }
        if (chapters.size == 1) {
            return Result(chapters, chapters.first(), chapters.first(), Direction.UNKNOWN, "single")
        }

        val ordinalEvidence = ordinalDirection(chapters)
        val latestByMetadata = latestChapter(chapters, latestTitle, ordinalEvidence.direction)
        val metadataDirection = when (chapters.indexOf(latestByMetadata)) {
            0 -> Direction.DESCENDING
            chapters.lastIndex -> Direction.ASCENDING
            else -> Direction.UNKNOWN
        }
        val direction = if (metadataDirection != Direction.UNKNOWN) {
            metadataDirection
        } else {
            ordinalEvidence.direction
        }
        val chronological = if (direction == Direction.DESCENDING) chapters.asReversed() else chapters
        val latest = latestByMetadata ?: when (direction) {
            Direction.DESCENDING -> chapters.first()
            Direction.ASCENDING, Direction.UNKNOWN -> chapters.last()
        }
        val evidence = when {
            metadataDirection == Direction.DESCENDING -> "latest_metadata_at_start"
            metadataDirection == Direction.ASCENDING -> "latest_metadata_at_end"
            direction != Direction.UNKNOWN -> ordinalEvidence.evidence
            latestByMetadata != null -> "latest_metadata_interior"
            else -> "source_order_fallback"
        }
        return Result(
            chronological = chronological,
            first = chronological.firstOrNull(),
            latest = latest,
            sourceDirection = direction,
            evidence = evidence
        )
    }

    private fun latestChapter(
        chapters: List<MediaChapterItem>,
        latestTitle: String?,
        ordinalDirection: Direction
    ): MediaChapterItem? {
        if (latestTitle.isNullOrBlank()) return null
        val latestNormalized = normalizer.normalize(latestTitle)
        val latestKey = comparableTitle(latestNormalized.displayTitle)
        val exactCandidates = if (latestKey.isBlank()) {
            emptyList()
        } else {
            chapters.filter { chapter ->
                comparableTitle(normalizer.normalize(chapter.title).displayTitle) == latestKey
            }
        }
        selectLatestCandidate(exactCandidates, ordinalDirection)?.let { return it }

        val ordinal = latestNormalized.ordinal ?: return null
        val candidates = chapters.filter { chapter -> normalizer.normalize(chapter.title).ordinal == ordinal }
        return selectLatestCandidate(candidates, ordinalDirection)
    }

    private fun selectLatestCandidate(
        candidates: List<MediaChapterItem>,
        ordinalDirection: Direction
    ): MediaChapterItem? {
        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.first()
            ordinalDirection == Direction.DESCENDING -> candidates.first()
            else -> candidates.last()
        }
    }

    private fun ordinalDirection(chapters: List<MediaChapterItem>): OrdinalEvidence {
        val ordinals = chapters.mapNotNull { chapter -> normalizer.normalize(chapter.title).ordinal }
        if (ordinals.size < 2) return OrdinalEvidence(Direction.UNKNOWN, "ordinal_insufficient")
        var ascending = 0
        var descending = 0
        ordinals.zipWithNext().forEach { (current, next) ->
            when {
                next > current -> ascending++
                next < current -> descending++
            }
        }
        val direction = when {
            ascending == 0 && descending > 0 -> Direction.DESCENDING
            descending == 0 && ascending > 0 -> Direction.ASCENDING
            ascending >= descending * DIRECTION_DOMINANCE_RATIO -> Direction.ASCENDING
            descending >= ascending * DIRECTION_DOMINANCE_RATIO -> Direction.DESCENDING
            else -> Direction.UNKNOWN
        }
        return OrdinalEvidence(
            direction,
            "ordinal_${direction.name.lowercase(Locale.ROOT)}_${ascending}_$descending"
        )
    }

    private fun comparableTitle(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》]+"""), "")
    }

    private data class OrdinalEvidence(
        val direction: Direction,
        val evidence: String
    )

    private const val DIRECTION_DOMINANCE_RATIO = 2
}
