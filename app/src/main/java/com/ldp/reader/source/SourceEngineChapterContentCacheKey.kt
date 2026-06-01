package com.ldp.reader.source

object SourceEngineChapterContentCacheKey {
    fun fileName(bookId: String?, title: String?, link: String?): String? {
        val sourceEngineChapter = SourceEngineBookRoute.isShelfBookId(bookId) ||
            SourceEngineBookRoute.isBookId(bookId) ||
            SourceEngineBookRoute.isChapterId(link)
        if (!sourceEngineChapter) {
            return title
        }
        require(SourceEngineBookRoute.isChapterId(link)) {
            "Source-engine chapter link is required for content cache key."
        }
        return link
    }
}
