package com.ldp.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTextSourceReferenceStoreTest {
    @Test
    fun sourceUriSurvivesImportUntilTheShelfItemIsRemoved() {
        val storage = InMemorySourceReferenceStorage()
        val store = LocalTextSourceReferenceStore(storage)
        val bookId = "local-book-42"
        val sourceUri = "content://downloads/document/42"

        store.save(bookId, sourceUri)
        assertEquals(sourceUri, store.sourceFor(bookId))

        store.clear(bookId)
        assertNull(store.sourceFor(bookId))
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
