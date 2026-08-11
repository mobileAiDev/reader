package com.ldp.reader.ui.activity

import com.ldp.reader.document.epub.EpubCatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReaderUiStateTest {

    @Test
    fun `catalog highlights the entry for the currently visible spine section`() {
        val catalog = listOf(
            EpubCatalogEntry("封面", sectionIndex = 0),
            EpubCatalogEntry("第一章", sectionIndex = 1),
            EpubCatalogEntry("第一章小节", sectionIndex = 1, targetId = "part-2"),
            EpubCatalogEntry("第二章", sectionIndex = 2)
        )

        assertEquals(1, EpubReaderUiPolicy.checkedCatalogEntryIndex(catalog, currentSectionIndex = 1))
        assertEquals(3, EpubReaderUiPolicy.checkedCatalogEntryIndex(catalog, currentSectionIndex = 2))
        assertEquals(-1, EpubReaderUiPolicy.checkedCatalogEntryIndex(catalog, currentSectionIndex = -1))
    }

    @Test
    fun `catalog selection scrolls within loaded document instead of reloading virtual base URL`() {
        val script = EpubReaderUiPolicy.catalogJumpScript(sectionIndex = 1)

        assertTrue(script.contains("document.getElementById('epub-section-2')"))
        assertTrue(script.contains("scrollIntoView"))
        assertFalse(script.contains("location.href"))
        assertFalse(script.contains("window.location"))
    }

    @Test
    fun `catalog fragment selection uses its parser resolved target`() {
        val script = EpubReaderUiPolicy.catalogJumpScript(
            sectionIndex = 2,
            targetId = "epub-target-3-safe"
        )

        assertTrue(script.contains("document.getElementById('epub-target-3-safe')"))
        assertFalse(script.contains("document.getElementById('epub-section-3')"))
    }

    @Test
    fun `current catalog section is derived from visible combined spine sections`() {
        val script = EpubReaderUiPolicy.currentSectionScript()

        assertTrue(script.contains("document.querySelectorAll('.epub-section')"))
        assertTrue(script.contains("getBoundingClientRect().top"))
        assertTrue(script.contains("return current"))
    }

    @Test
    fun `progress percent clamps invalid and out of range positions`() {
        assertEquals(100, EpubReaderUiPolicy.readingPercent(scrollY = 0, maxScrollY = 0))
        assertEquals(0, EpubReaderUiPolicy.readingPercent(scrollY = -20, maxScrollY = 1_000))
        assertEquals(42, EpubReaderUiPolicy.readingPercent(scrollY = 420, maxScrollY = 1_000))
        assertEquals(100, EpubReaderUiPolicy.readingPercent(scrollY = 2_000, maxScrollY = 1_000))
    }

    @Test
    fun `only a readable non-link center single tap toggles reader chrome`() {
        assertEquals(
            true,
            EpubReaderUiPolicy.shouldToggleControls(
                hasReadableDocument = true,
                insideCenter = true,
                targetsLink = false
            )
        )
        assertEquals(
            false,
            EpubReaderUiPolicy.shouldToggleControls(
                hasReadableDocument = false,
                insideCenter = true,
                targetsLink = false
            )
        )
        assertEquals(
            false,
            EpubReaderUiPolicy.shouldToggleControls(
                hasReadableDocument = true,
                insideCenter = false,
                targetsLink = false
            )
        )
        assertEquals(
            false,
            EpubReaderUiPolicy.shouldToggleControls(
                hasReadableDocument = true,
                insideCenter = true,
                targetsLink = true
            )
        )
    }
}
