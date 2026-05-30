package com.ldp.reader.source

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.sourceengine.content.v8.V8ValidationPlanner
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.widget.page.TxtChapter
import java.security.MessageDigest
import java.nio.charset.Charset
import java.util.Locale

object SourceEngineCatalogMarkRegistry {
    data class CatalogIdentity(
        val catalogSize: Int,
        val firstTitle: String,
        val lastTitle: String,
        val tailTitleDigest: String
    )

    data class MarkUpdate(
        val sourceBookKey: String,
        val sourceLabel: String,
        val sourceUrl: String?,
        val bookName: String?,
        val author: String?,
        val marks: Map<Int, V8ChapterMarkResult>,
        val catalogIdentity: CatalogIdentity?,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    data class ApplyStats(
        val changed: Int,
        val matched: Int,
        val hidden: Int
    )

    private val markUpdates = MutableLiveData<MarkUpdate>()
    private val marksBySourceBook = LinkedHashMap<String, MarkSet>()
    private val marksBySourceBookIdentity = LinkedHashMap<String, MarkSet>()
    private val marksByBookIdentityTitle = LinkedHashMap<String, Map<String, TitleMark>>()
    private val runtimeContentMarksByChapterLink = LinkedHashMap<String, V8ChapterMarkResult>()

    val updates: LiveData<MarkUpdate> = markUpdates

    @Synchronized
    fun record(
        sourceBookKey: String,
        sourceLabel: String,
        marks: List<V8ChapterMarkResult>
    ) {
        record(sourceBookKey, sourceLabel, null, null, null, marks)
    }

    @Synchronized
    fun record(
        sourceBookKey: String,
        sourceLabel: String,
        sourceUrl: String?,
        bookName: String?,
        author: String?,
        marks: List<V8ChapterMarkResult>,
        catalogIdentity: CatalogIdentity? = null
    ) {
        val markSet = MarkSet(
            catalogIdentity = catalogIdentity,
            byChapterIndex = marks.associateBy { mark -> mark.chapterIndex }
        )
        marksBySourceBook[sourceBookKey] = markSet
        sourceBookIdentityKey(sourceUrl, bookName, author)?.let { key ->
            marksBySourceBookIdentity[key] = markSet
        }
        bookIdentityKey(bookName, author)?.let { key ->
            marksByBookIdentityTitle[key] = marks.associate { mark ->
                normalizedChapterTitle(mark.chapterTitle) to TitleMark(sourceBookKey, mark)
            }
        }
        markUpdates.postValue(
            MarkUpdate(
                sourceBookKey,
                sourceLabel,
                sourceUrl,
                bookName,
                author,
                markSet.byChapterIndex,
                catalogIdentity
            )
        )
    }

    @Synchronized
    fun applyTo(chapters: List<TxtChapter>): Int {
        return applyToWithStats(chapters).changed
    }

    @Synchronized
    fun recordReadableContent(chapter: TxtChapter): Boolean {
        return recordRuntimeContentMark(
            chapter,
            "runtime-readable-content",
            SOURCE_ENGINE_RUNTIME_READABLE_CONTENT_REASON
        )
    }

    private fun recordRuntimeContentMark(
        chapter: TxtChapter,
        sourceLabel: String,
        reason: String
    ): Boolean {
        val link = chapter.link ?: return false
        if (!SourceEngineBookRoute.isChapterId(link)) return false
        val payload = runCatching { SourceEngineBookRoute.decodeChapterId(link) }.getOrNull() ?: return false
        val mark = V8ChapterMarkResult(
            chapterIndex = payload.index,
            chapterTitle = chapter.title ?: payload.chapterName,
            state = V8ChapterMarkState.NORMAL,
            confidence = 1.0,
            qualityType = null,
            suggestionState = null,
            action = null,
            reasons = listOf(reason)
        )
        val previous = runtimeContentMarksByChapterLink[link]
        runtimeContentMarksByChapterLink[link] = mark
        val changed = chapter.applyIntegrityMark(mark)
        if (previous != mark || changed) {
            markUpdates.postValue(
                MarkUpdate(
                    sourceBookKey(payload.sourceUrl, payload.bookUrl),
                    sourceLabel,
                    payload.sourceUrl,
                    payload.bookName,
                    payload.author,
                    mapOf(payload.index to mark),
                    catalogIdentity = null
                )
            )
        }
        return changed || previous != mark
    }

    @Synchronized
    fun applyToWithStats(chapters: List<TxtChapter>): ApplyStats {
        val currentCatalogs = currentCatalogs(chapters.mapNotNull { chapter ->
            chapterContext(chapter.link, chapter.title)
        })
        var changed = 0
        var matched = 0
        chapters.forEach { chapter ->
            when (val resolution = markResolutionForChapterLink(chapter.link, currentCatalogs)) {
                is MarkResolution.Found -> {
                    matched += 1
                    if (chapter.applyIntegrityMark(resolution.mark)) changed += 1
                }
                MarkResolution.Clear -> {
                    if (chapter.applyIntegrityMark(null)) changed += 1
                }
                MarkResolution.NoRegistry -> {
                    if (chapter.hasStaleSourceIntegrityMark() && chapter.applyIntegrityMark(null)) changed += 1
                }
            }
        }
        return ApplyStats(
            changed = changed,
            matched = matched,
            hidden = chapters.count { chapter -> chapter.hasHiddenSourceIntegrityMark() }
        )
    }

    @Synchronized
    fun applyToBookChapters(chapters: List<BookChapterBean>): Int {
        return applyToBookChaptersWithStats(chapters).changed
    }

    @Synchronized
    fun applyToBookChaptersWithStats(chapters: List<BookChapterBean>): ApplyStats {
        val currentCatalogs = currentCatalogs(chapters.mapNotNull { chapter ->
            chapterContext(chapter.link, chapter.title)
        })
        var changed = 0
        var matched = 0
        chapters.forEach { chapter ->
            when (val resolution = markResolutionForChapterLink(chapter.link, currentCatalogs)) {
                is MarkResolution.Found -> {
                    matched += 1
                    if (chapter.applyIntegrityMark(resolution.mark)) changed += 1
                }
                MarkResolution.Clear -> {
                    if (chapter.applyIntegrityMark(null)) changed += 1
                }
                MarkResolution.NoRegistry -> {
                    if (chapter.hasStaleSourceIntegrityMark() && chapter.applyIntegrityMark(null)) changed += 1
                }
            }
        }
        return ApplyStats(
            changed = changed,
            matched = matched,
            hidden = chapters.count { chapter -> chapter.hasHiddenSourceIntegrityMark() }
        )
    }

    @Synchronized
    fun countMatching(chapters: List<TxtChapter>): Int {
        val currentCatalogs = currentCatalogs(chapters.mapNotNull { chapter ->
            chapterContext(chapter.link, chapter.title)
        })
        return chapters.count { chapter -> markResolutionForChapterLink(chapter.link, currentCatalogs) is MarkResolution.Found }
    }

    @Synchronized
    fun countMatchingBookChapters(chapters: List<BookChapterBean>): Int {
        val currentCatalogs = currentCatalogs(chapters.mapNotNull { chapter ->
            chapterContext(chapter.link, chapter.title)
        })
        return chapters.count { chapter -> markResolutionForChapterLink(chapter.link, currentCatalogs) is MarkResolution.Found }
    }

    @Synchronized
    internal fun clearForTest() {
        marksBySourceBook.clear()
        marksBySourceBookIdentity.clear()
        marksByBookIdentityTitle.clear()
        runtimeContentMarksByChapterLink.clear()
    }

    fun sourceBookKey(sourceUrl: String, bookUrl: String): String {
        return sourceUrl + "\n" + bookUrl
    }

    fun catalogIdentity(chapterTitles: List<String>): CatalogIdentity {
        return CatalogIdentity(
            catalogSize = chapterTitles.size,
            firstTitle = chapterTitles.firstOrNull().orEmpty(),
            lastTitle = chapterTitles.lastOrNull().orEmpty(),
            tailTitleDigest = md5(
                chapterTitles
                    .takeLast(V8ValidationPlanner.TAIL_RISK_WINDOW_CHAPTERS)
                    .joinToString("\n")
            )
        )
    }

    private fun markResolutionForChapterLink(
        link: String?,
        currentCatalogs: CurrentCatalogs
    ): MarkResolution {
        if (!SourceEngineBookRoute.isChapterId(link)) return MarkResolution.NoRegistry
        val payload = runCatching { SourceEngineBookRoute.decodeChapterId(requireNotNull(link)) }.getOrNull()
            ?: return MarkResolution.NoRegistry
        val sourceBookKey = sourceBookKey(payload.sourceUrl, payload.bookUrl)
        var staleIndexRegistry = false
        marksBySourceBook[sourceBookKey]?.let { markSet ->
            if (markSet.matches(currentCatalogs.bySourceBook[sourceBookKey])) {
                return markSet.byChapterIndex[payload.index]?.let { mark -> MarkResolution.Found(mark) }
                    ?: MarkResolution.Clear
            }
            staleIndexRegistry = true
        }
        sourceBookIdentityKey(payload.sourceUrl, payload.bookName, payload.author)?.let { key ->
            marksBySourceBookIdentity[key]?.let { markSet ->
                if (markSet.matches(currentCatalogs.bySourceIdentity[key])) {
                    return markSet.byChapterIndex[payload.index]?.let { mark -> MarkResolution.Found(mark) }
                        ?: MarkResolution.Clear
                }
                staleIndexRegistry = true
            }
        }
        bookIdentityKey(payload.bookName, payload.author)?.let { key ->
            marksByBookIdentityTitle[key]?.get(normalizedChapterTitle(payload.chapterName))?.let { titleMark ->
                if (titleMark.sourceBookKey != sourceBookKey) {
                    return MarkResolution.Found(titleMark.mark)
                }
            }
        }
        runtimeContentMarksByChapterLink[link]?.let { mark ->
            return MarkResolution.Found(mark)
        }
        return if (staleIndexRegistry) MarkResolution.Clear else MarkResolution.NoRegistry
    }

    private fun sourceBookIdentityKey(sourceUrl: String?, bookName: String?, author: String?): String? {
        val source = sourceUrl?.trim().orEmpty()
        val name = normalizedIdentityPart(bookName)
        val writer = normalizedIdentityPart(author)
        if (source.isBlank() || name.isBlank()) return null
        return source + "\n" + name + "\n" + writer
    }

    private fun bookIdentityKey(bookName: String?, author: String?): String? {
        val name = normalizedIdentityPart(bookName)
        val writer = normalizedIdentityPart(author)
        if (name.isBlank()) return null
        return name + "\n" + writer
    }

    private fun normalizedIdentityPart(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("""\s+"""), "")
            .orEmpty()
    }

