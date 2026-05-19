package com.ldp.reader.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTailBoundaryLocatorTest {
    @Test
    fun returnsFullCatalogWhenLastChapterIsReadable() = runBlocking {
        val result = CatalogTailBoundaryLocator(maxBacktrackChapters = 256)
            .locate(chapterCount = 100) { true }

        assertEquals(100, result.keepUntil)
        assertEquals("tail-readable-window", result.method)
        assertTrue("checked=${result.checkedCount}", result.checkedCount <= 12)
    }

    @Test
    fun trimsSingleBadTailChapter() = runBlocking {
        val result = CatalogTailBoundaryLocator(maxBacktrackChapters = 256)
            .locate(chapterCount = 100) { index -> index < 99 }

        assertEquals(99, result.keepUntil)
        assertEquals("tail-window-exponential-binary", result.method)
        assertTrue("checked=${result.checkedCount}", result.checkedCount <= 12)
    }

    @Test
    fun locatesDozensOfBadTailChaptersWithoutLinearScan() = runBlocking {
        val firstBadIndex = 2920
        val result = CatalogTailBoundaryLocator(maxBacktrackChapters = 2048)
            .locate(chapterCount = 3000) { index -> index < firstBadIndex }

        assertEquals(firstBadIndex, result.keepUntil)
        assertEquals("tail-window-exponential-binary", result.method)
        assertTrue("checked=${result.checkedCount}", result.checkedCount < 40)
    }

    @Test
    fun locatesHundredsOfBadTailChaptersWithoutLinearScan() = runBlocking {
        val firstBadIndex = 3300
        val result = CatalogTailBoundaryLocator(maxBacktrackChapters = 2048)
            .locate(chapterCount = 4000) { index -> index < firstBadIndex }

        assertEquals(firstBadIndex, result.keepUntil)
        assertEquals("tail-window-exponential-binary", result.method)
        assertTrue("checked=${result.checkedCount}", result.checkedCount < 45)
    }

    @Test
    fun trimsFromFirstBadChapterInTailWindowEvenWhenLastChapterLooksReadable() = runBlocking {
        val firstBadIndex = 1491
        val readableButUntrustedTail = setOf(1495, 1496, 1497)
        val result = CatalogTailBoundaryLocator(maxBacktrackChapters = 2048, tailWindowChapters = 12)
            .locate(chapterCount = 1498) { index ->
                index < firstBadIndex || index in readableButUntrustedTail
            }

        assertEquals(firstBadIndex, result.keepUntil)
        assertEquals("tail-window-exponential-binary", result.method)
        assertTrue("checked=${result.checkedCount}", result.checkedCount < 35)
    }

    @Test
    fun trimsBacktrackWindowWhenNoReadableAnchorIsFound() = runBlocking {
        val result = CatalogTailBoundaryLocator(maxBacktrackChapters = 256)
            .locate(chapterCount = 3000) { false }

        assertEquals(2744, result.keepUntil)
        assertEquals("exponential-no-readable-anchor", result.method)
        assertTrue("checked=${result.checkedCount}", result.checkedCount < 30)
    }
}
