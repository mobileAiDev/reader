package com.ldp.reader.ui.fragment

import org.junit.Assert.assertEquals
import org.junit.Test

class BookShelfStateRestoreTest {
    @Test
    fun knownFilterNameSurvivesConfigurationRecreation() {
        BookShelfViewModel.FilterKey.entries.forEach { filter ->
            assertEquals(filter, BookShelfFragment.restoredShelfFilter(filter.name))
        }
    }

    @Test
    fun missingBlankOrObsoleteFilterSafelyFallsBackToAll() {
        assertEquals(
            BookShelfViewModel.FilterKey.ALL,
            BookShelfFragment.restoredShelfFilter(null)
        )
        assertEquals(
            BookShelfViewModel.FilterKey.ALL,
            BookShelfFragment.restoredShelfFilter("  ")
        )
        assertEquals(
            BookShelfViewModel.FilterKey.ALL,
            BookShelfFragment.restoredShelfFilter("REMOVED_LEGACY_FILTER")
        )
    }
}