    private fun normalizedChapterTitle(value: String?): String {
        return normalizedIdentityPart(value)
    }

    private data class MarkSet(
        val catalogIdentity: CatalogIdentity?,
        val byChapterIndex: Map<Int, V8ChapterMarkResult>
    ) {
        fun matches(currentCatalogIdentity: CatalogIdentity?): Boolean {
            return catalogIdentity == null || catalogIdentity == currentCatalogIdentity
        }
    }

    private data class TitleMark(
        val sourceBookKey: String,
        val mark: V8ChapterMarkResult
    )

    private data class ChapterContext(
        val payload: SourceEngineBookRoute.ChapterPayload,
        val displayTitle: String
    )

    private data class CurrentCatalogs(
        val bySourceBook: Map<String, CatalogIdentity>,
        val bySourceIdentity: Map<String, CatalogIdentity>
    )

    private fun currentCatalogs(chapters: List<ChapterContext>): CurrentCatalogs {
        return CurrentCatalogs(
            bySourceBook = chapters.groupBy { chapter ->
                sourceBookKey(chapter.payload.sourceUrl, chapter.payload.bookUrl)
            }.mapValues { (_, grouped) ->
                catalogIdentity(grouped.map { chapter -> chapter.displayTitle })
            },
            bySourceIdentity = chapters.groupBy { chapter ->
                sourceBookIdentityKey(
                    chapter.payload.sourceUrl,
                    chapter.payload.bookName,
                    chapter.payload.author
                ).orEmpty()
            }.filterKeys { key -> key.isNotBlank() }
                .mapValues { (_, grouped) ->
                    catalogIdentity(grouped.map { chapter -> chapter.displayTitle })
                }
        )
    }

