package com.ldp.reader.ui.file

/**
 * Keeps the permission-free Storage Access Framework path as the default while
 * preserving direct device scanning for users who previously granted it.
 */
object LocalImportEntryPolicy {
    fun initialTab(hasBroadStorageAccess: Boolean): LocalImportTab {
        return if (hasBroadStorageAccess) {
            LocalImportTab.RECENT_FILES
        } else {
            LocalImportTab.PICK_FILE
        }
    }

    fun requiresBroadStorageAccess(tab: LocalImportTab): Boolean {
        return tab != LocalImportTab.PICK_FILE
    }

    fun canScan(tab: LocalImportTab, hasBroadStorageAccess: Boolean): Boolean {
        return hasBroadStorageAccess && requiresBroadStorageAccess(tab)
    }
}
