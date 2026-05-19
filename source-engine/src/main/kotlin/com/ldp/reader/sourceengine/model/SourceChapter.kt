package com.ldp.reader.sourceengine.model

data class SourceChapter(
    val source: BookSource,
    val book: SourceBook,
    val index: Int,
    val name: String,
    val chapterUrl: String
)
