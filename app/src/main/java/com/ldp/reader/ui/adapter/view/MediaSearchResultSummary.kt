package com.ldp.reader.ui.adapter.view

import com.ldp.reader.media.MediaSearchBook
import com.ldp.reader.media.ReaderMediaKind

internal object MediaSearchResultSummary {
    fun text(book: MediaSearchBook, kind: ReaderMediaKind?): String {
        val countLabel = book.chapterCount
            .takeIf { it > 0 }
            ?.let { count ->
                when (kind) {
                    ReaderMediaKind.COMIC -> "共 $count 话"
                    ReaderMediaKind.AUDIO -> "共 $count 集"
                    ReaderMediaKind.NOVEL, null -> ""
                }
            }
            .orEmpty()
        val latest = book.latest.trim()
            .takeUnless { latest ->
                if (countLabel.isBlank()) {
                    false
                } else {
                    val normalizedLatest = latest.filterNot(Char::isWhitespace)
                    val normalizedCount = countLabel.filterNot(Char::isWhitespace)
                    normalizedLatest == normalizedCount ||
                        normalizedLatest == normalizedCount.removePrefix("共")
                }
            }
            .orEmpty()
        return listOf(countLabel, book.author, latest, book.intro)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }
}
