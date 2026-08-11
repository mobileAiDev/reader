package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCatalogStartupPolicyTest {
    @Test
    fun collectedNetworkBookWithoutPersistedCatalogWaitsForRemoteCatalog() {
        assertEquals(
            ReaderCatalogStartupAction.WAIT_FOR_REMOTE_CATALOG,
            ReaderCatalogStartupPolicy.decide(
                isLocalBook = false,
                persistedChapterCount = 0
            )
        )
    }

    @Test
    fun collectedNetworkBookWithPersistedCatalogDisplaysItImmediately() {
        assertEquals(
            ReaderCatalogStartupAction.LOAD_PERSISTED_CATALOG,
            ReaderCatalogStartupPolicy.decide(
                isLocalBook = false,
                persistedChapterCount = 51
            )
        )
    }

    @Test
    fun localBookStillStartsItsLocalCatalogParserWhenCacheIsEmpty() {
        assertEquals(
            ReaderCatalogStartupAction.LOAD_PERSISTED_CATALOG,
            ReaderCatalogStartupPolicy.decide(
                isLocalBook = true,
                persistedChapterCount = 0
            )
        )
    }

    @Test
    fun emptyRemoteCatalogIsNeverDisplayableAsARealCatalog() {
        assertFalse(ReaderCatalogStartupPolicy.canDisplayRemoteCatalog(0))
        assertTrue(ReaderCatalogStartupPolicy.canDisplayRemoteCatalog(1))
    }
}
