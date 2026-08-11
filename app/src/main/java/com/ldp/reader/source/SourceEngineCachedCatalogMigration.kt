package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.sourceengine.catalog.CatalogNavigationPollutionFilter
import com.ldp.reader.widget.page.BookRecordChapterResolver

/**
 * Upgrades persisted SourceEngine catalogs through the same conservative filter
 * used for newly parsed source catalogs.
 */
object SourceEngineCachedCatalogMigration {
    private val navigationPollutionFilter = CatalogNavigationPollutionFilter()

    fun sanitize(chapters: List<BookChapterBean>): Result {
        val filtered = navigationPollutionFilter.filter(chapters) { chapter -> chapter.title.orEmpty() }
        if (filtered.removedOriginalIndices.isEmpty()) {
            return Result(
                originalChapters = chapters,
                chapters = chapters,
                retainedOriginalIndices = filtered.retainedOriginalIndices,
                removedCount = 0
            )
        }
        filtered.entries.forEachIndexed { index, chapter ->
            chapter.start = index.toLong()
        }
        return Result(
            originalChapters = chapters,
            chapters = filtered.entries,
            retainedOriginalIndices = filtered.retainedOriginalIndices,
            removedCount = filtered.removedOriginalIndices.size
        )
    }

    class Result internal constructor(
        private val originalChapters: List<BookChapterBean>,
        val chapters: List<BookChapterBean>,
        retainedOriginalIndices: List<Int>,
        val removedCount: Int
    ) {
        private val newIndexByOldIndex = IntArray(originalChapters.size) { MISSING_INDEX }.apply {
            retainedOriginalIndices.forEachIndexed { newIndex, oldIndex ->
                this[oldIndex] = newIndex
            }
        }

        val changed: Boolean
            get() = removedCount > 0

        fun newIndexForOldIndex(oldIndex: Int): Int? {
            if (oldIndex !in newIndexByOldIndex.indices) return null
            return newIndexByOldIndex[oldIndex].takeIf { index -> index != MISSING_INDEX }
                ?: nearestRetainedNewIndex(oldIndex)
        }

        fun isOldIndexRetained(oldIndex: Int): Boolean {
            return oldIndex in newIndexByOldIndex.indices && newIndexByOldIndex[oldIndex] != MISSING_INDEX
        }

        /**
         * Moves a legacy numeric record with the filtered catalog while preserving
         * any existing anchor and the page position inside the chapter.
         */
        fun remapRecord(record: BookRecordBean): Boolean {
            if (!changed) return false
            val targetOldIndex = BookRecordChapterResolver.resolve(
                previousOrdinal = record.chapter,
                savedLink = record.chapterLink,
                savedTitle = record.chapterTitle,
                chapters = originalChapters,
                linkOf = { chapter -> chapter.link },
                titleOf = { chapter -> chapter.title }
            ) ?: record.chapter
            if (targetOldIndex !in originalChapters.indices) return false
            val newIndex = newIndexForOldIndex(targetOldIndex) ?: return false
            val targetRetained = isOldIndexRetained(targetOldIndex)
            val anchorChapter = if (targetRetained) {
                originalChapters[targetOldIndex]
            } else {
                chapters[newIndex]
            }
            var changedRecord = false
            if (record.chapter != newIndex) {
                record.chapter = newIndex
                changedRecord = true
            }
            if ((!targetRetained || record.chapterLink.isNullOrBlank()) && !anchorChapter.link.isNullOrBlank()) {
                record.chapterLink = anchorChapter.link
                changedRecord = true
            }
            if ((!targetRetained || record.chapterTitle.isNullOrBlank()) && !anchorChapter.title.isNullOrBlank()) {
                record.chapterTitle = anchorChapter.title
                changedRecord = true
            }
            if (!targetRetained && record.pagePos != 0) {
                record.pagePos = 0
                changedRecord = true
            }
            return changedRecord
        }

        private fun nearestRetainedNewIndex(oldIndex: Int): Int? {
            for (candidateOldIndex in (oldIndex + 1)..newIndexByOldIndex.lastIndex) {
                val newIndex = newIndexByOldIndex[candidateOldIndex]
                if (newIndex != MISSING_INDEX) return newIndex
            }
            for (candidateOldIndex in (oldIndex - 1) downTo 0) {
                val newIndex = newIndexByOldIndex[candidateOldIndex]
                if (newIndex != MISSING_INDEX) return newIndex
            }
            return null
        }
    }

    private const val MISSING_INDEX = -1
}
