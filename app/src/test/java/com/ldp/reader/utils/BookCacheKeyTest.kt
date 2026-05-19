package com.ldp.reader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCacheKeyTest {
    @Test
    fun keepsShortSafeSegmentsReadable() {
        assertEquals("第一章 陨落的天才", BookCacheKey.fileSegment("第一章 陨落的天才"))
        assertEquals("backend_book_123", BookCacheKey.folderSegment("backend_book_123"))
    }

    @Test
    fun hashesLongSourceEngineBookIdsForFileSystemSegments() {
        val routeId = "source_engine_book_" + "a".repeat(900)

        val segment = BookCacheKey.folderSegment(routeId)!!

        assertTrue(segment.startsWith("book_"))
        assertTrue(segment.toByteArray(Charsets.UTF_8).size < 255)
        assertEquals(segment, BookCacheKey.folderSegment(routeId))
        assertNotEquals(routeId, segment)
    }

    @Test
    fun hashesUnsafeChapterTitles() {
        val segment = BookCacheKey.fileSegment("第十章 风/雷")!!

        assertTrue(segment.startsWith("chapter_"))
        assertTrue(!segment.contains("/"))
        assertEquals(segment, BookCacheKey.fileSegment("第十章 风/雷"))
    }
}
