package com.ldp.reader.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookShelfAddGenerationTest {
    @Test
    fun givingUpInvalidatesPendingShelfAddButAllowsANewerAdd() {
        val generation = BookShelfAddGeneration()
        val pendingAdd = generation.beginAdd()

        generation.giveUp()

        assertFalse(generation.isCurrent(pendingAdd))
        val newerAdd = generation.beginAdd()
        assertTrue(generation.isCurrent(newerAdd))
    }

    @Test
    fun handingCatalogPersistenceToReaderInvalidatesPendingDetailWrite() {
        val generation = BookShelfAddGeneration()
        val pendingDetailWrite = generation.beginAdd()

        generation.handOffCatalogPersistenceToReader()

        assertFalse(generation.isCurrent(pendingDetailWrite))
        assertTrue(
            "reader handoff must not cancel the surrounding shelf-add workflow",
            generation.isShelfAddCurrent(pendingDetailWrite)
        )
    }
}
