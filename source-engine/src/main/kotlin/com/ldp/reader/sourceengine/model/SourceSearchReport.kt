package com.ldp.reader.sourceengine.model

data class SourceSearchReport(
    val books: List<SourceBook>,
    val attempts: List<SourceSearchAttempt>
)

data class SourceSearchAttempt(
    val sourceName: String,
    val success: Boolean,
    val resultCount: Int,
    val message: String
)
