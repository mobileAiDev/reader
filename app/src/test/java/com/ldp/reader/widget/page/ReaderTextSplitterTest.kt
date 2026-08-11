package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderTextSplitterTest {

    @Test
    fun measuredBreakNeverReturnsHalfOfSupplementaryCharacter() {
        val text = "𠮷野家😀正文"

        val firstCount = ReaderTextSplitter.safeBreakCount(text, measuredCount = 1)
        val emojiStart = text.indexOf("😀")
        val emojiCount = ReaderTextSplitter.safeBreakCount(text, measuredCount = emojiStart + 1)

        assertEquals(2, firstCount)
        assertEquals("𠮷", text.substring(0, firstCount))
        assertEquals(emojiStart, emojiCount)
        assertFalse(text.substring(0, emojiCount).contains('\uFFFD'))
    }

    @Test
    fun zeroMeasurementStillMakesProgressWithoutSplittingFirstCodePoint() {
        assertEquals(1, ReaderTextSplitter.safeBreakCount("正文", measuredCount = 0))
        assertEquals(2, ReaderTextSplitter.safeBreakCount("😀正文", measuredCount = 0))
        assertEquals(0, ReaderTextSplitter.safeBreakCount("", measuredCount = 0))
    }

    @Test
    fun regularMeasuredCountIsPreserved() {
        assertEquals(3, ReaderTextSplitter.safeBreakCount("abcdef", measuredCount = 3))
        assertEquals(6, ReaderTextSplitter.safeBreakCount("abcdef", measuredCount = 99))
    }
}
