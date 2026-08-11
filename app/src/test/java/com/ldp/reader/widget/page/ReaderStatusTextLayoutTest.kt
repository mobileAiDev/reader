package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStatusTextLayoutTest {
    @Test
    fun longErrorWrapsWithinReadableWidthUsingAFixedStatusSize() {
        val message = "本章加载失败，轻点屏幕左右两侧重试"

        val lines = ReaderStatusTextLayout.wrap(
            text = message,
            maxWidth = 9f,
            breakText = { remaining, width -> minOf(remaining.length, width.toInt()) }
        )

        assertTrue(lines.size > 1)
        assertEquals(message, lines.joinToString(separator = ""))
        assertTrue(lines.all { it.length <= 9 })
        assertEquals(16, ReaderStatusTextLayout.TEXT_SIZE_SP)
    }
}