    private fun chapterContext(link: String?, displayTitle: String?): ChapterContext? {
        if (!SourceEngineBookRoute.isChapterId(link)) return null
        val payload = runCatching { SourceEngineBookRoute.decodeChapterId(requireNotNull(link)) }.getOrNull()
            ?: return null
        return ChapterContext(payload, displayTitle.orEmpty())
    }

    private fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charset.defaultCharset()))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private sealed class MarkResolution {
        data class Found(val mark: V8ChapterMarkResult) : MarkResolution()
        object Clear : MarkResolution()
        object NoRegistry : MarkResolution()
    }

    private fun TxtChapter.applyIntegrityMark(mark: V8ChapterMarkResult?): Boolean {
        val state = mark?.state?.name
        val confidence = mark?.confidence ?: 0.0
        val reason = mark?.let { sourceIntegrityPersistedReason(it.reasons) }
        if (sourceIntegrityState == state &&
            sourceIntegrityConfidence == confidence &&
            sourceIntegrityReason == reason
        ) {
            return false
        }
        sourceIntegrityState = state
        sourceIntegrityConfidence = confidence
        sourceIntegrityReason = reason
        return true
    }

    private fun BookChapterBean.applyIntegrityMark(mark: V8ChapterMarkResult?): Boolean {
        val state = mark?.state?.name
        val confidence = mark?.confidence ?: 0.0
        val reason = mark?.let { sourceIntegrityPersistedReason(it.reasons) }
        if (sourceIntegrityState == state &&
            sourceIntegrityConfidence == confidence &&
            sourceIntegrityReason == reason
        ) {
            return false
        }
        sourceIntegrityState = state
        sourceIntegrityConfidence = confidence
        sourceIntegrityReason = reason
        return true
    }
}

fun TxtChapter.hasHiddenSourceIntegrityMark(): Boolean {
    return isCurrentSourceIntegrityReason(sourceIntegrityReason) &&
        (sourceIntegrityState == V8ChapterMarkState.WRONG.name ||
            sourceIntegrityState == V8ChapterMarkState.NON_STORY.name ||
            sourceIntegrityState == V8ChapterMarkState.BAD_EXTRACTION.name)
}

fun TxtChapter.hasSourceIntegrityAnalysisMark(): Boolean {
    return !sourceIntegrityState.isNullOrBlank() &&
        isCurrentSourceIntegrityAnalysisReason(sourceIntegrityReason)
}

private fun TxtChapter.hasStaleSourceIntegrityMark(): Boolean {
    return !sourceIntegrityState.isNullOrBlank() && !isCurrentSourceIntegrityReason(sourceIntegrityReason)
}

private fun BookChapterBean.hasStaleSourceIntegrityMark(): Boolean {
    return !sourceIntegrityState.isNullOrBlank() && !isCurrentSourceIntegrityReason(sourceIntegrityReason)
}
