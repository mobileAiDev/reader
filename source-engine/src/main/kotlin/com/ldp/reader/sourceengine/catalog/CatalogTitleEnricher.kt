package com.ldp.reader.sourceengine.catalog

import com.ldp.reader.sourceengine.model.CanonicalChapter
import com.ldp.reader.sourceengine.model.CanonicalChapterList

class CatalogTitleEnricher(
    private val normalizer: ChapterNormalizer = ChapterNormalizer()
) {
    fun isSafeTitleUpgrade(currentTitle: String, candidateTitle: String): Boolean {
        val current = normalizer.normalize(currentTitle)
        val candidate = normalizer.normalize(candidateTitle)
        if (current.ordinal == null || current.ordinal != candidate.ordinal) return false
        val canonicalCurrentTitle = ChapterTitleCanonicalizer.canonicalizeKnownUpdateDecoration(
            current.displayTitle
        )
        if (
            canonicalCurrentTitle != current.displayTitle &&
            candidate.displayTitle == canonicalCurrentTitle
        ) {
            return true
        }
        val ellipsis = TRAILING_ELLIPSIS.find(current.displayTitle) ?: return false
        if (TRAILING_ELLIPSIS.containsMatchIn(candidate.displayTitle)) return false
        val titlePrefix = current.displayTitle.substring(0, ellipsis.range.first).trimEnd()
        return titlePrefix.isNotBlank() &&
            candidate.displayTitle.length > titlePrefix.length &&
            candidate.displayTitle.startsWith(titlePrefix)
    }

    fun hasSafeTitleUpgradeForSameKey(
        currentTitlesByKey: Map<String, String>,
        candidateTitlesByKey: Map<String, String>
    ): Boolean {
        return candidateTitlesByKey.any { (key, candidateTitle) ->
            currentTitlesByKey[key]?.let { currentTitle ->
                isSafeTitleUpgrade(currentTitle, candidateTitle)
            } == true
        }
    }

    fun enrich(
        primaryCatalog: CanonicalChapterList,
        candidateCatalogs: List<CanonicalChapterList>
    ): Result {
        val candidatesByOrdinal = candidateCatalogs
            .asSequence()
            .flatMap { catalog -> catalog.chapters.asSequence() }
            .mapNotNull { chapter -> chapter.ordinal?.let { ordinal -> ordinal to chapter } }
            .groupBy({ it.first }, { it.second })
        var upgradedCount = 0
        val chapters = primaryCatalog.chapters.map { primaryChapter ->
            val canonicalPrimaryTitle = ChapterTitleCanonicalizer.canonicalizeKnownUpdateDecoration(
                primaryChapter.displayTitle
            )
            val canonicalPrimaryChapter = if (canonicalPrimaryTitle == primaryChapter.displayTitle) {
                primaryChapter
            } else {
                primaryChapter.copy(displayTitle = canonicalPrimaryTitle)
            }
            val upgradedTitle = upgradedTitle(
                canonicalPrimaryChapter,
                primaryChapter.ordinal?.let(candidatesByOrdinal::get).orEmpty()
            )
            val displayTitle = upgradedTitle ?: canonicalPrimaryTitle
            if (displayTitle == primaryChapter.displayTitle) {
                primaryChapter
            } else {
                upgradedCount += 1
                primaryChapter.copy(displayTitle = displayTitle)
            }
        }
        return Result(
            catalog = primaryCatalog.copy(chapters = chapters),
            upgradedCount = upgradedCount
        )
    }

    private fun upgradedTitle(
        primaryChapter: CanonicalChapter,
        candidates: List<CanonicalChapter>
    ): String? {
        if (primaryChapter.ordinal == null) return null
        val eligibleTitles = candidates.mapNotNull { candidate ->
            val candidateTitle = canonicalCompletionTitle(candidate.displayTitle)
            candidateTitle.takeIf { title ->
                isSafeTitleUpgrade(primaryChapter.displayTitle, title)
            }
        }
        return eligibleTitles
            .distinct()
            .singleOrNull()
    }

    private fun canonicalCompletionTitle(title: String): String {
        return ChapterTitleCanonicalizer.canonicalizeKnownUpdateDecoration(
            normalizer.normalize(title).displayTitle
        )
    }

    data class Result(
        val catalog: CanonicalChapterList,
        val upgradedCount: Int
    )

    private companion object {
        val TRAILING_ELLIPSIS = Regex("""(?:\.{3,}|…+|．{3,})\s*$""")
    }
}
