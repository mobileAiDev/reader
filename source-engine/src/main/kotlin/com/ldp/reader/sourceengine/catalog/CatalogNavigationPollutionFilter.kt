package com.ldp.reader.sourceengine.catalog

import com.ldp.reader.sourceengine.model.NormalizedChapterTitle

/**
 * Removes chapter-list navigation controls only when the surrounding catalog proves
 * that they are not real chapters. This is shared by freshly parsed source catalogs
 * and persisted app catalogs so the migration rule cannot drift from parsing.
 */
class CatalogNavigationPollutionFilter(
    private val normalizer: ChapterNormalizer = ChapterNormalizer()
) {
    data class Result<T>(
        val entries: List<T>,
        val retainedOriginalIndices: List<Int>,
        val removedOriginalIndices: List<Int>
    )

    fun <T> filter(entries: List<T>, titleOf: (T) -> String): Result<T> {
        return filter(
            entries = entries,
            titleOf = titleOf,
            normalizedTitleOf = { entry -> normalizer.normalize(titleOf(entry)) }
        )
    }

    internal fun <T> filter(
        entries: List<T>,
        titleOf: (T) -> String,
        normalizedTitleOf: (T) -> NormalizedChapterTitle
    ): Result<T> {
        if (entries.size < MIN_CATALOG_SIZE) return unchanged(entries)

        val normalizedTitles = entries.map(normalizedTitleOf)
        val navigationOnly = entries.map { entry -> isNavigationOnlyTitle(titleOf(entry)) }
        val drop = BooleanArray(entries.size)
        var index = 0
        while (index < entries.size) {
            if (!navigationOnly[index]) {
                index++
                continue
            }
            val blockStart = index
            while (index < entries.size && navigationOnly[index]) {
                index++
            }
            val blockEnd = index
            val blockSize = blockEnd - blockStart
            if (blockSize !in 1..MAX_NAVIGATION_BLOCK) continue
            if (
                blockStart < CONTEXT_CHAPTERS ||
                blockEnd + CONTEXT_CHAPTERS > entries.size
            ) {
                continue
            }
            if (
                !isAscendingOrdinalRun(
                    normalizedTitles,
                    blockStart - CONTEXT_CHAPTERS,
                    CONTEXT_CHAPTERS
                ) ||
                !isAscendingOrdinalRun(
                    normalizedTitles,
                    blockEnd,
                    CONTEXT_CHAPTERS
                )
            ) {
                continue
            }
            val previousOrdinal = normalizedTitles[blockStart - 1].ordinal ?: continue
            val nextOrdinal = normalizedTitles[blockEnd].ordinal ?: continue
            if (nextOrdinal != previousOrdinal + 1) continue
            val candidateOrdinals = normalizedTitles.subList(blockStart, blockEnd)
                .mapNotNull { title -> title.ordinal }
            if (
                candidateOrdinals.size != blockSize ||
                candidateOrdinals.any { ordinal -> ordinal - nextOrdinal < MIN_NAVIGATION_JUMP }
            ) {
                continue
            }
            val hasCanonicalDuplicates = candidateOrdinals.distinct().all { ordinal ->
                hasLaterCanonicalOrdinal(
                    normalizedTitles = normalizedTitles,
                    navigationOnly = navigationOnly,
                    fromIndex = blockEnd,
                    ordinal = ordinal
                )
            }
            val hasExplicitLatestNavigation = entries.subList(blockStart, blockEnd)
                .any { entry -> LATEST_NAVIGATION_MARKER.containsMatchIn(titleOf(entry)) }
            if (!hasCanonicalDuplicates && !hasExplicitLatestNavigation) continue
            for (dropIndex in blockStart until blockEnd) {
                drop[dropIndex] = true
            }
        }

        val retainedOriginalIndices = entries.indices.filterNot { entryIndex -> drop[entryIndex] }
        val removedOriginalIndices = entries.indices.filter { entryIndex -> drop[entryIndex] }
        return Result(
            entries = retainedOriginalIndices.map { entryIndex -> entries[entryIndex] },
            retainedOriginalIndices = retainedOriginalIndices,
            removedOriginalIndices = removedOriginalIndices
        )
    }

    private fun <T> unchanged(entries: List<T>): Result<T> {
        return Result(
            entries = entries,
            retainedOriginalIndices = entries.indices.toList(),
            removedOriginalIndices = emptyList()
        )
    }

    private fun isAscendingOrdinalRun(
        titles: List<NormalizedChapterTitle>,
        start: Int,
        length: Int
    ): Boolean {
        if (start < 0 || length < 2 || start + length > titles.size) return false
        val ordinals = titles.subList(start, start + length).map { title -> title.ordinal ?: return false }
        return ordinals.zipWithNext().all { (current, next) -> next == current + 1 }
    }

    private fun hasLaterCanonicalOrdinal(
        normalizedTitles: List<NormalizedChapterTitle>,
        navigationOnly: List<Boolean>,
        fromIndex: Int,
        ordinal: Int
    ): Boolean {
        for (candidateIndex in (fromIndex + 1) until normalizedTitles.lastIndex) {
            if (navigationOnly[candidateIndex] || normalizedTitles[candidateIndex].ordinal != ordinal) continue
            if (navigationOnly[candidateIndex - 1] || navigationOnly[candidateIndex + 1]) continue
            if (
                normalizedTitles[candidateIndex - 1].ordinal == ordinal - 1 &&
                normalizedTitles[candidateIndex + 1].ordinal == ordinal + 1
            ) {
                return true
            }
        }
        return false
    }

    private fun isNavigationOnlyTitle(title: String): Boolean {
        return NAVIGATION_ONLY_TITLE_PATTERNS.any { pattern -> pattern.matches(title.trim()) }
    }

    private companion object {
        const val MIN_CATALOG_SIZE = 20
        const val MAX_NAVIGATION_BLOCK = 3
        const val CONTEXT_CHAPTERS = 3
        const val MIN_NAVIGATION_JUMP = 20
        val LATEST_NAVIGATION_MARKER = Regex("""[（(]\s*最新\s*[)）]""")
        val NAVIGATION_ONLY_TITLE_PATTERNS = listOf(
            Regex(
                """^\s*[0-9０-９]{1,6}\s*[.．、]\s*第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*[章节回话]\s*(?:[（(]\s*最新\s*[)）])?\s*$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^\s*(?:vip|正文|最新章节?)\s*[.．、:：]?\s*第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*[章节回话]\s*[（(]\s*最新\s*[)）]\s*$""",
                RegexOption.IGNORE_CASE
            )
        )
    }
}
