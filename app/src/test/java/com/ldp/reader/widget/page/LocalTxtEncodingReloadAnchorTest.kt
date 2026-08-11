package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTxtEncodingReloadAnchorTest {

    @Test
    fun capturesTheLiveUnsavedChapterInsteadOfAnyPreviouslyPersistedOrdinal() {
        val chapters = listOf(
            chapter("第一章", 0, 100),
            chapter("第二章", 100, 300),
            chapter("第三章", 300, 500)
        )

        val anchor = requireNotNull(
            LocalTxtReadingAnchorResolver.capture(
                chapters = chapters,
                currentChapterOrdinal = 2,
                currentPageIndex = 3,
                currentPageCount = 8
            )
        )

        assertEquals(2, anchor.chapterOrdinal)
        assertEquals("第三章", anchor.chapterTitle)
        assertEquals(375L, anchor.approximateByteOffset)
    }

    @Test
    fun sameChapterMidpointKeepsItsRelativePageAfterReparse() {
        val chapters = listOf(
            chapter("第一章", 0, 100),
            chapter("第二章", 100, 500)
        )
        val anchor = requireNotNull(
            LocalTxtReadingAnchorResolver.capture(chapters, 1, 4, 10)
        )

        val resolved = requireNotNull(LocalTxtReadingAnchorResolver.resolve(anchor, chapters))

        assertEquals(1, resolved.chapterOrdinal)
        assertEquals(8, resolved.pageProgress.resolvePageIndex(20))
    }

    @Test
    fun changedChapterBoundariesFallBackToTheSameApproximateByteLocation() {
        val oldCatalog = listOf(
            chapter("乱码标题", 100, 300),
            chapter("后续旧章", 300, 500)
        )
        val anchor = requireNotNull(
            LocalTxtReadingAnchorResolver.capture(oldCatalog, 0, 5, 10)
        )
        val reparsedCatalog = listOf(
            chapter("序章", 0, 150),
            chapter("第一章 正确标题", 150, 350),
            chapter("第二章", 350, 520)
        )

        val resolved = requireNotNull(
            LocalTxtReadingAnchorResolver.resolve(anchor, reparsedCatalog)
        )

        assertEquals(1, resolved.chapterOrdinal)
        assertEquals(3, resolved.pageProgress.resolvePageIndex(12))
    }

    @Test
    fun invalidTitleAndOffsetUseClampedOrdinalAndRetainBoundedPageProgress() {
        val staleAnchor = LocalTxtReadingAnchor(
            chapterOrdinal = 99,
            chapterTitle = "已经不存在的章节",
            chapterStart = 800,
            chapterEnd = 1_000,
            currentPageIndex = 2,
            currentPageCount = 5,
            approximateByteOffset = 880
        )
        val reparsedCatalog = listOf(
            chapter("第一章", 0, 100),
            chapter("第二章", 100, 200)
        )

        val resolved = requireNotNull(
            LocalTxtReadingAnchorResolver.resolve(staleAnchor, reparsedCatalog)
        )

        assertEquals(1, resolved.chapterOrdinal)
        assertEquals(4, resolved.pageProgress.resolvePageIndex(10))
    }

    @Test
    fun rapidEncodingChangesKeepTheOriginalReadableAnchorAndOnlyLatestRequestPublishes() {
        val requests = LocalTxtChapterRefreshRequests()
        val readableAnchor = LocalTxtReadingAnchor(
            chapterOrdinal = 8,
            chapterTitle = "第九章",
            chapterStart = 8_000,
            chapterEnd = 9_000,
            currentPageIndex = 6,
            currentPageCount = 10,
            approximateByteOffset = 8_600
        )
        val transientLoadingAnchor = readableAnchor.copy(
            chapterOrdinal = 0,
            chapterTitle = "加载中",
            currentPageIndex = 0,
            currentPageCount = 0,
            approximateByteOffset = 0
        )

        val first = requests.beginEncodingReload(
            liveAnchor = readableAnchor,
            currentPageReadable = true
        )
        val second = requests.beginEncodingReload(
            liveAnchor = transientLoadingAnchor,
            currentPageReadable = false
        )

        assertFalse(requests.isLatest(first))
        assertTrue(requests.isLatest(second))
        assertEquals(readableAnchor, second.readingAnchor)

        requests.invalidate()
        assertFalse(requests.isLatest(second))
    }

    private fun chapter(title: String, start: Long, end: Long): TxtChapter {
        return TxtChapter().apply {
            this.title = title
            this.start = start
            this.end = end
        }
    }
}
