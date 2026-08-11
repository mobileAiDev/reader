package com.ldp.reader.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCatalogWritebackPolicyTest {
    @Test
    fun persistsACollectedRemoteCatalogWhenOnlyItsTitlesWereSafelyUpgraded() {
        assertTrue(
            ReaderCatalogWritebackPolicy.shouldPersist(
                remoteCatalogLoaded = true,
                collected = true,
                bookUpdated = false,
                changedIntegrityMarks = 0,
                restoredIntegrityMarks = 0,
                hasSafeTitleUpgrade = true,
                catalogChanged = true,
                catalogPersistenceAllowed = true
            )
        )
    }

    @Test
    fun doesNotPersistATitleUpgradeForABookThatIsNotCollected() {
        assertFalse(
            ReaderCatalogWritebackPolicy.shouldPersist(
                remoteCatalogLoaded = true,
                collected = false,
                bookUpdated = false,
                changedIntegrityMarks = 0,
                restoredIntegrityMarks = 0,
                hasSafeTitleUpgrade = true,
                catalogChanged = true,
                catalogPersistenceAllowed = true
            )
        )
    }

    @Test
    fun doesNotPersistBeforeTheRemoteCatalogHasLoaded() {
        assertFalse(
            ReaderCatalogWritebackPolicy.shouldPersist(
                remoteCatalogLoaded = false,
                collected = true,
                bookUpdated = false,
                changedIntegrityMarks = 0,
                restoredIntegrityMarks = 0,
                hasSafeTitleUpgrade = true,
                catalogChanged = true,
                catalogPersistenceAllowed = true
            )
        )
    }

    @Test
    fun doesNotPersistAnIncompatibleCatalogEvenWhenTheBookWasMarkedUpdated() {
        assertFalse(
            ReaderCatalogWritebackPolicy.shouldPersist(
                remoteCatalogLoaded = true,
                collected = true,
                bookUpdated = true,
                changedIntegrityMarks = 0,
                restoredIntegrityMarks = 0,
                hasSafeTitleUpgrade = false,
                catalogChanged = true,
                catalogPersistenceAllowed = false
            )
        )
    }
}
