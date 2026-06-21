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
import kotlin.math.abs
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object ComicRouteSelectionPolicy {
    fun shouldPreferReadableCandidate(
        candidateChapters: Int,
        candidateSampleItems: Int,
        candidateNavigationItems: Int,
        candidateExperienceScore: Int,
        currentChapters: Int,
        currentSampleItems: Int,
        currentNavigationItems: Int,
        currentExperienceScore: Int,
        strongCatalogChapters: Int,
        strongSampleItems: Int,
        strongNavigationItems: Int,
        closeChapterDelta: Int,
        meaningfulExperienceDelta: Int
    ): Boolean {
        val chapterDiff = candidateChapters - currentChapters
        val candidateStrongExperience = candidateSampleItems >= strongSampleItems &&
            candidateNavigationItems >= strongNavigationItems
        val currentStrongExperience = currentSampleItems >= strongSampleItems &&
            currentNavigationItems >= strongNavigationItems
        if (candidateStrongExperience != currentStrongExperience) {
            return candidateStrongExperience && candidateChapters >= strongCatalogChapters
        }
        if (abs(chapterDiff) <= closeChapterDelta) {
            val candidateEnoughNavigation = candidateNavigationItems >= strongNavigationItems
            val currentEnoughNavigation = currentNavigationItems >= strongNavigationItems
            if (candidateEnoughNavigation != currentEnoughNavigation) return candidateEnoughNavigation
            val candidateEnoughSamples = candidateSampleItems >= strongSampleItems
            val currentEnoughSamples = currentSampleItems >= strongSampleItems
            if (candidateEnoughSamples != currentEnoughSamples) return candidateEnoughSamples

            val scoreDiff = candidateExperienceScore - currentExperienceScore
            val bothCatalogStrong = candidateChapters >= strongCatalogChapters &&
                currentChapters >= strongCatalogChapters
            val bothStrongReadable = bothCatalogStrong && candidateEnoughSamples &&
                currentEnoughSamples && candidateEnoughNavigation && currentEnoughNavigation
            if (bothStrongReadable && chapterDiff != 0) {
                if (chapterDiff > 0 && scoreDiff > -meaningfulExperienceDelta) return true
                if (chapterDiff < 0 && scoreDiff < meaningfulExperienceDelta) return false
            }
            if (!candidateEnoughSamples && !currentEnoughSamples) {
                if (chapterDiff != 0) return chapterDiff > 0
            }
            val sampleDiff = candidateSampleItems - currentSampleItems
            if (sampleDiff != 0) return sampleDiff > 0
            val navigationDiff = candidateNavigationItems - currentNavigationItems
            if (navigationDiff != 0) return navigationDiff > 0
            if (abs(scoreDiff) >= meaningfulExperienceDelta) return scoreDiff > 0
        }
        if (chapterDiff != 0) return chapterDiff > 0
        val scoreDiff = candidateExperienceScore - currentExperienceScore
        if (scoreDiff != 0) return scoreDiff > 0
        val sampleDiff = candidateSampleItems - currentSampleItems
        if (sampleDiff != 0) return sampleDiff > 0
        val navigationDiff = candidateNavigationItems - currentNavigationItems
        if (navigationDiff != 0) return navigationDiff > 0
        return false
    }

    fun shouldSoftReturnReadable(
        elapsedMs: Long,
        chapters: Int,
        sampleItems: Int,
        navigationItems: Int,
        remainingChapterHints: List<Int>,
        readableSoftTimeoutMs: Long,
        strongCatalogChapters: Int,
        acceptableSampleItems: Int,
        strongSampleItems: Int,
        strongNavigationItems: Int,
        closeChapterDelta: Int
    ): Boolean {
        if (elapsedMs < readableSoftTimeoutMs) return false
        if (chapters < strongCatalogChapters || sampleItems < acceptableSampleItems) return false
        if (remainingChapterHints.isEmpty()) return true
        val strongExperience = sampleItems >= strongSampleItems && navigationItems >= strongNavigationItems
        val knownRemainingMaxHint = remainingChapterHints.filter { it > 0 }.maxOrNull()
        if (knownRemainingMaxHint != null && chapters < knownRemainingMaxHint - closeChapterDelta) {
            return false
        }
        if (remainingChapterHints.any { it <= 0 }) {
            return strongExperience
        }
        return strongExperience || navigationItems >= strongNavigationItems
    }
}

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
    private val routeCandidateExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "media-route-candidate").apply { isDaemon = true }
    }
    private val qualityRouter = MediaSourceQualityRouter()
    private val searchCatalogCache = ConcurrentHashMap<String, MediaSearchCatalogSignal>()
    private val routeChapterCache = ConcurrentHashMap<String, List<MediaSourceChapter>>()
    private val comicImageProbeCache = ConcurrentHashMap<String, ComicImageProbeResult>()
    private val comicSourceImageHealth = ConcurrentHashMap<String, Int>()

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
        var emittedComicPartial = false
        val searchStartedAtMs = System.currentTimeMillis()
        val searchBooks = searchSourcesFast(searchSources, keyword, timeoutMs = searchTimeoutMs(kind)) { partialBooks ->
            val partial = if (kind == ReaderMediaKind.COMIC) {
                val elapsedMs = System.currentTimeMillis() - searchStartedAtMs
                val exactSourceCount = exactQuerySourceCount(kind, keyword, partialBooks)
                val shouldBuildPartial = !emittedComicPartial &&
                    exactSourceCount >= COMIC_PARTIAL_MIN_EXACT_SOURCES &&
                    elapsedMs >= COMIC_PARTIAL_MIN_DELAY_MS &&
                    elapsedMs <= COMIC_SEARCH_TIMEOUT_MS - COMIC_PARTIAL_TIMEOUT_GUARD_MS
                if (!shouldBuildPartial) {
                    emptyList()
                } else {
                    buildSearchBooks(kind, keyword, partialBooks, recordSignals = false)
                        .also { if (it.isNotEmpty()) emittedComicPartial = true }
                }
            } else {
                buildSearchBooks(kind, keyword, partialBooks, recordSignals = false)
            }
            if (partial.isNotEmpty() || kind != ReaderMediaKind.COMIC) {
                onPartialResults?.invoke(partial)
            }
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
        val results = search(kind = kind, query = keyword, maxSources = maxSources)
        val groups = results.take(maxBooks.coerceIn(1, MAX_SEARCH_RESULTS))
        AiBridgeTrace.event(
            "media_flow_search_grouped",
            "${kind.seedKey}:$keyword",
            AiBridgeTrace.fields(
                "raw" to results.size,
                "groups" to groups.size
            )
        )
        return groups
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
            books = searchBooksForDisplayGate(kind, searchBooks),
            maxGroups = MAX_SEARCH_VALIDATION_GROUPS,
            maxSourcesPerGroup = maxSearchValidationSourcesPerGroup(kind),
            readable = ::catalogSearchBook,
            readableExecutor = if (kind == ReaderMediaKind.COMIC) routeCandidateExecutor else null,
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
        val rankedBooks = qualityRouter.rankSearchResults(
            kind = kind,
            keyword = keyword,
            books = displayableBooks,
            limit = MAX_SEARCH_RESULTS,
            catalogSignalProvider = { book -> catalogSearchSignal(kind, book) },
            imageHealthProvider = { book ->
                if (kind == ReaderMediaKind.COMIC) comicRouteSourceImageHealth(book.source) else 0
            }
        )
        if (recordSignals) {
            rankedBooks.take(MAX_RANK_TRACE_RESULTS).forEachIndexed { index, ranked ->
                AiBridgeTrace.event(
                    "media_search_ranked_result",
                    "${kind.seedKey}:$keyword",
                    AiBridgeTrace.fields(
                        "rank" to index + 1,
                        "title" to ranked.book.name,
                        "source" to ranked.book.source.sourceName,
                        "chapters" to ranked.chapterCount,
                        "imageHealth" to ranked.imageHealth,
                        "sources" to ranked.sourceCount,
                        "score" to ranked.score
                    )
                )
            }
        }
        return rankedBooks
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
                    sourceCount = ranked.sourceCount,
                    chapterCount = ranked.chapterCount
                )
            }
    }

    private fun searchBooksForDisplayGate(
        kind: ReaderMediaKind,
        books: List<MediaSourceBook>
    ): List<MediaSourceBook> {
        if (kind != ReaderMediaKind.COMIC) return books
        return books.mapIndexed { index, book -> index to book }
            .sortedWith(
                compareByDescending<Pair<Int, MediaSourceBook>> { (_, book) ->
                    cachedCatalogSearchSignal(kind, book).chapterCount
                }.thenBy { (index, _) -> index }
            )
            .map { (_, book) -> book }
    }

    private fun exactQuerySourceCount(
        kind: ReaderMediaKind,
        keyword: String,
        books: List<MediaSourceBook>
    ): Int {
        if (kind != ReaderMediaKind.COMIC) return books.map { sourceKey(it.source) }.toSet().size
        val queryKey = MediaTitleKey.normalized(keyword)
        if (queryKey.isBlank()) return 0
        return books
            .asSequence()
            .filter { book -> MediaTitleKey.normalizedForQuery(book.name, keyword) == queryKey }
            .map { book -> sourceKey(book.source) }
            .toSet()
            .size
    }

    private fun searchSourcesFast(
        sources: List<MediaSourceDefinition>,
        keyword: String,
        timeoutMs: Long = SEARCH_TIMEOUT_MS,
        onPartialBooks: ((List<MediaSourceBook>) -> Unit)? = null
    ): List<MediaSourceBook> {
        val completionService = ExecutorCompletionService<List<MediaSourceBook>>(searchExecutor)
        val futures = sources.map { source -> completionService.submit(Callable { searchSingleSource(source, keyword) }) }
        val books = ArrayList<MediaSourceBook>()
        var completed = 0
        var lastPartialAt = 0L
        val deadline = System.currentTimeMillis() + timeoutMs
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

    private fun catalogSearchBook(kind: ReaderMediaKind, book: MediaSourceBook): Boolean {
        return catalogSearchSignal(kind, book).readable
    }

    private fun catalogSearchSignal(kind: ReaderMediaKind, book: MediaSourceBook): MediaSearchCatalogSignal {
        if (kind == ReaderMediaKind.NOVEL) return MediaSearchCatalogSignal.EMPTY
        val key = catalogSearchCacheKey(kind, book)
        return searchCatalogCache.getOrPut(key) {
            runCatching { validateSearchBookCatalogSignal(kind, book) }.getOrElse { throwable ->
                traceSearchReadability(kind, book, "catalog_exception:${throwable.javaClass.simpleName}", 0, false)
                traceSearchReadabilityError(kind, book, throwable)
                MediaSearchCatalogSignal.EMPTY
            }
        }
    }

    private fun cachedCatalogSearchSignal(kind: ReaderMediaKind, book: MediaSourceBook): MediaSearchCatalogSignal {
        if (kind == ReaderMediaKind.NOVEL) return MediaSearchCatalogSignal.EMPTY
        return searchCatalogCache[catalogSearchCacheKey(kind, book)] ?: MediaSearchCatalogSignal.EMPTY
    }

    private fun catalogSearchCacheKey(kind: ReaderMediaKind, book: MediaSourceBook): String {
        return listOf("catalog", kind.seedKey, book.source.sourceUrl, book.bookUrl).joinToString("|")
    }

    private fun validateSearchBookCatalogSignal(kind: ReaderMediaKind, book: MediaSourceBook): MediaSearchCatalogSignal {
        val detail = when (val result = engine.detail(book)) {
            is MediaEngineResult.Success -> result.value.also { qualityRouter.recordDetailResolved(kind, it) }
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, book)
                traceSearchReadability(kind, book, "catalog_detail_failed", 0, false)
                return MediaSearchCatalogSignal.EMPTY
            }
        }
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> emptyList()
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        val readable = chapters.isNotEmpty()
        traceSearchReadability(kind, book, "catalog_chapters", chapters.size, readable)
        return MediaSearchCatalogSignal(
            chapterCount = chapters.size,
            expectedChapterCount = MediaCatalogCompleteness.expectedCount(detail)
        )
    }

    private fun readableComicPageCount(chapter: MediaSourceChapter): Int {
        return runCatching {
            comicReadableSampleCount(chapter, maxPages = 1)
        }.getOrDefault(0)
    }

    private fun comicReadableSampleCount(chapter: MediaSourceChapter, maxPages: Int): Int {
        val pages = comicPageRequests(chapter, maxPages = maxPages)
        if (pages.isEmpty()) return 0
        val readable = pages
            .take(MAX_COMIC_IMAGE_PROBE_PAGES_PER_CHAPTER)
            .firstOrNull { request -> probeComicImage(chapter, request).ok }
        return if (readable == null) 0 else pages.size
    }

    private fun probeComicImage(
        chapter: MediaSourceChapter,
        request: MediaRequest
    ): ComicImageProbeResult {
        val key = comicImageProbeKey(request)
        comicImageProbeCache[key]?.let { return it }
        val result = ComicImagePlaybackProbe.probe(request)
        recordComicImageProbe(chapter.source, result)
        traceComicImageProbe(chapter, request, result)
        if (!result.cancelled) {
            comicImageProbeCache.putIfAbsent(key, result)
        }
        return result
    }

    private fun recordComicImageProbe(source: MediaSourceDefinition, result: ComicImageProbeResult) {
        if (result.cancelled) return
        val delta = if (result.ok) COMIC_IMAGE_HEALTH_SUCCESS_DELTA else COMIC_IMAGE_HEALTH_FAILURE_DELTA
        adjustComicSourceImageHealth(source, delta)
    }

    fun recordSearchCoverLoad(routeId: String, url: String, loaded: Boolean) {
        if (MediaRouteRegistry.kind(routeId) != ReaderMediaKind.COMIC) return
        val book = MediaRouteRegistry.book(routeId) ?: return
        val delta = if (loaded) COMIC_IMAGE_HEALTH_SUCCESS_DELTA else COMIC_IMAGE_HEALTH_FAILURE_DELTA
        val health = adjustComicSourceImageHealth(book.source, delta)
        AiBridgeTrace.event(
            "media_comic_cover_health",
            book.name,
            AiBridgeTrace.fields(
                "source" to book.source.sourceName,
                "loaded" to loaded,
                "health" to health,
                "url" to comicTraceUrl(url)
            )
        )
    }

    private fun adjustComicSourceImageHealth(source: MediaSourceDefinition, delta: Int): Int {
        return comicSourceImageHealth.merge(sourceKey(source), delta) { current, change ->
            (current + change).coerceIn(COMIC_IMAGE_HEALTH_MIN, COMIC_IMAGE_HEALTH_MAX)
        } ?: delta.coerceIn(COMIC_IMAGE_HEALTH_MIN, COMIC_IMAGE_HEALTH_MAX)
    }

    private fun comicRouteSourceImageHealth(source: MediaSourceDefinition): Int {
        return comicSourceImageHealth[sourceKey(source)] ?: 0
    }

    private fun comicImageProbeKey(request: MediaRequest): String {
        return request.url + "|" + request.headers.entries
            .sortedBy { it.key.lowercase() }
            .joinToString("&") { "${it.key}=${it.value}" }
    }

    private fun comicUnreadableReason(chapter: MediaSourceChapter): String {
        val pages = runCatching { comicPageRequests(chapter, maxPages = 1) }.getOrDefault(emptyList())
        val request = pages.firstOrNull() ?: return "tail_window_unreadable"
        val probe = probeComicImage(chapter, request)
        return if (probe.ok) "" else "image_unreadable:${probe.error.ifBlank { "unknown" }}"
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
        val initialCandidates = (listOf(primary) + MediaRouteRegistry.alternates(routeId))
            .distinctBy { resolvedBookKey(it) }
        val rawCandidates = if (kind == ReaderMediaKind.COMIC) {
            supplementComicRouteCandidates(primary, initialCandidates)
        } else {
            initialCandidates
        }
        if (rawCandidates.size <= 1) return rawCandidates
        val sourceOrder = qualityRouter.waterfallSourcesForQuery(
            kind = kind,
            sources = rawCandidates.map { it.source }.distinctBy { sourceKey(it) },
            query = primary.name
        ).mapIndexed { index, source -> sourceKey(source) to index }.toMap()
        val indexed = rawCandidates.mapIndexed { index, book ->
            IndexedRouteBook(
                book = book,
                sourceRank = sourceOrder[sourceKey(book.source)] ?: Int.MAX_VALUE,
                originalIndex = index
            )
        }
        val baseOrder = compareBy<IndexedRouteBook> { it.sourceRank }
            .thenBy { it.originalIndex }
        val order = if (kind == ReaderMediaKind.COMIC) {
            compareBy<IndexedRouteBook> { comicRouteCandidateTier(it.book) }
                .thenBy { comicRouteCandidateImageTier(it.book) }
                .thenByDescending { comicRouteCandidateChapterHint(it.book) }
                .thenByDescending { comicRouteSourceImageHealth(it.book.source) }
                .thenByDescending { qualityRouter.sourceQualityScore(kind, it.book.source) }
                .then(baseOrder)
        } else {
            baseOrder
        }
        return indexed.sortedWith(order).map { it.book }
    }

    private fun supplementComicRouteCandidates(
        primary: MediaSourceBook,
        initialCandidates: List<MediaSourceBook>
    ): List<MediaSourceBook> {
        val startedAt = System.currentTimeMillis()
        val initialKeys = initialCandidates.mapTo(LinkedHashSet()) { resolvedBookKey(it) }
        val initialSourceKeys = initialCandidates.mapTo(LinkedHashSet()) { sourceKey(it.source) }
        val supplementSources = qualityRouter.waterfallSourcesForQuery(
            kind = ReaderMediaKind.COMIC,
            sources = MediaSourceRuntime.compatibleSourcesForType(ReaderMediaKind.COMIC.sourceType)
                .filter { !it.searchUrl.isNullOrBlank() }
                .filterNot { sourceKey(it) in initialSourceKeys },
            query = primary.name
        ).take(MAX_COMIC_ROUTE_SUPPLEMENT_SOURCES)
        if (supplementSources.isEmpty()) {
            traceComicRouteCandidatesSupplemented(primary, 0, 0, emptyList(), startedAt)
            return initialCandidates
        }
        val targetKey = alternateTitleKey(ReaderMediaKind.COMIC, primary.name, primary)
        val matchedBooks = searchSourcesFast(
            sources = supplementSources,
            keyword = primary.name,
            timeoutMs = COMIC_ROUTE_SUPPLEMENT_SEARCH_TIMEOUT_MS
        ).asSequence()
            .filter { book -> MediaTitleKey.matchesQuery(ReaderMediaKind.COMIC, primary.name, book.name) }
            .filter { book -> alternateTitleKey(ReaderMediaKind.COMIC, primary.name, book) == targetKey }
            .toList()
        val supplementalBooks = matchedBooks.asSequence()
            .filterNot { book -> resolvedBookKey(book) in initialKeys }
            .distinctBy { book -> resolvedBookKey(book) }
            .sortedWith(
                compareByDescending<MediaSourceBook> { book -> comicRouteCandidateChapterHint(book) }
                    .thenByDescending { book -> qualityRouter.sourceQualityScore(ReaderMediaKind.COMIC, book.source) }
                    .thenBy { book -> book.source.sourceName }
            )
            .take(MAX_COMIC_ROUTE_SUPPLEMENT_RESULTS)
            .toList()
        traceComicRouteCandidatesSupplemented(
            primary = primary,
            searchedSources = supplementSources.size,
            foundBooks = matchedBooks.size,
            addedBooks = supplementalBooks,
            startedAt = startedAt
        )
        if (supplementalBooks.isEmpty()) return initialCandidates
        return (initialCandidates + supplementalBooks).distinctBy { resolvedBookKey(it) }
    }

    private fun comicRouteCandidateTier(book: MediaSourceBook): Int {
        val hint = comicRouteCandidateChapterHint(book)
        return when {
            hint >= COMIC_STRONG_CATALOG_CHAPTERS -> 0
            hint > 0 -> 1
            else -> 2
        }
    }

    private fun comicRouteCandidateImageTier(book: MediaSourceBook): Int {
        val health = comicRouteSourceImageHealth(book.source)
        return if (health <= COMIC_IMAGE_HEALTH_UNHEALTHY_MAX) 1 else 0
    }

    private fun comicRouteCandidateChapterHint(book: MediaSourceBook): Int {
        val cached = cachedCatalogSearchSignal(ReaderMediaKind.COMIC, book).chapterCount
        val expected = MediaCatalogCompleteness.expectedCount(book.lastChapter, book.intro)
        return maxOf(cached, expected)
    }

    private fun selectRouteSource(
        kind: ReaderMediaKind,
        candidates: List<MediaSourceBook>
    ): RouteSourceSelection? {
        if (kind == ReaderMediaKind.COMIC) {
            return selectComicRouteSource(candidates)
        }
        val startedAt = System.currentTimeMillis()
        var firstWithDetail: RouteSourceSelection? = null
        var firstWithChapters: RouteSourceSelection? = null
        var bestReadable: RouteSourceSelection? = null
        var bestCatalog: RouteSourceSelection? = null
        val limitedCandidates = candidates.take(maxDetailSelectionSources(kind))
        for ((candidateIndex, book) in limitedCandidates.withIndex()) {
            if (
                kind == ReaderMediaKind.COMIC &&
                comicSelectionHardTimedOut(System.currentTimeMillis() - startedAt)
            ) {
                if (bestReadable != null) return bestReadable
                traceComicSelectionUnavailable(
                    bestCatalog = bestCatalog,
                    firstWithChapters = firstWithChapters,
                    candidates = candidates.size,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    reason = "hard_timeout"
                )
                return null
            }
            if (
                kind == ReaderMediaKind.COMIC &&
                System.currentTimeMillis() - startedAt >= COMIC_DETAIL_SELECTION_NEXT_GUARD_MS
            ) {
                if (bestReadable != null) {
                    return bestReadable
                }
                traceComicSelectionGuardContinued(
                    reason = "next_guard_no_readable",
                    candidates = candidates.size,
                    elapsedMs = System.currentTimeMillis() - startedAt
                )
            }
            if (kind == ReaderMediaKind.COMIC && bestReadable != null) {
                val elapsedMs = System.currentTimeMillis() - startedAt
                val remainingCandidates = limitedCandidates.drop(candidateIndex)
                if (shouldReturnComicReadableBeforeCandidate(bestReadable, remainingCandidates, elapsedMs)) {
                    traceComicReadableSoftReturn(bestReadable, remainingCandidates, elapsedMs)
                    return bestReadable
                }
            }
            val candidateStartedAt = System.currentTimeMillis()
            val comicCandidateDeadlineAt = if (kind == ReaderMediaKind.COMIC) {
                candidateStartedAt + COMIC_DETAIL_SELECTION_SOURCE_TIMEOUT_MS
            } else {
                Long.MAX_VALUE
            }
            val selection = loadRouteCandidate(kind, book, comicCandidateDeadlineAt)
            if (selection == null) {
                if (kind == ReaderMediaKind.COMIC && System.currentTimeMillis() >= comicCandidateDeadlineAt) {
                    recordComicRouteCandidateTimedOut(book)
                    traceComicCandidateLoadTimedOut(book, startedAt, candidateStartedAt)
                }
                continue
            }
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
                ReaderMediaKind.COMIC -> readableComicRouteWindow(selection.chapters, comicCandidateDeadlineAt)
                ReaderMediaKind.AUDIO -> readableAudioRouteWindow(selection.chapters, audioExperience ?: MediaRouteExperience.EMPTY)
                ReaderMediaKind.NOVEL -> null
            }
            if (hit != null) {
                qualityRouter.recordContentResolved(kind, hit.item, hit.itemCount)
                val experience = when (kind) {
                    ReaderMediaKind.COMIC -> comicRouteExperience(selection.chapters, comicCandidateDeadlineAt)
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
                traceRouteSourceEvaluated(kind, readableSelection, true, startedAt)
                if (shouldPreferReadableSelection(kind, readableSelection, bestReadable)) {
                    bestReadable = readableSelection
                }
                if (kind == ReaderMediaKind.COMIC && shouldReturnComicSelection(readableSelection)) {
                    return readableSelection
                }
                if (kind == ReaderMediaKind.AUDIO) {
                    return readableSelection
                }
            } else {
                recordUnreadableComicSelection(kind, selection)
                traceRouteSourceEvaluated(kind, selection, false, startedAt)
            }
            if (kind == ReaderMediaKind.COMIC && System.currentTimeMillis() >= comicCandidateDeadlineAt) {
                traceComicCandidateTimeboxed(selection, startedAt, candidateStartedAt)
            }
            if (
                kind == ReaderMediaKind.COMIC &&
                comicSelectionHardTimedOut(System.currentTimeMillis() - startedAt)
            ) {
                if (bestReadable != null) return bestReadable
                traceComicSelectionUnavailable(
                    bestCatalog = bestCatalog,
                    firstWithChapters = firstWithChapters,
                    candidates = candidates.size,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    reason = "hard_timeout"
                )
                return null
            }
            if (
                kind == ReaderMediaKind.COMIC &&
                bestReadable != null &&
                isStrongComicSelection(bestReadable) &&
                System.currentTimeMillis() - startedAt >= COMIC_DETAIL_SELECTION_SOFT_TIMEOUT_MS
            ) {
                return bestReadable
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
        return when (kind) {
            ReaderMediaKind.COMIC -> bestReadable
                ?: run {
                    traceComicSelectionUnavailable(
                        bestCatalog = bestCatalog,
                        firstWithChapters = firstWithChapters,
                        candidates = candidates.size,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        reason = "strict_comic_unavailable"
                    )
                    null
                }
            ReaderMediaKind.AUDIO -> bestReadable
                ?: audioCatalogFallbackSelection(
                    bestCatalog = bestCatalog,
                    firstWithChapters = firstWithChapters,
                    firstWithDetail = firstWithDetail,
                    reason = "strict_audio_unavailable",
                    candidates = candidates.size,
                    elapsedMs = System.currentTimeMillis() - startedAt
                )
            ReaderMediaKind.NOVEL -> bestReadable
                ?: firstWithChapters?.copy(reason = "chapters_only")
                ?: firstWithDetail?.copy(reason = "detail_only")
        }
    }

    private fun selectComicRouteSource(candidates: List<MediaSourceBook>): RouteSourceSelection? {
        val startedAt = System.currentTimeMillis()
        var firstWithDetail: RouteSourceSelection? = null
        var firstWithChapters: RouteSourceSelection? = null
        var bestReadable: RouteSourceSelection? = null
        var bestCatalog: RouteSourceSelection? = null
        val limitedCandidates = candidates.take(maxDetailSelectionSources(ReaderMediaKind.COMIC))
        val pendingCandidates = ArrayDeque(limitedCandidates)
        val completionService = ExecutorCompletionService<ComicRouteCandidateEvaluation>(routeCandidateExecutor)
        val running = LinkedHashMap<Future<ComicRouteCandidateEvaluation>, ComicRouteCandidateSubmission>()
        fun submitNext() {
            while (running.size < COMIC_DETAIL_SELECTION_PARALLELISM && pendingCandidates.isNotEmpty()) {
                val book = pendingCandidates.removeFirst()
                val submittedAt = System.currentTimeMillis()
                val deadlineAt = startedAt + COMIC_DETAIL_SELECTION_HARD_TIMEOUT_MS
                val future = completionService.submit(
                    Callable {
                        evaluateComicRouteCandidate(
                            book = book,
                            selectionStartedAt = startedAt,
                            candidateStartedAt = submittedAt,
                            deadlineAtMs = deadlineAt
                        )
                    }
                )
                running[future] = ComicRouteCandidateSubmission(book, submittedAt)
            }
        }
        traceComicParallelSelectionStarted(limitedCandidates)
        submitNext()
        try {
            while (running.isNotEmpty()) {
                val elapsedMs = System.currentTimeMillis() - startedAt
                if (comicSelectionHardTimedOut(elapsedMs)) {
                    if (bestReadable != null) return bestReadable
                    running.forEach { (future, submission) ->
                        future.cancel(true)
                        recordComicRouteCandidateTimedOut(submission.book)
                        traceComicCandidateLoadTimedOut(submission.book, startedAt, submission.submittedAt)
                    }
                    traceComicSelectionUnavailable(
                        bestCatalog = bestCatalog,
                        firstWithChapters = firstWithChapters,
                        candidates = candidates.size,
                        elapsedMs = elapsedMs,
                        reason = "hard_timeout"
                    )
                    return null
                }
                if (
                    bestReadable != null &&
                    shouldReturnComicReadableBeforeCandidate(
                        selection = bestReadable,
                        remainingCandidates = running.values.map { it.book } + pendingCandidates.toList(),
                        elapsedMs = elapsedMs
                    )
                ) {
                    traceComicReadableSoftReturn(
                        selection = bestReadable,
                        remainingCandidates = running.values.map { it.book } + pendingCandidates.toList(),
                        elapsedMs = elapsedMs
                    )
                    return bestReadable
                }
                val remainingWaitMs = (COMIC_DETAIL_SELECTION_HARD_TIMEOUT_MS - elapsedMs)
                    .coerceAtMost(COMIC_PARALLEL_POLL_INTERVAL_MS)
                    .coerceAtLeast(1L)
                val future = completionService.poll(remainingWaitMs, TimeUnit.MILLISECONDS) ?: continue
                val submission = running.remove(future) ?: continue
                val evaluation = runCatching { future.get() }.getOrElse {
                    recordComicRouteCandidateTimedOut(submission.book)
                    traceRouteCandidateException(ReaderMediaKind.COMIC, submission.book, it)
                    null
                }
                val selection = evaluation?.selection
                if (selection != null) {
                    if (firstWithDetail == null) firstWithDetail = selection
                    if (selection.chapters.isNotEmpty() && firstWithChapters == null) {
                        firstWithChapters = selection
                    }
                    if (selection.chapters.isNotEmpty() && selection.chapters.size > (bestCatalog?.chapters?.size ?: -1)) {
                        bestCatalog = selection
                    }
                    if (
                        evaluation.readable &&
                        shouldPreferReadableSelection(ReaderMediaKind.COMIC, selection, bestReadable)
                    ) {
                        bestReadable = selection
                    }
                }
                submitNext()
            }
        } finally {
            running.keys.forEach { it.cancel(true) }
        }
        return bestReadable ?: run {
            traceComicSelectionUnavailable(
                bestCatalog = bestCatalog,
                firstWithChapters = firstWithChapters,
                candidates = candidates.size,
                elapsedMs = System.currentTimeMillis() - startedAt,
                reason = "strict_comic_unavailable"
            )
            null
        }
    }

    private fun evaluateComicRouteCandidate(
        book: MediaSourceBook,
        selectionStartedAt: Long,
        candidateStartedAt: Long,
        deadlineAtMs: Long
    ): ComicRouteCandidateEvaluation {
        if (System.currentTimeMillis() >= deadlineAtMs) {
            recordComicRouteCandidateTimedOut(book)
            return ComicRouteCandidateEvaluation(book = book, selection = null, readable = false)
        }
        val selection = loadRouteCandidateBlocking(ReaderMediaKind.COMIC, book)
            ?: return ComicRouteCandidateEvaluation(book = book, selection = null, readable = false)
        val hit = readableComicRouteWindow(selection.chapters, deadlineAtMs)
        if (hit != null) {
            qualityRouter.recordContentResolved(ReaderMediaKind.COMIC, hit.item, hit.itemCount)
            val experience = comicRouteExperience(selection.chapters, deadlineAtMs)
            val readableSelection = selection.copy(
                reason = "tail_window",
                tailTitle = MediaDisplayTextCleaner.clean(hit.item.name).ifBlank { hit.item.name },
                tailOffset = hit.offsetFromLatest,
                tailItems = hit.itemCount,
                experienceScore = experience.score,
                sampleItems = experience.sampleItems,
                navigationItems = experience.navigationItems
            )
            traceRouteSourceEvaluated(ReaderMediaKind.COMIC, readableSelection, true, selectionStartedAt)
            return ComicRouteCandidateEvaluation(book = book, selection = readableSelection, readable = true)
        }
        recordUnreadableComicSelection(ReaderMediaKind.COMIC, selection)
        traceRouteSourceEvaluated(ReaderMediaKind.COMIC, selection, false, selectionStartedAt)
        if (System.currentTimeMillis() >= deadlineAtMs) {
            traceComicCandidateTimeboxed(selection, selectionStartedAt, candidateStartedAt)
        }
        return ComicRouteCandidateEvaluation(book = book, selection = selection, readable = false)
    }

    private fun comicSelectionHardTimedOut(elapsedMs: Long): Boolean {
        return elapsedMs >= COMIC_DETAIL_SELECTION_HARD_TIMEOUT_MS
    }

    private fun readableComicRouteWindow(
        chapters: List<MediaSourceChapter>,
        deadlineAtMs: Long = Long.MAX_VALUE
    ): MediaTailWindowHit<MediaSourceChapter>? {
        if (chapters.isEmpty()) return null
        val tailStart = maxOf(0, chapters.size - MAX_COMIC_DETAIL_TAIL_WINDOW)
        for (index in chapters.lastIndex downTo tailStart) {
            if (System.currentTimeMillis() >= deadlineAtMs) return null
            val count = runCatching { readableComicPageCount(chapters[index]) }.getOrDefault(0)
            if (count > 0) {
                return MediaTailWindowHit(
                    item = chapters[index],
                    itemCount = count,
                    offsetFromLatest = chapters.lastIndex - index
                )
            }
        }
        return null
    }

    private fun shouldReturnComicSelection(selection: RouteSourceSelection): Boolean {
        return isStrongComicSelection(selection)
    }

    private fun shouldReturnComicReadableBeforeCandidate(
        selection: RouteSourceSelection,
        remainingCandidates: List<MediaSourceBook>,
        elapsedMs: Long
    ): Boolean {
        return ComicRouteSelectionPolicy.shouldSoftReturnReadable(
            elapsedMs = elapsedMs,
            chapters = selection.chapters.size,
            sampleItems = selection.sampleItems,
            navigationItems = selection.navigationItems,
            remainingChapterHints = remainingCandidates.map { comicRouteCandidateChapterHint(it) },
            readableSoftTimeoutMs = COMIC_DETAIL_SELECTION_READABLE_SOFT_TIMEOUT_MS,
            strongCatalogChapters = COMIC_STRONG_CATALOG_CHAPTERS,
            acceptableSampleItems = MIN_COMIC_ACCEPTABLE_SAMPLE_ITEMS,
            strongSampleItems = MIN_COMIC_STRONG_SAMPLE_ITEMS,
            strongNavigationItems = MIN_COMIC_STRONG_NAVIGATION_ITEMS,
            closeChapterDelta = COMIC_CLOSE_CHAPTER_DELTA
        )
    }

    private fun isStrongComicSelection(selection: RouteSourceSelection): Boolean {
        return selection.chapters.size >= COMIC_STRONG_CATALOG_CHAPTERS &&
            hasStrongComicExperience(selection)
    }

    private fun hasStrongComicExperience(selection: RouteSourceSelection): Boolean {
        return selection.sampleItems >= MIN_COMIC_STRONG_SAMPLE_ITEMS &&
            selection.navigationItems >= MIN_COMIC_STRONG_NAVIGATION_ITEMS
    }

    private fun traceComicReadableSoftReturn(
        selection: RouteSourceSelection,
        remainingCandidates: List<MediaSourceBook>,
        elapsedMs: Long
    ) {
        AiBridgeTrace.event(
            "media_route_source_comic_readable_soft_return",
            selection.book.name,
            AiBridgeTrace.fields(
                "source" to selection.book.source.sourceName,
                "chapters" to selection.chapters.size,
                "samples" to selection.sampleItems,
                "navigation" to selection.navigationItems,
                "remaining" to remainingCandidates.size,
                "remainingMaxHint" to (remainingCandidates.maxOfOrNull { comicRouteCandidateChapterHint(it) } ?: 0),
                "elapsedMs" to elapsedMs
            )
        )
    }

    private fun traceComicSelectionUnavailable(
        bestCatalog: RouteSourceSelection?,
        firstWithChapters: RouteSourceSelection?,
        candidates: Int,
        elapsedMs: Long,
        reason: String
    ) {
        val fallback = bestCatalog ?: firstWithChapters
        AiBridgeTrace.event(
            "media_route_source_comic_unavailable",
            fallback?.book?.name ?: "comic",
            AiBridgeTrace.fields(
                "source" to (fallback?.book?.source?.sourceName ?: ""),
                "reason" to reason,
                "candidates" to candidates,
                "chapters" to (fallback?.chapters?.size ?: 0),
                "elapsedMs" to elapsedMs
            )
        )
    }

    private fun traceComicSelectionGuardContinued(
        reason: String,
        candidates: Int,
        elapsedMs: Long
    ) {
        AiBridgeTrace.event(
            "media_route_source_comic_guard_continue",
            "comic",
            AiBridgeTrace.fields(
                "reason" to reason,
                "candidates" to candidates,
                "elapsedMs" to elapsedMs
            )
        )
    }

    private fun traceComicParallelSelectionStarted(candidates: List<MediaSourceBook>) {
        AiBridgeTrace.event(
            "media_route_source_comic_parallel_started",
            "comic",
            AiBridgeTrace.fields(
                "parallelism" to COMIC_DETAIL_SELECTION_PARALLELISM,
                "candidates" to candidates.size,
                "sources" to candidates.take(10).joinToString("|") { it.source.sourceName },
                "hints" to candidates.take(10).joinToString("|") { comicRouteCandidateChapterHint(it).toString() }
            )
        )
    }

    private fun traceComicCandidateTimeboxed(
        selection: RouteSourceSelection,
        selectionStartedAt: Long,
        candidateStartedAt: Long
    ) {
        AiBridgeTrace.event(
            "media_route_source_comic_candidate_timeboxed",
            selection.book.name,
            AiBridgeTrace.fields(
                "source" to selection.book.source.sourceName,
                "chapters" to selection.chapters.size,
                "sourceElapsedMs" to (System.currentTimeMillis() - candidateStartedAt).coerceAtLeast(0L),
                "elapsedMs" to (System.currentTimeMillis() - selectionStartedAt).coerceAtLeast(0L)
            )
        )
    }

    private fun traceComicCandidateLoadTimedOut(
        book: MediaSourceBook,
        selectionStartedAt: Long,
        candidateStartedAt: Long
    ) {
        AiBridgeTrace.event(
            "media_route_source_comic_candidate_load_timeout",
            book.name,
            AiBridgeTrace.fields(
                "source" to book.source.sourceName,
                "hint" to comicRouteCandidateChapterHint(book),
                "sourceElapsedMs" to (System.currentTimeMillis() - candidateStartedAt).coerceAtLeast(0L),
                "elapsedMs" to (System.currentTimeMillis() - selectionStartedAt).coerceAtLeast(0L)
            )
        )
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
            ReaderMediaKind.COMIC -> MAX_COMIC_DETAIL_SELECTION_SOURCES
            ReaderMediaKind.NOVEL -> MAX_DETAIL_SELECTION_SOURCES
        }
    }

    private fun loadRouteCandidate(
        kind: ReaderMediaKind,
        book: MediaSourceBook,
        deadlineAtMs: Long = Long.MAX_VALUE
    ): RouteSourceSelection? {
        if (kind != ReaderMediaKind.COMIC || deadlineAtMs == Long.MAX_VALUE) {
            return loadRouteCandidateBlocking(kind, book)
        }
        val remainingMs = (deadlineAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        if (remainingMs <= 0L) return null
        val future = routeCandidateExecutor.submit(Callable { loadRouteCandidateBlocking(kind, book) })
        return try {
            future.get(remainingMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    private fun loadRouteCandidateBlocking(kind: ReaderMediaKind, book: MediaSourceBook): RouteSourceSelection? {
        val detail = when (val result = engine.detail(book)) {
            is MediaEngineResult.Success -> result.value.also { qualityRouter.recordDetailResolved(kind, it) }
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, book)
                traceRouteCandidateDetailFailed(kind, book, result.failure)
                return null
            }
        }
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> {
                traceRouteCandidateChaptersFailed(kind, detail, result.failure)
                emptyList()
            }
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

    private fun recordUnreadableComicSelection(kind: ReaderMediaKind, selection: RouteSourceSelection) {
        if (kind != ReaderMediaKind.COMIC) return
        if (selection.chapters.isEmpty()) return
        val chapter = selection.chapters.lastOrNull() ?: return
        qualityRouter.recordContentResolved(kind, chapter, 0)
    }

    private fun recordComicRouteCandidateTimedOut(book: MediaSourceBook) {
        adjustComicSourceImageHealth(book.source, COMIC_IMAGE_HEALTH_FAILURE_DELTA * 2)
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

    private fun traceRouteSourceEvaluated(
        kind: ReaderMediaKind,
        selection: RouteSourceSelection,
        readable: Boolean,
        startedAt: Long
    ) {
        if (kind != ReaderMediaKind.COMIC) return
        AiBridgeTrace.event(
            "media_route_source_evaluated",
            selection.book.name,
            AiBridgeTrace.fields(
                "source" to selection.book.source.sourceName,
                "chapters" to selection.chapters.size,
                "hint" to comicRouteCandidateChapterHint(selection.book),
                "readable" to readable,
                "reason" to selection.reason,
                "tail" to selection.tailTitle,
                "tailOffset" to selection.tailOffset,
                "tailItems" to selection.tailItems,
                "experience" to selection.experienceScore,
                "samples" to selection.sampleItems,
                "navigation" to selection.navigationItems,
                "elapsedMs" to (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            )
        )
    }

    private fun traceRouteCandidateDetailFailed(
        kind: ReaderMediaKind,
        book: MediaSourceBook,
        failure: MediaEngineFailure
    ) {
        if (kind != ReaderMediaKind.COMIC) return
        AiBridgeTrace.event(
            "media_route_source_detail_failed",
            book.name,
            AiBridgeTrace.fields(
                "source" to book.source.sourceName,
                "url" to comicTraceUrl(book.bookUrl),
                "failure" to failureTraceMessage(failure)
            )
        )
    }

    private fun traceRouteCandidateChaptersFailed(
        kind: ReaderMediaKind,
        detail: MediaSourceBookDetail,
        failure: MediaEngineFailure
    ) {
        if (kind != ReaderMediaKind.COMIC) return
        AiBridgeTrace.event(
            "media_route_source_chapters_failed",
            detail.name,
            AiBridgeTrace.fields(
                "source" to detail.book.source.sourceName,
                "toc" to comicTraceUrl(detail.tocUrl),
                "failure" to failureTraceMessage(failure)
            )
        )
    }

    private fun traceRouteCandidateException(
        kind: ReaderMediaKind,
        book: MediaSourceBook,
        throwable: Throwable
    ) {
        if (kind != ReaderMediaKind.COMIC) return
        AiBridgeTrace.event(
            "media_route_source_candidate_exception",
            book.name,
            AiBridgeTrace.fields(
                "source" to book.source.sourceName,
                "type" to throwable.javaClass.simpleName,
                "message" to traceText(throwable.message.orEmpty(), 120)
            )
        )
    }

    private fun shouldPreferReadableSelection(
        kind: ReaderMediaKind,
        candidate: RouteSourceSelection,
        current: RouteSourceSelection?
    ): Boolean {
        if (current == null) return true
        if (kind == ReaderMediaKind.COMIC) {
            return ComicRouteSelectionPolicy.shouldPreferReadableCandidate(
                candidateChapters = candidate.chapters.size,
                candidateSampleItems = candidate.sampleItems,
                candidateNavigationItems = candidate.navigationItems,
                candidateExperienceScore = candidate.experienceScore,
                currentChapters = current.chapters.size,
                currentSampleItems = current.sampleItems,
                currentNavigationItems = current.navigationItems,
                currentExperienceScore = current.experienceScore,
                strongCatalogChapters = COMIC_STRONG_CATALOG_CHAPTERS,
                strongSampleItems = MIN_COMIC_STRONG_SAMPLE_ITEMS,
                strongNavigationItems = MIN_COMIC_STRONG_NAVIGATION_ITEMS,
                closeChapterDelta = COMIC_CLOSE_CHAPTER_DELTA,
                meaningfulExperienceDelta = COMIC_MEANINGFUL_EXPERIENCE_DELTA
            )
        }
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

    private fun comicRouteExperience(
        chapters: List<MediaSourceChapter>,
        deadlineAtMs: Long = Long.MAX_VALUE
    ): MediaRouteExperience {
        if (chapters.isEmpty()) return MediaRouteExperience.EMPTY
        val indices = linkedSetOf(0, chapters.size / 2, chapters.lastIndex)
        navigationIndex(chapters.size)?.let { middle ->
            indices += middle - 1
            indices += middle
            indices += middle + 1
        }
        val countsByIndex = LinkedHashMap<Int, Int>()
        for (index in indices.filter { it in chapters.indices }) {
            if (System.currentTimeMillis() >= deadlineAtMs) break
            countsByIndex[index] = comicReadableSampleCount(chapters[index], MAX_COMIC_SELECTION_SAMPLE_PAGES)
        }
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
        val navigationOk = navigationCounts.size >= 3 && navigationCounts.all { it >= MIN_COMIC_MULTI_PAGE_SAMPLE }
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

    fun comicReadablePages(chapterRouteId: String): List<MediaRequest> {
        val kind = MediaRouteRegistry.kind(chapterRouteId) ?: return emptyList()
        if (kind != ReaderMediaKind.COMIC) return emptyList()
        val chapter = MediaRouteRegistry.chapter(chapterRouteId) ?: return emptyList()
        val directPages = comicPageRequests(chapter)
        val resolved = readableComicPagesForChapter(chapter, directPages)
            ?: fallbackReadableComicPages(kind, chapterRouteId, chapter)
            ?: emptyList()
        qualityRouter.recordContentResolved(kind, chapter, resolved.size)
        return resolved
    }

    private fun readableComicPagesForChapter(
        chapter: MediaSourceChapter,
        pages: List<MediaRequest>
    ): List<MediaRequest>? {
        if (pages.isEmpty()) return null
        val readable = pages
            .take(MAX_COMIC_IMAGE_PROBE_PAGES_PER_CHAPTER)
            .any { request -> probeComicImage(chapter, request).ok }
        return pages.takeIf { readable }
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
                displayedChapterCount = book.chapterCount,
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
        val comicSample = if (kind == ReaderMediaKind.COMIC && itemCount > 0) {
            comicPageRequests(tail, maxPages = 1).firstOrNull()
        } else {
            null
        }
        val sampleUrl = if (itemCount > 0) {
            when (kind) {
                ReaderMediaKind.AUDIO -> resolveAudioRequest(kind, tail, requirePlayable = true)?.url.orEmpty()
                ReaderMediaKind.COMIC -> comicSample?.url.orEmpty()
                ReaderMediaKind.NOVEL -> ""
            }
        } else {
            ""
        }
        val error = when {
            itemCount > 0 -> ""
            kind == ReaderMediaKind.AUDIO -> "experience_window_unreadable"
            kind == ReaderMediaKind.COMIC -> comicUnreadableReason(tail)
            else -> ""
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
            error = error
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
            val comicSample = if (kind == ReaderMediaKind.COMIC && itemCount > 0) {
                comicPageRequests(tail, maxPages = 1).firstOrNull()
            } else {
                null
            }
            val sampleUrl = if (itemCount > 0) {
                when (kind) {
                    ReaderMediaKind.AUDIO -> resolveAudioRequest(kind, tail, requirePlayable = true)?.url.orEmpty()
                    ReaderMediaKind.COMIC -> comicSample?.url.orEmpty()
                    ReaderMediaKind.NOVEL -> ""
                }
            } else {
                ""
            }
            val error = when {
                itemCount > 0 -> ""
                kind == ReaderMediaKind.COMIC -> comicUnreadableReason(tail)
                else -> "tail_window_unreadable"
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
                error = error
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

    private fun traceComicImageProbe(
        chapter: MediaSourceChapter,
        request: MediaRequest,
        result: ComicImageProbeResult
    ) {
        AiBridgeTrace.event(
            if (result.cancelled) "media_comic_image_probe_cancelled" else "media_comic_image_probe",
            chapter.book.name,
            AiBridgeTrace.fields(
                "source" to chapter.source.sourceName,
                "chapter" to MediaDisplayTextCleaner.clean(chapter.name).ifBlank { chapter.name },
                "ok" to result.ok,
                "cancelled" to result.cancelled,
                "status" to result.statusCode,
                "type" to result.contentType,
                "bytes" to result.bytesRead,
                "error" to result.error.ifBlank { "-" },
                "url" to comicTraceUrl(request.url)
            )
        )
    }

    private fun traceComicPageExtractEmpty(
        chapter: MediaSourceChapter,
        stage: String,
        raw: String,
        message: String
    ) {
        AiBridgeTrace.event(
            "media_comic_page_extract_empty",
            chapter.book.name,
            AiBridgeTrace.fields(
                "source" to chapter.source.sourceName,
                "chapter" to MediaDisplayTextCleaner.clean(chapter.name).ifBlank { chapter.name },
                "stage" to stage,
                "raw" to raw.length,
                "loginLimit" to (raw.contains("访问次数") ||
                    raw.contains("登录") ||
                    raw.contains("login", ignoreCase = true)),
                "captcha" to (raw.contains("captcha", ignoreCase = true) || raw.contains("验证码")),
                "removed" to (raw.contains("下架") || raw.contains("不存在") || raw.contains("404")),
                "rule" to traceText(chapter.source.ruleContent.rules["content"].orEmpty(), 120),
                "msg" to traceText(message, 120),
                "url" to comicTraceUrl(chapter.chapterUrl)
            )
        )
    }

    private fun failureTraceMessage(failure: MediaEngineFailure): String {
        return when (failure) {
            is MediaEngineFailure.ParseError -> failure.message
            is MediaEngineFailure.ContractViolation -> failure.message
            is MediaEngineFailure.NetworkError -> failure.message
            is MediaEngineFailure.RuleError -> failure.message
        }
    }

    private fun traceText(value: String, limit: Int): String {
        return value.replace(Regex("""\s+"""), "_").take(limit)
    }

    private fun comicTraceUrl(url: String): String {
        return url
            .replace("https://", "")
            .replace("http://", "")
            .replace(Regex("""[\s=:/\\#?&]+"""), "_")
            .take(120)
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
                "sources" to candidates.take(10).joinToString("|") { it.source.sourceName },
                "hints" to candidates.take(10).joinToString("|") { comicRouteCandidateChapterHint(it).toString() },
                "healths" to candidates.take(10).joinToString("|") {
                    comicRouteSourceImageHealth(it.source).toString()
                }
            )
        )
    }

    private fun traceComicRouteCandidatesSupplemented(
        primary: MediaSourceBook,
        searchedSources: Int,
        foundBooks: Int,
        addedBooks: List<MediaSourceBook>,
        startedAt: Long
    ) {
        AiBridgeTrace.event(
            "media_route_source_candidates_supplemented",
            primary.name,
            AiBridgeTrace.fields(
                "searched" to searchedSources,
                "found" to foundBooks,
                "added" to addedBooks.size,
                "sources" to addedBooks.take(8).joinToString("|") { it.source.sourceName },
                "titles" to addedBooks.take(8).joinToString("|") { it.name },
                "elapsedMs" to (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
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

    private fun fallbackReadableComicPages(
        kind: ReaderMediaKind,
        chapterRouteId: String,
        primaryChapter: MediaSourceChapter
    ): List<MediaRequest>? {
        val bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId) ?: return null
        return MediaRouteRegistry.alternates(bookRouteId)
            .take(MAX_FALLBACK_SOURCES)
            .firstNotNullOfOrNull { alternate ->
                val alternateChapterPages = resolveAlternateComicChapterPages(kind, alternate, primaryChapter)
                readableComicPagesForChapter(alternateChapterPages.chapter, alternateChapterPages.pages)
            }
    }

    private fun resolveAlternateComicPages(
        kind: ReaderMediaKind,
        alternate: MediaSourceBook,
        primaryChapter: MediaSourceChapter
    ): List<MediaRequest> {
        return resolveAlternateComicChapterPages(kind, alternate, primaryChapter).pages
    }

    private fun resolveAlternateComicChapterPages(
        kind: ReaderMediaKind,
        alternate: MediaSourceBook,
        primaryChapter: MediaSourceChapter
    ): ComicChapterPages {
        val detail = when (val result = engine.detail(alternate)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> {
                qualityRouter.recordDetailFailed(kind, alternate)
                return ComicChapterPages(primaryChapter, emptyList())
            }
        }
        qualityRouter.recordDetailResolved(kind, detail)
        val chapters = when (val result = engine.chapters(detail)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> return ComicChapterPages(primaryChapter, emptyList())
        }
        qualityRouter.recordChapterListResolved(kind, detail, chapters.size)
        val alternateChapter = matchAlternateChapter(chapters, primaryChapter)
            ?: return ComicChapterPages(primaryChapter, emptyList())
        val pages = comicPageRequests(alternateChapter)
        qualityRouter.recordContentResolved(kind, alternateChapter, pages.size)
        return ComicChapterPages(alternateChapter, pages)
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
            is MediaEngineResult.Failure -> {
                traceComicPageExtractEmpty(chapter, "raw_failed", "", failureTraceMessage(result.failure))
                null
            }
        }
        if (raw != null) {
            val adapted = MediaContentJsAdapter.adaptComicRawContent(raw, chapter)
            val pages = ComicPageExtractor.extractRequests(adapted, chapter.chapterUrl, chapter.source.headers)
            if (pages.isNotEmpty()) return maxPages?.let { pages.take(it) } ?: pages
            traceComicPageExtractEmpty(chapter, "rule_empty", raw, "")
        }
        return lazyComicPageRequests(chapter, maxPages).also { pages ->
            if (pages.isEmpty() && raw != null) {
                traceComicPageExtractEmpty(chapter, "lazy_empty", raw, "")
            }
        }
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

    private fun searchTimeoutMs(kind: ReaderMediaKind): Long {
        return when (kind) {
            ReaderMediaKind.COMIC -> COMIC_SEARCH_TIMEOUT_MS
            ReaderMediaKind.NOVEL,
            ReaderMediaKind.AUDIO -> SEARCH_TIMEOUT_MS
        }
    }

    private fun maxSearchValidationSourcesPerGroup(kind: ReaderMediaKind): Int {
        return when (kind) {
            ReaderMediaKind.COMIC -> MAX_COMIC_SEARCH_VALIDATION_SOURCES_PER_GROUP
            ReaderMediaKind.NOVEL,
            ReaderMediaKind.AUDIO -> MAX_SEARCH_VALIDATION_SOURCES_PER_GROUP
        }
    }

    private const val MAX_FALLBACK_SOURCES = 24
    private const val MAX_DETAIL_SELECTION_SOURCES = 12
    private const val MAX_COMIC_DETAIL_SELECTION_SOURCES = 18
    private const val MAX_AUDIO_DETAIL_SELECTION_SOURCES = 6
    private const val AUDIO_DETAIL_SELECTION_SOFT_TIMEOUT_MS = 12_000L
    private const val COMIC_DETAIL_SELECTION_SOFT_TIMEOUT_MS = 12_000L
    private const val COMIC_DETAIL_SELECTION_READABLE_SOFT_TIMEOUT_MS = 10_000L
    private const val COMIC_DETAIL_SELECTION_SOURCE_TIMEOUT_MS = 8_000L
    private const val COMIC_DETAIL_SELECTION_PARALLELISM = 4
    private const val COMIC_PARALLEL_POLL_INTERVAL_MS = 250L
    private const val COMIC_DETAIL_SELECTION_NEXT_GUARD_MS = 28_000L
    private const val COMIC_DETAIL_SELECTION_HARD_TIMEOUT_MS = 30_000L
    private const val COMIC_SEARCH_TIMEOUT_MS = 20_000L
    private const val COMIC_PARTIAL_MIN_DELAY_MS = 8_000L
    private const val COMIC_PARTIAL_MIN_EXACT_SOURCES = 4
    private const val COMIC_PARTIAL_TIMEOUT_GUARD_MS = 3_000L
    private const val COMIC_ROUTE_SUPPLEMENT_SEARCH_TIMEOUT_MS = 6_000L
    private const val MAX_SEARCH_VALIDATION_GROUPS = 24
    private const val MAX_SEARCH_VALIDATION_SOURCES_PER_GROUP = 12
    private const val MAX_COMIC_SEARCH_VALIDATION_SOURCES_PER_GROUP = 10
    private const val MAX_COMIC_ROUTE_SUPPLEMENT_SOURCES = 24
    private const val MAX_COMIC_ROUTE_SUPPLEMENT_RESULTS = 8
    private const val MAX_GATE_TRACE_GROUPS = 16
    private const val MAX_RANK_TRACE_RESULTS = 12
    private const val MAX_TAIL_PROBE_TOTAL_MS = 60_000L
    private const val MAX_SOURCE_AUDIT_TOTAL_MS = 90_000L
    private const val MAX_COMIC_SELECTION_SAMPLE_PAGES = 12
    private const val MAX_COMIC_IMAGE_PROBE_PAGES_PER_CHAPTER = 1
    private const val MAX_COMIC_DETAIL_TAIL_WINDOW = 2
    private const val MIN_COMIC_MULTI_PAGE_SAMPLE = 2
    private const val COMIC_STRONG_CATALOG_CHAPTERS = 50
    private const val MIN_COMIC_ACCEPTABLE_SAMPLE_ITEMS = 10
    private const val MIN_COMIC_STRONG_SAMPLE_ITEMS = 24
    private const val MIN_COMIC_STRONG_NAVIGATION_ITEMS = 9
    private const val COMIC_CLOSE_CHAPTER_DELTA = 3
    private const val COMIC_MEANINGFUL_EXPERIENCE_DELTA = 2_000
    private const val COMIC_IMAGE_HEALTH_SUCCESS_DELTA = 3
    private const val COMIC_IMAGE_HEALTH_FAILURE_DELTA = -4
    private const val COMIC_IMAGE_HEALTH_MIN = -40
    private const val COMIC_IMAGE_HEALTH_MAX = 60
    private const val COMIC_IMAGE_HEALTH_UNHEALTHY_MAX = -1

    private data class IndexedRouteBook(
        val book: MediaSourceBook,
        val sourceRank: Int,
        val originalIndex: Int
    )

    private data class ComicRouteCandidateSubmission(
        val book: MediaSourceBook,
        val submittedAt: Long
    )

    private data class ComicRouteCandidateEvaluation(
        val book: MediaSourceBook,
        val selection: RouteSourceSelection?,
        val readable: Boolean
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

    private data class ComicChapterPages(
        val chapter: MediaSourceChapter,
        val pages: List<MediaRequest>
    )

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
    val displayedChapterCount: Int,
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
