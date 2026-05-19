package com.ldp.reader.sourceengine.model

data class ContentQualityReport(
    val qualityScore: Int,
    val rawLength: Int,
    val cleanedLength: Int,
    val paragraphCount: Int,
    val removedLineCount: Int,
    val duplicateLineCount: Int,
    val pollutionMarkers: List<String>,
    val warnings: List<String>,
    val coherenceScore: Int = 100,
    val coherenceMarkers: List<String> = emptyList()
)
