package com.ldp.reader.widget.page

internal object ReaderStatusTextLayout {
    const val TEXT_SIZE_SP = 16
    const val HORIZONTAL_PADDING_DP = 32
    const val LINE_SPACING_DP = 6

    fun wrap(
        text: String,
        maxWidth: Float,
        breakText: (remaining: String, maxWidth: Float) -> Int
    ): List<String> {
        if (text.isEmpty()) return listOf("")
        require(maxWidth > 0f) { "maxWidth must be positive" }

        return buildList {
            text.split('\n').forEach { paragraph ->
                if (paragraph.isEmpty()) {
                    add("")
                    return@forEach
                }

                var offset = 0
                while (offset < paragraph.length) {
                    val remaining = paragraph.substring(offset)
                    val count = breakText(remaining, maxWidth)
                        .coerceIn(1, remaining.length)
                    add(remaining.substring(0, count))
                    offset += count
                }
            }
        }
    }
}
