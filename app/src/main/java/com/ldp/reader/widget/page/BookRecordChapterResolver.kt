package com.ldp.reader.widget.page

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal object BookRecordChapterResolver {
    fun resolve(
        previousOrdinal: Int,
        savedLink: String?,
        savedTitle: String?,
        chapters: List<TxtChapter>
    ): Int? {
        return resolve(
            previousOrdinal = previousOrdinal,
            savedLink = savedLink,
            savedTitle = savedTitle,
            chapters = chapters,
            linkOf = { chapter -> chapter.link },
            titleOf = { chapter -> chapter.title }
        )
    }

    fun <T> resolve(
        previousOrdinal: Int,
        savedLink: String?,
        savedTitle: String?,
        chapters: List<T>,
        linkOf: (T) -> String?,
        titleOf: (T) -> String?
    ): Int? {
        if (chapters.isEmpty()) return null

        val normalizedLink = savedLink?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedLink != null) {
            nearestMatch(previousOrdinal, chapters.indices.filter { index ->
                linkOf(chapters[index])?.trim() == normalizedLink
            })?.let { return it }
        }

        val normalizedTitle = normalizeTitle(savedTitle) ?: return null
        return nearestMatch(previousOrdinal, chapters.indices.filter { index ->
            normalizeTitle(titleOf(chapters[index])) == normalizedTitle
        })
    }

    internal fun normalizeTitle(title: String?): String? {
        val normalized = title
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            ?.lowercase(Locale.ROOT)
            ?.filter { it.isLetterOrDigit() }
            .orEmpty()
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun nearestMatch(previousOrdinal: Int, candidates: List<Int>): Int? {
        return candidates.minWithOrNull(
            compareBy<Int> { candidate -> abs(candidate.toLong() - previousOrdinal.toLong()) }
                .thenBy { it }
        )
    }
}
