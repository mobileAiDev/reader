package com.ldp.reader.sourceengine.model

data class CleanContent(
    val rawContent: String,
    val cleanedContent: String,
    val report: ContentQualityReport
)
