package com.ldp.reader.widget.page

/** Resolves a visible catalog row to the full chapter ordinal consumed by [PageLoader]. */
internal object ReaderChapterSelectionResolver {
    fun fullChapterPosition(chapter: TxtChapter, adapterPosition: Int): Int {
        return chapter.catalogIndex.takeIf { it >= 0 } ?: adapterPosition
    }
}
