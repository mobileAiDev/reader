package com.ldp.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalDocumentPageProgressStoreTest {

    @Test
    fun `reopening the same local document restores its page`() {
        val storage = InMemoryLocalDocumentPageStorage()
        LocalDocumentPageProgressStore(storage).save(
            documentId = "pdf|file:///Download/reader_pdf_test.pdf",
            pageIndex = 2
        )

        assertEquals(
            2,
            LocalDocumentPageProgressStore(storage)
                .load("pdf|file:///Download/reader_pdf_test.pdf")
        )
    }

    @Test
    fun `first open starts at page one and a shorter replacement clamps the saved page`() {
        val storage = InMemoryLocalDocumentPageStorage()
        val store = LocalDocumentPageProgressStore(storage)

        assertNull(store.load("comic|file:///Download/new.cbz"))
        assertEquals(0, restoredLocalDocumentPageIndex(savedPageIndex = null, pageCount = 4))
        assertEquals(1, restoredLocalDocumentPageIndex(savedPageIndex = 3, pageCount = 2))
        assertEquals(0, restoredLocalDocumentPageIndex(savedPageIndex = 3, pageCount = 0))
    }

    @Test
    fun `dominant page follows the item occupying most of the viewport`() {
        assertEquals(
            2,
            dominantLocalDocumentPageIndex(
                visiblePages = listOf(
                    LocalDocumentVisiblePage(index = 1, visiblePixels = 420),
                    LocalDocumentVisiblePage(index = 2, visiblePixels = 1_840)
                ),
                fallbackIndex = 1,
                pageCount = 4
            )
        )
    }

    @Test
    fun `page label includes current and total page counts`() {
        assertEquals("3 / 4", localDocumentPageLabel(pageIndex = 2, pageCount = 4))
        assertEquals("", localDocumentPageLabel(pageIndex = 2, pageCount = 0))
    }

    private class InMemoryLocalDocumentPageStorage : LocalDocumentPageProgressStorage {
        private val values = LinkedHashMap<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }
    }
}
