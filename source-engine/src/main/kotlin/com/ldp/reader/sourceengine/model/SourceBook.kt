package com.ldp.reader.sourceengine.model

data class SourceBook(
    val source: BookSource,
    val name: String,
    val author: String,
    val bookUrl: String,
    val coverUrl: String,
    val intro: String,
    val kind: String,
    val lastChapter: String
)
