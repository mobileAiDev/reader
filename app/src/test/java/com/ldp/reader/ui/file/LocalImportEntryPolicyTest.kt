package com.ldp.reader.ui.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportEntryPolicyTest {
    @Test
    fun `permission-free entry starts with the system file picker`() {
        assertEquals(
            LocalImportTab.PICK_FILE,
            LocalImportEntryPolicy.initialTab(hasBroadStorageAccess = false)
        )
    }

    @Test
    fun `an existing granted user keeps the recent-files entry`() {
        assertEquals(
            LocalImportTab.RECENT_FILES,
            LocalImportEntryPolicy.initialTab(hasBroadStorageAccess = true)
        )
    }

    @Test
    fun `only device-wide scanning tabs require broad storage access`() {
        assertTrue(LocalImportEntryPolicy.requiresBroadStorageAccess(LocalImportTab.RECENT_FILES))
        assertTrue(LocalImportEntryPolicy.requiresBroadStorageAccess(LocalImportTab.PHONE_DIRECTORY))
        assertFalse(LocalImportEntryPolicy.requiresBroadStorageAccess(LocalImportTab.PICK_FILE))
    }

    @Test
    fun `a scanner runs only for a selected scan tab with broad access`() {
        assertFalse(
            LocalImportEntryPolicy.canScan(
                LocalImportTab.RECENT_FILES,
                hasBroadStorageAccess = false
            )
        )
        assertFalse(
            LocalImportEntryPolicy.canScan(
                LocalImportTab.PICK_FILE,
                hasBroadStorageAccess = true
            )
        )
        assertTrue(
            LocalImportEntryPolicy.canScan(
                LocalImportTab.PHONE_DIRECTORY,
                hasBroadStorageAccess = true
            )
        )
    }
}
