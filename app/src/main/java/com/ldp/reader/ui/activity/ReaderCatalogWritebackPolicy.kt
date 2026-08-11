package com.ldp.reader.ui.activity

internal object ReaderCatalogWritebackPolicy {
    fun shouldPersist(
        remoteCatalogLoaded: Boolean,
        collected: Boolean,
        bookUpdated: Boolean,
        changedIntegrityMarks: Int,
        restoredIntegrityMarks: Int,
        hasSafeTitleUpgrade: Boolean,
        catalogChanged: Boolean,
        catalogPersistenceAllowed: Boolean
    ): Boolean {
        return remoteCatalogLoaded && collected && catalogPersistenceAllowed &&
            (bookUpdated || changedIntegrityMarks > 0 || restoredIntegrityMarks > 0 ||
                hasSafeTitleUpgrade || catalogChanged)
    }
}
