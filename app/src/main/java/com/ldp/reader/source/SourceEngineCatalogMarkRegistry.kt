package com.ldp.reader.source

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.sourceengine.content.v8.V8ValidationPlanner
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.sourceengine.model.SourceChapter
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
    private val marksByBookIdentityTitle = LinkedHashMap<String, LinkedHashMap<String, List<TitleMark>>>()
    private val runtimeContentMarksByChapterLink = LinkedHashMap<String, V8ChapterMarkResult>()
    private val displayedContentSourcesByChapterLink = LinkedHashMap<String, DisplayedContentSource>()

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
        catalogIdentity: CatalogIdentity? = null,
        coveredChapterIndexes: Collection<Int>? = null
    ) {
        val markSet = MarkSet(
            catalogIdentity = catalogIdentity,
            byChapterIndex = marks.associateBy { mark -> mark.chapterIndex },
            coveredChapterIndexes = coveredChapterIndexes?.toSet()
        )
        marksBySourceBook[sourceBookKey] = markSet
        sourceBookIdentityKey(sourceUrl, bookName, author)?.let { key ->
            marksBySourceBookIdentity[key] = markSet
        }
        bookIdentityKey(bookName, author)?.let { key ->
            replaceTitleMarksForSource(
                key,
                sourceBookKey,
                catalogIdentity,
                marks,
                allowCatalogGrowth = coveredChapterIndexes != null
            )
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
    fun recordDisplayedContentSource(
        displayChapter: TxtChapter,
        contentChapter: SourceChapter,
        sourceLabel: String
    ): Boolean {
        val link = displayChapter.link ?: return false
        if (!SourceEngineBookRoute.isChapterId(link)) return false
        val displayedSource = DisplayedContentSource(
            sourceBookKey = sourceBookKey(contentChapter.source.sourceUrl, contentChapter.book.bookUrl),
            sourceUrl = contentChapter.source.sourceUrl,
            bookUrl = contentChapter.book.bookUrl,
            bookName = contentChapter.book.name,
            author = contentChapter.book.author,
            chapterIndex = contentChapter.index,
            chapterTitle = contentChapter.name
        )
        val previous = displayedContentSourcesByChapterLink[link]
        displayedContentSourcesByChapterLink[link] = displayedSource
        if (previous != displayedSource) {
            markUpdates.postValue(
                MarkUpdate(
                    sourceBookKey = displayedSource.sourceBookKey,
                    sourceLabel = sourceLabel,
                    sourceUrl = displayedSource.sourceUrl,
                    bookName = displayedSource.bookName,
                    author = displayedSource.author,
                    marks = emptyMap(),
                    catalogIdentity = null
                )
            )
        }
        return previous != displayedSource
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
    fun updateTargetsChapters(update: MarkUpdate, chapters: List<TxtChapter>): Boolean {
        val contexts = chapters.mapNotNull { chapter -> chapterContext(chapter.link, chapter.title) }
        if (contexts.isEmpty()) return false
        if (contexts.any { chapter ->
                sourceBookKey(chapter.payload.sourceUrl, chapter.payload.bookUrl) == update.sourceBookKey
            }) {
            return true
        }
        val updateSourceIdentityKey = sourceBookIdentityKey(update.sourceUrl, update.bookName, update.author)
        if (updateSourceIdentityKey != null && contexts.any { chapter ->
                sourceBookIdentityKey(
                    chapter.payload.sourceUrl,
                    chapter.payload.bookName,
                    chapter.payload.author
                ) == updateSourceIdentityKey
            }) {
            return true
        }
        val updateBookIdentityKey = bookIdentityKey(update.bookName, update.author)
        return updateBookIdentityKey != null && contexts.any { chapter ->
            bookIdentityKey(chapter.payload.bookName, chapter.payload.author) == updateBookIdentityKey
        }
    }

    @Synchronized
    internal fun clearForTest() {
        marksBySourceBook.clear()
        marksBySourceBookIdentity.clear()
        marksByBookIdentityTitle.clear()
        runtimeContentMarksByChapterLink.clear()
        displayedContentSourcesByChapterLink.clear()
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
        val chapterLink = requireNotNull(link)
        val payload = runCatching { SourceEngineBookRoute.decodeChapterId(chapterLink) }.getOrNull()
            ?: return MarkResolution.NoRegistry
        val sourceBookKey = sourceBookKey(payload.sourceUrl, payload.bookUrl)
        val bookIdentityKey = bookIdentityKey(payload.bookName, payload.author)
        val titleKey = normalizedChapterTitle(payload.chapterName)
        val currentBookCatalog = bookIdentityKey?.let { key -> currentCatalogs.byBookIdentity[key] }
        displayedContentSourcesByChapterLink[chapterLink]?.let { displayedSource ->
            displayedSourceMark(displayedSource, currentBookCatalog)?.let { mark ->
                return MarkResolution.Found(mark)
            }
        }
        var staleIndexRegistry = false
        marksBySourceBook[sourceBookKey]?.let { markSet ->
            if (markSet.matches(currentCatalogs.bySourceBook[sourceBookKey])) {
                val mark = markSet.byChapterIndex[payload.index]
                if (mark != null) {
                    return MarkResolution.Found(
                        boundaryTitleOverrideMark(
                            bookIdentityKey = bookIdentityKey,
                            titleKey = titleKey,
                            currentSourceBookKey = sourceBookKey,
                            currentCatalogIdentity = currentBookCatalog,
                            exactMarkSet = markSet,
                            exactMark = mark
                        ) ?: mark
                    )
                }
                if (markSet.coversChapter(payload.index)) return MarkResolution.Clear
            } else {
                staleIndexRegistry = true
            }
        }
        sourceBookIdentityKey(payload.sourceUrl, payload.bookName, payload.author)?.let { key ->
            marksBySourceBookIdentity[key]?.let { markSet ->
                if (markSet.matches(currentCatalogs.bySourceIdentity[key])) {
                    val mark = markSet.byChapterIndex[payload.index]
                    if (mark != null) {
                        return MarkResolution.Found(
                            boundaryTitleOverrideMark(
                                bookIdentityKey = bookIdentityKey,
                                titleKey = titleKey,
                                currentSourceBookKey = sourceBookKey,
                                currentCatalogIdentity = currentBookCatalog,
                                exactMarkSet = markSet,
                                exactMark = mark
                            ) ?: mark
                        )
                    }
                    if (markSet.coversChapter(payload.index)) return MarkResolution.Clear
                } else {
                    staleIndexRegistry = true
                }
            }
        }
        bestSameSourceTitleMark(bookIdentityKey, titleKey, sourceBookKey, currentBookCatalog)
            ?.let { titleMark ->
                return MarkResolution.Found(titleMark.mark)
            }
        bestCrossSourceTitleMark(bookIdentityKey, titleKey, sourceBookKey, currentBookCatalog)
            ?.let { titleMark ->
                return MarkResolution.Found(titleMark.mark)
            }
        runtimeContentMarksByChapterLink[link]?.let { mark ->
            return MarkResolution.Found(mark)
        }
        return if (staleIndexRegistry) MarkResolution.Clear else MarkResolution.NoRegistry
    }

    private fun replaceTitleMarksForSource(
        bookIdentityKey: String,
        sourceBookKey: String,
        catalogIdentity: CatalogIdentity?,
        marks: List<V8ChapterMarkResult>,
        allowCatalogGrowth: Boolean
    ) {
        val titleMarks = marksByBookIdentityTitle.getOrPut(bookIdentityKey) { LinkedHashMap() }
        titleMarks.keys.toList().forEach { titleKey ->
            val remaining = titleMarks[titleKey].orEmpty()
                .filterNot { titleMark ->
                    titleMark.sourceBookKey == sourceBookKey &&
                        titleMark.catalogIdentity == catalogIdentity
                }
            if (remaining.isEmpty()) {
                titleMarks.remove(titleKey)
            } else {
                titleMarks[titleKey] = remaining
            }
        }
        marks.forEach { mark ->
            val titleKey = normalizedChapterTitle(mark.chapterTitle)
            if (titleKey.isBlank()) return@forEach
            titleMarks[titleKey] = titleMarks[titleKey].orEmpty() +
                TitleMark(sourceBookKey, mark, catalogIdentity, allowCatalogGrowth)
        }
        if (titleMarks.isEmpty()) {
            marksByBookIdentityTitle.remove(bookIdentityKey)
        }
    }

    private fun displayedSourceMark(
        displayedSource: DisplayedContentSource,
        currentBookCatalog: CatalogIdentity?
    ): V8ChapterMarkResult? {
        marksBySourceBook[displayedSource.sourceBookKey]?.let { markSet ->
            if (markSet.matches(currentBookCatalog)) {
                markSet.byChapterIndex[displayedSource.chapterIndex]?.let { mark -> return mark }
            }
        }
        sourceBookIdentityKey(displayedSource.sourceUrl, displayedSource.bookName, displayedSource.author)
            ?.let { key ->
                marksBySourceBookIdentity[key]?.let { markSet ->
                    if (markSet.matches(currentBookCatalog)) {
                        markSet.byChapterIndex[displayedSource.chapterIndex]?.let { mark -> return mark }
                    }
                }
            }
        val bookKey = bookIdentityKey(displayedSource.bookName, displayedSource.author)
        val titleKey = normalizedChapterTitle(displayedSource.chapterTitle)
        return titleMarksFor(bookKey, titleKey, currentBookCatalog, displayedSource.sourceBookKey)
            .firstOrNull { titleMark -> titleMark.sourceBookKey == displayedSource.sourceBookKey }
            ?.mark
    }

    private fun boundaryTitleOverrideMark(
        bookIdentityKey: String?,
        titleKey: String,
        currentSourceBookKey: String,
        currentCatalogIdentity: CatalogIdentity?,
        exactMarkSet: MarkSet,
        exactMark: V8ChapterMarkResult
    ): V8ChapterMarkResult? {
        if (exactMark.state.isHiddenSourceIntegrityState()) return null
        val ambiguousExactMark = exactMark.state == V8ChapterMarkState.INCONCLUSIVE
        if (!ambiguousExactMark && !exactMarkSet.hasNearbyHiddenMark(exactMark.chapterIndex)) return null
        return titleMarksFor(bookIdentityKey, titleKey, currentCatalogIdentity, currentSourceBookKey)
            .filter { titleMark ->
                titleMark.sourceBookKey != currentSourceBookKey || ambiguousExactMark
            }
            .map { titleMark -> titleMark.mark }
            .filter { mark -> mark.state.isHiddenSourceIntegrityState() }
            .maxWithOrNull(
                compareBy<V8ChapterMarkResult> { mark -> hiddenStateSeverity(mark.state) }
                    .thenBy { mark -> mark.confidence }
            )
    }

    private fun bestSameSourceTitleMark(
        bookIdentityKey: String?,
        titleKey: String,
        currentSourceBookKey: String,
        currentCatalogIdentity: CatalogIdentity?
    ): TitleMark? {
        val candidates = titleMarksFor(bookIdentityKey, titleKey, currentCatalogIdentity, currentSourceBookKey)
            .filter { titleMark -> titleMark.sourceBookKey == currentSourceBookKey }
        return candidates
            .filter { titleMark -> titleMark.mark.state.isHiddenSourceIntegrityState() }
            .maxWithOrNull(
                compareBy<TitleMark> { titleMark -> hiddenStateSeverity(titleMark.mark.state) }
                    .thenBy { titleMark -> titleMark.mark.confidence }
            )
            ?: candidates.firstOrNull()
    }

    private fun bestCrossSourceTitleMark(
        bookIdentityKey: String?,
        titleKey: String,
        currentSourceBookKey: String,
        currentCatalogIdentity: CatalogIdentity?
    ): TitleMark? {
        val candidates = titleMarksFor(bookIdentityKey, titleKey, currentCatalogIdentity)
            .filter { titleMark -> titleMark.sourceBookKey != currentSourceBookKey }
        return candidates
            .filter { titleMark -> titleMark.mark.state.isHiddenSourceIntegrityState() }
            .maxWithOrNull(
                compareBy<TitleMark> { titleMark -> hiddenStateSeverity(titleMark.mark.state) }
                    .thenBy { titleMark -> titleMark.mark.confidence }
            )
            ?: candidates.firstOrNull()
    }

    private fun titleMarksFor(
        bookIdentityKey: String?,
        titleKey: String,
        currentCatalogIdentity: CatalogIdentity?,
        currentSourceBookKey: String? = null
    ): List<TitleMark> {
        if (bookIdentityKey.isNullOrBlank() || titleKey.isBlank()) return emptyList()
        return marksByBookIdentityTitle[bookIdentityKey]?.get(titleKey).orEmpty()
            .filter { titleMark ->
                titleMark.matches(
                    currentCatalogIdentity,
                    allowCatalogGrowth = currentSourceBookKey != null &&
                        titleMark.sourceBookKey == currentSourceBookKey &&
                        titleMark.allowCatalogGrowth
                )
            }
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
        val byChapterIndex: Map<Int, V8ChapterMarkResult>,
        val coveredChapterIndexes: Set<Int>?
    ) {
        fun matches(currentCatalogIdentity: CatalogIdentity?): Boolean {
            return catalogIdentity == null || catalogIdentity == currentCatalogIdentity
        }

        fun coversChapter(chapterIndex: Int): Boolean {
            return coveredChapterIndexes == null || chapterIndex in coveredChapterIndexes
        }

        fun hasNearbyHiddenMark(chapterIndex: Int): Boolean {
            return byChapterIndex.values.any { mark ->
                mark.state.isHiddenSourceIntegrityState() &&
                    kotlin.math.abs(mark.chapterIndex - chapterIndex) <= CROSS_SOURCE_BOUNDARY_OVERRIDE_RADIUS
            }
        }
    }

    private data class TitleMark(
        val sourceBookKey: String,
        val mark: V8ChapterMarkResult,
        val catalogIdentity: CatalogIdentity?,
        val allowCatalogGrowth: Boolean
    ) {
        fun matches(currentCatalogIdentity: CatalogIdentity?, allowCatalogGrowth: Boolean): Boolean {
            return catalogIdentity == null ||
                catalogIdentity == currentCatalogIdentity ||
                (allowCatalogGrowth && catalogIdentity.isTailAppendedBy(currentCatalogIdentity))
        }
    }

    private fun CatalogIdentity.isTailAppendedBy(currentCatalogIdentity: CatalogIdentity?): Boolean {
        val current = currentCatalogIdentity ?: return false
        return current.catalogSize > catalogSize &&
            normalizedChapterTitle(current.firstTitle) == normalizedChapterTitle(firstTitle)
    }

    private data class DisplayedContentSource(
        val sourceBookKey: String,
        val sourceUrl: String,
        val bookUrl: String,
        val bookName: String,
        val author: String,
        val chapterIndex: Int,
        val chapterTitle: String
    )

    private data class ChapterContext(
        val payload: SourceEngineBookRoute.ChapterPayload,
        val displayTitle: String
    )

    private data class CurrentCatalogs(
        val bySourceBook: Map<String, CatalogIdentity>,
        val bySourceIdentity: Map<String, CatalogIdentity>,
        val byBookIdentity: Map<String, CatalogIdentity>
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
                },
            byBookIdentity = chapters.groupBy { chapter ->
                bookIdentityKey(chapter.payload.bookName, chapter.payload.author).orEmpty()
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

    private fun V8ChapterMarkState.isHiddenSourceIntegrityState(): Boolean {
        return this == V8ChapterMarkState.WRONG ||
            this == V8ChapterMarkState.NON_STORY ||
            this == V8ChapterMarkState.BAD_EXTRACTION
    }

    private fun hiddenStateSeverity(state: V8ChapterMarkState): Int {
        return when (state) {
            V8ChapterMarkState.BAD_EXTRACTION -> 3
            V8ChapterMarkState.WRONG -> 2
            V8ChapterMarkState.NON_STORY -> 1
            else -> 0
        }
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

    fun hasHiddenSourceIntegrityMark(chapter: TxtChapter): Boolean {
        return isCurrentSourceIntegrityReason(chapter.sourceIntegrityReason) &&
            (chapter.sourceIntegrityState == V8ChapterMarkState.WRONG.name ||
                chapter.sourceIntegrityState == V8ChapterMarkState.NON_STORY.name ||
                chapter.sourceIntegrityState == V8ChapterMarkState.BAD_EXTRACTION.name)
    }

    private const val CROSS_SOURCE_BOUNDARY_OVERRIDE_RADIUS = 8
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
