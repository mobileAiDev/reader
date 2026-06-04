package com.ldp.reader.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTailWindowTest {
    @Test
    fun latestFirstUsesLastFiveChaptersInReverseOrder() {
        val chapters = (1..8).map { "第${it}章" }

        val window = MediaTailWindow.latestFirst(chapters)

        assertEquals(listOf("第8章", "第7章", "第6章", "第5章", "第4章"), window)
    }

    @Test
    fun firstUsableAllowsPreviousTailChapterWhenLatestFails() {
        val chapters = (1..8).toList()

        val hit = MediaTailWindow.firstUsable(chapters) { chapter ->
            if (chapter == 6) 3 else 0
        }

        assertEquals(6, hit?.item)
        assertEquals(3, hit?.itemCount)
        assertEquals(2, hit?.offsetFromLatest)
    }

    @Test
    fun firstUsableDoesNotScanOutsideTailWindow() {
        val chapters = (1..8).toList()

        val hit = MediaTailWindow.firstUsable(chapters) { chapter ->
            if (chapter == 3) 1 else 0
        }

        assertNull(hit)
    }

    @Test
    fun firstUsableLatestThenStartFallsBackToOpeningChapters() {
        val chapters = (1..8).toList()

        val hit = MediaTailWindow.firstUsableLatestThenStart(chapters) { chapter ->
            if (chapter == 2) 1 else 0
        }

        assertEquals(2, hit?.item)
        assertEquals(1, hit?.itemCount)
        assertEquals(6, hit?.offsetFromLatest)
    }
}
