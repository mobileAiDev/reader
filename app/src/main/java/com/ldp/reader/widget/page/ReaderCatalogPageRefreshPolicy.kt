package com.ldp.reader.widget.page

import com.ldp.reader.sourceengine.catalog.CatalogTitleEnricher

internal object ReaderCatalogPageRefreshPolicy {
    private val titleEnricher = CatalogTitleEnricher()

    fun shouldRepage(
        previousChapter: TxtChapter?,
        refreshedChapter: TxtChapter?,
        chapterOpen: Boolean,
        currentPageFinished: Boolean,
        refreshedChapterCached: Boolean
    ): Boolean {
        if (!chapterOpen || !currentPageFinished || !refreshedChapterCached) return false
        val previousLink = previousChapter?.link?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val refreshedLink = refreshedChapter?.link?.trim()?.takeIf(String::isNotEmpty) ?: return false
        if (previousLink != refreshedLink) return false
        val previousTitle = previousChapter.title?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val refreshedTitle = refreshedChapter.title?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return titleEnricher.isSafeTitleUpgrade(previousTitle, refreshedTitle)
    }
}
