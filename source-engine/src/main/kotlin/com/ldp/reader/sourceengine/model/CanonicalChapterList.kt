package com.ldp.reader.sourceengine.model

data class CanonicalChapterList(
    val chapters: List<CanonicalChapter>,
    val duplicateCount: Int,
    val missingOrdinalRanges: List<ChapterOrdinalRange>
)
