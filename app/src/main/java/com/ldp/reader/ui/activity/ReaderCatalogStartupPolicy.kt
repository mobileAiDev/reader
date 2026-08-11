package com.ldp.reader.ui.activity

enum class ReaderCatalogStartupAction {
    LOAD_PERSISTED_CATALOG,
    WAIT_FOR_REMOTE_CATALOG
}

object ReaderCatalogStartupPolicy {
    fun decide(
        isLocalBook: Boolean,
        persistedChapterCount: Int
    ): ReaderCatalogStartupAction {
        return if (isLocalBook || persistedChapterCount > 0) {
            ReaderCatalogStartupAction.LOAD_PERSISTED_CATALOG
        } else {
            ReaderCatalogStartupAction.WAIT_FOR_REMOTE_CATALOG
        }
    }

    fun canDisplayRemoteCatalog(chapterCount: Int): Boolean {
        return chapterCount > 0
    }
}
