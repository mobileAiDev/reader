package com.ldp.reader.sourceengine.model

data class SourceBookDetail(
    val book: SourceBook,
    val name: String,
    val author: String,
    val coverUrl: String,
    val intro: String,
    val kind: String,
    val lastChapter: String,
    val tocUrl: String
)
