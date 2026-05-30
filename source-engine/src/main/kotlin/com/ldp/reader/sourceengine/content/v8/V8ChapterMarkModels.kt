package com.ldp.reader.sourceengine.content.v8

data class V8ChapterMarkResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val state: V8ChapterMarkState,
    val confidence: Double,
    val qualityType: V8ChapterQualityType?,
    val suggestionState: V8NovelStateOutputType?,
    val action: V8CleanAction?,
    val reasons: List<String>
)

enum class V8ChapterMarkState {
    NORMAL,
    WRONG,
    NON_STORY,
    BAD_EXTRACTION,
    INCONCLUSIVE;

    val isBadForTail: Boolean
        get() = this == WRONG || this == NON_STORY || this == BAD_EXTRACTION
}

enum class V8NovelStateOutputType {
    NORMAL,
    NON_STORY,
    BAD_EXTRACTION,
    POLLUTED_SUFFIX,
    UNCERTAIN
}

enum class V8CleanAction {
    KEEP,
    MARK_ONLY,
    SUGGEST_DELETE,
    AUTO_DELETE_ALLOWED
}
