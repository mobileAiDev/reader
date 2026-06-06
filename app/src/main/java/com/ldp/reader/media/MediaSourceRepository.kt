package com.ldp.reader.media

import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.media.MediaEngineFailure
import com.ldp.reader.media.MediaEngineResult
import com.ldp.reader.media.engine.MediaLegadoEngine
import com.ldp.reader.media.engine.MediaOkHttpFetcher
import com.ldp.reader.media.legado.MediaHttpRequest
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceBookDetail
import com.ldp.reader.media.MediaSourceChapter
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object MediaSourceRepository {
    private const val MAX_SEARCH_SOURCES = 240
    private const val MAX_SEARCH_RESULTS = 60
    private const val SEARCH_TIMEOUT_MS = 30_000L
    private const val PARTIAL_RESULT_INTERVAL_MS = 650L
    private val engine = MediaLegadoEngine(fetcher = MediaOkHttpFetcher(5_000, 12_000))
    private val searchFetcher = MediaOkHttpFetcher(2_000, 6_000)
    private val playbackFetcher = MediaOkHttpFetcher(5_000, 12_000)
    private val searchExecutor = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "media-source-search").apply { isDaemon = true }
    }
    private val probeExecutor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "media-source-probe").apply { isDaemon = true }
    }
    private val qualityRouter = MediaSourceQualityRouter()
    private val searchReadabilityCache = ConcurrentHashMap<String, Boolean>()
    private val searchCatalogCache = ConcurrentHashMap<String, Boolean>()
    private val routeChapterCache = ConcurrentHashMap<String, List<MediaSourceChapter>>()

    fun search(
        kind: ReaderMediaKind,
        query: String,
        maxSources: Int = MAX_SEARCH_SOURCES,
        onPartialResults: ((List<MediaSearchBook>) -> Unit)? = null
    ): List<MediaSearchBook> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val sources = qualityRouter.waterfallSourcesForQuery(
            kind = kind,
            sources = MediaSourceRuntime.compatibleSourcesForType(kind.sourceType)
                .filter { !it.searchUrl.isNullOrBlank() },
            query = keyword
        )
        if (sources.isEmpty()) return emptyList()
        val searchSources = sources
            .filter { !it.searchUrl.isNullOrBlank() }
            .take(maxSources.coerceIn(1, MAX_SEARCH_SOURCES))
        if (searchSources.isEmpty()) return emptyList()
        AiBridgeTrace.event(
            "media_search_repository_begin",
            "${kind.seedKey}:$keyword",
            AiBridgeTrace.fields(
                "sources" to sources.size,
                "searchSources" to searchSources.size
            )
        )
        val searchBooks = searchSourcesFast(searchSources, keyword) { partialBooks ->
            onPartialResults?.invoke(buildSearchBooks(kind, keyword, partialBooks, recordSignals = false))
        }
        return buildSearchBooks(kind, keyword, searchBooks, recordSignals = true)
    }

    fun flowSearch(
        kind: ReaderMediaKind,
        query: String,
        maxBooks: Int,
        maxSources: Int
    ): List<MediaSearchBook> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val sources = qualityRouter.waterfallSourcesForQuery(
            kind = kind,
            sources = MediaSourceRuntime.compatibleSourcesForType(kind.sourceType)
                .filter { !it.searchUrl.isNullOrBlank() },
            query = keyword
        )
        val searchSources = sources
            .filter { !it.searchUrl.isNullOrBlank() }
            .take(maxSources.coerceIn(1, MAX_SEARCH_SOURCES))
        if (searchSources.isEmpty()) return emptyList()
        AiBridgeTrace.event(
            "media_flow_search_begin",
            "${kind.seedKey}:$keyword",
            AiBridgeTrace.fields(
                "sources" to sources.size,
                "searchSources" to searchSources.size,
                "maxBooks" to maxBooks
            )
        )
        val sourceOrder = searchSources
            .mapIndexed { index, source -> sourceKey(source) to index }
            .toMap()
        val queryKey = MediaTitleKey.normalized(keyword)
        val groups = searchSourcesFast(searchSources, keyword)
            .filter { MediaTitleKey.matchesQuery(kind, keyword, it.name) }
            .groupBy { alternateTitleKey(kind, keyword, it) }
            .values
            .sortedWith(
                compareBy<List<MediaSourceBook>> { group ->
                    if (group.any { MediaTitleKey.normalizedForQuery(it.name, keyword) == queryKey }) 0 else 1
                }
                    .thenBy { group -> group.minOf { sourceOrder[sourceKey(it.source)] ?: Int.MAX_VALUE } }
                    .thenByDescending { group -> group.size }
            )
            .take(maxBooks.coerceIn(1, MAX_SEARCH_RESULTS))
        AiBridgeTrace.event(
            "media_flow_search_grouped",
            "${kind.seedKey}:$keyword",
            AiBridgeTrace.fields(
                "raw" to groups.sumOf { it.size },
                "groups" to groups.size
            )
        )
        return groups.map { group ->
            val sorted = group
                .distinctBy { resolvedBookKey(it) }
                .sortedBy { sourceOrder[sourceKey(it.source)] ?: Int.MAX_VALUE }
            val book = sorted.first()
            MediaSearchBook(
                routeId = MediaRouteRegistry.registerBook(kind, book, sorted.drop(1)),
                title = MediaDisplayTextCleaner.clean(book.name).ifBlank { book.name },
                author = MediaDisplayTextCleaner.clean(book.author),
                coverUrl = book.coverUrl,
                intro = MediaDisplayTextCleaner.clean(book.intro),
                latest = MediaDisplayTextCleaner.clean(book.lastChapter),
                sourceName = book.source.sourceName,
                sourceCount = sorted.size
            )
        }
    }

    private fun buildSearchBooks(
        kind: ReaderMediaKind,
        keyword: String,
        searchBooks: List<MediaSourceBook>,
        recordSignals: Boolean
    ): List<MediaSearchBook> {
        val gateTraces = if (recordSignals) ArrayList<MediaSearchDisplayGateTrace>() else null
        val displayableBooks = MediaSearchDisplayGate.displayableBooks(
            kind = kind,
            keyword = keyword,
            books = searchBooks,
            maxGroups = MAX_SEARCH_VALIDATION_GROUPS,
            maxSourcesPerGroup = MAX_SEARCH_VALIDATION_SOURCES_PER_GROUP,
            readable = ::catalogSearchBook,
            onGroupEvaluated = { trace ->
                if (gateTraces != null && gateTraces.size < MAX_GATE_TRACE_GROUPS) {
                    gateTraces.add(trace)
                }
            }
        )
        AiBridgeTrace.event(
            "media_search_repository_displayable",
            "${kind.seedKey}:$keyword",
            AiBridgeTrace.fields(
                "raw" to searchBooks.size,
                "displayable" to displayableBooks.size,
                "record" to recordSignals
            )
        )
        gateTraces?.forEachIndexed { index, trace ->
            AiBridgeTrace.event(
                "media_search_display_gate_group",
                "${kind.seedKey}:$keyword",
                AiBridgeTrace.fields(
                    "i" to index,
                    "key" to trace.key,
                    "title" to trace.title,
                    "raw" to trace.rawCount,
                    "src" to trace.uniqueSources,
                    "cover" to trace.hasCover,
                    "match" to trace.matchesQuery,
                    "check" to trace.validated,
                    "read" to trace.readableCount,
                    "readSrc" to trace.readableSources,
                    "readCover" to trace.readableHasCover,
                    "show" to trace.displayed
                )
            )
        }
        val alternatesByTitle = searchBooks.groupBy { alternateTitleKey(kind, keyword, it) }
        return qualityRouter.rankSearchResults(kind, keyword, displayableBooks, MAX_SEARCH_RESULTS)
            .also { rankedBooks ->
                if (!recordSignals) return@also
                rankedBooks.forEach { ranked -> qualityRouter.recordSearchResult(kind, ranked.book, keyword) }
            }
            .map { ranked ->
                val book = ranked.book
                val alternates = alternatesByTitle[alternateTitleKey(kind, keyword, book)].orEmpty()
                    .filterNot { sameResolvedBook(it, book) }
                    .distinctBy { resolvedBookKey(it) }
                MediaSearchBook(
                    routeId = MediaRouteRegistry.registerBook(kind, book, alternates),
                    title = MediaDisplayTextCleaner.clean(book.name).ifBlank { book.name },
                    author = MediaDisplayTextCleaner.clean(book.author),
                    coverUrl = book.coverUrl,
                    intro = MediaDisplayTextCleaner.clean(book.intro),
                    latest = MediaDisplayTextCleaner.clean(book.lastChapter),
                    sourceName = book.source.sourceName,
                    sourceCount = ranked.sourceCount
                )
            }
    }

    private fun searchSourcesFast(
        sources: List<MediaSourceDefinition>,
        keyword: String,
        onPartialBooks: ((List<MediaSourceBook>) -> Unit)? = null
    ): List<MediaSourceBook> {
        val completionService = ExecutorCompletionService<List<MediaSourceBook>>(searchExecutor)
        val futures = sources.map { source -> completionService.submit(Callable { searchSingleSource(source, keyword) }) }
        val books = ArrayList<MediaSourceBook>()
        var completed = 0
        var lastPartialAt = 0L
        val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        return runCatching {
            while (completed < futures.size) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                val future = completionService.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                completed += 1
                val result = runCatching { future.get() }.getOrDefault(emptyList())
                if (result.isNotEmpty()) {
                    books.addAll(result)
                    val now = System.currentTimeMillis()
                    if (onPartialBooks != null && now - lastPartialAt >= PARTIAL_RESULT_INTERVAL_MS) {
                        onPartialBooks.invoke(books.toList())
                        lastPartialAt = now
                    }
                }
            }
            onPartialBooks?.invoke(books.toList())
            books.toList()
        }.getOrDefault(books.toList()).also {
            futures.forEach { future -> future.cancel(true) }
        }
    }

    private fun searchSingleSource(source: MediaSourceDefinition, keyword: String): List<MediaSourceBook> {
        return when (val result = MediaLegadoEngine(fetcher = searchFetcher).search(source, keyword)) {
            is MediaEngineResult.Success -> result.value.books
            is MediaEngineResult.Failure -> emptyList()
        }
    }

    private fun readableSearchBook(kind: ReaderMediaKind, book: MediaSourceBook): Boolean {
        val key = listOf(kind.seedKey, book.source.sourceUrl, book.bookUrl).joinToString("|")
        return searchReadabilityCache.getOrPut(key) {
            runCatching { validateSearchBookReadable(kind, book) }.getOrElse { throwable ->
                traceSearchReadability(kind, book, "exception:${throwable.javaClass.simpleName}", 0, false)
                traceSearchReadabilityError(kind, book, throwable)
                false
            }
        }
    }

    private fun catalogSearchBook(kind: ReaderMediaKind, book: MediaSourceBook): Boolean {
        if (kind == ReaderMediaKind.NOVEL) return true
        val key = listOf("catalog", kind.seedKey, book.source.sourceUrl, book.bookUrl).joinToString("|")
        return searchCatalogCache.getOrPut(key) {
            runCatching { validateSearchBookCatalog(kind, book) }.getOrElse { throwable ->
                traceSearchReadability(kind, book, "catalog_exception:${throwable.javaClass.simpleName}", 0, false)
                traceSearchReadabilityError(kind, book, throwable)
                false
            }
        }
    }

    private fun validateSearchBookCatalog(kind: ReaderMediaKind, book: MediaSourceBook): Boolean {
        val detail = when (val result = engine.detail(book)) {
            is MediaEngineResult.Success -> result.value.also { qualityRouter.recordDetailResolved(kind, it) }
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, book)
                traceSearchReadability(kind, book, "catalog_detail_failed", 0, false)
                return false
            }
        }
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> emptyList()
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        val readable = chapters.isNotEmpty()
        traceSearchReadability(kind, book, "catalog_chapters", chapters.size, readable)
        return readable
    }

    private fun validateSearchBookReadable(kind: ReaderMediaKind, book: MediaSourceBook): Boolean {
        if (kind == ReaderMediaKind.NOVEL) return true
        val detail = when (val result = engine.detail(book)) {
            is MediaEngineResult.Success -> result.value.also { qualityRouter.recordDetailResolved(kind, it) }
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, book)
                traceSearchReadability(kind, book, "detail_failed", 0, false)
                return false
            }
        }
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> emptyList()
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        if (chapters.isEmpty()) {
            traceSearchReadability(kind, book, "empty_chapters", chapters.size, false)
            return false
        }
        return when (kind) {
            ReaderMediaKind.COMIC -> {
                val hit = readableTailWindow(kind, chapters)
                val pageCount = hit?.itemCount ?: 0
                hit?.let { qualityRouter.recordContentResolved(kind, it.item, it.itemCount) }
                (pageCount > 0).also { readable ->
                    traceSearchReadability(kind, book, "comic_tail_window", chapters.size, readable)
                }
            }
            ReaderMediaKind.AUDIO -> (readableExperienceWindow(kind, chapters) != null).also { readable ->
                traceSearchReadability(kind, book, "audio_experience_window", chapters.size, readable)
            }
            ReaderMediaKind.NOVEL -> true
        }
    }

    private fun readableComicPageCount(chapter: MediaSourceChapter): Int {
        return runCatching {
            comicPageRequests(chapter, maxPages = 1).size
        }.getOrDefault(0)
    }

    private fun readableTailWindow(
        kind: ReaderMediaKind,
        chapters: List<MediaSourceChapter>
    ): MediaTailWindowHit<MediaSourceChapter>? {
        return MediaTailWindow.firstUsable(chapters) { chapter ->
            when (kind) {
                ReaderMediaKind.AUDIO -> if (resolveAudioRequest(kind, chapter, requirePlayable = true) != null) 1 else 0
                ReaderMediaKind.COMIC -> readableComicPageCount(chapter)
                ReaderMediaKind.NOVEL -> 0
            }
        }
    }

    private fun readableExperienceWindow(
        kind: ReaderMediaKind,
        chapters: List<MediaSourceChapter>
    ): MediaTailWindowHit<MediaSourceChapter>? {
        if (kind != ReaderMediaKind.AUDIO) return readableTailWindow(kind, chapters)
        return MediaTailWindow.firstUsableLatestThenStart(chapters) { chapter ->
            if (resolveAudioRequest(kind, chapter, requirePlayable = true) != null) 1 else 0
        }
    }

    private fun prepareRouteDetail(kind: ReaderMediaKind, routeId: String): MediaSourceBookDetail? {
        val primary = MediaRouteRegistry.book(routeId) ?: return null
        if (kind == ReaderMediaKind.NOVEL) {
            return loadRouteCandidate(kind, primary)?.also { selection ->
                MediaRouteRegistry.registerDetail(routeId, selection.detail)
                routeChapterCache[routeId] = selection.chapters
            }?.detail
        }
        val candidates = routeSourceCandidates(kind, routeId, primary)
        traceRouteSourceCandidates(kind, primary, candidates)
        val selection = selectRouteSource(kind, candidates) ?: return null
        val alternates = candidates
            .filterNot { sameResolvedBook(it, selection.book) }
            .distinctBy { resolvedBookKey(it) }
        routeChapterCache.remove(routeId)
        MediaRouteRegistry.replaceBook(routeId, selection.book, alternates)
        MediaRouteRegistry.registerDetail(routeId, selection.detail)
        routeChapterCache[routeId] = selection.chapters
        traceRouteSourceSelected(kind, selection, candidates.size)
        return selection.detail
    }

    private fun routeSourceCandidates(
        kind: ReaderMediaKind,
        routeId: String,
        primary: MediaSourceBook
    ): List<MediaSourceBook> {
        val rawCandidates = (listOf(primary) + MediaRouteRegistry.alternates(routeId))
            .distinctBy { resolvedBookKey(it) }
        if (rawCandidates.size <= 1) return rawCandidates
        val sourceOrder = qualityRouter.waterfallSourcesForQuery(
            kind = kind,
            sources = rawCandidates.map { it.source }.distinctBy { sourceKey(it) },
            query = primary.name
        ).mapIndexed { index, source -> sourceKey(source) to index }.toMap()
        return rawCandidates.mapIndexed { index, book ->
            IndexedRouteBook(
                book = book,
                sourceRank = sourceOrder[sourceKey(book.source)] ?: Int.MAX_VALUE,
                originalIndex = index
            )
        }.sortedWith(
            compareBy<IndexedRouteBook> { it.sourceRank }
                .thenBy { it.originalIndex }
        ).map { it.book }
    }

    private fun selectRouteSource(
        kind: ReaderMediaKind,
        candidates: List<MediaSourceBook>
    ): RouteSourceSelection? {
        val startedAt = System.currentTimeMillis()
        var firstWithDetail: RouteSourceSelection? = null
        var firstWithChapters: RouteSourceSelection? = null
        var bestReadable: RouteSourceSelection? = null
        var bestCatalog: RouteSourceSelection? = null
        candidates.take(maxDetailSelectionSources(kind)).forEach { book ->
            val selection = loadRouteCandidate(kind, book) ?: return@forEach
            if (firstWithDetail == null) {
                firstWithDetail = selection
            }
            if (selection.chapters.isNotEmpty() && firstWithChapters == null) {
                firstWithChapters = selection
            }
            if (selection.chapters.isNotEmpty() && selection.chapters.size > (bestCatalog?.chapters?.size ?: -1)) {
                bestCatalog = selection
            }
            val audioExperience = if (kind == ReaderMediaKind.AUDIO) {
                audioRouteExperience(selection.chapters)
            } else {
                null
            }
            val hit = when (kind) {
                ReaderMediaKind.COMIC -> readableTailWindow(kind, selection.chapters)
                ReaderMediaKind.AUDIO -> readableAudioRouteWindow(selection.chapters, audioExperience ?: MediaRouteExperience.EMPTY)
                ReaderMediaKind.NOVEL -> null
            }
            if (hit != null) {
                qualityRouter.recordContentResolved(kind, hit.item, hit.itemCount)
                val experience = when (kind) {
                    ReaderMediaKind.COMIC -> comicRouteExperience(selection.chapters)
                    ReaderMediaKind.AUDIO -> audioExperience ?: MediaRouteExperience.EMPTY
                    ReaderMediaKind.NOVEL -> MediaRouteExperience.EMPTY
                }
                val readableSelection = selection.copy(
                    reason = if (kind == ReaderMediaKind.AUDIO) "audio_experience" else "tail_window",
                    tailTitle = MediaDisplayTextCleaner.clean(hit.item.name).ifBlank { hit.item.name },
                    tailOffset = hit.offsetFromLatest,
                    tailItems = hit.itemCount,
                    experienceScore = experience.score,
                    sampleItems = experience.sampleItems,
                    navigationItems = experience.navigationItems
                )
                if (shouldPreferReadableSelection(kind, readableSelection, bestReadable)) {
                    bestReadable = readableSelection
                }
                if (kind == ReaderMediaKind.AUDIO) {
                    return readableSelection
                }
            }
            if (
                kind == ReaderMediaKind.AUDIO &&
                System.currentTimeMillis() - startedAt >= AUDIO_DETAIL_SELECTION_SOFT_TIMEOUT_MS
            ) {
                audioCatalogFallbackSelection(
                    bestCatalog = bestCatalog,
                    firstWithChapters = firstWithChapters,
                    firstWithDetail = firstWithDetail,
                    reason = "soft_timeout",
                    candidates = candidates.size,
                    elapsedMs = System.currentTimeMillis() - startedAt
                )?.let { return it }
            }
        }
        val readable = bestReadable
        val fuller = bestCatalog
        if (kind == ReaderMediaKind.COMIC && readable != null && fuller != null && fuller.book != readable.book) {
            val expectedCount = maxOf(
                MediaCatalogCompleteness.expectedCount(readable.detail),
                MediaCatalogCompleteness.expectedCount(fuller.detail)
            )
            if (
                MediaCatalogCompleteness.shouldPreferFullerCatalog(
                    expectedCount = expectedCount,
                    readableChapterCount = readable.chapters.size,
                    fullerChapterCount = fuller.chapters.size
                )
            ) {
                return fuller.copy(reason = "complete_catalog")
            }
        }
        return when (kind) {
            ReaderMediaKind.COMIC -> readable
                ?: firstWithChapters?.copy(reason = "chapters_only")
            ReaderMediaKind.AUDIO -> readable
                ?: audioCatalogFallbackSelection(
                    bestCatalog = bestCatalog,
                    firstWithChapters = firstWithChapters,
                    firstWithDetail = firstWithDetail,
                    reason = "strict_audio_unavailable",
                    candidates = candidates.size,
                    elapsedMs = System.currentTimeMillis() - startedAt
                )
            ReaderMediaKind.NOVEL -> readable
                ?: firstWithChapters?.copy(reason = "chapters_only")
                ?: firstWithDetail?.copy(reason = "detail_only")
        }
    }

    private fun audioCatalogFallbackSelection(
        bestCatalog: RouteSourceSelection?,
        firstWithChapters: RouteSourceSelection?,
        firstWithDetail: RouteSourceSelection?,
        reason: String,
        candidates: Int,
        elapsedMs: Long
    ): RouteSourceSelection? {
        val fallback = bestCatalog ?: firstWithChapters ?: firstWithDetail ?: return null
        val fallbackReason = if (fallback.chapters.isNotEmpty()) {
            "catalog_audio_pending:$reason"
        } else {
            "detail_audio_pending:$reason"
        }
        AiBridgeTrace.event(
            "media_route_source_audio_deferred",
            fallback.book.name,
            AiBridgeTrace.fields(
                "source" to fallback.book.source.sourceName,
                "reason" to fallbackReason,
                "candidates" to candidates,
                "chapters" to fallback.chapters.size,
                "elapsedMs" to elapsedMs
            )
        )
        return fallback.copy(reason = fallbackReason)
    }

    private fun maxDetailSelectionSources(kind: ReaderMediaKind): Int {
        return when (kind) {
            ReaderMediaKind.AUDIO -> MAX_AUDIO_DETAIL_SELECTION_SOURCES
            ReaderMediaKind.COMIC,
            ReaderMediaKind.NOVEL -> MAX_DETAIL_SELECTION_SOURCES
        }
    }

    private fun loadRouteCandidate(kind: ReaderMediaKind, book: MediaSourceBook): RouteSourceSelection? {
        val detail = when (val result = engine.detail(book)) {
            is MediaEngineResult.Success -> result.value.also { qualityRouter.recordDetailResolved(kind, it) }
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, book)
                return null
            }
        }
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> emptyList()
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        return RouteSourceSelection(
            book = book,
            detail = detail,
            chapters = chapters,
            reason = if (chapters.isEmpty()) "empty_chapters" else "chapters_only",
            tailTitle = "",
            tailOffset = -1,
            tailItems = 0
        )
    }

    private fun traceRouteSourceSelected(
        kind: ReaderMediaKind,
        selection: RouteSourceSelection,
        candidates: Int
    ) {
        AiBridgeTrace.event(
            "media_route_source_selected",
            "${kind.seedKey}:${selection.book.name}",
            AiBridgeTrace.fields(
                "source" to selection.book.source.sourceName,
                "reason" to selection.reason,
                "candidates" to candidates,
                "chapters" to selection.chapters.size,
                "tail" to selection.tailTitle,
                "tailOffset" to selection.tailOffset,
                "tailItems" to selection.tailItems,
                "experience" to selection.experienceScore,
                "samples" to selection.sampleItems,
                "navigation" to selection.navigationItems
            )
        )
    }

    private fun shouldPreferReadableSelection(
        kind: ReaderMediaKind,
        candidate: RouteSourceSelection,
        current: RouteSourceSelection?
    ): Boolean {
        if (current == null) return true
        if (kind == ReaderMediaKind.COMIC || kind == ReaderMediaKind.AUDIO) {
            val scoreDiff = candidate.experienceScore - current.experienceScore
            if (scoreDiff != 0) return scoreDiff > 0
            val sampleDiff = candidate.sampleItems - current.sampleItems
            if (sampleDiff != 0) return sampleDiff > 0
            val navigationDiff = candidate.navigationItems - current.navigationItems
            if (navigationDiff != 0) return navigationDiff > 0
        }
        return candidate.chapters.size > current.chapters.size
    }

    private fun readableAudioRouteWindow(
        chapters: List<MediaSourceChapter>,
        experience: MediaRouteExperience = audioRouteExperience(chapters)
    ): MediaTailWindowHit<MediaSourceChapter>? {
        if (experience.score <= 0) return null
        return MediaTailWindow.firstUsableLatestThenStart(chapters) { chapter ->
            if (resolveAudioRequest(ReaderMediaKind.AUDIO, chapter, requirePlayable = true) != null) 1 else 0
        }
    }

    private fun comicRouteExperience(chapters: List<MediaSourceChapter>): MediaRouteExperience {
        if (chapters.isEmpty()) return MediaRouteExperience.EMPTY
        val indices = linkedSetOf(0, chapters.size / 2, chapters.lastIndex)
        navigationIndex(chapters.size)?.let { middle ->
            indices += middle - 1
            indices += middle
            indices += middle + 1
        }
        val countsByIndex = indices
            .filter { it in chapters.indices }
            .associateWith { index -> comicPageRequests(chapters[index], maxPages = MAX_COMIC_SELECTION_SAMPLE_PAGES).size }
        val counts = countsByIndex.values.toList()
        if (counts.isEmpty()) return MediaRouteExperience.EMPTY
        val navigationCounts = navigationIndex(chapters.size)
            ?.let { middle ->
                listOf(middle - 1, middle, middle + 1)
                    .filter { it in chapters.indices }
                    .map { index -> countsByIndex[index] ?: 0 }
            }
            .orEmpty()
        val readableSamples = counts.count { it > 0 }
        val multiPageSamples = counts.count { it >= MIN_COMIC_MULTI_PAGE_SAMPLE }
        val sampleItems = counts.sumOf { it.coerceAtMost(MAX_COMIC_SELECTION_SAMPLE_PAGES) }
        val navigationItems = navigationCounts.sumOf { it.coerceAtMost(MAX_COMIC_SELECTION_SAMPLE_PAGES) }
        val navigationOk = navigationCounts.size >= 3 && navigationCounts.all { it > 0 }
        val score = (if (navigationOk) 10_000 else 0) +
            multiPageSamples * 1_000 +
            sampleItems * 100 +
            readableSamples * 10 -
            (counts.size - readableSamples) * 500
        return MediaRouteExperience(
            score = score,
            sampleItems = sampleItems,
            navigationItems = navigationItems
        )
    }

    private fun audioRouteExperience(chapters: List<MediaSourceChapter>): MediaRouteExperience {
        if (chapters.isEmpty()) return MediaRouteExperience.EMPTY
        val navigationMiddle = navigationIndex(chapters.size)
        val indices = linkedSetOf(0, chapters.lastIndex)
        navigationMiddle?.let { middle -> indices += middle }
        val requestsByIndex = indices
            .filter { it in chapters.indices }
            .associateWith { index ->
                resolveAudioRequest(ReaderMediaKind.AUDIO, chapters[index], requirePlayable = true)
            }
        if (requestsByIndex.isEmpty()) return MediaRouteExperience.EMPTY
        val samples = requestsByIndex.map { (index, request) ->
            chapters[index].chapterUrl to request?.url.orEmpty()
        }
        val playableSamples = samples.count { (_, url) -> url.isNotBlank() }
        val duplicateAudio = MediaPlaybackSignature.repeatedAudioUrlAcrossChapters(samples)
        if (playableSamples != samples.size || duplicateAudio) {
            traceAudioRouteExperienceRejected(
                chapters = chapters,
                reason = if (duplicateAudio) "duplicate_audio_signature" else "sample_unplayable",
                playableSamples = playableSamples,
                sampleCount = samples.size,
                samples = samples
            )
            return MediaRouteExperience.EMPTY
        }
        val navigationItems = navigationMiddle
            ?.let { middle -> if (requestsByIndex[middle]?.url?.isNotBlank() == true) 1 else 0 }
            ?: 0
        val score = 10_000 + playableSamples * 1_000 + navigationItems * 100
        return MediaRouteExperience(
            score = score,
            sampleItems = playableSamples,
            navigationItems = navigationItems
        )
    }

    private fun traceAudioRouteExperienceRejected(
        chapters: List<MediaSourceChapter>,
        reason: String,
        playableSamples: Int,
        sampleCount: Int,
        samples: List<Pair<String, String>>
    ) {
        val chapter = chapters.firstOrNull() ?: return
        AiBridgeTrace.event(
            "media_audio_route_rejected",
            "${chapter.book.name}:${chapter.source.sourceName}:$reason",
            AiBridgeTrace.fields(
                "source" to chapter.source.sourceName,
                "book" to chapter.book.name,
                "reason" to reason,
                "chapters" to chapters.size,
                "playableSamples" to playableSamples,
                "sampleCount" to sampleCount,
                "samples" to samples.joinToString("|") { (route, url) ->
                    "${route.traceToken(18)}:${MediaPlaybackSignature.audioUrl(url).traceToken(24)}"
                }
            )
        )
    }

    private fun String.traceToken(limit: Int): String {
        return replace(Regex("""[\s=:/\\#]+"""), "_").take(limit)
    }

    private fun navigationIndex(total: Int): Int? {
        if (total < 3) return null
        return (total / 2).coerceIn(1, total - 2)
    }

    fun detail(routeId: String): MediaBookDetail? {
        val kind = MediaRouteRegistry.kind(routeId) ?: return null
        val cached = MediaRouteRegistry.detail(routeId)
        val detail = cached ?: prepareRouteDetail(kind, routeId) ?: return null
        return MediaBookDetail(
            routeId = routeId,
            title = MediaDisplayTextCleaner.clean(detail.name).ifBlank { detail.name },
            author = MediaDisplayTextCleaner.clean(detail.author),
            coverUrl = detail.coverUrl,
            intro = MediaDisplayTextCleaner.clean(detail.intro),
            kind = detail.kind,
            latest = MediaDisplayTextCleaner.clean(detail.lastChapter),
            sourceName = detail.book.source.sourceName
        )
    }

    fun chapters(routeId: String): List<MediaChapterItem> {
        val kind = MediaRouteRegistry.kind(routeId) ?: return emptyList()
        val detail = MediaRouteRegistry.detail(routeId) ?: run {
            detail(routeId) ?: return emptyList()
            MediaRouteRegistry.detail(routeId) ?: return emptyList()
        }
        val registeredChapters = MediaRouteRegistry.chaptersForBookRoute(routeId)
        if (registeredChapters.size > 1) {
            qualityRouter.recordChapterListResolved(kind, detail, registeredChapters.size)
            return registeredChapters.map { chapter ->
                MediaChapterItem(
                    routeId = chapter.routeId,
                    title = MediaDisplayTextCleaner.clean(chapter.chapter.name).ifBlank { chapter.chapter.name },
                    index = chapter.chapter.index
                )
            }
        }
        if (registeredChapters.size == 1) {
            AiBridgeTrace.event(
                "media_chapters_refresh_needed",
                routeId,
                AiBridgeTrace.fields(
                    "kind" to kind.seedKey,
                    "registered" to registeredChapters.size,
                    "title" to detail.name
                )
            )
        }
        val chapters = routeChapterCache[routeId] ?: when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value.also { routeChapterCache[routeId] = it }
            is MediaEngineResult.Failure -> {
                if (registeredChapters.isNotEmpty()) {
                    qualityRouter.recordChapterListResolved(kind, detail, registeredChapters.size)
                    return registeredChapters.map { chapter ->
                        MediaChapterItem(
                            routeId = chapter.routeId,
                            title = MediaDisplayTextCleaner.clean(chapter.chapter.name).ifBlank { chapter.chapter.name },
                            index = chapter.chapter.index
                        )
                    }
                }
                return emptyList()
            }
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        if (registeredChapters.size == 1 && chapters.size > 1) {
            AiBridgeTrace.event(
                "media_chapters_refreshed",
                routeId,
                AiBridgeTrace.fields(
                    "kind" to kind.seedKey,
                    "registered" to registeredChapters.size,
                    "chapters" to chapters.size,
                    "title" to detail.name
                )
            )
        }
        return chapters.map { chapter ->
            MediaChapterItem(
                routeId = MediaRouteRegistry.registerChapter(kind, chapter, routeId),
                title = MediaDisplayTextCleaner.clean(chapter.name).ifBlank { chapter.name },
                index = chapter.index
            )
        }
    }

    fun audioUrl(chapterRouteId: String): String? {
        return audioRequest(chapterRouteId)?.url
    }

    fun audioRequest(chapterRouteId: String): MediaRequest? {
        val kind = MediaRouteRegistry.kind(chapterRouteId) ?: return null
        if (kind != ReaderMediaKind.AUDIO) return null
        val chapter = MediaRouteRegistry.chapter(chapterRouteId) ?: return null
        resolveAudioRequest(kind, chapter, requirePlayable = true)?.let { return it }
        qualityRouter.recordContentResolved(kind, chapter, 0)
        return fallbackAudioRequest(kind, chapterRouteId, chapter)
    }

    fun comicPages(chapterRouteId: String): List<MediaRequest> {
        val kind = MediaRouteRegistry.kind(chapterRouteId) ?: return emptyList()
        if (kind != ReaderMediaKind.COMIC) return emptyList()
        val chapter = MediaRouteRegistry.chapter(chapterRouteId) ?: return emptyList()
        val pages = comicPageRequests(chapter)
        if (pages.isNotEmpty()) {
            qualityRouter.recordContentResolved(kind, chapter, pages.size)
            return pages
        }
        qualityRouter.recordContentResolved(kind, chapter, 0)
        return fallbackComicPages(kind, chapterRouteId, chapter)
    }

    fun rawContent(chapterRouteId: String): String? {
        val kind = MediaRouteRegistry.kind(chapterRouteId) ?: return null
        val chapter = MediaRouteRegistry.chapter(chapterRouteId) ?: return null
        val raw = when (kind) {
            ReaderMediaKind.COMIC -> comicRawContent(chapter)
            else -> when (val result = engine.rawContent(chapter)) {
                is MediaEngineResult.Success -> result.value
                is MediaEngineResult.Failure -> null
            }
        }
        if (raw != null && isUsableRawContent(kind, raw)) return raw
        qualityRouter.recordContentResolved(kind, chapter, 0)
        return fallbackRawContent(kind, chapterRouteId, chapter)
    }

    fun recordResolvedContent(chapterRouteId: String, itemCount: Int) {
        val kind = MediaRouteRegistry.kind(chapterRouteId) ?: return
        val chapter = MediaRouteRegistry.chapter(chapterRouteId) ?: return
        qualityRouter.recordContentResolved(kind, chapter, itemCount)
    }

    fun tailProbe(
        kind: ReaderMediaKind,
        query: String,
        maxBooks: Int = 2,
        maxSourcesPerBook: Int = 6
    ): MediaTailProbeResult {
        val startedAt = System.currentTimeMillis()
        val books = search(kind, query)
        val bookProbes = books.take(maxBooks).map { book ->
            detail(book.routeId)
            chapters(book.routeId)
            val primary = MediaRouteRegistry.book(book.routeId)
            val sourceBooks = (listOfNotNull(primary) + MediaRouteRegistry.alternates(book.routeId))
                .distinctBy { resolvedBookKey(it) }
                .take(maxSourcesPerBook)
            val sourceProbes = probeSourceTails(kind, sourceBooks)
            MediaTailBookProbe(
                title = book.title,
                latest = book.latest,
                displayedSourceCount = book.sourceCount,
                probedSources = sourceProbes.size,
                usableSources = sourceProbes.count { it.ok },
                sourceProbes = sourceProbes
            )
        }
        return MediaTailProbeResult(
            kind = kind,
            query = query,
            books = bookProbes,
            durationMs = System.currentTimeMillis() - startedAt
        )
    }

    fun sourceAudit(
        kind: ReaderMediaKind,
        query: String,
        maxSources: Int = 60,
        builtInOnly: Boolean = false
    ): MediaSourceAuditResult {
        val startedAt = System.currentTimeMillis()
        val keyword = query.trim()
        if (keyword.isBlank()) {
            return MediaSourceAuditResult(kind, query, 0, 0, emptyList(), 0L)
        }
        val compatibleSources = if (builtInOnly) {
            MediaSourceRuntime.compatibleBuiltInSourcesForType(kind.sourceType)
        } else {
            MediaSourceRuntime.compatibleSourcesForType(kind.sourceType)
        }
        val sources = qualityRouter.waterfallSourcesForQuery(
            kind = kind,
            sources = compatibleSources.filter { !it.searchUrl.isNullOrBlank() },
            query = keyword
        )
        val auditSources = sources.take(maxSources)
        val completionService = ExecutorCompletionService<IndexedSourceAudit>(probeExecutor)
        val futures = auditSources.mapIndexed { index, source ->
            completionService.submit(Callable {
                IndexedSourceAudit(index, auditSingleSource(kind, keyword, source))
            })
        }
        val rows = arrayOfNulls<MediaSourceAuditRow>(auditSources.size)
        var completed = 0
        val deadline = System.currentTimeMillis() + MAX_SOURCE_AUDIT_TOTAL_MS
        while (completed < auditSources.size) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0L) break
            val future = completionService.poll(remainingMs.coerceAtMost(1_000L), TimeUnit.MILLISECONDS)
                ?: continue
            completed += 1
            val indexed = runCatching { future.get() }.getOrNull() ?: continue
            rows[indexed.index] = indexed.row.copy(index = indexed.index)
        }
        futures.forEach { it.cancel(true) }
        val resolvedRows = rows.mapIndexed { index, row ->
            row ?: timeoutAuditRow(index, auditSources[index])
        }
        return MediaSourceAuditResult(
            kind = kind,
            query = keyword,
            sourceCount = sources.size,
            auditedCount = auditSources.size,
            rows = resolvedRows,
            durationMs = System.currentTimeMillis() - startedAt,
            builtInOnly = builtInOnly
        )
    }

    private fun auditSingleSource(
        kind: ReaderMediaKind,
        keyword: String,
        source: MediaSourceDefinition
    ): MediaSourceAuditRow {
        val report = when (val result = MediaLegadoEngine(fetcher = searchFetcher).search(source, keyword)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> {
                return sourceAuditRow(source, error = "search_exception:${result.failure::class.java.simpleName}")
            }
        }
        val attempt = report.attempts.firstOrNull()
        if (attempt?.success == false) {
            return sourceAuditRow(
                source,
                searchCount = report.books.size,
                error = "search_failed:${attempt.message.take(80)}"
            )
        }
        if (report.books.isEmpty()) {
            return sourceAuditRow(source, searchCount = 0, error = "search_empty")
        }
        val matchingBooks = report.books.filter { auditTitleMatches(kind, keyword, it) }
        val selected = matchingBooks.firstOrNull() ?: report.books.first()
        if (matchingBooks.isEmpty()) {
            return sourceAuditRow(
                source,
                searchCount = report.books.size,
                firstTitle = report.books.firstOrNull()?.name.orEmpty(),
                selectedTitle = selected.name,
                selectedUrl = selected.bookUrl,
                matchingCount = 0,
                error = "no_query_match"
            )
        }
        val detail = when (val result = engine.detail(selected)) {
            is MediaEngineResult.Success -> result.value.also { qualityRouter.recordDetailResolved(kind, it) }
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, selected)
                return sourceAuditRow(
                    source,
                    searchCount = report.books.size,
                    matchingCount = matchingBooks.size,
                    firstTitle = report.books.firstOrNull()?.name.orEmpty(),
                    selectedTitle = selected.name,
                    selectedUrl = selected.bookUrl,
                    detailOk = false,
                    error = "detail_failed:${failureSummary(result.failure)}"
                )
            }
        }
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> {
                return sourceAuditRow(
                    source,
                    searchCount = report.books.size,
                    matchingCount = matchingBooks.size,
                    firstTitle = report.books.firstOrNull()?.name.orEmpty(),
                    selectedTitle = selected.name,
                    selectedUrl = selected.bookUrl,
                    tocUrl = detail.tocUrl,
                    detailOk = true,
                    chapterCount = 0,
                    error = "chapters_failed:${failureSummary(result.failure)}"
                )
            }
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        if (chapters.isEmpty()) {
            return sourceAuditRow(
                source,
                searchCount = report.books.size,
                matchingCount = matchingBooks.size,
                firstTitle = report.books.firstOrNull()?.name.orEmpty(),
                selectedTitle = selected.name,
                selectedUrl = selected.bookUrl,
                tocUrl = detail.tocUrl,
                detailOk = true,
                chapterCount = 0,
                error = "empty_chapters"
            )
        }
        val hit = when (kind) {
            ReaderMediaKind.AUDIO -> readableExperienceWindow(kind, chapters)
            ReaderMediaKind.COMIC -> readableTailWindow(kind, chapters)
            ReaderMediaKind.NOVEL -> null
        }
        val tail = hit?.item ?: chapters.last()
        val itemCount = hit?.itemCount ?: 0
        val sampleUrl = if (itemCount > 0) {
            when (kind) {
                ReaderMediaKind.AUDIO -> resolveAudioRequest(kind, tail, requirePlayable = true)?.url.orEmpty()
                ReaderMediaKind.COMIC -> comicPageRequests(tail, maxPages = 1).firstOrNull()?.url.orEmpty()
                ReaderMediaKind.NOVEL -> ""
            }
        } else {
            ""
        }
        qualityRouter.recordContentResolved(kind, tail, itemCount)
        return sourceAuditRow(
            source,
            searchCount = report.books.size,
            matchingCount = matchingBooks.size,
            firstTitle = report.books.firstOrNull()?.name.orEmpty(),
            selectedTitle = selected.name,
            selectedUrl = selected.bookUrl,
            tocUrl = detail.tocUrl,
            detailOk = true,
            chapterCount = chapters.size,
            tailTitle = MediaDisplayTextCleaner.clean(tail.name).ifBlank { tail.name },
            itemCount = itemCount,
            ok = itemCount > 0,
            offsetFromLatest = hit?.offsetFromLatest ?: -1,
            sampleUrl = sampleUrl,
            error = if (itemCount > 0) "" else if (kind == ReaderMediaKind.AUDIO) "experience_window_unreadable" else "tail_window_unreadable"
        )
    }

    private fun sourceAuditRow(
        source: MediaSourceDefinition,
        index: Int = -1,
        searchCount: Int = 0,
        matchingCount: Int = 0,
        firstTitle: String = "",
        selectedTitle: String = "",
        selectedUrl: String = "",
        tocUrl: String = "",
        detailOk: Boolean = false,
        chapterCount: Int = 0,
        tailTitle: String = "",
        itemCount: Int = 0,
        ok: Boolean = false,
        offsetFromLatest: Int = -1,
        sampleUrl: String = "",
        error: String = ""
    ): MediaSourceAuditRow {
        return MediaSourceAuditRow(
            index = index,
            sourceName = source.sourceName,
            sourceUrl = source.sourceUrl,
            searchCount = searchCount,
            matchingCount = matchingCount,
            firstTitle = MediaDisplayTextCleaner.clean(firstTitle).ifBlank { firstTitle },
            selectedTitle = MediaDisplayTextCleaner.clean(selectedTitle).ifBlank { selectedTitle },
            selectedUrl = selectedUrl,
            tocUrl = tocUrl,
            detailOk = detailOk,
            chapterCount = chapterCount,
            tailTitle = tailTitle,
            itemCount = itemCount,
            ok = ok,
            offsetFromLatest = offsetFromLatest,
            sampleUrl = sampleUrl,
            error = error
        )
    }

    private fun timeoutAuditRow(index: Int, source: MediaSourceDefinition): MediaSourceAuditRow {
        return sourceAuditRow(source, index = index, error = "timeout")
    }

    private fun auditTitleMatches(kind: ReaderMediaKind, keyword: String, book: MediaSourceBook): Boolean {
        return MediaTitleKey.matchesQuery(kind, keyword, book.name)
    }

    private fun probeSourceTails(
        kind: ReaderMediaKind,
        sourceBooks: List<MediaSourceBook>
    ): List<MediaTailSourceProbe> {
        if (sourceBooks.isEmpty()) return emptyList()
        val completionService = ExecutorCompletionService<IndexedTailSourceProbe>(probeExecutor)
        val futures = sourceBooks.mapIndexed { index, sourceBook ->
            completionService.submit(Callable {
                IndexedTailSourceProbe(index, probeSourceTail(kind, sourceBook))
            })
        }
        val probes = arrayOfNulls<MediaTailSourceProbe>(sourceBooks.size)
        var completed = 0
        val deadline = System.currentTimeMillis() + MAX_TAIL_PROBE_TOTAL_MS
        while (completed < sourceBooks.size) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0L) break
            val future = completionService.poll(remainingMs.coerceAtMost(1_000L), TimeUnit.MILLISECONDS)
                ?: continue
            completed += 1
            val indexed = runCatching { future.get() }.getOrNull() ?: continue
            probes[indexed.index] = indexed.probe
        }
        futures.forEach { future -> future.cancel(true) }
        return probes.mapIndexed { index, probe ->
            probe ?: timeoutProbe(sourceBooks[index])
        }
    }

    private fun timeoutProbe(book: MediaSourceBook): MediaTailSourceProbe {
        return MediaTailSourceProbe(
            sourceName = book.source.sourceName,
            title = MediaDisplayTextCleaner.clean(book.name).ifBlank { book.name },
            chapterCount = 0,
            tailTitle = "",
            itemCount = 0,
            ok = false,
            offsetFromLatest = -1,
            sampleUrl = "",
            error = "timeout"
        )
    }

    private fun probeSourceTail(kind: ReaderMediaKind, book: MediaSourceBook): MediaTailSourceProbe {
        return runCatching {
            val detail = when (val result = engine.detail(book)) {
                is MediaEngineResult.Success -> result.value.also { qualityRouter.recordDetailResolved(kind, it) }
                is MediaEngineResult.Failure -> {
                    qualityRouter.recordDetailFailed(kind, book)
                    return MediaTailSourceProbe(
                        sourceName = book.source.sourceName,
                        title = MediaDisplayTextCleaner.clean(book.name).ifBlank { book.name },
                        chapterCount = 0,
                tailTitle = "",
                itemCount = 0,
                ok = false,
                offsetFromLatest = -1,
                sampleUrl = "",
                error = "detail_failed"
                    )
                }
            }
            val chapters = when (val result = engine.chapters(detail)) {
                is MediaEngineResult.Success -> result.value
                is MediaEngineResult.Failure -> emptyList()
            }
            qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
            val lastChapter = chapters.lastOrNull() ?: return MediaTailSourceProbe(
                sourceName = book.source.sourceName,
                title = MediaDisplayTextCleaner.clean(book.name).ifBlank { book.name },
                chapterCount = chapters.size,
                tailTitle = "",
                itemCount = 0,
                ok = false,
                offsetFromLatest = -1,
                sampleUrl = "",
                error = "empty_chapters"
            )
            val hit = when (kind) {
                ReaderMediaKind.AUDIO -> readableExperienceWindow(kind, chapters)
                ReaderMediaKind.COMIC -> readableTailWindow(kind, chapters)
                ReaderMediaKind.NOVEL -> null
            }
            val tail = hit?.item ?: lastChapter
            val itemCount = hit?.itemCount ?: 0
            val sampleUrl = if (itemCount > 0) {
                when (kind) {
                    ReaderMediaKind.AUDIO -> resolveAudioRequest(kind, tail, requirePlayable = true)?.url.orEmpty()
                    ReaderMediaKind.COMIC -> comicPageRequests(tail, maxPages = 1).firstOrNull()?.url.orEmpty()
                    ReaderMediaKind.NOVEL -> ""
                }
            } else {
                ""
            }
            qualityRouter.recordContentResolved(kind, tail, itemCount)
            MediaTailSourceProbe(
                sourceName = book.source.sourceName,
                title = MediaDisplayTextCleaner.clean(book.name).ifBlank { book.name },
                chapterCount = chapters.size,
                tailTitle = MediaDisplayTextCleaner.clean(tail.name).ifBlank { tail.name },
                itemCount = itemCount,
                ok = itemCount > 0,
                offsetFromLatest = hit?.offsetFromLatest ?: -1,
                sampleUrl = sampleUrl,
                error = if (itemCount > 0) "" else "tail_window_unreadable"
            )
        }.getOrElse { throwable ->
            MediaTailSourceProbe(
                sourceName = book.source.sourceName,
                title = MediaDisplayTextCleaner.clean(book.name).ifBlank { book.name },
                chapterCount = 0,
                tailTitle = "",
                itemCount = 0,
                ok = false,
                offsetFromLatest = -1,
                sampleUrl = "",
                error = throwable.javaClass.simpleName
            )
        }
    }

    private fun fallbackAudioUrl(
        kind: ReaderMediaKind,
        chapterRouteId: String,
        primaryChapter: MediaSourceChapter
    ): String? {
        return fallbackAudioRequest(kind, chapterRouteId, primaryChapter)?.url
    }

    private fun fallbackAudioRequest(
        kind: ReaderMediaKind,
        chapterRouteId: String,
        primaryChapter: MediaSourceChapter
    ): MediaRequest? {
        val bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId) ?: return null
        return MediaRouteRegistry.alternates(bookRouteId)
            .take(MAX_FALLBACK_SOURCES)
            .firstNotNullOfOrNull { alternate ->
                resolveAlternateAudioRequest(kind, alternate, primaryChapter)
            }
    }

    private fun resolveAlternateAudioUrl(
        kind: ReaderMediaKind,
        alternate: MediaSourceBook,
        primaryChapter: MediaSourceChapter
    ): String? {
        return resolveAlternateAudioRequest(kind, alternate, primaryChapter)?.url
    }

    private fun resolveAlternateAudioRequest(
        kind: ReaderMediaKind,
        alternate: MediaSourceBook,
        primaryChapter: MediaSourceChapter
    ): MediaRequest? {
        val detail = when (val result = engine.detail(alternate)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, alternate)
                return null
            }
        }
        qualityRouter.recordDetailResolved(kind, detail)
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> return null
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        val alternateChapter = matchAlternateChapter(chapters, primaryChapter) ?: return null
        return resolveAudioRequest(kind, alternateChapter, requirePlayable = true)
    }

    private fun resolveAudioUrl(kind: ReaderMediaKind, chapter: MediaSourceChapter): String? {
        return resolveAudioRequest(kind, chapter, requirePlayable = true)?.url
    }

    private fun resolveAudioRequest(
        kind: ReaderMediaKind,
        chapter: MediaSourceChapter,
        requirePlayable: Boolean = false
    ): MediaRequest? {
        val chapterPageUrl = resolvedMediaPageUrl(chapter)
        AudioUrlExtractor.extractRequest(chapterPageUrl, chapter.book.bookUrl, chapter.source.headers)
            ?.withAudioPlaybackHeaders(chapter.book.bookUrl)
            ?.let { request ->
                if (!requirePlayable || AudioPlaybackProbe.isPlayable(request)) {
                    qualityRouter.recordContentResolved(kind, chapter, 1)
                    traceAudioResolve(kind, chapter, "chapter_url", "", chapterPageUrl, request.url)
                    return request
                }
                traceAudioResolve(kind, chapter, "chapter_url_unplayable", "", chapterPageUrl, request.url)
            }
        val raw = when (val result = engine.rawContent(chapter)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> null
        }.orEmpty()
        AudioPlaybackUrlResolver.resolveRequest(raw, chapterPageUrl, playbackFetcher, chapter.source.headers)
            ?.let { request ->
                if (requirePlayable && !AudioPlaybackProbe.isPlayable(request)) {
                    traceAudioResolve(kind, chapter, "raw_unplayable", raw, chapterPageUrl, request.url)
                    return@let
                }
                qualityRouter.recordContentResolved(kind, chapter, 1)
                traceAudioResolve(kind, chapter, "raw", raw, chapterPageUrl, request.url)
                return request
            }
        AudioPlaybackUrlResolver.resolveRequestFromPage(chapterPageUrl, playbackFetcher, chapter.source.headers)
            ?.let { request ->
                if (requirePlayable && !AudioPlaybackProbe.isPlayable(request)) {
                    traceAudioResolve(kind, chapter, "page_unplayable", raw, chapterPageUrl, request.url)
                    return@let
                }
                qualityRouter.recordContentResolved(kind, chapter, 1)
                traceAudioResolve(kind, chapter, "page", raw, chapterPageUrl, request.url)
                return request
            }
        qualityRouter.recordContentResolved(kind, chapter, 0)
        traceAudioResolve(kind, chapter, "none", raw, chapterPageUrl, "")
        return null
    }

    private fun traceSearchReadability(
        kind: ReaderMediaKind,
        book: MediaSourceBook,
        stage: String,
        chapters: Int,
        readable: Boolean
    ) {
        if (kind == ReaderMediaKind.NOVEL) return
        AiBridgeTrace.event(
            "media_search_readability",
            "${kind.seedKey}:${book.name}",
            AiBridgeTrace.fields(
                "stage" to stage,
                "ok" to readable,
                "chapters" to chapters,
                "source" to book.source.sourceName,
                "url" to book.bookUrl,
                "cover" to book.coverUrl.isNotBlank()
            )
        )
    }

    private fun traceSearchReadabilityError(
        kind: ReaderMediaKind,
        book: MediaSourceBook,
        throwable: Throwable
    ) {
        if (kind == ReaderMediaKind.NOVEL) return
        val cause = throwable.cause
        AiBridgeTrace.event(
            "media_search_readability_error",
            "${kind.seedKey}:${book.name}",
            AiBridgeTrace.fields(
                "type" to throwable.javaClass.name,
                "cause" to cause?.javaClass?.name.orEmpty(),
                "msg" to throwable.message.orEmpty(),
                "causeMsg" to cause?.message.orEmpty(),
                "at" to throwable.stackTrace.firstOrNull()?.toString().orEmpty(),
                "causeAt" to cause?.stackTrace?.firstOrNull()?.toString().orEmpty(),
                "source" to book.source.sourceName
            )
        )
    }

    private fun traceAudioResolve(
        kind: ReaderMediaKind,
        chapter: MediaSourceChapter,
        stage: String,
        raw: String,
        pageUrl: String,
        mediaUrl: String
    ) {
        if (kind != ReaderMediaKind.AUDIO) return
        AiBridgeTrace.event(
            "media_audio_resolve",
            chapter.book.name,
            AiBridgeTrace.fields(
                "stage" to stage,
                "source" to chapter.source.sourceName,
                "chapter" to chapter.name,
                "raw" to raw.length,
                "dataCode" to raw.contains("data-code", ignoreCase = true),
                "media" to (AudioUrlExtractor.extract(raw) != null),
                "page" to pageUrl,
                "url" to mediaUrl
            )
        )
    }

    private fun traceRouteSourceCandidates(
        kind: ReaderMediaKind,
        primary: MediaSourceBook,
        candidates: List<MediaSourceBook>
    ) {
        if (kind == ReaderMediaKind.NOVEL) return
        AiBridgeTrace.event(
            "media_route_source_candidates",
            primary.name,
            AiBridgeTrace.fields(
                "kind" to kind.seedKey,
                "count" to candidates.size,
                "primary" to primary.source.sourceName,
                "sources" to candidates.take(10).joinToString("|") { it.source.sourceName }
            )
        )
    }

    private fun resolvedMediaPageUrl(chapter: MediaSourceChapter): String {
        return MediaRequestParser.parse(chapter.chapterUrl, chapter.book.bookUrl, chapter.source.headers)?.url
            ?: chapter.chapterUrl
    }

    private fun MediaRequest.withAudioPlaybackHeaders(pageUrl: String): MediaRequest {
        return copy(headers = MediaPlaybackHeaders.audio(headers, pageUrl))
    }

    private fun fallbackComicPages(
        kind: ReaderMediaKind,
        chapterRouteId: String,
        primaryChapter: MediaSourceChapter
    ): List<MediaRequest> {
        val bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId) ?: return emptyList()
        return MediaRouteRegistry.alternates(bookRouteId)
            .take(MAX_FALLBACK_SOURCES)
            .firstNotNullOfOrNull { alternate ->
                resolveAlternateComicPages(kind, alternate, primaryChapter).takeIf { it.isNotEmpty() }
            }
            .orEmpty()
    }

    private fun resolveAlternateComicPages(
        kind: ReaderMediaKind,
        alternate: MediaSourceBook,
        primaryChapter: MediaSourceChapter
    ): List<MediaRequest> {
        val detail = when (val result = engine.detail(alternate)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, alternate)
                return emptyList()
            }
        }
        qualityRouter.recordDetailResolved(kind, detail)
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> return emptyList()
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        val alternateChapter = matchAlternateChapter(chapters, primaryChapter) ?: return emptyList()
        val pages = comicPageRequests(alternateChapter)
        qualityRouter.recordContentResolved(kind, alternateChapter, pages.size)
        return pages
    }

    private fun fallbackRawContent(
        kind: ReaderMediaKind,
        chapterRouteId: String,
        primaryChapter: MediaSourceChapter
    ): String? {
        val bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId) ?: return null
        return MediaRouteRegistry.alternates(bookRouteId)
            .take(MAX_FALLBACK_SOURCES)
            .firstNotNullOfOrNull { alternate ->
                resolveAlternateRawContent(kind, alternate, primaryChapter)
            }
    }

    private fun resolveAlternateRawContent(
        kind: ReaderMediaKind,
        alternate: MediaSourceBook,
        primaryChapter: MediaSourceChapter
    ): String? {
        val detail = when (val result = engine.detail(alternate)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, alternate)
                return null
            }
        }
        qualityRouter.recordDetailResolved(kind, detail)
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> return null
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        val alternateChapter = matchAlternateChapter(chapters, primaryChapter) ?: return null
        val raw = when (kind) {
            ReaderMediaKind.COMIC -> comicRawContent(alternateChapter)
            else -> when (val result = engine.rawContent(alternateChapter)) {
                is MediaEngineResult.Success -> result.value
                is MediaEngineResult.Failure -> null
            }
        } ?: return null
        if (!isUsableRawContent(kind, raw)) {
            qualityRouter.recordContentResolved(kind, alternateChapter, 0)
            return null
        }
        qualityRouter.recordContentResolved(kind, alternateChapter, resolvedItemCount(kind, raw))
        return raw
    }

    private fun comicRawContent(chapter: MediaSourceChapter): String? {
        val raw = when (val result = engine.rawContent(chapter)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> return null
        }
        val adapted = MediaContentJsAdapter.adaptComicRawContent(raw, chapter)
        if (ComicPageExtractor.extractRequests(adapted, chapter.chapterUrl, chapter.source.headers).isNotEmpty()) {
            return adapted
        }
        val lazyPages = lazyComicPageRequests(chapter, maxPages = null)
        if (lazyPages.isNotEmpty()) {
            return lazyPages.joinToString("\n") { request ->
                """<img src="${request.url}">"""
            }
        }
        return adapted
    }

    private fun comicPageRequests(chapter: MediaSourceChapter, maxPages: Int? = null): List<MediaRequest> {
        val raw = when (val result = engine.rawContent(chapter)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> null
        }
        if (raw != null) {
            val adapted = MediaContentJsAdapter.adaptComicRawContent(raw, chapter)
            val pages = ComicPageExtractor.extractRequests(adapted, chapter.chapterUrl, chapter.source.headers)
            if (pages.isNotEmpty()) return maxPages?.let { pages.take(it) } ?: pages
        }
        return lazyComicPageRequests(chapter, maxPages)
    }

    private fun lazyComicPageRequests(chapter: MediaSourceChapter, maxPages: Int?): List<MediaRequest> {
        val response = runCatching {
            playbackFetcher.fetch(
                MediaHttpRequest(
                    url = chapter.chapterUrl,
                    headers = chapter.source.headers
                )
            )
        }.getOrNull() ?: return emptyList()
        return ComicLazyImageResolver.resolveRequests(
            pageHtml = response.body,
            pageUrl = response.finalUrl,
            fetcher = playbackFetcher,
            defaultHeaders = chapter.source.headers,
            maxPages = maxPages
        )
    }

    private fun matchAlternateChapter(
        chapters: List<MediaSourceChapter>,
        primaryChapter: MediaSourceChapter
    ): MediaSourceChapter? {
        val primaryName = fallbackTitleKey(primaryChapter.name)
        return primaryName.takeIf { it.isNotBlank() }
            ?.let { name -> chapters.firstOrNull { fallbackTitleKey(it.name) == name } }
            ?: chapters.firstOrNull { it.index == primaryChapter.index }
            ?: chapters.getOrNull(primaryChapter.index)
    }

    private fun isUsableRawContent(kind: ReaderMediaKind, raw: String): Boolean {
        return when (kind) {
            ReaderMediaKind.COMIC -> ComicPageExtractor.extract(raw).isNotEmpty()
            ReaderMediaKind.AUDIO -> AudioUrlExtractor.extract(raw) != null
            ReaderMediaKind.NOVEL -> raw.isNotBlank()
        }
    }

    private fun resolvedItemCount(kind: ReaderMediaKind, raw: String): Int {
        return when (kind) {
            ReaderMediaKind.COMIC -> ComicPageExtractor.extract(raw).size
            ReaderMediaKind.AUDIO -> if (AudioUrlExtractor.extract(raw) != null) 1 else 0
            ReaderMediaKind.NOVEL -> if (raw.isNotBlank()) 1 else 0
        }
    }

    private fun fallbackTitleKey(book: MediaSourceBook, keyword: String = ""): String = fallbackTitleKey(book.name, keyword)

    private fun alternateTitleKey(kind: ReaderMediaKind, keyword: String, book: MediaSourceBook): String {
        return when (kind) {
            ReaderMediaKind.AUDIO,
            ReaderMediaKind.COMIC -> MediaTitleKey.consensusKey(kind, keyword, book.name)
            ReaderMediaKind.NOVEL -> fallbackTitleKey(book, keyword)
        }
    }

    private fun fallbackTitleKey(value: String, keyword: String = ""): String {
        return if (keyword.isBlank()) {
            MediaTitleKey.normalized(value)
        } else {
            MediaTitleKey.normalizedForQuery(value, keyword)
        }
    }

    private fun sourceKey(source: MediaSourceDefinition): String {
        return MediaSourceIdentity.sourceKey(source)
    }

    private fun failureSummary(failure: MediaEngineFailure): String {
        val type = failure::class.java.simpleName
        val message = when (failure) {
            is MediaEngineFailure.ContractViolation -> failure.message
            is MediaEngineFailure.NetworkError -> failure.message
            is MediaEngineFailure.ParseError -> failure.message
            is MediaEngineFailure.RuleError -> failure.message
        }
            .ifBlank { type }
            .replace(Regex("""\s+"""), "_")
            .take(120)
        return "$type:$message"
    }

    private fun sameResolvedBook(left: MediaSourceBook, right: MediaSourceBook): Boolean {
        return resolvedBookKey(left) == resolvedBookKey(right)
    }

    private fun resolvedBookKey(book: MediaSourceBook): String {
        return sourceKey(book.source) + "|" + book.bookUrl
    }

    private const val MAX_FALLBACK_SOURCES = 24
    private const val MAX_DETAIL_SELECTION_SOURCES = 12
    private const val MAX_AUDIO_DETAIL_SELECTION_SOURCES = 6
    private const val AUDIO_DETAIL_SELECTION_SOFT_TIMEOUT_MS = 12_000L
    private const val MAX_SEARCH_VALIDATION_GROUPS = 24
    private const val MAX_SEARCH_VALIDATION_SOURCES_PER_GROUP = 12
    private const val MAX_GATE_TRACE_GROUPS = 16
    private const val MAX_TAIL_PROBE_TOTAL_MS = 60_000L
    private const val MAX_SOURCE_AUDIT_TOTAL_MS = 90_000L
    private const val MAX_COMIC_SELECTION_SAMPLE_PAGES = 12
    private const val MIN_COMIC_MULTI_PAGE_SAMPLE = 2

    private data class IndexedRouteBook(
        val book: MediaSourceBook,
        val sourceRank: Int,
        val originalIndex: Int
    )

    private data class RouteSourceSelection(
        val book: MediaSourceBook,
        val detail: MediaSourceBookDetail,
        val chapters: List<MediaSourceChapter>,
        val reason: String,
        val tailTitle: String,
        val tailOffset: Int,
        val tailItems: Int,
        val experienceScore: Int = 0,
        val sampleItems: Int = 0,
        val navigationItems: Int = 0
    )

    private data class MediaRouteExperience(
        val score: Int,
        val sampleItems: Int,
        val navigationItems: Int
    ) {
        companion object {
            val EMPTY = MediaRouteExperience(0, 0, 0)
        }
    }

    private data class IndexedTailSourceProbe(
        val index: Int,
        val probe: MediaTailSourceProbe
    )

    private data class IndexedSourceAudit(
        val index: Int,
        val row: MediaSourceAuditRow
    )
}

