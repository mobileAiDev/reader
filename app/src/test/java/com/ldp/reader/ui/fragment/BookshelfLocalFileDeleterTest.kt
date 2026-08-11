package com.ldp.reader.ui.fragment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookshelfLocalFileDeleterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletingAnExistingLocalFileRemovesItAndReportsSuccess() {
        val localBookFile = temporaryFolder.newFile("reader-local-book.txt").apply {
            writeText("local book")
        }

        val outcome = BookshelfLocalFileDeleter { false }.delete(localBookFile.absolutePath)

        assertEquals(BookshelfLocalFileDeleteOutcome.DELETED, outcome)
        assertFalse(localBookFile.exists())
    }

    @Test
    fun fileUriDeletesTheReferencedLocalFileInsteadOfTreatingItAsMissing() {
        val localBookFile = temporaryFolder.newFile("reader-file-uri.epub").apply {
            writeText("epub fixture")
        }

        val outcome = BookshelfLocalFileDeleter { false }.delete(localBookFile.toURI().toString())

        assertEquals(BookshelfLocalFileDeleteOutcome.DELETED, outcome)
        assertFalse(localBookFile.exists())
    }

    @Test
    fun contentUriUsesThePersistedDocumentPermissionBoundary() {
        val deletedUris = mutableListOf<String>()
        val contentUri = "content://com.android.providers.downloads.documents/document/42"
        val deleter = BookshelfLocalFileDeleter { uri ->
            deletedUris += uri
            true
        }

        val outcome = deleter.delete(contentUri)

        assertEquals(BookshelfLocalFileDeleteOutcome.DELETED, outcome)
        assertEquals(listOf(contentUri), deletedUris)
    }

    @Test
    fun managedImportUsesItsSavedSourceUriAndNeverMistakesThePrivateCopyForTheOriginal() {
        val privateCopy = "/data/user/0/com.ldp.reader/files/imports/text/book.txt"
        val sourceUri = "content://downloads/document/original"

        assertEquals(
            sourceUri,
            bookshelfOriginalFileTarget(
                shelfPath = privateCopy,
                savedSourceReference = sourceUri,
                isManagedCopy = true
            )
        )
        assertEquals(
            null,
            bookshelfOriginalFileTarget(
                shelfPath = privateCopy,
                savedSourceReference = null,
                isManagedCopy = true
            )
        )
    }
}
