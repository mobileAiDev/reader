package com.ldp.reader.widget.page

/** Keeps measured line breaks on valid UTF-16 code-point boundaries. */
internal object ReaderTextSplitter {
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
}
