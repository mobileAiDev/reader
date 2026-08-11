package com.ldp.reader.ui.file

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportSelectionStateTest {
    @Test
    fun `search and filter projections do not discard source selection`() {
        val selected = File("/storage/Books/selected.txt")
        val other = File("/storage/Books/other.txt")
        val state = LocalImportSelectionState()
        state.reconcileSource(listOf(selected, other), preserveSelection = true)
        assertTrue(state.toggle(selected))

        // A search/filter projection is deliberately not a source reconciliation.
        assertEquals(listOf(selected), state.selectedFiles())
        assertTrue(state.isSelected(selected))
    }

    @Test
    fun `async refresh preserves choices that remain in the same source`() {
        val selected = File("/storage/Books/selected.txt")
        val state = LocalImportSelectionState()
        state.reconcileSource(listOf(selected), preserveSelection = true)
        state.toggle(selected)

        val refreshedInstance = File("/storage/Books/selected.txt")
        state.reconcileSource(listOf(refreshedInstance), preserveSelection = true)

        assertEquals(1, state.count)
        assertTrue(state.isSelected(refreshedInstance))
        assertEquals(refreshedInstance, state.selectedFiles().single())
    }

    @Test
    fun `temporary empty async result does not silently cancel a choice`() {
        val selected = File("/storage/Books/selected.txt")
        val state = LocalImportSelectionState()
        state.reconcileSource(listOf(selected), preserveSelection = true)
        state.toggle(selected)

        state.reconcileSource(emptyList(), preserveSelection = true)

        assertEquals(listOf(selected), state.selectedFiles())
    }

    @Test
    fun `real directory transition clears old directory choices`() {
        val selected = File("/storage/Books/selected.txt")
        val state = LocalImportSelectionState()
        state.reconcileSource(listOf(selected), preserveSelection = true)
        state.toggle(selected)

        state.reconcileSource(
            listOf(File("/storage/Other/next.txt")),
            preserveSelection = false
        )

        assertEquals(0, state.count)
        assertFalse(state.isSelected(selected))
    }

    @Test
    fun `configuration restore replaces selection from saved paths`() {
        val stale = File("/storage/Old/stale.txt")
        val first = File("/storage/Books/first.txt")
        val second = File("/storage/Books/second.txt")
        val state = LocalImportSelectionState()
        state.toggle(stale)

        state.replaceSelection(listOf(first, second))

        assertEquals(listOf(first, second), state.selectedFiles())
        assertFalse(state.isSelected(stale))
        assertTrue(state.isSelected(first))
        assertTrue(state.isSelected(second))
    }

    @Test
    fun `invalid import removes only the failed original selections`() {
        val failed = File("/storage/Books/deleted.txt")
        val selectedWhileSaving = File("/storage/Books/new-choice.txt")
        val state = LocalImportSelectionState()
        state.setSelected(listOf(failed, selectedWhileSaving), selected = true)

        state.remove(listOf(File(failed.absolutePath)))

        assertFalse(state.isSelected(failed))
        assertTrue(state.isSelected(selectedWhileSaving))
        assertEquals(listOf(selectedWhileSaving), state.selectedFiles())
    }
}
