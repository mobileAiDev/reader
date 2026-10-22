package com.ldp.reader.widget.page

/** Keeps measured line breaks on valid UTF-16 code-point boundaries. */
internal object ReaderTextSplitter {
    /**
     * Closing punctuation that Chinese typesetting never starts a line with (标点避头).
     * "…" covers both halves of "……", "—" both halves of "——".
     */
    private const val NO_LINE_START = "，。、；：？！）】」』》〉〕”’…—～·%,.;:?!)]}"

    /** Opening punctuation that never ends a line (标点避尾). */
    private const val NO_LINE_END = "（【「『《〈〔“‘([{"

    /** A line gives up at most this many characters to keep punctuation attached. */
    private const val MAX_PUNCTUATION_SHIFT = 2

    fun safeBreakCount(text: String, measuredCount: Int): Int {
        if (text.isEmpty()) return 0
        var count = measuredCount.coerceIn(0, text.length)
        if (
            count in 1 until text.length &&
            text[count - 1].isHighSurrogate() &&
            text[count].isLowSurrogate()
        ) {
            count -= 1
        }
        if (count > 0) return count
        return if (text.length >= 2 && text[0].isHighSurrogate() && text[1].isLowSurrogate()) 2 else 1
    }

    /**
     * Measured break adjusted for CJK line-breaking rules: the next line must not open with
     * closing punctuation and this line must not end with opening punctuation. The preceding
     * character moves down with the punctuation; justified drawing absorbs the freed width.
     */
    fun lineBreakCount(text: String, measuredCount: Int): Int {
        val count = safeBreakCount(text, measuredCount)
        if (count >= text.length) return count
        var adjusted = count
        while (
            adjusted > 1 &&
            count - adjusted < MAX_PUNCTUATION_SHIFT &&
            (NO_LINE_START.indexOf(text[adjusted]) >= 0 || NO_LINE_END.indexOf(text[adjusted - 1]) >= 0)
        ) {
            adjusted -= 1
        }
        val settled = NO_LINE_START.indexOf(text[adjusted]) < 0 &&
            NO_LINE_END.indexOf(text[adjusted - 1]) < 0
        return if (settled) safeBreakCount(text, adjusted) else count
    }
}
