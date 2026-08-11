package com.ldp.reader.media

import java.net.URI
import java.util.Locale

internal data class ComicChapterContentDecision(
    val acceptable: Boolean,
    val reason: String = "",
    val expectedIdentity: String = "",
    val actualIdentities: Set<String> = emptySet()
)

internal object ComicChapterContentGate {
    fun evaluate(
        chapter: MediaSourceChapter,
        pages: List<MediaRequest>
    ): ComicChapterContentDecision {
        if (pages.isEmpty()) return ComicChapterContentDecision(acceptable = false, reason = "empty_pages")

        val expectedIdentity = currentComicIdentity(chapter)
        if (expectedIdentity.isBlank()) return ComicChapterContentDecision(acceptable = true)

        val assetIdentities = pages.mapNotNull { request -> baoziAssetIdentity(request.url) }
        // No Baozi-style assets means this ownership policy does not apply to the source.
        if (assetIdentities.isEmpty()) return ComicChapterContentDecision(acceptable = true)
        val actualIdentities = assetIdentities.toSet()
        val matchingAssets = assetIdentities.count { identity ->
            sameComicIdentity(expectedIdentity, identity)
        }
        val mismatchedAssets = assetIdentities.size - matchingAssets
        val ownedAssetMajority = matchingAssets * 2 > pages.size
        return if (mismatchedAssets == 0 && ownedAssetMajority) {
            ComicChapterContentDecision(acceptable = true)
        } else {
            val reason = when {
                matchingAssets == 0 && mismatchedAssets > 0 -> "baozi_asset_book_mismatch"
                mismatchedAssets > 0 -> "baozi_asset_mixed_book_mismatch"
                else -> "baozi_asset_ownership_too_sparse"
            }
            ComicChapterContentDecision(
                acceptable = false,
                reason = reason,
                expectedIdentity = expectedIdentity,
                actualIdentities = actualIdentities
            )
        }
    }

    private fun currentComicIdentity(chapter: MediaSourceChapter): String {
        return comicSlug(chapter.book.bookUrl)
            ?: comicSlug(chapter.chapterUrl)
            ?: ""
    }

    private fun comicSlug(url: String): String? {
        val segments = pathSegments(url)
        val comicIndex = segments.indexOfFirst { segment -> segment.equals("comic", ignoreCase = true) }
        if (comicIndex < 0) return null
        val next = segments.getOrNull(comicIndex + 1) ?: return null
        val slug = if (next.equals("chapter", ignoreCase = true)) {
            segments.getOrNull(comicIndex + 2)
        } else {
            next
        }
        return slug?.let(::identityStem)?.takeIf { it.isNotBlank() }
    }

    private fun baoziAssetIdentity(url: String): String? {
        val uri = parseUri(url) ?: return null
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host != "bzcdn.net" && !host.endsWith(".bzcdn.net")) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val assetRoot = segments.indexOfFirst { segment -> segment.equals("scomic", ignoreCase = true) }
        if (assetRoot < 0) return null
        return segments.getOrNull(assetRoot + 1)
            ?.let(::identityStem)
            ?.takeIf { it.isNotBlank() }
    }

    private fun sameComicIdentity(expected: String, actual: String): Boolean {
        return expected == actual
    }

    private fun identityStem(slug: String): String {
        return slug
            .substringBefore('-')
            .lowercase(Locale.ROOT)
            .filter { character -> character.isLetterOrDigit() }
    }

    private fun pathSegments(url: String): List<String> {
        return parseUri(url)?.path.orEmpty().split('/').filter { it.isNotBlank() }
    }

    private fun parseUri(url: String): URI? {
        return runCatching { URI(url.trim()) }.getOrNull()
    }
}
