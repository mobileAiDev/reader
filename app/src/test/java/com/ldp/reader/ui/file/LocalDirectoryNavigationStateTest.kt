package com.ldp.reader.ui.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDirectoryNavigationStateTest {
    @Test
    fun `directory transitions keep an exact scroll-aware back stack`() {
        val state = LocalDirectoryNavigationState.create("/storage")

        assertTrue(state.enter("/storage/Download", currentScrollOffset = 240))
        assertTrue(state.enter("/storage/Download/Books", currentScrollOffset = 680))

        assertEquals("/storage/Download/Books", state.currentPath)
        assertEquals(
            LocalDirectoryHistoryEntry("/storage/Download", 680),
            state.navigateBack()
        )
        assertEquals("/storage/Download", state.currentPath)
        assertEquals(
            LocalDirectoryHistoryEntry("/storage", 240),
            state.navigateBack()
        )
        assertEquals("/storage", state.currentPath)
        assertNull(state.navigateBack())
    }

    @Test
    fun `configuration restore preserves current directory history order and offsets`() {
        val restored = LocalDirectoryNavigationState.restore(
            rootPath = "/storage",
            currentPath = "/storage/Download/Books",
            historyPaths = listOf("/storage/Download", "/storage"),
            historyScrollOffsets = intArrayOf(680, 240)
        )

        assertTrue(restored.canNavigateBack)
        assertEquals(
            listOf(
                LocalDirectoryHistoryEntry("/storage/Download", 680),
                LocalDirectoryHistoryEntry("/storage", 240)
            ),
            restored.historySnapshot()
        )
        assertEquals("/storage/Download", restored.navigateBack()?.path)
        assertEquals("/storage", restored.navigateBack()?.path)
        assertFalse(restored.canNavigateBack)
    }

    @Test
    fun `invalid restored values safely fall back to the storage root`() {
        val restored = LocalDirectoryNavigationState.restore(
            rootPath = "/storage",
            currentPath = " ",
            historyPaths = listOf("", " /storage "),
            historyScrollOffsets = intArrayOf(-5)
        )

        assertEquals("/storage", restored.currentPath)
        assertEquals(
            listOf(LocalDirectoryHistoryEntry("/storage", 0)),
            restored.historySnapshot()
        )
    }
}