data class MediaTailProbeResult(
    val kind: ReaderMediaKind,
    val query: String,
    val books: List<MediaTailBookProbe>,
    val durationMs: Long
)

data class MediaTailBookProbe(
    val title: String,
    val latest: String,
    val displayedSourceCount: Int,
    val probedSources: Int,
    val usableSources: Int,
    val sourceProbes: List<MediaTailSourceProbe>
)

data class MediaTailSourceProbe(
    val sourceName: String,
    val title: String,
    val chapterCount: Int,
    val tailTitle: String,
    val itemCount: Int,
    val ok: Boolean,
    val offsetFromLatest: Int,
    val sampleUrl: String,
    val error: String
)

data class MediaSourceAuditResult(
    val kind: ReaderMediaKind,
    val query: String,
    val sourceCount: Int,
    val auditedCount: Int,
    val rows: List<MediaSourceAuditRow>,
    val durationMs: Long,
    val builtInOnly: Boolean = false
)

data class MediaSourceAuditRow(
    val index: Int,
    val sourceName: String,
    val sourceUrl: String,
    val searchCount: Int,
    val matchingCount: Int,
    val firstTitle: String,
    val selectedTitle: String,
    val selectedUrl: String,
    val tocUrl: String,
    val detailOk: Boolean,
    val chapterCount: Int,
    val tailTitle: String,
    val itemCount: Int,
    val ok: Boolean,
    val offsetFromLatest: Int,
    val sampleUrl: String,
    val error: String
)
