package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.sourceengine.catalog.CatalogTitleEnricher

/**
 * Protects a persisted source catalog from a same-size rewrite masquerading as a refresh.
 * Existing rows must keep their stable route identity (same source and same book page for
 * encoded source-engine routes; volatile book metadata such as lastChapter, intro, or cover
 * that is embedded in the encoded link is ignored). Titles may stay identical or receive a
 * narrowly verified display-title repair (truncated-title completion or known
 * update-decoration removal); new rows may only be appended after that prefix.
 */
internal object SourceEngineCatalogPersistencePolicy {
    private val titleEnricher = CatalogTitleEnricher()

    data class Evaluation(
        val compatible: Boolean,
        val changed: Boolean,
        val safeTitleUpgrades: Int,
        val appendedChapters: Int,
        val refreshedRoutes: Int = 0
    )

    fun evaluate(
        persisted: List<BookChapterBean>,
        candidate: List<BookChapterBean>
    ): Evaluation {
        if (candidate.isEmpty()) return rejected()
        if (!hasStableUniqueRoutes(candidate)) return rejected()
        if (persisted.isEmpty()) {
            return Evaluation(
                compatible = true,
                changed = true,
                safeTitleUpgrades = 0,
                appendedChapters = candidate.size
            )
        }
        // A legacy catalog with blank or duplicate routes cannot be used as a safe comparison
        // baseline. A fully valid candidate is allowed to replace it as an explicit repair.
        if (!hasStableUniqueRoutes(persisted)) {
            return Evaluation(
                compatible = true,
                changed = true,
                safeTitleUpgrades = 0,
                appendedChapters = 0
            )
        }
        if (candidate.size < persisted.size) {
            return rejected()
        }

        var safeTitleUpgrades = 0
        var refreshedRoutes = 0
        persisted.indices.forEach { index ->
            val current = persisted[index]
            val incoming = candidate[index]
            if (current.link != incoming.link) {
                if (!sameStableRoute(current.link, incoming.link)) return rejected()
                refreshedRoutes += 1
            }
            val currentTitle = current.title.orEmpty()
            val incomingTitle = incoming.title.orEmpty()
            if (currentTitle == incomingTitle) return@forEach
            if (!titleEnricher.isSafeTitleUpgrade(currentTitle, incomingTitle)) {
                return rejected()
            }
            safeTitleUpgrades += 1
        }

        val appended = candidate.size - persisted.size
        return Evaluation(
            compatible = true,
            changed = safeTitleUpgrades > 0 || appended > 0 || refreshedRoutes > 0,
            safeTitleUpgrades = safeTitleUpgrades,
            appendedChapters = appended,
            refreshedRoutes = refreshedRoutes
        )
    }

    /**
     * Two encoded source-engine chapter routes are the same catalog row when they point at the
     * same source and the same book page. The encoded payload also carries book-level metadata
     * (lastChapter, intro, cover, kind) that changes whenever the source publishes a new
     * chapter, so raw link equality would reject every refreshed catalog of an updating book.
     * Position is already enforced by the caller's index-aligned comparison, and title changes
     * are governed by the safe-title-upgrade rule.
     */
    private fun sameStableRoute(persistedLink: String?, incomingLink: String?): Boolean {
        val persistedRoute = stableRouteKey(persistedLink) ?: return false
        return persistedRoute == stableRouteKey(incomingLink)
    }

    private fun stableRouteKey(link: String?): String? {
        if (link == null || !SourceEngineBookRoute.isChapterId(link)) return null
        val payload = runCatching { SourceEngineBookRoute.decodeChapterId(link) }.getOrNull() ?: return null
        return payload.sourceUrl.trim() + "\n" + payload.bookUrl.trim()
    }

    fun canReplacePersistedCatalog(
        persisted: List<BookChapterBean>,
        candidate: List<BookChapterBean>
    ): Boolean = evaluate(persisted, candidate).compatible

    fun shouldPromote(
        current: List<BookChapterBean>,
        refreshed: List<BookChapterBean>
    ): Boolean {
        val evaluation = evaluate(current, refreshed)
        return evaluation.compatible && evaluation.changed
    }

    private fun hasStableUniqueRoutes(chapters: List<BookChapterBean>): Boolean {
        val routes = chapters.map { chapter -> chapter.link?.takeIf(String::isNotBlank) ?: return false }
        return routes.toSet().size == routes.size
    }

    private fun rejected(): Evaluation {
        return Evaluation(
            compatible = false,
            changed = false,
            safeTitleUpgrades = 0,
            appendedChapters = 0
        )
    }
}
