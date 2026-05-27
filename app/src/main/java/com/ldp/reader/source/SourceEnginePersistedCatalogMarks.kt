package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkState
import java.util.Locale

object SourceEnginePersistedCatalogMarks {
    data class MergeResult(
        val incomingMarkedBefore: Int,
        val persistedMarked: Int,
        val persistedHidden: Int,
        val restored: Int,
        val exactRestored: Int,
        val identityRestored: Int
    )

    fun mergeInto(
        incoming: List<BookChapterBean>,
        persisted: List<BookChapterBean>
    ): MergeResult {
        incoming.forEach { chapter -> chapter.clearStaleSourceIntegrityMark() }
        val incomingMarkedBefore = countMarked(incoming)
        val persistedMarked = countMarked(persisted)
        val persistedHidden = countHidden(persisted)
        if (incoming.isEmpty() || persistedMarked == 0) {
            return MergeResult(
                incomingMarkedBefore = incomingMarkedBefore,
                persistedMarked = persistedMarked,
                persistedHidden = persistedHidden,
                restored = 0,
                exactRestored = 0,
                identityRestored = 0
            )
        }

        val exactMarks = persisted
            .filter { chapter -> chapter.hasSourceIntegrityMark() && !chapter.link.isNullOrBlank() }
            .associateBy { chapter -> chapter.link }
        val identityMarks = persisted
            .filter { chapter -> chapter.hasSourceIntegrityMark() }
            .mapNotNull { chapter -> sourceChapterIdentityKey(chapter)?.let { key -> key to chapter } }
            .toMap()

        var exactRestored = 0
        var identityRestored = 0
        incoming.forEach { chapter ->
            if (chapter.hasSourceIntegrityMark()) return@forEach
            val persistedChapter = exactMarks[chapter.link]?.also {
                exactRestored += 1
            } ?: sourceChapterIdentityKey(chapter)?.let { key ->
                identityMarks[key]?.also { identityRestored += 1 }
            } ?: return@forEach
            chapter.sourceIntegrityState = persistedChapter.sourceIntegrityState
            chapter.sourceIntegrityConfidence = persistedChapter.sourceIntegrityConfidence
            chapter.sourceIntegrityReason = persistedChapter.sourceIntegrityReason
        }

        return MergeResult(
            incomingMarkedBefore = incomingMarkedBefore,
            persistedMarked = persistedMarked,
            persistedHidden = persistedHidden,
            restored = exactRestored + identityRestored,
            exactRestored = exactRestored,
            identityRestored = identityRestored
        )
    }

    fun countMarked(chapters: List<BookChapterBean>): Int {
        return chapters.count { chapter -> chapter.hasSourceIntegrityMark() }
    }

    fun countHidden(chapters: List<BookChapterBean>): Int {
        return chapters.count { chapter -> chapter.hasHiddenSourceIntegrityMark() }
    }

    private fun sourceChapterIdentityKey(chapter: BookChapterBean): String? {
        val link = chapter.link ?: return null
        if (!SourceEngineBookRoute.isChapterId(link)) return null
        val payload = runCatching { SourceEngineBookRoute.decodeChapterId(link) }.getOrNull() ?: return null
        val source = payload.sourceUrl.trim()
        val bookName = normalizedIdentityPart(payload.bookName)
        val author = normalizedIdentityPart(payload.author)
        val chapterName = normalizedIdentityPart(payload.chapterName)
        if (source.isBlank() || bookName.isBlank() || chapterName.isBlank()) return null
        return source + "\n" + bookName + "\n" + author + "\n" + payload.index + "\n" + chapterName
    }

    private fun normalizedIdentityPart(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("""\s+"""), "")
            .orEmpty()
    }
}

fun BookChapterBean.hasHiddenSourceIntegrityMark(): Boolean {
    return isCurrentSourceIntegrityReason(sourceIntegrityReason) &&
        (sourceIntegrityState == V5ChapterMarkState.WRONG.name ||
            sourceIntegrityState == V5ChapterMarkState.NON_STORY.name ||
            sourceIntegrityState == V5ChapterMarkState.BAD_EXTRACTION.name)
}

private fun BookChapterBean.hasSourceIntegrityMark(): Boolean {
    return !sourceIntegrityState.isNullOrBlank() && isCurrentSourceIntegrityReason(sourceIntegrityReason)
}

private fun BookChapterBean.clearStaleSourceIntegrityMark() {
    if (sourceIntegrityState.isNullOrBlank() || isCurrentSourceIntegrityReason(sourceIntegrityReason)) return
    sourceIntegrityState = null
    sourceIntegrityConfidence = 0.0
    sourceIntegrityReason = null
}
