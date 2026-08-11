package com.ldp.reader.document.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubReadingProgressStoreTest {

    @Test
    fun `reopening the same EPUB restores its saved reading position`() {
        val storage = InMemoryEpubProgressStorage()
        val firstReader = EpubReadingProgressStore(storage)
        firstReader.save(
            documentId = "file:///books/reader_epub_scroll_test.epub",
            progress = EpubReadingProgress(
                scrollY = 4_800,
                maxScrollY = 12_000,
                textZoom = 110
            )
        )

        val reopenedReader = EpubReadingProgressStore(storage)

        assertEquals(
            EpubReadingProgress(scrollY = 4_800, maxScrollY = 12_000, textZoom = 110),
            reopenedReader.load("file:///books/reader_epub_scroll_test.epub")
        )
    }

    @Test
    fun `restored position is clamped when EPUB content becomes shorter`() {
        assertEquals(3_200, restoredEpubScrollY(savedScrollY = 4_800, currentMaxScrollY = 3_200))
        assertEquals(0, restoredEpubScrollY(savedScrollY = -20, currentMaxScrollY = 3_200))
        assertEquals(0, restoredEpubScrollY(savedScrollY = 800, currentMaxScrollY = -1))
    }

    @Test
    fun `restored position keeps reading ratio when viewport changes`() {
        assertEquals(
            2_000,
            restoredEpubScrollY(
                savedScrollY = 4_000,
                savedMaxScrollY = 8_000,
                currentMaxScrollY = 4_000
            )
        )
        assertEquals(
            6_000,
            restoredEpubScrollY(
                savedScrollY = 4_000,
                savedMaxScrollY = 8_000,
                currentMaxScrollY = 12_000
            )
        )
    }

    @Test
    fun `legacy absolute position without saved range remains compatible`() {
        assertEquals(
            3_200,
            restoredEpubScrollY(
                savedScrollY = 4_800,
                savedMaxScrollY = 0,
                currentMaxScrollY = 3_200
            )
        )
    }

    @Test
    fun `web content height is converted to a bounded scroll range`() {
        assertEquals(
            9_000,
            epubMaxScrollY(contentHeightCss = 5_000, webViewScale = 2f, viewportHeight = 1_000)
        )
        assertEquals(
            0,
            epubMaxScrollY(contentHeightCss = 500, webViewScale = 1f, viewportHeight = 1_000)
        )
        assertEquals(
            Int.MAX_VALUE,
            epubMaxScrollY(Int.MAX_VALUE, Float.MAX_VALUE, viewportHeight = 0)
        )
    }

    private class InMemoryEpubProgressStorage : EpubReadingProgressStorage {
        private val values = LinkedHashMap<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }
    }
}
