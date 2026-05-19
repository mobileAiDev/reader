package com.ldp.reader.sourceengine.model

data class NormalizedChapterTitle(
    val rawTitle: String,
    val displayTitle: String,
    val key: String,
    val ordinal: Int?
)
