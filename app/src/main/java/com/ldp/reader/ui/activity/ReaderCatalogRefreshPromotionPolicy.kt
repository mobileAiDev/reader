package com.ldp.reader.ui.activity

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.source.SourceEngineCatalogPersistencePolicy

internal object ReaderCatalogRefreshPromotionPolicy {
    fun shouldRetainCurrentCatalog(
        current: List<BookChapterBean>,
        refreshed: List<BookChapterBean>
    ): Boolean {
        return !SourceEngineCatalogPersistencePolicy.evaluate(current, refreshed).compatible
    }

    fun shouldPromote(
        current: List<BookChapterBean>,
        refreshed: List<BookChapterBean>
    ): Boolean {
        return SourceEngineCatalogPersistencePolicy.shouldPromote(current, refreshed)
    }
}
