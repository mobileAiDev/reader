package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.sourceengine.catalog.CatalogTitleEnricher

/**
 * Protects a persisted source catalog from a same-size rewrite masquerading as a refresh.
 * Existing rows may stay identical or receive a narrowly verified display-title repair
 * (truncated-title completion or known update-decoration removal); new rows may only be
 * appended after that unchanged/safely-enriched prefix.
 */
internal object SourceEngineCatalogPersistencePolicy {
    private val titleEnricher = CatalogTitleEnricher()

    data class Evaluation(
        val compatible: Boolean,
        val changed: Boolean,
        val safeTitleUpgrades: Int,
        val appendedChapters: Int
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
        persisted.indices.forEach { index ->
            val current = persisted[index]
            val incoming = candidate[index]
            if (current.link != incoming.link) return rejected()
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
            changed = safeTitleUpgrades > 0 || appended > 0,
            safeTitleUpgrades = safeTitleUpgrades,
            appendedChapters = appended
        )
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
