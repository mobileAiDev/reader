package com.ldp.reader.sourceengine.model

data class CanonicalChapter(
    val key: String,
    val displayTitle: String,
    val ordinal: Int?,
    val sourceChapters: List<SourceChapter>
) {
    val duplicateCount: Int
        get() = (sourceChapters.size - 1).coerceAtLeast(0)
}
