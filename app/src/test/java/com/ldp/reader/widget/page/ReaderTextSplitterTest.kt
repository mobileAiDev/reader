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

    @Test
    fun closingPunctuationNeverStartsTheNextLine() {
        val text = "少年翻开那本旧书。老船夫笑了"
        val measured = text.indexOf('。')

        val count = ReaderTextSplitter.lineBreakCount(text, measured)

        assertEquals(measured - 1, count)
        assertEquals("书。老船夫笑了", text.substring(count))
    }

    @Test
    fun consecutiveClosingMarksMoveTogetherWithinTheShiftBudget() {
        val text = "他说“走吧”。然后离开"
        val measured = text.indexOf('”')

        val count = ReaderTextSplitter.lineBreakCount(text, measured)

        assertEquals("吧”。然后离开", text.substring(count))
    }

    @Test
    fun openingQuoteNeverEndsALine() {
        val text = "母亲站在门口「什么也没说」"
        val measured = text.indexOf('「') + 1

        val count = ReaderTextSplitter.lineBreakCount(text, measured)

        assertEquals("「什么也没说」", text.substring(count))
    }

    @Test
    fun unbreakablePunctuationRunKeepsTheMeasuredBreak() {
        val text = "甲……。。！？"
        val measured = 3

        assertEquals(measured, ReaderTextSplitter.lineBreakCount(text, measured))
    }

    @Test
    fun ordinaryBreaksAndParagraphEndsAreUntouched() {
        assertEquals(3, ReaderTextSplitter.lineBreakCount("正文内容继续", measuredCount = 3))
        assertEquals(4, ReaderTextSplitter.lineBreakCount("正文。\n", measuredCount = 4))
    }
}
