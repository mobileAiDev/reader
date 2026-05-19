package com.ldp.reader.sourceengine.content

interface ContentBelongingChecker {
    fun inspect(input: ContentInspectionInput): ContentBelongingReport
}

data class ContentInspectionInput(
    val cleanedContent: String,
    val chapterTitle: String,
    val bookName: String = "",
    val author: String = "",
    val referenceContents: List<String> = emptyList()
)

data class ContentBelongingReport(
    val belongsToChapter: Boolean,
    val score: Int,
    val markers: List<String>
)
