package com.ldp.reader.ui.fragment

import com.ldp.reader.document.LocalTextSourceReferenceStorage
import com.ldp.reader.document.LocalTextSourceReferenceStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookshelfLocalFileRemovalExecutorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun uncheckedRemovalClearsTheShelfMappingWithoutDeletingTheOriginal() {
        val original = temporaryFolder.newFile("keep-original.txt").apply {
            writeText("keep me")
        }
        val sourceStore = sourceStore().apply {
            save("book-1", original.toURI().toString())
        }
        val clearedEncodingOverrides = mutableListOf<String>()
        val executor = BookshelfLocalFileRemovalExecutor(
            sourceReferences = sourceStore,
            fileDeleter = BookshelfLocalFileDeleter { false },
            encodingOverrideClearer = clearedEncodingOverrides::add
        )

        val result = executor.execute(
            entries = listOf(
                BookshelfLocalFileEntry(
                    bookId = "book-1",
                    shelfPath = original.absolutePath,
                    isManagedCopy = false
                )
            ),
            deleteLocalFiles = false
        )

        assertEquals(listOf("book-1"), result.removableBookIds)
        assertTrue(result.failedBookIds.isEmpty())
        assertTrue(original.exists())
        assertEquals(null, sourceStore.sourceFor("book-1"))
        assertEquals(listOf("book-1"), clearedEncodingOverrides)
    }

    @Test
    fun checkedSuccessfulRemovalDeletesTheOriginalAndClearsItsMapping() {
        val original = temporaryFolder.newFile("delete-original.txt").apply {
            writeText("delete me")
        }
        val sourceStore = sourceStore().apply {
            save("book-2", original.toURI().toString())
        }
        val clearedEncodingOverrides = mutableListOf<String>()
        val executor = BookshelfLocalFileRemovalExecutor(
            sourceReferences = sourceStore,
            fileDeleter = BookshelfLocalFileDeleter { false },
            encodingOverrideClearer = clearedEncodingOverrides::add
        )

        val result = executor.execute(
            entries = listOf(
                BookshelfLocalFileEntry(
                    bookId = "book-2",
                    shelfPath = original.absolutePath,
                    isManagedCopy = false
                )
            ),
            deleteLocalFiles = true
        )

        assertEquals(listOf("book-2"), result.removableBookIds)
        assertTrue(result.failedBookIds.isEmpty())
        assertFalse(original.exists())
        assertEquals(null, sourceStore.sourceFor("book-2"))
        assertEquals(listOf("book-2"), clearedEncodingOverrides)
    }

    @Test
    fun alreadyMissingOriginalStillClearsOnlyTheRemovedShelfBooksOverride() {
        val missingOriginal = File(temporaryFolder.root, "already-gone.txt")
        val sourceStore = sourceStore()
        val clearedEncodingOverrides = mutableListOf<String>()
        val executor = BookshelfLocalFileRemovalExecutor(
            sourceReferences = sourceStore,
            fileDeleter = BookshelfLocalFileDeleter { false },
            encodingOverrideClearer = clearedEncodingOverrides::add
        )

        val result = executor.execute(
            entries = listOf(
                BookshelfLocalFileEntry(
                    bookId = "book-removed",
                    shelfPath = missingOriginal.absolutePath,
                    isManagedCopy = false
                )
            ),
            deleteLocalFiles = true
        )

        assertEquals(listOf("book-removed"), result.removableBookIds)
        assertTrue(result.failedBookIds.isEmpty())
        assertEquals(listOf("book-removed"), clearedEncodingOverrides)
    }

    @Test
    fun failedDeletionKeepsTheShelfEntryAndSourceMappingForRetry() {
        val sourceUri = "content://downloads/document/denied"
        val sourceStore = sourceStore().apply {
            save("book-3", sourceUri)
        }
        val clearedEncodingOverrides = mutableListOf<String>()
        val executor = BookshelfLocalFileRemovalExecutor(
            sourceReferences = sourceStore,
            fileDeleter = BookshelfLocalFileDeleter { false },
            encodingOverrideClearer = clearedEncodingOverrides::add
        )

        val result = executor.execute(
            entries = listOf(
                BookshelfLocalFileEntry(
                    bookId = "book-3",
                    shelfPath = "/data/user/0/com.ldp.reader/files/imports/text/copy.txt",
                    isManagedCopy = true
                )
            ),
            deleteLocalFiles = true
        )

        assertTrue(result.removableBookIds.isEmpty())
        assertEquals(listOf("book-3"), result.failedBookIds)
        assertEquals(sourceUri, sourceStore.sourceFor("book-3"))
        assertTrue(clearedEncodingOverrides.isEmpty())
    }

    private fun sourceStore(): LocalTextSourceReferenceStore {
        return LocalTextSourceReferenceStore(InMemorySourceReferenceStorage())
    }

    private class InMemorySourceReferenceStorage : LocalTextSourceReferenceStorage {
        private val values = mutableMapOf<String, String>()

        override fun read(bookId: String): String? = values[bookId]

        override fun write(bookId: String, sourceUri: String) {
            values[bookId] = sourceUri
        }

        override fun remove(bookId: String) {
            values.remove(bookId)
        }
    }
}
