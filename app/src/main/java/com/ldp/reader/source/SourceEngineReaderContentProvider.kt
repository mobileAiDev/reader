package com.ldp.reader.source

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.catalog.ChapterNormalizer
import com.ldp.reader.sourceengine.content.BookContentFingerprint
import com.ldp.reader.sourceengine.content.BookContentFingerprinter
import com.ldp.reader.sourceengine.content.BookContentFingerprintProfile
import com.ldp.reader.sourceengine.legado.LegadoSourceEngine
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.CanonicalChapter
import com.ldp.reader.sourceengine.model.CanonicalChapterList
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.NormalizedChapterTitle
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceBookDetail
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.sourceengine.search.BookSearchRanker
import com.ldp.reader.sourceengine.search.RankedSearchBook
import com.ldp.reader.sourceengine.search.SearchCandidate
import com.ldp.reader.utils.BookCoverUrl
import com.ldp.reader.utils.BookManager
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.widget.page.TxtChapter
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class SourceEngineReaderContentProvider internal constructor(
    private val engineFetcher: OkHttpSourceEngineFetcher = OkHttpSourceEngineFetcher(10_000, 20_000),
    private val searchEngineFetcher: OkHttpSourceEngineFetcher = OkHttpSourceEngineFetcher(10_000, 15_000),
    private val detailProbeEngineFetcher: OkHttpSourceEngineFetcher = OkHttpSourceEngineFetcher(10_000, 15_000),
    private val engine: LegadoSourceEngine = LegadoSourceEngine(fetcher = engineFetcher),
    private val searchEngine: LegadoSourceEngine = LegadoSourceEngine(fetcher = searchEngineFetcher),
    private val detailProbeEngine: LegadoSourceEngine = LegadoSourceEngine(fetcher = detailProbeEngineFetcher),
    private val sourceProvider: () -> List<BookSource> = { SourceEngineRuntime.compatibleSources() },
    private val sourceFinder: (String) -> BookSource = { sourceUrl -> SourceEngineRuntime.findSource(sourceUrl) },
    private val sourceQualityRouter: SourceQualityRouter = SourceQualityRouter()
) : ReaderContentProvider {
    override val providerName: String = "source-engine"
    private val searchRanker = BookSearchRanker()
    private val chapterNormalizer = ChapterNormalizer()
    private val bookContentFingerprinter = BookContentFingerprinter()
    private val tailBoundaryLocator = CatalogTailBoundaryLocator(MAX_CATALOG_TAIL_BACKTRACK_CHAPTERS)
    private val catalogTailTrimCache = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val searchCoverCache = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val searchValidationCache = Collections.synchronizedMap(mutableMapOf<String, ValidatedSearchCandidate>())
    private val searchValidationLocks = Collections.synchronizedMap(mutableMapOf<String, Mutex>())
    private val bookFingerprintCache = Collections.synchronizedMap(mutableMapOf<String, BookContentFingerprintProfile>())
    private val bookFingerprintTrustedUpperCache = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val bookFingerprintBuildLocks = Collections.synchronizedMap(mutableMapOf<String, Mutex>())
    private val bookContentWaterfallCache = Collections.synchronizedMap(mutableMapOf<String, BookContentWaterfall>())
    private val catalogTailTrimLocks = Collections.synchronizedMap(mutableMapOf<String, Mutex>())
    private val requestScopeIds = AtomicLong()

    override suspend fun searchHotWords(): List<String> {
        return DEFAULT_HOT_WORDS
    }

    override suspend fun searchKeyWords(query: String?): List<String> {
        val keyword = query?.trim().orEmpty()
        if (keyword.isBlank()) return emptyList()
        return buildList {
            add(keyword)
            addAll(completedTitleQueries(keyword))
        }.distinct().take(MAX_KEYWORD_SUGGESTIONS)
    }

    private suspend fun <T> withSourceRequestScope(
        operation: String,
        key: String?,
        block: suspend () -> T
    ): T {
        val scope = newSourceRequestScope(operation, key, parent = null)
        return try {
            withContext(sourceRequestContext(scope)) {
                block()
            }
        } finally {
            cancelSourceRequests(scope)
        }
    }

    private fun newSourceRequestScope(
        operation: String,
        key: String?,
        parent: SourceRequestScope?
    ): SourceRequestScope {
        return SourceRequestScope(
            id = requestScopeIds.incrementAndGet(),
            name = "$operation:${key.orEmpty().debugToken()}",
            parent = parent
        )
    }

    private fun sourceRequestContext(scope: SourceRequestScope): CoroutineContext {
        return engineFetcher.requestScopeContext(scope) +
            searchEngineFetcher.requestScopeContext(scope) +
            detailProbeEngineFetcher.requestScopeContext(scope)
    }

    private fun activeSourceRequestContext(): CoroutineContext {
        val scope = engineFetcher.currentRequestScope() ?: return EmptyCoroutineContext
        return sourceRequestContext(scope)
    }

    private fun currentSourceRequestScope(): SourceRequestScope? {
        return engineFetcher.currentRequestScope()
    }

    private fun cancelSourceRequests(scope: SourceRequestScope) {
        engineFetcher.cancel(scope)
        searchEngineFetcher.cancel(scope)
        detailProbeEngineFetcher.cancel(scope)
    }

    override suspend fun searchBooks(query: String?): List<BookSearchResult> = withSourceRequestScope("search", query) {
        withContext(Dispatchers.IO) {
            searchBooksWaterfall(
                query = query,
                totalTimeoutMs = SEARCH_TIMEOUT_MS,
                progressive = false,
                onUpdate = {}
            )
        }
    }

    override suspend fun searchBooksProgressively(
        query: String?,
        onUpdate: suspend (List<BookSearchResult>) -> Unit
    ): List<BookSearchResult> = withSourceRequestScope("searchProgressive", query) {
        withContext(Dispatchers.IO) {
            searchBooksWaterfall(
                query = query,
                totalTimeoutMs = SEARCH_PROGRESSIVE_TOTAL_TIMEOUT_MS,
                progressive = true,
                onUpdate = onUpdate
            )
        }
    }

    private suspend fun searchBooksWaterfall(
        query: String?,
        totalTimeoutMs: Long,
        progressive: Boolean,
        onUpdate: suspend (List<BookSearchResult>) -> Unit
    ): List<BookSearchResult> {
        val keyword = query?.trim().orEmpty()
        if (keyword.isBlank()) return emptyList()
        val startedAt = System.currentTimeMillis()
        val candidates = Collections.synchronizedList(ArrayList<SearchCandidate>())
        val compatibleSources = sourceProvider()
        val sources = searchSourcesFor(compatibleSources, keyword).take(MAX_SEARCH_SOURCES)
        AiBridgeTrace.state(
            "source_search_source_window",
            keyword,
            "compatible_${compatibleSources.size}_selected_${sources.size}_first_${
                sources.take(24).joinToString("|") { source -> sourceLabel(source).debugToken() }
            }"
        )
        traceSearchTierWindow(keyword, sources)
        val searchQueries = searchQueriesFor(keyword)
        val startedSearchRequests = AtomicInteger()
        val completedSearchRequests = AtomicInteger()
        val successfulSearchRequests = AtomicInteger()
        val sourceRequestTraces = Collections.synchronizedList(ArrayList<SearchSourceRequestTrace>())
        val firstSourceResponseLogged = AtomicBoolean(false)
        val firstAcceptedCandidateLogged = AtomicBoolean(false)
        val firstExactCandidateLogged = AtomicBoolean(false)
        val semaphore = Semaphore(MAX_CONCURRENT_SEARCHES)
        var lastEmittedKey = ""
        var lastProgressCandidateCount = 0
        var lastOutput: List<BookSearchResult> = emptyList()

        val searchExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_SEARCHES)
        val searchDispatcher = searchExecutor.asCoroutineDispatcher()
        val searchScope = CoroutineScope(searchDispatcher + SupervisorJob() + activeSourceRequestContext())
        try {
            val jobs = sources.flatMapIndexed { sourceIndex, source ->
                searchQueries.map { searchQuery ->
                    searchScope.async {
                        semaphore.withPermit {
                            val requestStartedAt = System.currentTimeMillis()
                            var success = false
                            var resultCount = 0
                            var acceptedCount = 0
                            var firstAccepted = ""
                            startedSearchRequests.incrementAndGet()
                            val search = try {
                                when (val value = searchEngine.search(listOf(source), searchQuery, maxSources = 1)) {
                                    is EngineResult.Success -> {
                                        successfulSearchRequests.incrementAndGet()
                                        success = true
                                        value.value
                                    }
                                    is EngineResult.Failure -> null
                                }
                            } finally {
                                completedSearchRequests.incrementAndGet()
                            } ?: run {
                                recordSearchSourceTrace(
                                    keyword = keyword,
                                    trace = SearchSourceRequestTrace(
                                        source = sourceLabel(source),
                                        sourceIndex = sourceIndex,
                                        query = searchQuery,
                                        durationMs = System.currentTimeMillis() - requestStartedAt,
                                        success = success,
                                        resultCount = resultCount,
                                        acceptedCount = acceptedCount,
                                        firstAccepted = firstAccepted
                                    ),
                                    traces = sourceRequestTraces,
                                    firstSourceResponseLogged = firstSourceResponseLogged,
                                    firstAcceptedCandidateLogged = firstAcceptedCandidateLogged,
                                    firstExactCandidateLogged = firstExactCandidateLogged
                                )
                                return@withPermit
                            }
                            resultCount = search.books.size
                            search.books.take(MAX_RESULTS_PER_SOURCE).forEachIndexed { resultIndex, book ->
                                val candidate = SearchCandidate(
                                    book = book,
                                    sourceIndex = sourceIndex,
                                    resultIndex = resultIndex,
                                    searchQuery = searchQuery
                                )
                                if (searchRanker.score(keyword, candidate).score > 0) {
                                    acceptedCount += 1
                                    if (firstAccepted.isBlank()) {
                                        firstAccepted = "${book.name}/${book.author}"
                                    }
                                    synchronized(candidates) {
                                        candidates.add(candidate)
                                    }
                                }
                            }
                            recordSearchSourceTrace(
                                keyword = keyword,
                                trace = SearchSourceRequestTrace(
                                    source = sourceLabel(source),
                                    sourceIndex = sourceIndex,
                                    query = searchQuery,
                                    durationMs = System.currentTimeMillis() - requestStartedAt,
                                    success = success,
                                    resultCount = resultCount,
                                    acceptedCount = acceptedCount,
                                    firstAccepted = firstAccepted
                                ),
                                traces = sourceRequestTraces,
                                firstSourceResponseLogged = firstSourceResponseLogged,
                                firstAcceptedCandidateLogged = firstAcceptedCandidateLogged,
                                firstExactCandidateLogged = firstExactCandidateLogged
                            )
                        }
                    }
                }
            }
            val deadline = startedAt + totalTimeoutMs
            while (jobs.any { job -> !job.isCompleted } && System.currentTimeMillis() < deadline) {
                delay(SEARCH_PROGRESS_POLL_INTERVAL_MS)
                if (!progressive) continue
                val snapshot = synchronized(candidates) { candidates.toList() }
                if (
                    snapshot.size >= FIRST_PROGRESS_MIN_CANDIDATES &&
                    snapshot.size != lastProgressCandidateCount
                ) {
                    lastProgressCandidateCount = snapshot.size
                    val progressOutput = searchOutputForCandidates(
                        keyword = keyword,
                        candidateSnapshot = snapshot,
                        startedAt = startedAt,
                        stage = "progress",
                        rankTimeoutMs = SEARCH_PROGRESS_RANK_TIMEOUT_MS,
                        progressiveRank = true
                    )
                    val progressKey = searchOutputIdentityKey(progressOutput)
                    if (progressOutput.isNotEmpty() && progressKey != lastEmittedKey) {
                        lastOutput = progressOutput
                        lastEmittedKey = progressKey
                        AiBridgeTrace.state(
                            "source_search_progress",
                            keyword,
                            "raw_${snapshot.size}_count_${progressOutput.size}" +
                                "_elapsedMs_${System.currentTimeMillis() - startedAt}"
                        )
                        onUpdate(progressOutput)
                    }
                }
            }
            if (jobs.any { job -> !job.isCompleted }) {
                AiBridgeTrace.state(
                    "source_search_timeout",
                    keyword,
                    "timeoutMs_${totalTimeoutMs}_started_${startedSearchRequests.get()}" +
                        "_completed_${completedSearchRequests.get()}_candidates_${synchronized(candidates) { candidates.size }}"
                )
            }
            jobs.forEach { it.cancel() }
            currentSourceRequestScope()?.let { searchEngineFetcher.cancel(it) }
        } finally {
            searchScope.coroutineContext.cancelChildren()
            searchDispatcher.close()
            searchExecutor.shutdownNow()
        }

        val candidateSnapshot = synchronized(candidates) { candidates.toList() }
        val candidateCount = candidateSnapshot.size
        Log.i(
            TAG,
            "operation=searchCandidates provider=$providerName key=$keyword " +
                "rawCandidates=$candidateCount elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        AiBridgeTrace.state(
            "source_search_candidates",
            keyword,
            "raw_${candidateCount}_elapsedMs_${System.currentTimeMillis() - startedAt}"
        )
        AiBridgeTrace.state(
            "source_search_requests",
            keyword,
            "sources_${sources.size}_queries_${searchQueries.size}" +
                "_started_${startedSearchRequests.get()}_completed_${completedSearchRequests.get()}" +
                "_success_${successfulSearchRequests.get()}_candidates_$candidateCount"
        )
        traceSearchSourceLatency(keyword, sourceRequestTraces)
        if (progressive) {
            val readyOutput = searchOutputForCandidates(
                keyword = keyword,
                candidateSnapshot = candidateSnapshot,
                startedAt = startedAt,
                stage = "completed_ready",
                rankTimeoutMs = SEARCH_PROGRESS_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS,
                progressiveRank = true
            )
            val readyKey = searchOutputIdentityKey(readyOutput)
            if (readyOutput.isNotEmpty() && readyKey != lastEmittedKey) {
                lastOutput = readyOutput
                lastEmittedKey = readyKey
                AiBridgeTrace.state(
                    "source_search_completed_ready",
                    keyword,
                    "raw_${candidateCount}_count_${readyOutput.size}" +
                        "_elapsedMs_${System.currentTimeMillis() - startedAt}"
                )
                onUpdate(readyOutput)
            }
        }
        val output = searchOutputForCandidates(
            keyword = keyword,
            candidateSnapshot = candidateSnapshot,
            startedAt = startedAt,
            stage = "completed",
            rankTimeoutMs = null
        )
        if (progressive) {
            val outputKey = searchOutputIdentityKey(output)
            if (output.isNotEmpty() && outputKey != lastEmittedKey) {
                onUpdate(output)
            }
        }
        val finalOutput = if (output.isNotEmpty() || lastOutput.isEmpty()) output else lastOutput
        AiBridgeTrace.state(
            "source_search_completed",
            keyword,
            "raw_${candidateCount}_count_${finalOutput.size}_durationMs_${System.currentTimeMillis() - startedAt}"
        )
        return finalOutput
    }

    private fun traceSearchTierWindow(keyword: String, sources: List<BookSource>) {
        val snapshots = sources.map { source -> source to sourceQualityRouter.sourceDebugSnapshot(source) }
        AiBridgeTrace.state(
            "source_search_tier_window",
            keyword,
            "tier1_${snapshots.count { it.second.tier == 1 }}" +
                "_tier2_${snapshots.count { it.second.tier == 2 }}" +
                "_tier3_${snapshots.count { it.second.tier == 3 }}" +
                "_first_${snapshots.take(24).joinToString("|") { (source, snapshot) ->
                    "${sourceLabel(source)}:tier_${snapshot.tier}:score_${snapshot.score}:bucket_${snapshot.bucket}".debugToken()
                }}"
        )
    }

    private fun recordSearchSourceTrace(
        keyword: String,
        trace: SearchSourceRequestTrace,
        traces: MutableList<SearchSourceRequestTrace>,
        firstSourceResponseLogged: AtomicBoolean,
        firstAcceptedCandidateLogged: AtomicBoolean,
        firstExactCandidateLogged: AtomicBoolean
    ) {
        traces.add(trace)
        if (firstSourceResponseLogged.compareAndSet(false, true)) {
            AiBridgeTrace.event(
                "source_search_first_source_response",
                keyword,
                trace.debugValue()
            )
        }
        if (trace.acceptedCount > 0 && firstAcceptedCandidateLogged.compareAndSet(false, true)) {
            AiBridgeTrace.event(
                "source_search_first_accepted_candidate",
                keyword,
                trace.debugValue()
            )
        }
        if (
            trace.acceptedCount > 0 &&
            normalizeHint(trace.firstAccepted.substringBefore('/')) == normalizeHint(keyword) &&
            firstExactCandidateLogged.compareAndSet(false, true)
        ) {
            AiBridgeTrace.event(
                "source_search_first_exact_candidate",
                keyword,
                trace.debugValue()
            )
        }
    }

    private fun traceSearchSourceLatency(
        keyword: String,
        traces: List<SearchSourceRequestTrace>
    ) {
        val snapshot = synchronized(traces) { traces.toList() }
        if (snapshot.isEmpty()) return
        val accepted = snapshot.filter { trace -> trace.acceptedCount > 0 }
        AiBridgeTrace.state(
            "source_search_source_latency",
            keyword,
            "requests_${snapshot.size}" +
                "_success_${snapshot.count { trace -> trace.success }}" +
                "_acceptedSources_${accepted.size}" +
                "_noResult_${snapshot.count { trace -> trace.success && trace.resultCount == 0 }}" +
                "_fastAccepted_${accepted.sortedBy { trace -> trace.durationMs }.take(8).joinToString("|") { trace ->
                    trace.debugValue()
                }}" +
                "_slow_${snapshot.sortedByDescending { trace -> trace.durationMs }.take(8).joinToString("|") { trace ->
                    trace.debugValue()
                }}"
        )
    }

    private suspend fun searchOutputForCandidates(
        keyword: String,
        candidateSnapshot: List<SearchCandidate>,
        startedAt: Long,
        stage: String,
        rankTimeoutMs: Long?,
        progressiveRank: Boolean = false
    ): List<BookSearchResult> {
        val stageStartedAt = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_search_rank_stage_started",
            keyword,
            "stage_${stage}_raw_${candidateSnapshot.size}_progressive_${progressiveRank}"
        )
        val ranked = if (rankTimeoutMs == null) {
            rankSearchCandidates(keyword, candidateSnapshot, progressiveRank)
        } else {
            withTimeoutOrNull(rankTimeoutMs) {
                rankSearchCandidates(keyword, candidateSnapshot, progressiveRank)
            } ?: emptyList()
        }
        val rankedAt = System.currentTimeMillis()
        Log.i(
            TAG,
            "operation=searchRanked stage=$stage provider=$providerName key=$keyword " +
                "ranked=${ranked.size} elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        AiBridgeTrace.state(
            "source_search_ranked_$stage",
            keyword,
            "raw_${candidateSnapshot.size}_ranked_${ranked.size}_top_${
                ranked.take(5).joinToString("_") { it.debugLabel().debugToken() }
            }_rankMs_${rankedAt - stageStartedAt}_elapsedMs_${rankedAt - startedAt}"
        )
        val coverFilledRanked = if (progressiveRank) ranked else fillSearchCovers(ranked)
        val coverFilledAt = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_search_rank_stage_finished",
            keyword,
            "stage_${stage}_raw_${candidateSnapshot.size}_ranked_${ranked.size}" +
                "_rankMs_${rankedAt - stageStartedAt}" +
                "_coverMs_${coverFilledAt - rankedAt}" +
                "_elapsedMs_${coverFilledAt - startedAt}"
        )
        return coverFilledRanked.map { rankedBook ->
            val book = rankedBook.book
            BookSearchResult().apply {
                routeId = SourceEngineBookRoute.bookId(book)
                title = searchRanker.displayTitle(book)
                author = cleanAuthor(book.author)
                cover = BookCoverUrl.clean(book.coverUrl).takeIf { BookCoverUrl.isLikelyImage(it) }.orEmpty()
                desc = cleanIntro(book.intro)
            }
        }
    }

    private fun searchOutputIdentityKey(books: List<BookSearchResult>): String {
        return books.joinToString("\n") { book ->
            book.routeId ?: "${book.title.orEmpty()}\t${book.author.orEmpty()}"
        }
    }

    override suspend fun refreshSearchCovers(
        query: String?,
        books: List<BookSearchResult>
    ): List<BookSearchResult> = withSourceRequestScope("searchCoverRefresh", query) {
        withContext(Dispatchers.IO) {
        if (books.isEmpty()) return@withContext books
        val updated = books.toMutableList()
        supervisorScope {
            books.take(MAX_BACKGROUND_COVER_REFRESH_RESULTS).mapIndexed { index, book ->
                async {
                    val cacheKey = searchCoverCacheKey(book)
                    val cached = searchCoverCache[cacheKey]
                    if (BookCoverUrl.isLikelyImage(cached)) {
                        return@async index to copySearchResultWithCover(book, cached.orEmpty())
                    }
                    if (BookCoverUrl.isLikelyImage(book.cover)) return@async null
                    val routeId = book.routeId ?: return@async null
                    val route = SourceEngineBookRoute.decodeBookId(routeId)
                    val sourceBook = runCatching {
                        SourceEngineBookRoute.toSourceBook(
                        sourceFinder(route.sourceUrl),
                            route
                        )
                    }.getOrNull() ?: return@async null
                    val cover = withTimeoutOrNull(BACKGROUND_COVER_REFRESH_ITEM_TIMEOUT_MS) {
                        findCoverFallback(sourceBook)
                    }
                    if (!BookCoverUrl.isLikelyImage(cover)) return@async null
                    val cleaned = BookCoverUrl.clean(cover)
                    searchCoverCache[cacheKey] = cleaned
                    index to copySearchResultWithCover(book, cleaned)
                }
            }.awaitAll().filterNotNull()
        }.forEach { (index, book) ->
            updated[index] = book
        }
        updated
        }
    }

    private fun searchCoverCacheKey(book: BookSearchResult): String {
        return book.routeId
            ?: "${book.title.orEmpty()}\n${book.author.orEmpty()}"
    }

    private fun copySearchResultWithCover(
        book: BookSearchResult,
        cover: String
    ): BookSearchResult {
        return BookSearchResult().apply {
            this.cover = cover
            title = book.title
            author = book.author
            desc = book.desc
            sources = book.sources
            routeId = book.routeId
        }
    }

    private fun searchQueriesFor(keyword: String): List<String> {
        return buildList {
            add(keyword)
            addAll(titleAliasQueries(keyword))
            addAll(shortPrefixQueries(keyword))
        }.distinct()
    }

    private fun shortPrefixQueries(keyword: String): List<String> {
        val normalized = normalizeHint(keyword)
        if (normalized.length < LONG_TITLE_PREFIX_QUERY_MIN_CHARS) return emptyList()
        return listOf(normalized.take(SHORT_PREFIX_QUERY_CHARS))
            .filter { query -> query.length >= MIN_COMPLETION_QUERY_CHARS && query != normalized }
            .distinct()
    }

    private fun titleAliasQueries(keyword: String): List<String> {
        val raw = keyword.trim()
        val normalizedKeyword = normalizeHint(raw)
        return buildList {
            if (raw.contains("仙路")) {
                add(raw.replace("仙路", "仙途"))
            }
        }.map { it.trim() }
            .filter { it.isNotBlank() && normalizeHint(it) != normalizedKeyword }
            .distinct()
    }

    private fun completedTitleQueries(keyword: String): List<String> {
        val normalizedKeyword = normalizeHint(keyword)
        if (normalizedKeyword.length < MIN_COMPLETION_QUERY_CHARS) return emptyList()
        return DEFAULT_HOT_WORDS
            .filter { title ->
                val normalizedTitle = normalizeHint(title)
                normalizedTitle != normalizedKeyword && normalizedTitle.startsWith(normalizedKeyword)
            }
    }

    private fun normalizeHint(value: String): String {
        return value
            .lowercase()
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+"""), "")
            .trim()
    }

    private suspend fun rankSearchCandidates(
        keyword: String,
        candidates: List<SearchCandidate>,
        progressive: Boolean = false
    ): List<ValidatedSearchCandidate> {
        val scored = searchRanker.scoreCandidates(keyword, candidates)
        if (scored.isEmpty()) return emptyList()

        val queryKey = normalizeHint(keyword)
        val titleGroups = scored
            .groupBy { ranked -> searchIdentityKey(ranked.book) }
            .entries
            .map { (identityKey, group) ->
                SearchTitleGroup(
                    identityKey,
                    searchRanker.canonicalTitleKey(group.first().book),
                    group.sortedWith(rankedSearchComparator),
                    authorConsensusFor(group.map { ranked -> ranked.book })
                )
            }
            .filter { group -> titleGroupMatchesKeyword(queryKey, group.titleKey) }
            .filter { group ->
                group.candidates.uniqueSearchSourceCount() >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT ||
                    (!progressive && queryKey.length >= MIN_EXACT_GROUP_ONLY_QUERY_CHARS && group.titleKey == queryKey)
            }
            .let { groups -> prioritizeExactTitleGroups(queryKey, groups, progressive) }
            .sortedWith(searchTitleGroupComparator(queryKey))
            .take(if (progressive) MAX_PROGRESS_VALIDATION_TITLE_GROUPS else MAX_VALIDATION_TITLE_GROUPS)
        AiBridgeTrace.state(
            "source_search_groups",
            keyword,
            "scored_${scored.size}_matched_${titleGroups.size}" +
                "_top_${titleGroups.take(5).joinToString("_") { group ->
                    "${group.identityKey.replace('\n', '@')}/${group.candidates.uniqueSearchSourceCount()}".debugToken()
                }}"
        )
        if (titleGroups.isEmpty()) return emptyList()
        val validationStageStartedAt = System.currentTimeMillis()
        val semaphore = Semaphore(MAX_CONCURRENT_VALIDATIONS)
        return supervisorScope {
            val groupValidations = titleGroups.map { titleGroup ->
                async {
                    validateSearchTitleGroup(
                        titleGroup = titleGroup,
                        semaphore = semaphore,
                        allowEarlyReturn = progressive
                    )
                }
            }
            val validatedGroups = if (progressive) {
                val waitForPreferredExactGroup = shouldWaitForExactTitleAuthorGroups(queryKey, titleGroups)
                val timeoutMs = if (waitForPreferredExactGroup) {
                    SEARCH_PROGRESS_EXACT_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS
                } else {
                    SEARCH_PROGRESS_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS
                }
                val preferredIdentityKeys = if (waitForPreferredExactGroup) {
                    preferredProgressIdentityKeys(titleGroups)
                } else {
                    emptySet()
                }
                AiBridgeTrace.event(
                    "source_search_validation_stage_started",
                    keyword,
                    "mode_progress_groups_${titleGroups.size}_timeoutMs_${timeoutMs}" +
                        "_waitPreferred_${waitForPreferredExactGroup}" +
                        "_preferred_${preferredIdentityKeys.joinToString("|") { key -> key.replace('\n', '@').debugToken() }}"
                )
                awaitValidatedSearchGroupsUntilOutput(
                    keyword = keyword,
                    groupValidations = groupValidations,
                    timeoutMs = timeoutMs,
                    targetResultCount = SEARCH_PROGRESS_RESULT_TARGET,
                    preferredIdentityKeys = preferredIdentityKeys
                )
            } else {
                AiBridgeTrace.event(
                    "source_search_validation_stage_started",
                    keyword,
                    "mode_completed_groups_${titleGroups.size}_timeoutMs_${SEARCH_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS}"
                )
                awaitFinishedValuesWithin(groupValidations, SEARCH_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS)
            }
            AiBridgeTrace.event(
                "source_search_validation_stage_finished",
                keyword,
                "groups_${titleGroups.size}_completed_${validatedGroups.size}" +
                    "_candidates_${validatedGroups.flatten().size}" +
                    "_durationMs_${System.currentTimeMillis() - validationStageStartedAt}"
            )
            val mergedGroups = mergeValidatedSearchGroups(
                keyword = keyword,
                candidates = validatedGroups.flatten(),
                fastForProgress = progressive
            )
            if (progressive) {
                progressVisibleCandidates(mergedGroups)
            } else {
                mergedGroups
            }
        }
            .sortedWith(validatedSearchComparator)
            .take(MAX_SEARCH_RESULTS)
    }

    private suspend fun mergeValidatedSearchGroups(
        keyword: String,
        candidates: List<ValidatedSearchCandidate>,
        fastForProgress: Boolean = false
    ): List<ValidatedSearchCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val groups = candidates
            .groupBy { candidate -> searchIdentityKey(candidate.book) }
            .values
            .mapNotNull { group ->
                mergeValidatedTitleGroup(
                    group = group.sortedWith(validatedSearchComparator),
                    fastForProgress = fastForProgress
                )
            }
        AiBridgeTrace.state(
            "source_search_validated_groups",
            keyword,
            "groups_${groups.size}_top_${groups.take(5).joinToString("_") { group ->
                "${group.book.name}@${group.book.author}/${group.chapterCount}".debugToken()
            }}"
        )
        return groups
    }

    private fun shouldWaitForExactTitleAuthorGroups(
        queryKey: String,
        titleGroups: List<SearchTitleGroup>
    ): Boolean {
        return exactTitleOnlyReady(queryKey, titleGroups, progressive = true) &&
            titleGroups.size > 1 &&
            titleGroups.all { group -> group.titleKey == queryKey }
    }

    private fun preferredProgressIdentityKeys(titleGroups: List<SearchTitleGroup>): Set<String> {
        if (titleGroups.isEmpty()) return emptySet()
        val maxSourceCount = titleGroups.maxOf { group -> group.candidates.uniqueSearchSourceCount() }
        return titleGroups
            .filter { group -> group.candidates.uniqueSearchSourceCount() == maxSourceCount }
            .mapTo(LinkedHashSet()) { group -> group.identityKey }
    }

    private suspend fun awaitValidatedSearchGroupsUntilOutput(
        keyword: String,
        groupValidations: List<Deferred<List<ValidatedSearchCandidate>?>>,
        timeoutMs: Long,
        targetResultCount: Int,
        preferredIdentityKeys: Set<String>
    ): List<List<ValidatedSearchCandidate>> {
        if (groupValidations.isEmpty()) return emptyList()
        val completed = ArrayList<List<ValidatedSearchCandidate>>()
        val pending = groupValidations.toMutableList()
        try {
            withTimeoutOrNull(timeoutMs) {
                while (pending.isNotEmpty()) {
                    val next = select<Pair<Deferred<List<ValidatedSearchCandidate>?>, List<ValidatedSearchCandidate>?>> {
                        pending.forEach { deferred ->
                            deferred.onAwait { value -> deferred to value }
                        }
                    }
                    pending.remove(next.first)
                    next.second?.let { group ->
                        completed.add(group)
                        val merged = mergeValidatedSearchGroups(
                            keyword = keyword,
                            candidates = completed.flatten(),
                            fastForProgress = true
                        )
                        val visibleMerged = progressVisibleCandidates(merged)
                        val preferredReady = preferredIdentityKeys.isEmpty() ||
                            visibleMerged.any { candidate -> searchIdentityKey(candidate.book) in preferredIdentityKeys }
                        if (visibleMerged.size >= targetResultCount && preferredReady) {
                            return@withTimeoutOrNull
                        }
                    }
                }
            }
        } finally {
            pending.forEach { deferred ->
                if (!deferred.isCompleted) deferred.cancel()
            }
        }
        return completed.toList()
    }

    private suspend fun progressVisibleCandidates(
        candidates: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.filter { candidate -> searchProgressCandidateReady(candidate) }
    }

    private suspend fun searchProgressCandidateReady(candidate: ValidatedSearchCandidate): Boolean {
        if (candidate.chapterCount < MIN_PROGRESSIVE_FIRST_DISPLAY_CHAPTERS) {
            AiBridgeTrace.event(
                "source_search_progress_candidate_deferred",
                candidate.book.name,
                "reason_short_catalog_source_${sourceLabel(candidate.book).debugToken()}" +
                    "_chapters_${candidate.chapterCount}_hint_${candidate.freshnessHint}"
            )
            return false
        }
        val freshnessHint = candidate.freshnessHint
        if (freshnessHint < MIN_PROGRESSIVE_FIRST_DISPLAY_CHAPTERS) return true
        val resolved = candidate.resolved
        if (resolved == null) {
            AiBridgeTrace.event(
                "source_search_progress_candidate_deferred",
                candidate.book.name,
                "reason_missing_resolved_source_${sourceLabel(candidate.book).debugToken()}" +
                    "_chapters_${candidate.chapterCount}_hint_$freshnessHint"
            )
            return false
        }
        AiBridgeTrace.event(
            "source_search_progress_candidate_ready",
            candidate.book.name,
            "source_${sourceLabel(candidate.book).debugToken()}" +
                "_chapters_${candidate.chapterCount}_hint_$freshnessHint" +
                "_validation_${candidate.validation.debugToken()}" +
                "_resolved_${resolved.catalog.chapters.size}"
        )
        return true
    }

    private fun lastChapterOrdinal(chapters: List<CanonicalChapter>): Int {
        return chapters
            .asReversed()
            .firstNotNullOfOrNull { chapter ->
                chapter.ordinal ?: chapterNormalizer.normalize(chapter.displayTitle).ordinal
            } ?: 0
    }

    private fun tailOrdinalGapCount(chapters: List<CanonicalChapter>): Int {
        val ordinals = chapters
            .mapNotNull { chapter ->
                chapter.ordinal ?: chapterNormalizer.normalize(chapter.displayTitle).ordinal
            }
            .toSet()
        val last = ordinals.maxOrNull() ?: return 0
        val first = maxOf(1, last - READABLE_TAIL_CONTINUITY_WINDOW + 1)
        return (first..last).count { ordinal -> ordinal !in ordinals }
    }

    private fun tailContinuityScore(lastOrdinal: Int, gapCount: Int): Int {
        return lastOrdinal - gapCount * READABLE_TAIL_GAP_PENALTY_ORDINALS
    }

    private fun titleGroupMatchesKeyword(queryKey: String, titleKey: String): Boolean {
        if (queryKey.isBlank() || titleKey.isBlank()) return false
        return titleKey == queryKey ||
            titleKey.startsWith(queryKey) ||
            titleKey.contains(queryKey) ||
            (queryKey.contains(titleKey) && titleKey.length >= 2)
    }

    private fun prioritizeExactTitleGroups(
        queryKey: String,
        groups: List<SearchTitleGroup>,
        progressive: Boolean
    ): List<SearchTitleGroup> {
        val exactGroups = groups.filter { group -> group.titleKey == queryKey }
        if (exactGroups.isEmpty()) return groups
        val exactOnly = exactTitleOnlyReady(queryKey, exactGroups, progressive)
        val exactMaxSources = exactGroups.maxOf { group -> group.candidates.uniqueSearchSourceCount() }
        AiBridgeTrace.state(
            "source_search_exact_title_groups",
            queryKey,
            "exact_${exactGroups.size}_maxSources_${exactMaxSources}" +
                "_suppressed_${if (exactOnly) groups.size - exactGroups.size else 0}" +
                "_mode_${if (progressive) "progress" else "completed"}" +
                "_exactOnly_${exactOnly}"
        )
        return if (exactOnly) exactGroups else groups
    }

    private fun exactTitleOnlyReady(
        queryKey: String,
        exactGroups: List<SearchTitleGroup>,
        progressive: Boolean
    ): Boolean {
        if (queryKey.length >= MIN_EXACT_GROUP_ONLY_QUERY_CHARS) return true
        return progressive &&
            queryKey.length >= MIN_COMPLETION_QUERY_CHARS &&
            exactGroups.any { group ->
                group.candidates.uniqueSearchSourceCount() >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
            }
    }

    private fun searchIdentityKey(book: SourceBook): String {
        val titleKey = searchRanker.canonicalTitleKey(book)
        val authorKey = normalizedAuthor(book.author)
        return when {
            titleKey.isNotBlank() && authorKey.isNotBlank() -> "$titleKey\n$authorKey"
            titleKey.isNotBlank() -> titleKey
            authorKey.isNotBlank() -> authorKey
            else -> ""
        }
    }

    private data class SearchTitleGroup(
        val identityKey: String,
        val titleKey: String,
        val candidates: List<RankedSearchBook>,
        val authorConsensus: Map<String, Int>
    )

    private fun searchTitleGroupComparator(queryKey: String): Comparator<SearchTitleGroup> {
        return compareByDescending<SearchTitleGroup> { group -> if (group.titleKey == queryKey) 1 else 0 }
            .thenByDescending { group -> group.candidates.uniqueSearchSourceCount() }
            .thenByDescending { group -> group.candidates.firstOrNull()?.score ?: 0 }
            .thenBy { group -> group.candidates.firstOrNull()?.book?.name?.length ?: Int.MAX_VALUE }
            .thenBy { group -> group.candidates.firstOrNull()?.book?.name.orEmpty() }
    }

    private fun List<RankedSearchBook>.uniqueSearchSourceCount(): Int {
        return map { ranked -> ranked.book.source.sourceUrl.ifBlank { ranked.sourceIndex.toString() } }
            .toSet()
            .size
    }

    private suspend fun mergeValidatedTitleGroup(
        group: List<ValidatedSearchCandidate>,
        fastForProgress: Boolean = false
    ): ValidatedSearchCandidate? {
        val catalogBackedGroup = group.filter { candidate ->
            searchCandidateTrustedForFirstDisplay(candidate)
        }
        val consensusCatalogGroup = trustedTitleAuthorConsensusGroup(catalogBackedGroup)
        if (consensusCatalogGroup.size < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) {
            traceSearchTitleGroupRejected(group, "insufficient-trusted")
            return null
        }
        val consensusKey = validatedTitleAuthorConsensusKey(consensusCatalogGroup.first()).orEmpty()
        val metadataCoverGroup = group.filter { candidate ->
            validatedTitleAuthorConsensusKey(candidate) == consensusKey &&
            searchCandidateTrustedForDisplayMetadata(candidate) &&
                hasTrustedSearchCover(candidate)
        }
        if (metadataCoverGroup.isEmpty()) {
            traceSearchTitleGroupRejected(group, "missing-trusted-cover")
            return null
        }
        val readingCandidate = if (fastForProgress) {
            selectProgressReadingCandidate(consensusCatalogGroup)
        } else {
            selectReadingCandidate(consensusCatalogGroup)
        }
        val coverCandidate = if (hasTrustedSearchCover(readingCandidate)) {
            readingCandidate
        } else {
            metadataCoverGroup
                .minWithOrNull(coverCandidateComparator)
        }
        val selectedCoverQuality = coverCandidate?.trustedSearchCoverQuality() ?: readingCandidate.coverQuality
        val selectedBook = if (coverCandidate != null && coverCandidate.book.coverUrl != readingCandidate.book.coverUrl) {
            readingCandidate.book.copy(coverUrl = coverCandidate.book.coverUrl)
        } else {
            readingCandidate.book
        }
        val consensusGroup = group.filter { candidate -> validatedTitleAuthorConsensusKey(candidate) == consensusKey }
        val score = consensusGroup.maxOf { candidate -> candidate.ranked.score } +
            catalogScoreForCount(readingCandidate.chapterCount) +
            coverScore(selectedCoverQuality)
        val merged = readingCandidate.copy(
            book = selectedBook,
            score = score,
            coverQuality = selectedCoverQuality,
            validation = readingCandidate.validation + "+merged-cover"
        )
        AiBridgeTrace.event(
            "source_search_group_merged",
            readingCandidate.book.name,
            "trusted_${consensusCatalogGroup.size}_metadataCover_${metadataCoverGroup.size}" +
                "_reading_${sourceLabel(readingCandidate.book).debugToken()}" +
                "_cover_${sourceLabel(coverCandidate?.book ?: readingCandidate.book).debugToken()}"
        )
        rememberSearchSessionEvidence(merged.book, consensusGroup, consensusCatalogGroup)
        return merged
    }

    private fun trustedTitleAuthorConsensusGroup(
        candidates: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        return candidates
            .groupBy { candidate -> validatedTitleAuthorConsensusKey(candidate) }
            .filterKeys { key -> !key.isNullOrBlank() }
            .values
            .sortedWith(
                compareByDescending<List<ValidatedSearchCandidate>> { group -> group.size }
                    .thenByDescending { group -> group.maxOf { candidate -> candidate.score } }
                    .thenBy { group -> group.minOf { candidate -> candidate.ranked.sourceIndex } }
            )
            .firstOrNull()
            .orEmpty()
    }

    private fun validatedTitleAuthorConsensusKey(candidate: ValidatedSearchCandidate): String? {
        val titleKey = searchRanker.canonicalTitleKey(candidate.book)
        val authorKey = normalizedAuthor(candidate.book.author)
        if (titleKey.isBlank() || authorKey.isBlank()) return null
        return "$titleKey\n$authorKey"
    }

    private fun selectProgressReadingCandidate(
        candidates: List<ValidatedSearchCandidate>
    ): ValidatedSearchCandidate {
        val selected = candidates.minWith(progressReadingCandidateComparator)
        AiBridgeTrace.event(
            "source_search_progress_reading_candidate_selected",
            selected.book.name,
            "source_${sourceLabel(selected.book).debugToken()}" +
                "_chapters_${selected.chapterCount}" +
                "_hint_${selected.freshnessHint}" +
                "_cover_${selected.coverQuality.usable}" +
                "_validation_${selected.validation.debugToken()}"
        )
        return selected
    }

    private suspend fun selectReadingCandidate(
        candidates: List<ValidatedSearchCandidate>
    ): ValidatedSearchCandidate {
        val signals = candidates.map { candidate ->
            val readableChapters = candidate.resolved?.let { resolved -> tailReadableChapters(resolved) }.orEmpty()
            val lastReadableOrdinal = lastChapterOrdinal(readableChapters)
            val tailOrdinalGapCount = tailOrdinalGapCount(readableChapters)
            ReadingCandidateSignal(
                candidate = candidate,
                readableChapterCount = readableChapters.size,
                lastReadableOrdinal = lastReadableOrdinal,
                tailOrdinalGapCount = tailOrdinalGapCount,
                tailContinuityScore = tailContinuityScore(lastReadableOrdinal, tailOrdinalGapCount)
            )
        }
        val selected = signals.minWith(readingCandidateSignalComparator)
        AiBridgeTrace.event(
            "source_search_reading_candidate_signals",
            selected.candidate.book.name,
            signals.sortedWith(readingCandidateSignalComparator).take(8).joinToString("|") { signal ->
                "${sourceLabel(signal.candidate.book).debugToken()}" +
                    ":readable_${signal.readableChapterCount}" +
                    ":last_${signal.lastReadableOrdinal}" +
                    ":gaps_${signal.tailOrdinalGapCount}" +
                    ":tailScore_${signal.tailContinuityScore}" +
                    ":raw_${signal.candidate.chapterCount}"
            }
        )
        AiBridgeTrace.event(
            "source_search_reading_candidate_selected",
            selected.candidate.book.name,
            "source_${sourceLabel(selected.candidate.book).debugToken()}" +
                "_readable_${selected.readableChapterCount}" +
                "_lastOrdinal_${selected.lastReadableOrdinal}" +
                "_tailGaps_${selected.tailOrdinalGapCount}" +
                "_tailScore_${selected.tailContinuityScore}" +
                "_raw_${selected.candidate.chapterCount}"
        )
        return selected.candidate
    }

    private fun hasTrustedSearchCover(candidate: ValidatedSearchCandidate): Boolean {
        return candidate.trustedSearchCoverQuality().usable
    }

    private fun ValidatedSearchCandidate.trustedSearchCoverQuality(): CoverQuality {
        if (coverQuality.usable) return coverQuality
        return if (BookCoverUrl.isLikelyImage(book.coverUrl)) {
            CoverQuality(true, MIN_COVER_WIDTH, MIN_COVER_HEIGHT, "trusted-url")
        } else {
            coverQuality
        }
    }

    private fun traceSearchTitleGroupRejected(
        group: List<ValidatedSearchCandidate>,
        reason: String
    ) {
        val first = group.firstOrNull() ?: return
        AiBridgeTrace.event(
            "source_search_group_rejected",
            first.book.name,
            "reason_${reason}_candidates_${group.size}" +
                "_trusted_${group.count { candidate -> searchCandidateTrustedForFirstDisplay(candidate) }}" +
                "_cover_${group.count { candidate -> hasTrustedSearchCover(candidate) }}" +
                "_intro_${group.count { candidate -> cleanIntro(candidate.book.intro).isNotBlank() }}" +
                "_authors_${group.take(5).joinToString("_") { candidate ->
                    normalizedAuthor(candidate.book.author).debugToken()
                }}" +
                "_validations_${group.take(5).joinToString("_") { candidate -> candidate.validation.debugToken() }}"
        )
    }

    private fun searchCandidateTrustedForFirstDisplay(candidate: ValidatedSearchCandidate): Boolean {
        return searchCatalogValidated(candidate.chapterCount, candidate.validation) &&
            !candidate.pageCatalog &&
            candidate.resolved != null &&
            normalizeHint(candidate.book.author).isNotBlank() &&
            cleanIntro(candidate.book.intro).isNotBlank()
    }

    private fun searchCandidateTrustedForDisplayMetadata(candidate: ValidatedSearchCandidate): Boolean {
        return candidate.resolved != null &&
            candidate.chapterCount >= MIN_READABLE_CATALOG_CHAPTERS &&
            normalizeHint(candidate.book.author).isNotBlank() &&
            cleanIntro(candidate.book.intro).isNotBlank()
    }

    private fun rememberSearchSessionEvidence(
        selectedBook: SourceBook,
        group: List<ValidatedSearchCandidate>,
        trustedGroup: List<ValidatedSearchCandidate>
    ) {
        val waterfall = rememberBookContentWaterfall(selectedBook, group.map { candidate -> candidate.ranked })
        trustedGroup.mapNotNull { candidate -> candidate.resolved }
            .forEach { resolved -> promoteResolvedBookInWaterfall(waterfall, resolved) }
        Log.i(
            TAG,
            "operation=searchSessionEvidence provider=$providerName title=${selectedBook.name} " +
                "author=${selectedBook.author} trusted=${verifiedBookCount(waterfall)} candidates=${group.size}"
        )
    }

    private suspend fun validateSearchTitleGroup(
        titleGroup: SearchTitleGroup,
        semaphore: Semaphore,
        allowEarlyReturn: Boolean
    ): List<ValidatedSearchCandidate>? = supervisorScope {
        val groupStartedAt = System.currentTimeMillis()
        val maxValidationCandidates = if (allowEarlyReturn) {
            MAX_PROGRESS_SEARCH_VALIDATION_CANDIDATES_PER_TITLE
        } else {
            MAX_SEARCH_VALIDATION_CANDIDATES_PER_TITLE
        }
        val validationCandidates = validationCandidatesForTitle(titleGroup.candidates)
        val candidates = if (allowEarlyReturn) {
            validationCandidates.distinctBy { ranked -> searchValidationSourceKey(ranked) }
        } else {
            validationCandidates
        }.take(maxValidationCandidates)
        if (candidates.isEmpty()) return@supervisorScope null
        traceSearchValidationPlan(titleGroup, candidates)
        AiBridgeTrace.event(
            "source_search_validation_group_started",
            titleGroup.identityKey.replace('\n', '@'),
            "total_${titleGroup.candidates.size}_selected_${candidates.size}_early_${allowEarlyReturn}"
        )
        val completed = ArrayList<ValidatedSearchCandidate>()
        val groupRequestScope = if (allowEarlyReturn) {
            newSourceRequestScope(
                operation = "searchValidationGroup",
                key = titleGroup.identityKey,
                parent = currentSourceRequestScope()
            )
        } else {
            null
        }
        val validationScope = if (groupRequestScope == null) {
            this
        } else {
            CoroutineScope(Dispatchers.IO + SupervisorJob() + sourceRequestContext(groupRequestScope))
        }
        val pending = candidates.map { ranked ->
            validationScope.async {
                validateSearchCandidateForTitle(ranked, titleGroup, semaphore)
            }
        }.toMutableSet()
        try {
            val deadline = System.currentTimeMillis() + SEARCH_TITLE_GROUP_VALIDATION_TIMEOUT_MS
            while (pending.isNotEmpty()) {
                val remainingMs = deadline - System.currentTimeMillis()
                if (remainingMs <= 0) break
                val next = withTimeoutOrNull(remainingMs) {
                    select<Pair<Deferred<ValidatedSearchCandidate>, ValidatedSearchCandidate>> {
                        pending.forEach { deferred ->
                            deferred.onAwait { value -> deferred to value }
                        }
                    }
                } ?: break
                pending.remove(next.first)
                completed.add(next.second)
                val merged = mergeValidatedTitleGroup(completed, fastForProgress = allowEarlyReturn)
                if (
                    allowEarlyReturn &&
                    merged != null &&
                    hasStrongSearchCoverage(merged) &&
                    searchProgressCandidateReady(merged)
                ) {
                    pending.forEach { it.cancel() }
                    groupRequestScope?.let { cancelSourceRequests(it) }
                    AiBridgeTrace.event(
                        "source_search_validation_group_finished",
                        titleGroup.identityKey.replace('\n', '@'),
                        "outcome_early_completed_${completed.size}" +
                            "_cancelled_${pending.size}" +
                            "_merged_${merged.book.name.debugToken()}/${merged.chapterCount}/${merged.validation}" +
                            "_durationMs_${System.currentTimeMillis() - groupStartedAt}"
                    )
                    return@supervisorScope completed.toList()
                }
            }
            val outcome = if (pending.isNotEmpty()) "timeout" else "completed"
            pending.forEach { it.cancel() }
            groupRequestScope?.let { cancelSourceRequests(it) }
            val merged = mergeValidatedTitleGroup(completed, fastForProgress = allowEarlyReturn)
            AiBridgeTrace.event(
                "source_search_validation_group_finished",
                titleGroup.identityKey.replace('\n', '@'),
                "outcome_${outcome}_completed_${completed.size}" +
                    "_cancelled_${pending.size}" +
                    "_merged_${merged?.book?.name.orEmpty().debugToken()}/${merged?.chapterCount ?: 0}/${merged?.validation.orEmpty()}" +
                    "_durationMs_${System.currentTimeMillis() - groupStartedAt}"
            )
            completed.takeIf { it.isNotEmpty() }?.toList()
        } finally {
            if (groupRequestScope != null) {
                pending.forEach { it.cancel() }
                cancelSourceRequests(groupRequestScope)
                validationScope.coroutineContext.cancelChildren()
            }
        }
    }

    private suspend fun validateSearchCandidateForTitle(
        ranked: RankedSearchBook,
        titleGroup: SearchTitleGroup,
        semaphore: Semaphore
    ): ValidatedSearchCandidate {
        return semaphore.withPermit {
            try {
                validateSearchCandidate(
                    ranked,
                    titleGroup.authorConsensus[normalizedAuthor(ranked.book.author)] ?: 0
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AiBridgeTrace.event(
                    "source_search_validation_error",
                    ranked.book.name,
                    "error_${error.javaClass.simpleName}_message_${error.message.orEmpty().debugToken()}" +
                        "_source_${sourceLabel(ranked.book).debugToken()}"
                )
                fallbackValidatedSearchCandidate(ranked)
            }
        }
    }

    private fun hasStrongSearchCoverage(candidate: ValidatedSearchCandidate): Boolean {
        return searchCatalogValidated(candidate.chapterCount, candidate.validation)
    }

    private fun validationCandidatesForTitle(group: List<RankedSearchBook>): List<RankedSearchBook> {
        val priorityCandidates = group
            .sortedWith(
                compareBy<RankedSearchBook> { ranked -> sourcePriorityIndex(ranked.book.source, ranked.book.name) }
                    .thenByDescending { ranked -> ranked.score }
                    .thenBy { ranked -> ranked.sourceIndex }
                    .thenBy { ranked -> ranked.resultIndex }
            )
            .take(MAX_VALIDATION_PRIORITY_PER_TITLE)
        return (group.take(MAX_VALIDATION_PER_TITLE) +
            priorityCandidates +
            group.filter { ranked -> BookCoverUrl.isLikelyImage(ranked.book.coverUrl) }
                .take(MAX_VALIDATION_COVER_FALLBACK_PER_TITLE))
            .distinctBy { ranked ->
                ranked.book.source.sourceUrl + "\n" + ranked.book.bookUrl
            }
    }

    private fun searchValidationSourceKey(ranked: RankedSearchBook): String {
        return ranked.book.source.sourceUrl.ifBlank { ranked.sourceIndex.toString() }
    }

    private fun traceSearchValidationPlan(
        titleGroup: SearchTitleGroup,
        candidates: List<RankedSearchBook>
    ) {
        val first = candidates.firstOrNull()?.book ?: titleGroup.candidates.firstOrNull()?.book ?: return
        AiBridgeTrace.event(
            "source_search_validation_plan",
            first.name,
            "identity_${titleGroup.identityKey.replace('\n', '@').debugToken()}" +
                "_total_${titleGroup.candidates.size}_selected_${candidates.size}" +
                "_items_${candidates.take(8).joinToString("__") { ranked ->
                    "${sourceLabel(ranked.book)}/idx_${ranked.sourceIndex}/${normalizedAuthor(ranked.book.author)}/" +
                        "cover_${BookCoverUrl.isLikelyImage(ranked.book.coverUrl)}"
                }.debugToken()}"
        )
    }

    private suspend fun validateSearchCandidate(
        ranked: RankedSearchBook,
        authorConsensus: Int
    ): ValidatedSearchCandidate {
        val cacheKey = searchValidationCacheKey(ranked.book)
        searchValidationCache[cacheKey]?.let { cached ->
            AiBridgeTrace.event(
                "source_search_validation_cache_hit",
                ranked.book.name,
                "source_${sourceLabel(ranked.book).debugToken()}_validation_${cached.validation}" +
                    "_chapters_${cached.chapterCount}_cover_${cached.coverQuality.usable}"
            )
            return rebaseValidatedSearchCandidate(cached, ranked, authorConsensus)
        }
        val lock = synchronized(searchValidationLocks) {
            searchValidationLocks.getOrPut(cacheKey) { Mutex() }
        }
        return lock.withLock {
            searchValidationCache[cacheKey]?.let { cached ->
                AiBridgeTrace.event(
                    "source_search_validation_cache_hit",
                    ranked.book.name,
                    "source_${sourceLabel(ranked.book).debugToken()}_validation_${cached.validation}" +
                        "_chapters_${cached.chapterCount}_cover_${cached.coverQuality.usable}"
                )
                return@withLock rebaseValidatedSearchCandidate(cached, ranked, authorConsensus)
            }
            val validated = validateSearchCandidateUncached(ranked, authorConsensus)
            if (validated.cacheableSearchValidation()) {
                searchValidationCache[cacheKey] = validated
            }
            validated
        }
    }

    private suspend fun validateSearchCandidateUncached(
        ranked: RankedSearchBook,
        authorConsensus: Int
    ): ValidatedSearchCandidate {
        val validationStartedAt = System.currentTimeMillis()
        val fallback = fallbackValidatedSearchCandidate(ranked, authorConsensus)
        val validated = withTimeoutOrNull(SEARCH_VALIDATION_TIMEOUT_MS) {
            val detailStartedAt = System.currentTimeMillis()
            val detail = when (val value = engine.getBookDetail(ranked.book)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> {
                    AiBridgeTrace.event(
                        "source_search_validation",
                        ranked.book.name,
                        "source_${sourceLabel(ranked.book).debugToken()}_validation_detail-failed" +
                            "_detailMs_${System.currentTimeMillis() - detailStartedAt}" +
                            "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
                    )
                    return@withTimeoutOrNull fallback.copy(
                        score = fallback.score - DETAIL_FAILURE_PENALTY,
                        validation = "detail-failed"
                    )
                }
            }
            if (detailAgreementScore(ranked.book, detail) < 0) {
                AiBridgeTrace.event(
                    "source_search_validation",
                    ranked.book.name,
                    "validation_detail-title-mismatch_source_${sourceLabel(ranked.book).debugToken()}" +
                        "_detailMs_${System.currentTimeMillis() - detailStartedAt}" +
                        "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
                )
                return@withTimeoutOrNull fallback.copy(
                    score = 0,
                    validation = "detail-title-mismatch"
                )
            }
            val detailMs = System.currentTimeMillis() - detailStartedAt
            val catalogStartedAt = System.currentTimeMillis()
            val catalog = when (val value = engine.getCanonicalChapterList(detail)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> null
            }
            val catalogMs = System.currentTimeMillis() - catalogStartedAt
            val enrichedBook = detail.toSearchBook(ranked.book)
            val coverQuality = searchValidationCoverQuality(enrichedBook)
            val resolved = catalog?.let {
                ResolvedSourceBook(
                    book = enrichedBook,
                    detail = detail.copy(
                        book = enrichedBook,
                        name = enrichedBook.name,
                        author = enrichedBook.author,
                        coverUrl = enrichedBook.coverUrl,
                        intro = enrichedBook.intro,
                        kind = enrichedBook.kind,
                        lastChapter = enrichedBook.lastChapter
                    ),
                    catalog = it,
                    routeId = SourceEngineBookRoute.bookId(enrichedBook)
                )
            }
            val catalogChapters = catalog?.chapters.orEmpty()
            val chapterCount = catalogChapters.size
            val pageCatalog = looksLikePageCatalog(catalogChapters)
            val tailStartedAt = System.currentTimeMillis()
            val readableTailContent = if (resolved != null && catalogChapters.isNotEmpty()) {
                readableSearchTailContent(catalogChapters)
            } else {
                0
            }
            val tailMs = System.currentTimeMillis() - tailStartedAt
            val score = ranked.score +
                coverScore(coverQuality) +
                catalogScore(catalog) +
                searchTailContentScore(readableTailContent) +
                detailAgreementScore(ranked.book, detail) +
                sourceQualityRouter.routeScoreBoost(enrichedBook)
            val validation = when {
                catalog == null -> "detail-only"
                pageCatalog -> "detail-catalog-page-list"
                readableTailContent > 0 -> "detail-catalog-tail-content"
                else -> "detail-catalog-unreadable"
            }
            val validated = ValidatedSearchCandidate(
                ranked = ranked,
                book = enrichedBook,
                score = score,
                chapterCount = chapterCount,
                freshnessHint = estimatedChapterOrdinal(enrichedBook),
                duplicateCount = catalog?.duplicateCount ?: 0,
                coverQuality = coverQuality,
                authorConsensus = authorConsensus,
                validation = validation,
                resolved = resolved,
                pageCatalog = pageCatalog
            )
            sourceQualityRouter.recordSearchValidation(
                book = enrichedBook,
                chapterCount = chapterCount,
                freshnessHint = validated.freshnessHint,
                coverUsable = coverQuality.usable,
                validation = validated.validation
            )
            AiBridgeTrace.event(
                "source_search_validation",
                enrichedBook.name,
                "source_${sourceLabel(enrichedBook).debugToken()}_validation_${validated.validation}" +
                    "_author_${normalizedAuthor(enrichedBook.author).debugToken()}" +
                    "_chapters_${chapterCount}_hint_${validated.freshnessHint}_tailContent_${readableTailContent}" +
                    "_cover_${coverQuality.usable}_${coverQuality.reason.debugToken()}" +
                    "_pageCatalog_${validated.pageCatalog}" +
                    "_intro_${cleanIntro(enrichedBook.intro).isNotBlank()}" +
                    "_detailMs_${detailMs}_catalogMs_${catalogMs}_tailMs_${tailMs}" +
                    "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
            )
            validated
        }
        if (validated == null) {
            AiBridgeTrace.event(
                "source_search_validation",
                ranked.book.name,
                "source_${sourceLabel(ranked.book).debugToken()}_validation_timeout" +
                    "_timeoutMs_${SEARCH_VALIDATION_TIMEOUT_MS}" +
                    "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
            )
        }
        return validated ?: fallback
    }

    private fun searchValidationCacheKey(book: SourceBook): String {
        return sourceBookKey(book)
    }

    private fun rebaseValidatedSearchCandidate(
        cached: ValidatedSearchCandidate,
        ranked: RankedSearchBook,
        authorConsensus: Int
    ): ValidatedSearchCandidate {
        val score = when (cached.validation) {
            "detail-title-mismatch" -> 0
            else -> ranked.score + (cached.score - cached.ranked.score)
        }
        return cached.copy(
            ranked = ranked,
            score = score,
            authorConsensus = authorConsensus
        )
    }

    private fun ValidatedSearchCandidate.cacheableSearchValidation(): Boolean {
        return validation != "unvalidated"
    }

    private fun fallbackValidatedSearchCandidate(
        ranked: RankedSearchBook,
        authorConsensus: Int = 0
    ): ValidatedSearchCandidate {
        val hasCoverUrl = BookCoverUrl.isLikelyImage(ranked.book.coverUrl)
        val validated = ValidatedSearchCandidate(
            ranked = ranked,
            book = ranked.book,
            score = ranked.score - UNVALIDATED_RESULT_PENALTY + sourceQualityRouter.routeScoreBoost(ranked.book),
            chapterCount = 0,
            freshnessHint = estimatedChapterOrdinal(ranked.book),
            duplicateCount = 0,
            coverQuality = if (hasCoverUrl) {
                CoverQuality(true, MIN_COVER_WIDTH, MIN_COVER_HEIGHT, "url-only")
            } else {
                CoverQuality(false, 0, 0, "unvalidated")
            },
            authorConsensus = authorConsensus,
            validation = "unvalidated",
            resolved = null,
            pageCatalog = false
        )
        sourceQualityRouter.recordSearchValidation(
            book = ranked.book,
            chapterCount = 0,
            freshnessHint = validated.freshnessHint,
            coverUsable = hasCoverUrl,
            validation = validated.validation
        )
        return validated
    }

    private fun SourceBookDetail.toSearchBook(fallback: SourceBook): SourceBook {
        val cleanedIntro = cleanIntro(intro)
        val fallbackIntro = cleanIntro(fallback.intro)
        return fallback.copy(
            name = name.ifBlank { fallback.name },
            author = cleanAuthor(author.ifBlank { fallback.author }),
            coverUrl = BookCoverUrl.bestLikelyImage(coverUrl, fallback.coverUrl),
            intro = cleanedIntro.ifBlank { fallbackIntro },
            kind = kind.ifBlank { fallback.kind },
            lastChapter = lastChapter.ifBlank { fallback.lastChapter }
        )
    }

    private fun cleanAuthor(value: String): String {
        return value
            .replace(Regex("""^(?:作\s*者[:：]?\s*)+"""), "")
            .replace(Regex("""[_＿][A-Za-z]*\d+$"""), "")
            .trim()
    }

    private fun normalizedAuthor(value: String): String {
        return normalizeHint(cleanAuthor(value))
    }

    private fun cleanIntro(value: String): String {
        val cleaned = value
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""</?p[^>]*>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""各位书友.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (isInvalidIntroFragment(cleaned)) return ""
        return cleaned
    }

    private fun isInvalidIntroFragment(value: String): Boolean {
        if (value.isBlank()) return false
        val normalized = value.lowercase()
        return normalized.contains("meta property") ||
            normalized.contains("og:image") ||
            normalized.contains("content=\"http") ||
            normalized.contains("content='http") ||
            Regex("""^["']?\s*/?\s*>""").containsMatchIn(value)
    }

    private fun coverScore(quality: CoverQuality): Int {
        return if (quality.usable) COVER_PRESENT_BONUS else MISSING_COVER_PENALTY
    }

    private fun inspectCoverQuality(book: SourceBook): CoverQuality {
        return inspectCoverUrl(book.coverUrl)
    }

    private fun searchValidationCoverQuality(book: SourceBook): CoverQuality {
        val url = BookCoverUrl.clean(book.coverUrl)
        return if (BookCoverUrl.isLikelyImage(url)) {
            CoverQuality(true, MIN_COVER_WIDTH, MIN_COVER_HEIGHT, "trusted-url")
        } else {
            CoverQuality(false, 0, 0, "missing-or-placeholder-url")
        }
    }

    private fun inspectCoverUrl(coverUrl: String?): CoverQuality {
        val url = BookCoverUrl.clean(coverUrl)
        if (!BookCoverUrl.isUsable(url)) {
            return CoverQuality(false, 0, 0, "missing-or-placeholder-url")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return CoverQuality(true, 0, 0, "local-or-non-http")
        }
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", COVER_USER_AGENT)
                .header("Referer", refererFor(url))
                .build()
            coverHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return CoverQuality(false, 0, 0, "http-${response.code}")
                }
                val bytes = response.body?.bytes() ?: return CoverQuality(false, 0, 0, "empty-body")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val width = options.outWidth
                val height = options.outHeight
                val usable = isBookCoverShape(width, height)
                CoverQuality(
                    usable = usable,
                    width = width,
                    height = height,
                    reason = if (usable) "ok" else "bad-shape"
                )
            }
        }.getOrElse { error ->
            CoverQuality(false, 0, 0, error.javaClass.simpleName)
        }
    }

    private fun isBookCoverShape(width: Int, height: Int): Boolean {
        if (width < MIN_COVER_WIDTH || height < MIN_COVER_HEIGHT) return false
        val ratio = width.toFloat() / height.toFloat()
        return ratio in MIN_COVER_RATIO..MAX_COVER_RATIO
    }

    private fun refererFor(url: String): String {
        val uri = Uri.parse(url)
        val scheme = uri.scheme ?: return url
        val host = uri.host ?: return url
        return "$scheme://$host/"
    }

    private suspend fun fillSearchCovers(
        ranked: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        if (ranked.isEmpty() || ranked.all { BookCoverUrl.isLikelyImage(it.book.coverUrl) }) {
            return ranked
        }
        return withTimeoutOrNull(SEARCH_COVER_FILL_TOTAL_TIMEOUT_MS) {
            supervisorScope {
                val semaphore = Semaphore(MAX_SEARCH_COVER_FILL_CONCURRENT)
                ranked.mapIndexed { index, candidate ->
                    async {
                        if (BookCoverUrl.isLikelyImage(candidate.book.coverUrl)) return@async candidate
                        if (index >= MAX_SEARCH_COVER_FILL_RESULTS) return@async candidate
                        semaphore.withPermit {
                            val fallback = withTimeoutOrNull(SEARCH_COVER_FILL_ITEM_TIMEOUT_MS) {
                                findCoverFallback(candidate.book)
                            }
                            if (!BookCoverUrl.isLikelyImage(fallback)) return@withPermit candidate
                            val filledBook = candidate.book.copy(coverUrl = fallback.orEmpty())
                            Log.i(
                                TAG,
                                "operation=searchCoverFallbackResolved provider=$providerName " +
                                    "title=${candidate.book.name} source=${sourceLabel(candidate.book)} cover=$fallback"
                            )
                            candidate.copy(
                                book = filledBook,
                                coverQuality = CoverQuality(
                                    usable = true,
                                    width = MIN_COVER_WIDTH,
                                    height = MIN_COVER_HEIGHT,
                                    reason = "search-fallback"
                                ),
                                validation = candidate.validation + "+search-cover"
                            )
                        }
                    }
                }.awaitAll()
            }
        } ?: ranked
    }

    private fun catalogScore(catalog: CanonicalChapterList?): Int {
        val chapterCount = catalog?.chapters?.size ?: 0
        return catalogScoreForCount(chapterCount)
    }

    private fun searchTailContentScore(readableTailContent: Int): Int {
        return if (readableTailContent > 0) SEARCH_READABLE_TAIL_BONUS else SEARCH_UNREADABLE_TAIL_PENALTY
    }

    private suspend fun readableSearchTailContent(
        chapters: List<CanonicalChapter>
    ): Int {
        val chapter = chapters.lastOrNull()?.sourceChapters?.firstOrNull() ?: return 0
        val content = loadCleanContentWithTimeout(chapter, SEARCH_TAIL_CONTENT_TIMEOUT_MS)
            ?: return 0
        return if (isReadableContent(content)) 1 else 0
    }

    private fun looksLikePageCatalog(chapters: List<CanonicalChapter>): Boolean {
        if (chapters.size < MIN_PAGE_CATALOG_CHAPTERS) return false
        val pageTitles = chapters.count { chapter ->
            PAGE_CATALOG_TITLE_PATTERN.matches(chapter.displayTitle.trim())
        }
        return pageTitles * 100 >= chapters.size * MIN_PAGE_CATALOG_PERCENT
    }

    private fun catalogScoreForCount(chapterCount: Int): Int {
        return when {
            chapterCount >= 1_000 -> 1_200
            chapterCount >= 500 -> 900
            chapterCount >= 100 -> 500
            chapterCount >= 30 -> 100
            chapterCount > 0 -> SHORT_CATALOG_PENALTY
            else -> MISSING_CATALOG_PENALTY
        }
    }

    internal fun searchCatalogValidated(chapterCount: Int, validation: String): Boolean {
        return validation.startsWith("detail-catalog-tail-content") &&
            !validation.contains("unreadable") &&
            chapterCount >= MIN_READABLE_CATALOG_CHAPTERS
    }

    private fun detailAgreementScore(original: SourceBook, detail: SourceBookDetail): Int {
        val originalTitle = searchRanker.canonicalTitleKey(original)
        val detailTitle = searchRanker.canonicalTitleKey(detail.toSearchBook(original))
        if (originalTitle.isBlank() || detailTitle.isBlank()) return 0
        return if (originalTitle == detailTitle || detailTitle.contains(originalTitle) || originalTitle.contains(detailTitle)) {
            0
        } else {
            DETAIL_TITLE_MISMATCH_PENALTY
        }
    }

    private fun prioritizedSearchSources(
        sources: List<BookSource>,
        bookName: String? = null
    ): List<BookSource> {
        return if (bookName.isNullOrBlank()) {
            sourceQualityRouter.waterfallSources(sources)
        } else {
            sourceQualityRouter.waterfallSourcesForBook(sources, bookName)
        }
    }

    private fun searchSourcesFor(sources: List<BookSource>, keyword: String): List<BookSource> {
        return prioritizedSearchSources(sources, keyword)
    }

    private suspend fun resolveReadableBook(sourceBook: SourceBook): ResolvedSourceBook = supervisorScope {
        val direct = async {
            loadReadableBookWithTimeout(sourceBook, engine, DETAIL_DIRECT_TIMEOUT_MS)
                ?.let { readableResolvedBook(0, it) }
        }
        val fallback = async {
            findReadableFallback(sourceBook)
                ?.let { readableResolvedBook(1, it) }
        }
        var directResult: ReadableResolvedSourceBook? = null
        var fallbackResult: ReadableResolvedSourceBook? = null
        var directCompleted = false
        var fallbackCompleted = false

        while (!directCompleted || !fallbackCompleted) {
            val outcome = awaitNextDetailResolution(direct, fallback, directCompleted, fallbackCompleted)
            when (outcome) {
                is DetailResolutionOutcome.Direct -> {
                    directCompleted = true
                    directResult = outcome.value
                }
                is DetailResolutionOutcome.Fallback -> {
                    fallbackCompleted = true
                    fallbackResult = outcome.value
                }
            }

            bestPreferredResolvedBook(listOfNotNull(directResult, fallbackResult))?.let { selected ->
                direct.cancel()
                fallback.cancel()
                logDetailFallbackIfNeeded(sourceBook, selected, directResult)
                return@supervisorScope selected.resolved
            }
        }

        val selected = bestResolvedBook(listOfNotNull(directResult, fallbackResult))
            ?: error("Source-engine readable source failed: ${sourceBook.name} ${sourceBook.bookUrl}")
        logDetailFallbackIfNeeded(sourceBook, selected, directResult)
        selected.resolved
    }

    private suspend fun awaitNextDetailResolution(
        direct: Deferred<ReadableResolvedSourceBook?>,
        fallback: Deferred<ReadableResolvedSourceBook?>,
        directCompleted: Boolean,
        fallbackCompleted: Boolean
    ): DetailResolutionOutcome {
        return select {
            if (!directCompleted) {
                direct.onAwait { DetailResolutionOutcome.Direct(it) }
            }
            if (!fallbackCompleted) {
                fallback.onAwait { DetailResolutionOutcome.Fallback(it) }
            }
        }
    }

    private fun bestPreferredResolvedBook(
        candidates: List<ReadableResolvedSourceBook>
    ): ReadableResolvedSourceBook? {
        return candidates
            .filter { candidate -> candidate.hasPreferredReadableCoverage() }
            .sortedWith(readableResolvedBookComparator)
            .firstOrNull()
    }

    private fun ReadableResolvedSourceBook.hasPreferredReadableCoverage(): Boolean {
        if (!resolved.hasReadableCatalogHead()) {
            return false
        }
        val chapterCount = resolved.catalog.chapters.size
        if (chapterCount < PREFERRED_CATALOG_CHAPTERS || readableChapterCount < PREFERRED_CATALOG_CHAPTERS) {
            return false
        }
        val trimmedTailCount = chapterCount - readableChapterCount
        if (trimmedTailCount <= 0) return true
        if (trimmedTailCount <= MAX_PREFERRED_TAIL_TRIM_CHAPTERS) return true
        return readableChapterCount * 100 >= chapterCount * MIN_PREFERRED_READABLE_PERCENT
    }

    private fun ResolvedSourceBook.hasReadableCatalogHead(): Boolean {
        val firstOrdinal = catalog.chapters.firstOrNull()?.ordinal
        return firstOrdinal == null || firstOrdinal <= 1
    }

    private fun bestResolvedBook(
        candidates: List<ReadableResolvedSourceBook>
    ): ReadableResolvedSourceBook? {
        return candidates
            .filter { candidate -> candidate.readableChapterCount >= MIN_READABLE_CATALOG_CHAPTERS }
            .sortedWith(readableResolvedBookComparator)
            .firstOrNull()
    }

    private suspend fun logDetailFallbackIfNeeded(
        sourceBook: SourceBook,
        selected: ReadableResolvedSourceBook,
        direct: ReadableResolvedSourceBook?
    ) {
        if (selected.order == 0 || selected.resolved.routeId == direct?.resolved?.routeId) return
        Log.i(
            TAG,
            "operation=detailFallbackResolved provider=$providerName " +
                "title=${sourceBook.name} from=${sourceLabel(sourceBook)} " +
                "to=${sourceLabel(selected.resolved.book)} chapters=${selected.resolved.catalog.chapters.size} " +
                "readable=${selected.readableChapterCount}"
        )
    }

    private suspend fun findReadableFallback(sourceBook: SourceBook): ResolvedSourceBook? {
        val rankedCandidates = fallbackCandidatesFor(sourceBook)
        rememberBookContentWaterfall(sourceBook, rankedCandidates)
        val ranked = rankedCandidates.take(MAX_DETAIL_FALLBACK_CANDIDATES)
        if (ranked.isEmpty()) return null
        Log.i(
            TAG,
            "operation=detailFallbackCandidates provider=$providerName title=${sourceBook.name} " +
                "count=${ranked.size} top=${ranked.take(8).joinToString(" | ") { fallbackDebugLabel(it) }}"
        )

        val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + activeSourceRequestContext())
        val semaphore = Semaphore(MAX_DETAIL_FALLBACK_CONCURRENT_PROBES)
        return try {
            val probes = ranked.mapIndexed { order, candidate ->
                probeScope.async {
                    semaphore.withPermit {
                        loadReadableBookWithTimeout(candidate.book, detailProbeEngine, DETAIL_PROBE_TIMEOUT_MS)
                            ?.let { order to it }
                    }
                }
            }
            val best = awaitBestReadableFallback(probes, DETAIL_FALLBACK_PROBE_TIMEOUT_MS)
            Log.i(
                TAG,
                "operation=detailFallbackProbes provider=$providerName title=${sourceBook.name} " +
                    "selected=${best?.let { resolvedDebugLabel(it.resolved) }}"
            )
            best?.resolved
        } finally {
            probeScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun awaitBestReadableFallback(
        probes: List<Deferred<Pair<Int, ResolvedSourceBook>?>>,
        timeoutMs: Long
    ): ReadableResolvedSourceBook? {
        val pending = probes.toMutableSet()
        val completed = ArrayList<ReadableResolvedSourceBook>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pending.isNotEmpty()) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0) break
            val next = withTimeoutOrNull(remainingMs) {
                select<Pair<Deferred<Pair<Int, ResolvedSourceBook>?>, Pair<Int, ResolvedSourceBook>?>> {
                    pending.forEach { deferred ->
                        deferred.onAwait { value -> deferred to value }
                    }
                }
            } ?: break
            pending.remove(next.first)
            val (order, resolved) = next.second ?: continue
            val ranked = readableResolvedBook(order, resolved)
            if (ranked.hasPreferredReadableCoverage()) {
                return ranked
            }
            completed.add(ranked)
        }
        return bestResolvedBook(completed)
    }

    private suspend fun rankResolvedByReadableTail(
        resolved: List<Pair<Int, ResolvedSourceBook>>
    ): List<ReadableResolvedSourceBook> {
        if (resolved.isEmpty()) return emptyList()
        val earlyRanked = ArrayList<ReadableResolvedSourceBook>()
        resolved.take(MAX_DETAIL_FALLBACK_EARLY_TAIL_RANK_CANDIDATES).forEach { (order, book) ->
            val ranked = readableResolvedBook(order, book)
            if (ranked.hasPreferredReadableCoverage()) {
                return listOf(ranked)
            }
            earlyRanked.add(ranked)
        }
        return withTimeoutOrNull(DETAIL_FALLBACK_TAIL_RANK_TIMEOUT_MS) {
            supervisorScope {
                val semaphore = Semaphore(MAX_DETAIL_FALLBACK_TAIL_RANK_CONCURRENT_PROBES)
                resolved.drop(MAX_DETAIL_FALLBACK_EARLY_TAIL_RANK_CANDIDATES).map { (order, book) ->
                    async {
                        semaphore.withPermit {
                            readableResolvedBook(order, book)
                        }
                    }
                }.awaitAll() + earlyRanked
            }
        } ?: earlyRanked
    }

    private suspend fun readableResolvedBook(
        order: Int,
        resolved: ResolvedSourceBook
    ): ReadableResolvedSourceBook {
        val readableChapters = tailReadableChapters(resolved)
        val lastReadableOrdinal = lastChapterOrdinal(readableChapters)
        val tailOrdinalGapCount = tailOrdinalGapCount(readableChapters)
        return ReadableResolvedSourceBook(
            order = order,
            resolved = resolved,
            readableChapterCount = readableChapters.size,
            lastReadableOrdinal = lastReadableOrdinal,
            tailOrdinalGapCount = tailOrdinalGapCount,
            tailContinuityScore = tailContinuityScore(lastReadableOrdinal, tailOrdinalGapCount)
        )
    }

    private suspend fun readableChapterCount(resolved: ResolvedSourceBook): Int {
        return tailReadableChapters(resolved).size
    }

    private val readableResolvedBookComparator = compareByDescending<ReadableResolvedSourceBook> {
        it.resolved.hasReadableCatalogHead()
    }.thenByDescending {
        it.tailContinuityScore
    }.thenByDescending {
        it.lastReadableOrdinal
    }.thenBy {
        it.tailOrdinalGapCount
    }.thenByDescending {
        it.readableChapterCount
    }.thenByDescending {
        it.resolved.catalog.chapters.size
    }.thenByDescending {
        sourceQualityRouter.bookSourceScore(it.resolved.book)
    }.thenBy {
        sourcePriorityIndex(it.resolved.book.source, it.resolved.book.name)
    }.thenBy {
        it.order
    }

    private fun sourcePriorityIndex(source: BookSource, bookName: String? = null): Int {
        return MAX_SOURCE_SCORE - if (bookName.isNullOrBlank()) {
            sourceQualityRouter.sourceScore(source)
        } else {
            sourceQualityRouter.bookSourceScore(source, bookName)
        }
    }

    private val contentWaterfallComparator = compareByDescending<RankedSearchBook> {
        sourceQualityRouter.bookSourceScore(it.book)
    }.thenByDescending {
        estimatedChapterOrdinal(it.book)
    }.thenByDescending {
        it.score
    }.thenBy {
        it.sourceIndex
    }.thenBy {
        it.resultIndex
    }

    private suspend fun fallbackCandidatesFor(sourceBook: SourceBook): List<RankedSearchBook> {
        val sources = prioritizedSearchSources(sourceProvider(), sourceBook.name)
            .take(MAX_DETAIL_FALLBACK_SOURCES)
        val candidates = Collections.synchronizedList(ArrayList<SearchCandidate>())
        val semaphore = Semaphore(MAX_DETAIL_FALLBACK_CONCURRENT_SEARCHES)
        val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + activeSourceRequestContext())
        try {
            val jobs = sources.mapIndexed { sourceIndex, source ->
                searchScope.async {
                    semaphore.withPermit {
                        val search = when (val value = searchEngine.search(listOf(source), sourceBook.name, maxSources = 1)) {
                            is EngineResult.Success -> value.value
                            is EngineResult.Failure -> return@withPermit
                        }
                        search.books.take(MAX_DETAIL_FALLBACK_RESULTS_PER_SOURCE).forEachIndexed { resultIndex, book ->
                            if (isSameTitleCandidate(sourceBook, book)) {
                                candidates.add(
                                    SearchCandidate(
                                        book = book,
                                        sourceIndex = sourceIndex,
                                        resultIndex = resultIndex,
                                        searchQuery = sourceBook.name
                                    )
                                )
                            }
                        }
                    }
                }
            }
            withTimeoutOrNull(DETAIL_FALLBACK_SEARCH_TIMEOUT_MS) {
                jobs.awaitAll()
            }
        } finally {
            currentSourceRequestScope()?.let { searchEngineFetcher.cancel(it) }
            searchScope.coroutineContext.cancelChildren()
        }
        val candidateSnapshot = synchronized(candidates) { candidates.toList() }
        val authorConsensus = authorConsensusFor(candidateSnapshot.map { candidate -> candidate.book })
        return searchRanker.scoreCandidates(sourceBook.name, candidateSnapshot)
            .filter { ranked ->
                ranked.book.source.sourceUrl != sourceBook.source.sourceUrl || ranked.book.bookUrl != sourceBook.bookUrl
            }
            .sortedWith(
                compareByDescending<RankedSearchBook> {
                    authorConfidenceScore(sourceBook, it.book, authorConsensus)
                }
                    .thenByDescending { sourceQualityRouter.bookSourceScore(it.book) }
                    .thenByDescending { estimatedChapterOrdinal(it.book) }
                    .thenByDescending { it.score }
                    .thenBy { it.sourceIndex }
                    .thenBy { it.resultIndex }
            )
    }

    private fun estimatedChapterOrdinal(book: SourceBook): Int {
        return listOf(book.lastChapter, book.kind)
            .asSequence()
            .map { text -> chapterNormalizer.normalize(text).ordinal ?: numericChapterHint(text) }
            .filter { it in 1..10_000 }
            .maxOrNull() ?: 0
    }

    private fun numericChapterHint(text: String): Int {
        return CHAPTER_HINT_PATTERNS
            .asSequence()
            .flatMap { pattern -> pattern.findAll(text) }
            .mapNotNull { match ->
                match.groupValues
                    .asSequence()
                    .drop(1)
                    .firstOrNull { group -> group.isNotBlank() }
                    ?.toIntOrNull()
            }
            .filter { it in 1..10_000 }
            .maxOrNull() ?: 0
    }

    private fun fallbackDebugLabel(ranked: RankedSearchBook): String {
        return "${ranked.book.source.sourceName}/${ranked.book.source.sourceUrl}" +
            "/score=${ranked.score}/hint=${estimatedChapterOrdinal(ranked.book)}" +
            "/last=${ranked.book.lastChapter}"
    }

    private fun resolvedDebugLabel(resolved: ResolvedSourceBook): String {
        return "${resolved.book.source.sourceName}/${resolved.book.source.sourceUrl}" +
            "/chapters=${resolved.catalog.chapters.size}" +
            "/first=${resolved.catalog.chapters.firstOrNull()?.displayTitle}" +
            "/last=${resolved.catalog.chapters.lastOrNull()?.displayTitle}"
    }

    private fun isSameTitleCandidate(target: SourceBook, candidate: SourceBook): Boolean {
        val targetTitle = searchRanker.canonicalTitleKey(target)
        return targetTitle.isNotBlank() && searchRanker.canonicalTitleKey(candidate) == targetTitle
    }

    private fun authorAgreementScore(target: SourceBook, candidate: SourceBook): Int {
        val expectedAuthor = normalizedAuthor(target.author)
        if (expectedAuthor.isBlank()) return 0
        val candidateAuthor = normalizedAuthor(candidate.author)
        if (candidateAuthor.isBlank()) return 1
        return if (
            candidateAuthor.contains(expectedAuthor) ||
            expectedAuthor.contains(candidateAuthor)
        ) {
            2
        } else {
            0
        }
    }

    private fun authorConfidenceScore(
        target: SourceBook,
        candidate: SourceBook,
        authorConsensus: Map<String, Int>
    ): Int {
        val candidateAuthor = normalizedAuthor(candidate.author)
        val consensusScore = if (candidateAuthor.isBlank()) {
            0
        } else {
            (authorConsensus[candidateAuthor] ?: 0) * AUTHOR_CONSENSUS_WEIGHT
        }
        return consensusScore + authorAgreementScore(target, candidate)
    }

    private fun authorConsensusFor(books: List<SourceBook>): Map<String, Int> {
        return books
            .map { book -> normalizedAuthor(book.author) }
            .filter { author -> author.isNotBlank() }
            .groupingBy { author -> author }
            .eachCount()
    }

    private suspend fun loadReadableBookWithTimeout(
        sourceBook: SourceBook,
        sourceEngine: LegadoSourceEngine,
        timeoutMs: Long
    ): ResolvedSourceBook? {
        return runDetachedWithTimeout(timeoutMs) {
            loadReadableBook(sourceBook, sourceEngine)
        }
    }

    private fun loadReadableBook(
        sourceBook: SourceBook,
        sourceEngine: LegadoSourceEngine
    ): ResolvedSourceBook? {
        return runCatching {
            val detail = when (val value = sourceEngine.getBookDetail(sourceBook)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> return null
            }
            if (detailAgreementScore(sourceBook, detail) < 0) return null
            val catalog = when (val value = sourceEngine.getCanonicalChapterList(detail)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> return null
            }
            if (catalog.chapters.size < MIN_READABLE_CATALOG_CHAPTERS) return null
            val enrichedBook = detail.toSearchBook(sourceBook)
            ResolvedSourceBook(
                book = enrichedBook,
                detail = detail.copy(
                    book = enrichedBook,
                    name = enrichedBook.name,
                    author = enrichedBook.author,
                    coverUrl = enrichedBook.coverUrl,
                    intro = enrichedBook.intro,
                    kind = enrichedBook.kind,
                    lastChapter = enrichedBook.lastChapter
                ),
                catalog = catalog,
                routeId = SourceEngineBookRoute.bookId(enrichedBook)
            )
        }.getOrNull()
    }

    private suspend fun <T> runDetachedWithTimeout(timeoutMs: Long, block: () -> T?): T? {
        val parentScope = currentSourceRequestScope()
        val childScope = newSourceRequestScope("detached", timeoutMs.toString(), parentScope)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + sourceRequestContext(childScope))
        val deferred = scope.async { block() }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
            cancelSourceRequests(childScope)
            scope.coroutineContext.cancelChildren()
        }
    }

    private fun sourceLabel(book: SourceBook): String {
        return "${book.source.sourceName}@${book.source.sourceUrl}"
    }

    private fun sourceLabel(source: BookSource): String {
        return "${source.sourceName}@${source.sourceUrl}"
    }

    private fun String.debugToken(): String {
        return replace(Regex("""[\s=:/\\#]+"""), "_").take(180)
    }

    override suspend fun getBookInfo(bookId: String?): BookDetailBeanInOwn = withSourceRequestScope("detail", bookId) {
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
            val source = sourceFinder(route.sourceUrl)
            val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
            AiBridgeTrace.event(
                "source_detail_started",
                sourceBook.name,
                "author_${sourceBook.author.debugToken()}_source_${sourceLabel(sourceBook).debugToken()}"
            )
            val preview = resolveDetailPreviewBook(sourceBook)
            val resolved = preview.resolved
            val detail = resolved.detail
            val rawChapters = resolved.catalog.chapters
            val previewCover = selectVerifiedCover(detail.coverUrl, resolved.book.coverUrl, sourceBook.coverUrl)
            BookDetailBeanInOwn().apply {
                routeId = resolved.routeId
                shelfBookId = SourceEngineBookRoute.shelfBookId(resolved.book)
                this.bookId = resolved.routeId.hashCode()
                title = detail.name
                author = cleanAuthor(detail.author)
                cover = previewCover
                desc = cleanIntro(detail.intro)
                lastChapter = null
                chaptersCount = 0
                updateTime = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "operation=detailPreviewResolved provider=$providerName title=$title author=$author " +
                        "mode=${preview.mode} rawChapters=${rawChapters.size} cover=${!cover.isNullOrBlank()} " +
                        "intro=${!desc.isNullOrBlank()} durationMs=${System.currentTimeMillis() - startedAt} " +
                        "source=${sourceLabel(resolved.book)}"
                )
                AiBridgeTrace.state(
                    "source_detail_preview_resolved",
                    title.orEmpty(),
                    "author_${author.orEmpty().debugToken()}_raw_${rawChapters.size}_last_hidden_until_catalog" +
                        "_cover_${!cover.isNullOrBlank()}_intro_${!desc.isNullOrBlank()}" +
                        "_mode_${preview.mode.debugToken()}_ms_${System.currentTimeMillis() - startedAt}" +
                        "_source_${sourceLabel(resolved.book).debugToken()}"
                )
            }
        }
    }

    private suspend fun resolveDetailPreviewBook(sourceBook: SourceBook): DetailPreviewResolved {
        val waterfall = rememberBookContentWaterfall(sourceBook)
        cachedResolvedBook(waterfall, sourceBook)?.let { resolved ->
            return DetailPreviewResolved(resolved, "session-cached")
        }
        bestFirstDisplayBook(waterfall)?.let { resolved ->
            return DetailPreviewResolved(resolved, "session-evidence")
        }
        val resolved = loadReadableBookWithTimeout(sourceBook, engine, DETAIL_PREVIEW_TIMEOUT_MS)
            ?: error("Source-engine detail preview failed: ${sourceBook.name} ${sourceBook.bookUrl}")
        cacheResolvedBookInWaterfall(waterfall, resolved)
        return DetailPreviewResolved(resolved, "direct")
    }

    private suspend fun selectCoverWithFallback(
        requestedBook: SourceBook,
        resolved: ResolvedSourceBook
    ): String {
        val direct = selectVerifiedCover(resolved.detail.coverUrl, resolved.book.coverUrl, requestedBook.coverUrl)
        if (inspectCoverUrl(direct).usable) return direct
        val fallback = findCoverFallback(requestedBook)
        return selectVerifiedCover(direct, fallback)
    }

    private fun selectVerifiedCover(vararg candidates: String?): String {
        val cleaned = candidates
            .map { candidate -> BookCoverUrl.clean(candidate) }
            .filter { candidate -> candidate.isNotBlank() }
            .distinct()
        cleaned.firstOrNull { candidate -> inspectCoverUrl(candidate).usable }?.let { return it }
        return cleaned.fold("") { best, candidate -> BookCoverUrl.bestLikelyImage(best, candidate) }
    }

    private suspend fun findCoverFallback(sourceBook: SourceBook): String? {
        val candidates = fallbackCandidatesFor(sourceBook).take(MAX_COVER_FALLBACK_CANDIDATES)
        if (candidates.isEmpty()) return null
        val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + activeSourceRequestContext())
        val semaphore = Semaphore(MAX_COVER_FALLBACK_CONCURRENT_PROBES)
        return try {
            val probes = candidates.mapIndexed { order, candidate ->
                probeScope.async {
                    semaphore.withPermit {
                        val detail = runDetachedWithTimeout(COVER_FALLBACK_DETAIL_TIMEOUT_MS) {
                            when (val value = detailProbeEngine.getBookDetail(candidate.book)) {
                                is EngineResult.Success -> value.value
                                is EngineResult.Failure -> null
                            }
                        } ?: return@withPermit null
                        if (detailAgreementScore(candidate.book, detail) < 0) return@withPermit null
                        val cover = selectVerifiedCover(detail.coverUrl, candidate.book.coverUrl)
                        if (!inspectCoverUrl(cover).usable) return@withPermit null
                        CoverFallback(order, candidate, cover)
                    }
                }
            }
            val resolved = awaitFinishedValuesWithin(probes, COVER_FALLBACK_TOTAL_TIMEOUT_MS)
            val best = resolved.sortedWith(
                compareByDescending<CoverFallback> { sourceQualityRouter.bookSourceScore(it.ranked.book) }
                    .thenBy { sourcePriorityIndex(it.ranked.book.source, it.ranked.book.name) }
                    .thenBy { it.order }
            ).firstOrNull()
            if (best != null) {
                Log.i(
                    TAG,
                    "operation=coverFallbackResolved provider=$providerName title=${sourceBook.name} " +
                        "source=${sourceLabel(best.ranked.book)} cover=${best.coverUrl}"
                )
            }
            best?.coverUrl
        } finally {
            probeScope.coroutineContext.cancelChildren()
        }
    }

    override suspend fun getBookFolder(bookId: String?, collBookBean: CollBookBean): List<BookChapterBean> =
        withSourceRequestScope("catalog", bookId) {
            withContext(Dispatchers.IO) {
            val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
            val source = sourceFinder(route.sourceUrl)
            val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
            AiBridgeTrace.event(
                "source_catalog_started",
                sourceBook.name,
                "author_${sourceBook.author.debugToken()}_source_${sourceLabel(sourceBook).debugToken()}"
            )
            val resolved = resolveFirstDisplayBook(sourceBook)
            val detail = resolved.detail
            val canonicalCatalog = resolved.catalog
            val chapters = tailReadableChapters(resolved)
            Log.i(
                TAG,
                "operation=catalogResolved provider=$providerName title=${detail.name} " +
                    "chapters=${chapters.size} rawChapters=${canonicalCatalog.chapters.size} " +
                    "duplicates=${canonicalCatalog.duplicateCount} " +
                    "first=${chapters.firstOrNull()?.displayTitle} last=${chapters.lastOrNull()?.displayTitle}"
            )
            AiBridgeTrace.state(
                "source_catalog_resolved",
                detail.name,
                "chapters_${chapters.size}_raw_${canonicalCatalog.chapters.size}" +
                    "_first_${chapters.firstOrNull()?.displayTitle.orEmpty().debugToken()}" +
                    "_last_${chapters.lastOrNull()?.displayTitle.orEmpty().debugToken()}"
            )
            chapters.mapIndexedNotNull { index, canonicalChapter ->
                val chapter = canonicalChapter.sourceChapters.firstOrNull() ?: return@mapIndexedNotNull null
                BookChapterBean().apply {
                    link = SourceEngineBookRoute.chapterId(chapter)
                    title = canonicalChapter.displayTitle
                    id = MD5Utils.strToMd5By16(link!!)
                    this.bookId = collBookBean.get_id()
                    start = index.toLong()
                }
            }
            }
        }

    override suspend fun getBookContent(
        bookId: String?,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        sourceIndex: Int
    ): String = withSourceRequestScope("content", bookChapter.link) {
        withContext(Dispatchers.IO) {
        val route = SourceEngineBookRoute.decodeChapterId(requireNotNull(bookChapter.link))
        val source = sourceFinder(route.sourceUrl)
        val chapter = SourceEngineBookRoute.toSourceChapter(source, route)
        AiBridgeTrace.event(
            "source_content_request",
            sourceBook.title ?: chapter.book.name,
            "chapter_${bookChapter.title.orEmpty().debugToken()}_source_${sourceLabel(chapter.book).debugToken()}"
        )
        val content = readFirstDisplayChapterContent(chapter, sourceBook, bookChapter)
            ?: error("Source-engine trusted content sources too few: ${chapter.book.name} ${chapter.name}")
        content.cleanedContent
        }
    }

    private fun isReadableContent(content: CleanContent): Boolean {
        return content.report.cleanedLength >= MIN_CLEAN_CONTENT_CHARS &&
            content.report.qualityScore >= MIN_CONTENT_QUALITY_SCORE &&
            content.report.coherenceScore >= MIN_CONTENT_COHERENCE_SCORE
    }

    private fun failContentQuality(content: CleanContent): Nothing {
        error(
            "Source-engine content quality too low: " +
                "score=${content.report.qualityScore}, " +
                "coherence=${content.report.coherenceScore}, " +
                "cleaned=${content.report.cleanedLength}, " +
                "warnings=${content.report.warnings.joinToString()}"
        )
    }

    private suspend fun resolveFirstDisplayBook(sourceBook: SourceBook): ResolvedSourceBook {
        rememberBookContentWaterfall(sourceBook).let { cached ->
            if (isTrustedDisplayReady(cached, FIRST_DISPLAY_TRUSTED_SOURCE_COUNT)) {
                bestFirstDisplayBook(cached)?.let { resolved ->
                    val readableCount = readableChapterCount(resolved)
                    if (readableCount < resolved.catalog.chapters.size) {
                        AiBridgeTrace.event(
                            "source_first_display_session_trimmed",
                            sourceBook.name,
                            "source_${sourceLabel(resolved.book).debugToken()}" +
                                "_raw_${resolved.catalog.chapters.size}" +
                                "_readable_$readableCount"
                        )
                    } else {
                        Log.i(
                            TAG,
                            "operation=firstDisplayFromSession provider=$providerName title=${sourceBook.name} " +
                                "author=${sourceBook.author} trusted=${verifiedBookCount(cached)}"
                        )
                        return resolved
                    }
                }
            }
        }
        val initialWaterfall = rememberBookContentWaterfall(sourceBook)
        val resolved = cachedResolvedBook(initialWaterfall, sourceBook) ?: resolveReadableBook(sourceBook)
        val waterfall = refreshBookContentWaterfall(resolved.book)
        promoteTrustedResolvedBookInWaterfall(waterfall, resolved)
        fillBookContentTierOnce(
            waterfall,
            FIRST_DISPLAY_TRUSTED_SOURCE_COUNT,
            FIRST_DISPLAY_TIER_FILL_TIMEOUT_MS
        )
        if (!isTrustedDisplayReady(waterfall, FIRST_DISPLAY_TRUSTED_SOURCE_COUNT)) {
            error("Source-engine first display trusted source count too low: ${sourceBook.name}")
        }
        return bestFirstDisplayBook(waterfall) ?: resolved
    }

    suspend fun prepareBookContentTier(
        bookId: String?,
        collBookBean: CollBookBean?,
        persist: Boolean = false
    ): Boolean =
        withSourceRequestScope("contentTier", bookId) {
            withContext(Dispatchers.IO) {
                val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
                val source = sourceFinder(route.sourceUrl)
                val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
                val waterfall = rememberBookContentWaterfall(sourceBook)
                if (persist) {
                    loadPersistedTierIntoWaterfall(waterfall, collBookBean?.get_id())
                }
                refreshBookContentWaterfall(sourceBook)
                loadReadableBookWithTimeout(sourceBook, detailProbeEngine, DETAIL_PROBE_TIMEOUT_MS)
                    ?.let { resolved -> promoteTrustedResolvedBookInWaterfall(waterfall, resolved) }
                val ready = fillBookContentTierOnce(
                    waterfall,
                    BOOK_CONTENT_TIER_TARGET_SIZE,
                    BOOK_CONTENT_TIER_FILL_TIMEOUT_MS
                )
                if (persist) {
                    persistVerifiedTier(waterfall, collBookBean?.get_id())
                }
                ready
            }
        }

    private suspend fun readFirstDisplayChapterContent(
        chapter: SourceChapter,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter
    ): CleanContent? {
        val waterfall = rememberBookContentWaterfall(chapter.book)
        loadPersistedTierIntoWaterfall(waterfall, sourceBook.get_id())
        val targetTitle = chapterNormalizer.normalize(chapter.name)
        val trusted = ArrayList<TrustedChapterContent>()

        readDirectTrustedChapterContent(waterfall, chapter)?.let { trusted.add(it) }
        trusted.addAll(
            readFromBookContentTier(
                waterfall,
                chapter,
                targetTitle,
                trusted.mapTo(mutableSetOf()) { item -> sourceBookKey(item.resolved.book) },
                FIRST_DISPLAY_TRUSTED_SOURCE_COUNT - trusted.size
            )
        )
        immediateSingleTrustedContent(
            waterfall,
            sourceBook,
            bookChapter,
            chapter,
            trusted.distinctBy { item -> sourceBookKey(item.resolved.book) }
        )?.let { return it }

        if (trusted.size < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) {
            refreshBookContentWaterfall(chapter.book)
            trusted.addAll(
                readFromContentCandidates(
                    waterfall,
                    chapter,
                    targetTitle,
                    trusted.mapTo(mutableSetOf()) { item -> sourceBookKey(item.resolved.book) },
                    FIRST_DISPLAY_TRUSTED_SOURCE_COUNT - trusted.size
                )
            )
        }

        val distinctTrusted = trusted.distinctBy { item -> sourceBookKey(item.resolved.book) }
        if (distinctTrusted.size < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) {
            Log.w(
                TAG,
                "operation=contentFirstDisplayMissing provider=$providerName book=${sourceBook.title} " +
                    "chapter=${bookChapter.title} trusted=${distinctTrusted.size}"
            )
            AiBridgeTrace.state(
                "source_content_missing_trusted",
                sourceBook.title ?: chapter.book.name,
                "chapter_${bookChapter.title.orEmpty().debugToken()}_trusted_${distinctTrusted.size}" +
                    "_required_${FIRST_DISPLAY_TRUSTED_SOURCE_COUNT}_verified_${verifiedBookCount(waterfall)}" +
                    "_candidates_${synchronized(waterfall.candidates) { waterfall.candidates.size }}"
            )
            return immediateSingleTrustedContent(waterfall, sourceBook, bookChapter, chapter, distinctTrusted)
        }

        distinctTrusted.forEach { item ->
            promoteResolvedBookInWaterfall(waterfall, item.resolved)
            recordTrustedFingerprintContent(item.chapter, item.content)
            sourceQualityRouter.recordContentResolved(item.chapter, item.content)
        }
        persistVerifiedTier(waterfall, sourceBook.get_id())
        val best = distinctTrusted.sortedWith(trustedChapterContentComparator).first()
        Log.i(
            TAG,
            "operation=contentFirstDisplayResolved provider=$providerName book=${sourceBook.title} " +
                "chapter=${bookChapter.title} trusted=${distinctTrusted.size} source=${sourceLabel(best.resolved.book)} " +
                "score=${best.content.report.qualityScore} coherence=${best.content.report.coherenceScore} " +
                "cleaned=${best.content.report.cleanedLength}"
        )
        AiBridgeTrace.event(
            "source_content_resolved",
            sourceBook.title ?: chapter.book.name,
            "chapter_${bookChapter.title.orEmpty().debugToken()}_trusted_${distinctTrusted.size}" +
                "_score_${best.content.report.qualityScore}_coherence_${best.content.report.coherenceScore}" +
                "_cleaned_${best.content.report.cleanedLength}"
        )
        AiBridgeTrace.state(
            "source_content_trusted_sources",
            "${sourceBook.title ?: chapter.book.name}#${bookChapter.title.orEmpty()}",
            "chapter_${bookChapter.title.orEmpty().debugToken()}_sources_${
                distinctTrusted.joinToString("|") { item -> sourceLabel(item.resolved.book).debugToken() }
            }"
        )
        return best.content
    }

    private fun immediateSingleTrustedContent(
        waterfall: BookContentWaterfall,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        chapter: SourceChapter,
        trusted: List<TrustedChapterContent>
    ): CleanContent? {
        if (trusted.size >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) return null
        val singleTrusted = trusted.sortedWith(trustedChapterContentComparator).firstOrNull() ?: return null
        promoteResolvedBookInWaterfall(waterfall, singleTrusted.resolved)
        recordTrustedFingerprintContent(singleTrusted.chapter, singleTrusted.content)
        sourceQualityRouter.recordContentResolved(singleTrusted.chapter, singleTrusted.content)
        persistVerifiedTier(waterfall, sourceBook.get_id())
        AiBridgeTrace.event(
            "source_content_single_trusted_display",
            sourceBook.title ?: chapter.book.name,
            "chapter_${bookChapter.title.orEmpty().debugToken()}" +
                "_source_${sourceLabel(singleTrusted.resolved.book).debugToken()}" +
                "_score_${singleTrusted.content.report.qualityScore}" +
                "_coherence_${singleTrusted.content.report.coherenceScore}" +
                "_cleaned_${singleTrusted.content.report.cleanedLength}" +
                "_trusted_${trusted.size}_required_${FIRST_DISPLAY_TRUSTED_SOURCE_COUNT}"
        )
        return singleTrusted.content
    }

    private suspend fun readDirectTrustedChapterContent(
        waterfall: BookContentWaterfall,
        chapter: SourceChapter
    ): TrustedChapterContent? {
        val startedAt = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_content_direct_started",
            chapter.book.name,
            "chapter_${chapter.name.debugToken()}_source_${sourceLabel(chapter.book).debugToken()}"
        )
        val resolved = loadReadableBookWithTimeout(
            chapter.book,
            detailProbeEngine,
            FINGERPRINT_PROFILE_RESOLVE_TIMEOUT_MS
        ) ?: run {
            AiBridgeTrace.event(
                "source_content_direct_rejected",
                chapter.book.name,
                "chapter_${chapter.name.debugToken()}_reason_detail" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        if (!promoteTrustedResolvedBookInWaterfall(waterfall, resolved)) {
            AiBridgeTrace.event(
                "source_content_direct_rejected",
                chapter.book.name,
                "chapter_${chapter.name.debugToken()}_reason_untrusted_book" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        val fingerprint = bookFingerprintForResolved(resolved)?.takeIf { fingerprint -> fingerprint.usable }
            ?: run {
                AiBridgeTrace.event(
                    "source_content_direct_rejected",
                    chapter.book.name,
                    "chapter_${chapter.name.debugToken()}_reason_fingerprint" +
                        "_durationMs_${System.currentTimeMillis() - startedAt}"
                )
                return null
            }
        val content = loadCleanContentWithTimeout(chapter, CONTENT_FALLBACK_CONTENT_TIMEOUT_MS, fingerprint)
            ?: run {
                AiBridgeTrace.event(
                    "source_content_direct_rejected",
                    chapter.book.name,
                    "chapter_${chapter.name.debugToken()}_reason_content_null" +
                        "_durationMs_${System.currentTimeMillis() - startedAt}"
                )
                return null
            }
        if (!isReadableContent(content)) {
            sourceQualityRouter.recordContentRejected(chapter)
            AiBridgeTrace.event(
                "source_content_direct_rejected",
                chapter.book.name,
                "chapter_${chapter.name.debugToken()}_reason_quality_score_${content.report.qualityScore}" +
                    "_coherence_${content.report.coherenceScore}_warnings_${content.report.warnings.joinToString(",").debugToken()}" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        AiBridgeTrace.event(
            "source_content_direct_trusted",
            chapter.book.name,
            "chapter_${chapter.name.debugToken()}_score_${content.report.qualityScore}" +
                "_coherence_${content.report.coherenceScore}_cleaned_${content.report.cleanedLength}" +
                "_durationMs_${System.currentTimeMillis() - startedAt}"
        )
        return TrustedChapterContent(0, resolved, chapter, content)
    }

    private suspend fun findReadableContentFallback(
        chapter: SourceChapter,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        rejectedContent: CleanContent?
    ): CleanContent? {
        val waterfall = ensureBookContentWaterfall(chapter.book)
        val targetTitle = chapterNormalizer.normalize(chapter.name)
        readFromBookContentTier(waterfall, chapter, targetTitle, emptySet(), CONTENT_FALLBACK_SUCCESS_LIMIT)
            .firstOrNull()
            ?.let { return it.content }
        val candidates = contentFallbackCandidatesFor(chapter.book, waterfall)
        if (candidates.isEmpty()) return null
        Log.i(
            TAG,
            "operation=contentFallbackCandidates provider=$providerName book=${sourceBook.title} " +
                "chapter=${bookChapter.title} rejectedScore=${rejectedContent?.report?.qualityScore} " +
                "rejectedCoherence=${rejectedContent?.report?.coherenceScore} candidates=${candidates.size}"
        )
        val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + activeSourceRequestContext())
        val semaphore = Semaphore(MAX_CONTENT_FALLBACK_CONCURRENT_PROBES)
        return try {
            val probes = candidates.mapIndexed { order, candidate ->
                probeScope.async {
                    semaphore.withPermit {
                        val resolved = resolveContentFallbackBook(waterfall, candidate) ?: return@withPermit null
                        if (!isTrustedResolvedBook(resolved)) {
                            markResolvedBookFailed(waterfall, resolved.book)
                            return@withPermit null
                        }
                        val fallbackChapter = matchingChapter(resolved.catalog, targetTitle)
                            ?: return@withPermit null
                        val fingerprint = bookFingerprintForResolved(resolved)?.takeIf { fingerprint -> fingerprint.usable }
                            ?: return@withPermit null
                        val content = runDetachedWithTimeout(CONTENT_FALLBACK_CONTENT_TIMEOUT_MS) {
                            when (val value = engine.getCleanContent(fallbackChapter, bookFingerprint = fingerprint)) {
                                is EngineResult.Success -> value.value
                                is EngineResult.Failure -> null
                            }
                        } ?: return@withPermit null
                        if (!isReadableContent(content)) return@withPermit null
                        ContentFallback(order, resolved, fallbackChapter, content)
                    }
                }
            }
            val resolved = awaitFinishedValuesWithinLimit(
                probes,
                CONTENT_FALLBACK_TOTAL_TIMEOUT_MS,
                CONTENT_FALLBACK_SUCCESS_LIMIT
            )
            val best = resolved.sortedWith(contentFallbackComparator).firstOrNull()
            if (best != null) {
                promoteResolvedBookInWaterfall(waterfall, best.resolved)
                recordTrustedFingerprintContent(best.chapter, best.content)
                sourceQualityRouter.recordContentResolved(best.chapter, best.content)
                Log.i(
                    TAG,
                    "operation=contentFallbackResolved provider=$providerName book=${sourceBook.title} " +
                        "chapter=${bookChapter.title} source=${sourceLabel(best.resolved.book)} " +
                        "score=${best.content.report.qualityScore} coherence=${best.content.report.coherenceScore} " +
                        "cleaned=${best.content.report.cleanedLength}"
                )
            } else {
                Log.w(
                    TAG,
                    "operation=contentFallbackMissing provider=$providerName book=${sourceBook.title} " +
                        "chapter=${bookChapter.title} candidates=${candidates.size}"
                )
            }
            best?.content
        } finally {
            probeScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun ensureBookContentWaterfall(sourceBook: SourceBook): BookContentWaterfall {
        val key = bookWaterfallKey(sourceBook)
        bookContentWaterfallCache[key]?.let { return it }
        val candidates = fallbackCandidatesFor(sourceBook)
        return rememberBookContentWaterfall(sourceBook, candidates)
    }

    private suspend fun refreshBookContentWaterfall(sourceBook: SourceBook): BookContentWaterfall {
        val candidates = fallbackCandidatesFor(sourceBook)
        return rememberBookContentWaterfall(sourceBook, candidates)
    }

    private fun rememberBookContentWaterfall(sourceBook: SourceBook): BookContentWaterfall {
        return rememberBookContentWaterfall(sourceBook, emptyList())
    }

    private fun rememberBookContentWaterfall(
        sourceBook: SourceBook,
        candidates: List<RankedSearchBook>
    ): BookContentWaterfall {
        val key = bookWaterfallKey(sourceBook)
        bookContentWaterfallCache[key]?.let { existing ->
            mergeWaterfallCandidates(existing, candidates)
            return existing
        }
        val waterfall = BookContentWaterfall(sourceBook, ArrayList(candidates))
        bookContentWaterfallCache[key] = waterfall
        Log.i(
            TAG,
            "operation=bookContentWaterfallPrepared provider=$providerName " +
                "title=${sourceBook.name} author=${sourceBook.author} candidates=${candidates.size}"
        )
        AiBridgeTrace.state(
            "source_content_waterfall_prepared",
            "${sourceBook.name}#${sourceBook.author}",
            "candidates_${candidates.size}_author_${sourceBook.author.debugToken()}"
        )
        return waterfall
    }

    private fun mergeWaterfallCandidates(
        waterfall: BookContentWaterfall,
        candidates: List<RankedSearchBook>
    ) {
        val existing = synchronized(waterfall.candidates) {
            waterfall.candidates.mapTo(mutableSetOf()) { candidate -> sourceBookKey(candidate.book) }
        }
        val newCandidates = candidates.filter { candidate -> sourceBookKey(candidate.book) !in existing }
        if (newCandidates.isEmpty()) return
        synchronized(waterfall.candidates) {
            waterfall.candidates.addAll(newCandidates)
        }
    }

    private suspend fun fillBookContentTierOnce(
        waterfall: BookContentWaterfall,
        targetSize: Int,
        timeoutMs: Long
    ): Boolean {
        if (isTrustedDisplayReady(waterfall, targetSize)) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isTrustedDisplayReady(waterfall, targetSize)) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0) break
            val needed = targetSize - verifiedBookCount(waterfall)
            val candidates = contentFallbackCandidatesFor(waterfall.sourceBook, waterfall)
                .filter { candidate -> !hasVerifiedBook(waterfall, candidate.book) }
                .take(BOOK_CONTENT_TIER_FILL_BATCH_SIZE)
            if (candidates.isEmpty()) break
            val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + activeSourceRequestContext())
            val semaphore = Semaphore(MAX_CONTENT_FALLBACK_CONCURRENT_PROBES)
            try {
                val probes = candidates.map { candidate ->
                    probeScope.async {
                        semaphore.withPermit {
                            val resolved = resolveContentFallbackBook(waterfall, candidate) ?: return@withPermit null
                            if (!promoteTrustedResolvedBookInWaterfall(waterfall, resolved)) return@withPermit null
                            resolved
                        }
                    }
                }
                awaitFinishedValuesWithinLimit(
                    probes,
                    remainingMs,
                    needed
                )
            } finally {
                probeScope.coroutineContext.cancelChildren()
            }
        }
        return isTrustedDisplayReady(waterfall, targetSize)
    }

    private fun contentFallbackCandidatesFor(
        sourceBook: SourceBook,
        waterfall: BookContentWaterfall
    ): List<RankedSearchBook> {
        val currentBookKey = sourceBookKey(sourceBook)
        return synchronized(waterfall.candidates) { waterfall.candidates.toList() }
            .filter { candidate -> sourceBookKey(candidate.book) != currentBookKey }
            .filter { candidate -> !hasFailedBook(waterfall, candidate.book) }
            .sortedWith(contentWaterfallComparator)
    }

    private suspend fun readFromBookContentTier(
        waterfall: BookContentWaterfall,
        currentChapter: SourceChapter,
        targetTitle: NormalizedChapterTitle,
        excludedBookKeys: Set<String>,
        limit: Int
    ): List<TrustedChapterContent> {
        if (limit <= 0) return emptyList()
        val currentBookKey = sourceBookKey(currentChapter.book)
        val trusted = ArrayList<TrustedChapterContent>()
        val resolvedBooks = verifiedBooksSnapshot(waterfall)
            .filter { resolved ->
                val key = sourceBookKey(resolved.book)
                key != currentBookKey && key !in excludedBookKeys
            }
        for (resolved in resolvedBooks) {
            val fallbackChapter = matchingChapter(resolved.catalog, targetTitle) ?: continue
            val fingerprint = bookFingerprintForResolved(resolved)?.takeIf { fingerprint -> fingerprint.usable }
                ?: continue
            val content = loadCleanContentWithTimeout(
                fallbackChapter,
                CONTENT_FALLBACK_CONTENT_TIMEOUT_MS,
                fingerprint
            ) ?: continue
            if (!isReadableContent(content)) continue
            Log.i(
                TAG,
                "operation=contentTierResolved provider=$providerName " +
                    "book=${waterfall.sourceBook.name} chapter=${currentChapter.name} " +
                    "source=${sourceLabel(resolved.book)} score=${content.report.qualityScore} " +
                    "coherence=${content.report.coherenceScore} cleaned=${content.report.cleanedLength}"
            )
            trusted.add(TrustedChapterContent(trusted.size + 1, resolved, fallbackChapter, content))
            AiBridgeTrace.event(
                "source_content_tier_trusted",
                waterfall.sourceBook.name,
                "chapter_${currentChapter.name.debugToken()}_source_${sourceLabel(resolved.book).debugToken()}" +
                    "_score_${content.report.qualityScore}_coherence_${content.report.coherenceScore}"
            )
            if (trusted.size >= limit) break
        }
        return trusted
    }

    private suspend fun readFromContentCandidates(
        waterfall: BookContentWaterfall,
        currentChapter: SourceChapter,
        targetTitle: NormalizedChapterTitle,
        excludedBookKeys: Set<String>,
        limit: Int
    ): List<TrustedChapterContent> {
        if (limit <= 0) return emptyList()
        val trusted = ArrayList<TrustedChapterContent>()
        val candidates = contentFallbackCandidatesFor(currentChapter.book, waterfall)
            .filter { candidate ->
                val key = sourceBookKey(candidate.book)
                key !in excludedBookKeys && !hasVerifiedBook(waterfall, candidate.book)
            }
        candidates.chunked(CONTENT_FALLBACK_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            if (trusted.size >= limit) return@forEachIndexed
            val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + activeSourceRequestContext())
            val semaphore = Semaphore(MAX_CONTENT_FALLBACK_CONCURRENT_PROBES)
            try {
                val probes = batch.mapIndexed { order, candidate ->
                    probeScope.async {
                        semaphore.withPermit {
                            val resolved = resolveContentFallbackBook(waterfall, candidate) ?: return@withPermit null
                            if (!promoteTrustedResolvedBookInWaterfall(waterfall, resolved)) return@withPermit null
                            val fallbackChapter = matchingChapter(resolved.catalog, targetTitle)
                                ?: return@withPermit null
                            val fingerprint = bookFingerprintForResolved(resolved)?.takeIf { fingerprint -> fingerprint.usable }
                                ?: return@withPermit null
                            val content = loadCleanContentWithTimeout(
                                fallbackChapter,
                                CONTENT_FALLBACK_CONTENT_TIMEOUT_MS,
                                fingerprint
                            ) ?: return@withPermit null
                            if (!isReadableContent(content)) {
                                sourceQualityRouter.recordContentRejected(fallbackChapter)
                                AiBridgeTrace.event(
                                    "source_content_candidate_rejected",
                                    currentChapter.book.name,
                                    "chapter_${currentChapter.name.debugToken()}_source_${sourceLabel(resolved.book).debugToken()}" +
                                        "_score_${content.report.qualityScore}_coherence_${content.report.coherenceScore}" +
                                        "_warnings_${content.report.warnings.joinToString(",").debugToken()}"
                                )
                                return@withPermit null
                            }
                            AiBridgeTrace.event(
                                "source_content_candidate_trusted",
                                currentChapter.book.name,
                                "chapter_${currentChapter.name.debugToken()}_source_${sourceLabel(resolved.book).debugToken()}" +
                                    "_score_${content.report.qualityScore}_coherence_${content.report.coherenceScore}"
                            )
                            TrustedChapterContent(
                                batchIndex * CONTENT_FALLBACK_BATCH_SIZE + order + 1,
                                resolved,
                                fallbackChapter,
                                content
                            )
                        }
                    }
                }
                trusted.addAll(
                    awaitFinishedValuesWithinLimit(
                        probes,
                        CONTENT_FALLBACK_TOTAL_TIMEOUT_MS,
                        limit - trusted.size
                    )
                )
            } finally {
                probeScope.coroutineContext.cancelChildren()
            }
        }
        return trusted
    }

    private suspend fun resolveContentFallbackBook(
        waterfall: BookContentWaterfall,
        candidate: RankedSearchBook
    ): ResolvedSourceBook? {
        val key = sourceBookKey(candidate.book)
        synchronized(waterfall.resolvedBooks) {
            if (waterfall.resolvedBooks.containsKey(key)) {
                return waterfall.resolvedBooks[key]
            }
        }
        val resolved = loadReadableBookWithTimeout(
            candidate.book,
            detailProbeEngine,
            CONTENT_FALLBACK_DETAIL_TIMEOUT_MS
        )
        if (resolved != null) {
            cacheResolvedBookInWaterfall(waterfall, resolved)
        } else {
            markResolvedBookFailed(waterfall, candidate.book)
        }
        return resolved
    }

    private suspend fun isTrustedResolvedBook(resolved: ResolvedSourceBook): Boolean {
        val fingerprint = bookFingerprintForResolved(resolved)
        return fingerprint?.usable == true &&
            tailReadableChapters(resolved).size >= MIN_READABLE_CATALOG_CHAPTERS
    }

    private suspend fun promoteTrustedResolvedBookInWaterfall(
        waterfall: BookContentWaterfall,
        resolved: ResolvedSourceBook
    ): Boolean {
        if (!isTrustedResolvedBook(resolved)) {
            markResolvedBookFailed(waterfall, resolved.book)
            return false
        }
        promoteResolvedBookInWaterfall(waterfall, resolved)
        return true
    }

    private fun cacheResolvedBookInWaterfall(
        waterfall: BookContentWaterfall,
        resolved: ResolvedSourceBook
    ) {
        val key = sourceBookKey(resolved.book)
        synchronized(waterfall.resolvedBooks) {
            waterfall.resolvedBooks[key] = resolved
        }
    }

    private fun promoteResolvedBookInWaterfall(
        waterfall: BookContentWaterfall,
        resolved: ResolvedSourceBook
    ) {
        cacheResolvedBookInWaterfall(waterfall, resolved)
        val key = sourceBookKey(resolved.book)
        synchronized(waterfall.verifiedBooks) {
            if (waterfall.verifiedBooks.none { existing -> sourceBookKey(existing.book) == key }) {
                waterfall.verifiedBooks.add(resolved)
            }
        }
    }

    private fun hasResolvedBook(waterfall: BookContentWaterfall, book: SourceBook): Boolean {
        val key = sourceBookKey(book)
        return synchronized(waterfall.resolvedBooks) {
            waterfall.resolvedBooks.containsKey(key)
        }
    }

    private fun cachedResolvedBook(waterfall: BookContentWaterfall, book: SourceBook): ResolvedSourceBook? {
        val key = sourceBookKey(book)
        return synchronized(waterfall.resolvedBooks) {
            waterfall.resolvedBooks[key]
        }
    }

    private fun hasVerifiedBook(waterfall: BookContentWaterfall, book: SourceBook): Boolean {
        val key = sourceBookKey(book)
        return synchronized(waterfall.verifiedBooks) {
            waterfall.verifiedBooks.any { existing -> sourceBookKey(existing.book) == key }
        }
    }

    private fun hasFailedBook(waterfall: BookContentWaterfall, book: SourceBook): Boolean {
        val key = sourceBookKey(book)
        return synchronized(waterfall.failedBooks) {
            key in waterfall.failedBooks
        }
    }

    private fun markResolvedBookFailed(waterfall: BookContentWaterfall, book: SourceBook) {
        val key = sourceBookKey(book)
        synchronized(waterfall.failedBooks) {
            waterfall.failedBooks.add(key)
        }
    }

    private fun isTrustedDisplayReady(waterfall: BookContentWaterfall, targetSize: Int): Boolean {
        return verifiedBookCount(waterfall) >= targetSize && hasTrustedCover(waterfall)
    }

    private fun hasTrustedCover(waterfall: BookContentWaterfall): Boolean {
        return verifiedBooksSnapshot(waterfall).any { resolved ->
            inspectCoverUrl(selectVerifiedCover(resolved.detail.coverUrl, resolved.book.coverUrl)).usable
        }
    }

    private suspend fun bestFirstDisplayBook(waterfall: BookContentWaterfall): ResolvedSourceBook? {
        val ranked = verifiedBooksSnapshot(waterfall).mapIndexed { index, resolved ->
            readableResolvedBook(index, resolved)
        }.sortedWith(readableResolvedBookComparator)
        val best = ranked.firstOrNull() ?: return null
        AiBridgeTrace.event(
            "source_first_display_best",
            waterfall.sourceBook.name,
            "source_${sourceLabel(best.resolved.book).debugToken()}" +
                "_readable_${best.readableChapterCount}" +
                "_lastOrdinal_${best.lastReadableOrdinal}" +
                "_tailGaps_${best.tailOrdinalGapCount}" +
                "_tailScore_${best.tailContinuityScore}" +
                "_raw_${best.resolved.catalog.chapters.size}" +
                "_trusted_${ranked.size}"
        )
        return best.resolved
    }

    private fun loadPersistedTierIntoWaterfall(
        waterfall: BookContentWaterfall,
        shelfBookId: String?
    ) {
        val file = persistedTierFile(shelfBookId) ?: return
        if (!file.exists()) return
        val candidates = file.readLines()
            .map { line -> line.trim() }
            .filter { routeId -> SourceEngineBookRoute.isBookId(routeId) }
            .mapNotNull { routeId ->
                runCatching {
                    val route = SourceEngineBookRoute.decodeBookId(routeId)
                    val source = sourceFinder(route.sourceUrl)
                    SourceEngineBookRoute.toSourceBook(source, route)
                }.getOrNull()
            }
            .mapIndexed { index, book ->
                RankedSearchBook(
                    book = book,
                    score = MAX_SOURCE_SCORE,
                    evidence = "persisted-tier",
                    sourceIndex = index,
                    resultIndex = 0
                )
            }
        mergeWaterfallCandidates(waterfall, candidates)
    }

    private fun persistVerifiedTier(
        waterfall: BookContentWaterfall,
        shelfBookId: String?
    ) {
        val file = persistedTierFile(shelfBookId) ?: return
        val routeIds = verifiedBooksSnapshot(waterfall)
            .take(BOOK_CONTENT_TIER_TARGET_SIZE)
            .map { resolved -> SourceEngineBookRoute.bookId(resolved.book) }
            .distinct()
        if (routeIds.isEmpty()) return
        file.parentFile?.mkdirs()
        file.writeText(routeIds.joinToString("\n"))
    }

    private fun persistedTierFile(shelfBookId: String?): File? {
        if (shelfBookId.isNullOrBlank()) return null
        return File(BookManager.cacheFolderPath(shelfBookId), SOURCE_ENGINE_TIER_FILE_NAME)
    }

    private fun verifiedBookCount(waterfall: BookContentWaterfall): Int {
        return synchronized(waterfall.verifiedBooks) {
            waterfall.verifiedBooks.size
        }
    }

    private fun verifiedBooksSnapshot(waterfall: BookContentWaterfall): List<ResolvedSourceBook> {
        return synchronized(waterfall.verifiedBooks) {
            waterfall.verifiedBooks.toList()
        }.sortedWith(
            compareByDescending<ResolvedSourceBook> { sourceQualityRouter.bookSourceScore(it.book) }
                .thenByDescending { it.catalog.chapters.size }
                .thenBy { sourcePriorityIndex(it.book.source, it.book.name) }
        )
    }

    private fun bookWaterfallKey(book: SourceBook): String {
        val author = normalizedAuthor(book.author)
        return normalizeHint(book.name) + "\n" + author
    }

    private fun sourceBookKey(book: SourceBook): String {
        return book.source.sourceUrl + "\n" + book.bookUrl
    }

    internal fun matchingChapter(
        catalog: CanonicalChapterList,
        targetTitle: NormalizedChapterTitle
    ): SourceChapter? {
        val targetKey = targetTitle.key
        val targetOrdinal = targetTitle.ordinal
        if (targetOrdinal == null) {
            return catalog.chapters.firstOrNull { chapter ->
                chapter.key == targetKey || chapterTitleSuffixCompatible(targetKey, chapter.displayTitle)
            }?.sourceChapters?.firstOrNull()
        }
        val targetSuffix = chapterTitleSuffixKey(targetTitle.displayTitle)
        val ordinalMatches = catalog.chapters.filter { chapter -> chapter.ordinal == targetOrdinal }
        ordinalMatches.firstOrNull { chapter ->
            chapterTitleSuffixCompatible(targetSuffix, chapter.displayTitle)
        }?.let { return it.sourceChapters.firstOrNull() }
        ordinalMatches.firstOrNull { chapter ->
            targetSuffix.isBlank() || chapterTitleSuffixKey(chapter.displayTitle).isBlank()
        }?.let { return it.sourceChapters.firstOrNull() }
        return null
    }

    private fun chapterTitleSuffixCompatible(
        normalizedTarget: String,
        candidateTitle: String
    ): Boolean {
        val targetSuffix = if (normalizedTarget.startsWith("n:")) {
            ""
        } else {
            chapterTitleSuffixKey(normalizedTarget)
        }
        val candidateSuffix = chapterTitleSuffixKey(candidateTitle)
        if (targetSuffix.isBlank() || candidateSuffix.isBlank()) return true
        return targetSuffix == candidateSuffix ||
            targetSuffix.contains(candidateSuffix) ||
            candidateSuffix.contains(targetSuffix)
    }

    private fun chapterTitleSuffixKey(title: String): String {
        val displayTitle = chapterNormalizer.normalize(title).displayTitle
        val chapterMatch = CHAPTER_TITLE_ORDINAL_PATTERN.findAll(displayTitle)
            .filter { match -> match.groupValues[2] != "卷" }
            .lastOrNull()
        val suffix = if (chapterMatch != null) {
            displayTitle.substring(chapterMatch.range.last + 1)
        } else {
            NUMERIC_CHAPTER_TITLE_PREFIX.replace(displayTitle, "")
        }
        return normalizeHint(
            SEARCH_NOISE_PARENTHESIS.replace(suffix, "")
        )
    }

    private suspend fun bookFingerprintForChapter(chapter: SourceChapter): BookContentFingerprint? {
        val stableBookKey = sourceBookKey(chapter.book)
        bookFingerprintCache[stableBookKey]?.snapshot?.let { return it.takeIf { fingerprint -> fingerprint.usable } }
        SourceEngineBookRoute.bookId(chapter.book).let { routeId ->
            bookFingerprintCache[routeId]?.snapshot?.let { return it.takeIf { fingerprint -> fingerprint.usable } }
        }
        val resolved = loadReadableBookWithTimeout(
            chapter.book,
            detailProbeEngine,
            FINGERPRINT_PROFILE_RESOLVE_TIMEOUT_MS
        ) ?: return null
        return bookFingerprintForResolved(resolved)
    }

    private suspend fun bookFingerprintForResolved(resolved: ResolvedSourceBook): BookContentFingerprint? {
        return bookFingerprintProfileForResolved(resolved)?.snapshot?.takeIf { fingerprint -> fingerprint.usable }
    }

    private suspend fun bookFingerprintProfileForResolved(resolved: ResolvedSourceBook): BookContentFingerprintProfile? {
        val cacheKey = fingerprintCacheKey(resolved)
        cachedFingerprintProfile(resolved, cacheKey)?.let { return it }
        val lock = synchronized(bookFingerprintBuildLocks) {
            bookFingerprintBuildLocks.getOrPut(cacheKey) { Mutex() }
        }
        return lock.withLock {
            cachedFingerprintProfile(resolved, cacheKey)?.let { return@withLock it }
            val trustedUpperExclusive = trustedFingerprintUpperExclusive(resolved.catalog.chapters.size)
            val profile = BookContentFingerprintProfile(bookContentFingerprinter, MAX_FINGERPRINT_PROFILE_CONTENTS)
            withTimeoutOrNull(FINGERPRINT_BUILD_TOTAL_TIMEOUT_MS) {
                val chapters = trustedFingerprintChapters(resolved.catalog.chapters)
                if (chapters.size < MIN_FINGERPRINT_TRUSTED_CHAPTERS) return@withTimeoutOrNull emptyList()
                supervisorScope {
                    val semaphore = Semaphore(MAX_FINGERPRINT_CONCURRENT_CONTENT_PROBES)
                    chapters.map { chapter ->
                        async {
                            semaphore.withPermit {
                                loadCleanContentWithTimeout(
                                    chapter,
                                    FINGERPRINT_CONTENT_TIMEOUT_MS,
                                    fingerprint = null
                                )?.takeIf { content -> isReadableContent(content) }?.cleanedContent
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }?.let { contents -> profile.addTrustedContents(contents) }
            cacheFingerprintProfile(resolved, cacheKey, profile, trustedUpperExclusive)
            Log.i(
                TAG,
                "operation=bookFingerprintBuilt provider=$providerName title=${resolved.detail.name} " +
                    "usable=${profile.snapshot.usable} trusted=${profile.snapshot.trustedChapterCount} " +
                    "characters=${profile.snapshot.characterTerms.size} environments=${profile.snapshot.environmentTerms.size}"
            )
            AiBridgeTrace.state(
                "source_book_fingerprint_built",
                resolved.detail.name,
                fingerprintTraceValue(profile.snapshot)
            )
            profile
        }
    }

    private fun cachedFingerprintProfile(
        resolved: ResolvedSourceBook,
        cacheKey: String = fingerprintCacheKey(resolved)
    ): BookContentFingerprintProfile? {
        val stableBookKey = sourceBookKey(resolved.book)
        val profile = bookFingerprintCache[cacheKey]
            ?: bookFingerprintCache[resolved.routeId]
            ?: bookFingerprintCache[stableBookKey]?.takeIf { cached -> cached.snapshot.usable }
            ?: run {
                val shared = bookFingerprintCache[stableBookKey]
                if (shared != null && !shared.snapshot.usable) {
                    AiBridgeTrace.event(
                        "source_book_fingerprint_cache_ignored",
                        resolved.detail.name,
                        "source_${sourceLabel(resolved.book).debugToken()}" +
                            "_reason_unusable_shared_trusted_${shared.snapshot.trustedChapterCount}"
                    )
                }
                return null
            }
        bookFingerprintCache[cacheKey] = profile
        bookFingerprintCache[resolved.routeId] = profile
        if (profile.snapshot.usable) {
            bookFingerprintCache[stableBookKey] = profile
        }
        val trustedUpperExclusive = bookFingerprintTrustedUpperCache[cacheKey]
            ?: bookFingerprintTrustedUpperCache[resolved.routeId]
            ?: bookFingerprintTrustedUpperCache[stableBookKey]?.takeIf { profile.snapshot.usable }
        if (trustedUpperExclusive != null) {
            bookFingerprintTrustedUpperCache[cacheKey] = trustedUpperExclusive
            bookFingerprintTrustedUpperCache[resolved.routeId] = trustedUpperExclusive
            if (profile.snapshot.usable) {
                bookFingerprintTrustedUpperCache[stableBookKey] = trustedUpperExclusive
            }
        }
        return profile
    }

    private fun cacheFingerprintProfile(
        resolved: ResolvedSourceBook,
        cacheKey: String,
        profile: BookContentFingerprintProfile,
        trustedUpperExclusive: Int
    ) {
        val stableBookKey = sourceBookKey(resolved.book)
        listOf(cacheKey, resolved.routeId).forEach { key ->
            bookFingerprintCache[key] = profile
            bookFingerprintTrustedUpperCache[key] = trustedUpperExclusive
        }
        if (profile.snapshot.usable) {
            bookFingerprintCache[stableBookKey] = profile
            bookFingerprintTrustedUpperCache[stableBookKey] = trustedUpperExclusive
        }
    }

    private fun recordTrustedFingerprintContent(
        chapter: SourceChapter,
        content: CleanContent
    ) {
        if (!isReadableContent(content)) return
        val routeId = SourceEngineBookRoute.bookId(chapter.book)
        val stableBookKey = sourceBookKey(chapter.book)
        val profile = bookFingerprintCache[routeId]
            ?: bookFingerprintCache[stableBookKey]?.takeIf { cached -> cached.snapshot.usable }
            ?: return
        val trustedUpperExclusive = bookFingerprintTrustedUpperCache[routeId]
            ?: bookFingerprintTrustedUpperCache[stableBookKey]?.takeIf { profile.snapshot.usable }
            ?: return
        if (chapter.index >= trustedUpperExclusive) return
        val before = profile.snapshot
        val after = profile.addTrustedContent(content.cleanedContent)
        if (after.usable) {
            bookFingerprintCache[stableBookKey] = profile
            bookFingerprintTrustedUpperCache[stableBookKey] = trustedUpperExclusive
        }
        if (after.trustedChapterCount != before.trustedChapterCount) {
            Log.i(
                TAG,
                "operation=bookFingerprintUpdated provider=$providerName title=${chapter.book.name} " +
                    "chapter=${chapter.name} trusted=${after.trustedChapterCount} " +
                    "characters=${after.characterTerms.size} environments=${after.environmentTerms.size}"
            )
            AiBridgeTrace.event(
                "source_book_fingerprint_updated",
                chapter.book.name,
                "chapter_${chapter.name.debugToken()}_trusted_${after.trustedChapterCount}" +
                    "_characters_${after.characterTerms.size}_environments_${after.environmentTerms.size}"
            )
            AiBridgeTrace.state(
                "source_book_fingerprint_snapshot",
                chapter.book.name,
                fingerprintTraceValue(after)
            )
        }
    }

    private fun fingerprintTraceValue(fingerprint: BookContentFingerprint): String {
        val characters = fingerprint.characterProfiles.take(8).joinToString(",") { term ->
            "${term.term}:${term.category}:${term.chapterHitCount}/${term.totalHitCount}/${term.weight}"
        }.debugToken()
        val environments = fingerprint.environmentProfiles.take(10).joinToString(",") { term ->
            "${term.term}:${term.category}:${term.chapterHitCount}/${term.totalHitCount}/${term.weight}"
        }.debugToken()
        return "usable_${fingerprint.usable}_trusted_${fingerprint.trustedChapterCount}" +
            "_characters_${fingerprint.characterTerms.size}_environments_${fingerprint.environmentTerms.size}" +
            "_topCharacters_$characters" +
            "_topEnvironments_$environments"
    }

    private fun trustedFingerprintChapters(chapters: List<CanonicalChapter>): List<SourceChapter> {
        if (chapters.isEmpty()) return emptyList()
        val trustedUpperExclusive = trustedFingerprintUpperExclusive(chapters.size)
        if (trustedUpperExclusive <= 0) return emptyList()
        val indexes = linkedSetOf<Int>()
        repeat(minOf(FINGERPRINT_TRUSTED_HEAD_CHAPTERS, trustedUpperExclusive)) { index ->
            indexes.add(index)
        }
        val fractions = listOf(0.10, 0.20, 0.35, 0.50, 0.65, 0.80, 0.90)
        fractions.forEach { fraction ->
            val index = (trustedUpperExclusive * fraction).toInt().coerceIn(0, trustedUpperExclusive - 1)
            indexes.add(index)
        }
        if (trustedUpperExclusive > FINGERPRINT_TRUSTED_HEAD_CHAPTERS) {
            indexes.add((trustedUpperExclusive - 1).coerceAtLeast(0))
        }
        return indexes
            .sorted()
            .take(MAX_FINGERPRINT_TRUSTED_CHAPTERS)
            .mapNotNull { index -> chapters.getOrNull(index)?.sourceChapters?.firstOrNull() }
    }

    private fun trustedFingerprintUpperExclusive(chapterCount: Int): Int {
        val excludedTail = when {
            chapterCount >= 100 -> FINGERPRINT_EXCLUDED_TAIL_CHAPTERS
            chapterCount >= 30 -> maxOf(FINGERPRINT_MIN_EXCLUDED_TAIL_CHAPTERS, chapterCount / 4)
            else -> FINGERPRINT_MIN_EXCLUDED_TAIL_CHAPTERS
        }.coerceAtMost(chapterCount)
        return (chapterCount - excludedTail).coerceAtLeast(0)
    }

    private fun fingerprintCacheKey(resolved: ResolvedSourceBook): String {
        return listOf(
            sourceBookKey(resolved.book),
            resolved.catalog.chapters.size.toString(),
            resolved.catalog.chapters.firstOrNull()?.displayTitle.orEmpty(),
            resolved.catalog.chapters.lastOrNull()?.displayTitle.orEmpty()
        ).joinToString("\n")
    }

    private suspend fun tailReadableChapters(
        resolved: ResolvedSourceBook
    ): List<CanonicalChapter> {
        val chapters = resolved.catalog.chapters
        if (chapters.isEmpty()) return chapters
        val cacheKey = tailTrimCacheKey(resolved)
        catalogTailTrimCache[cacheKey]?.let { keepUntil ->
            return chapters.take(keepUntil.coerceIn(0, chapters.size))
        }
        val lock = synchronized(catalogTailTrimLocks) {
            catalogTailTrimLocks.getOrPut(cacheKey) { Mutex() }
        }
        return lock.withLock {
            catalogTailTrimCache[cacheKey]?.let { keepUntil ->
                return@withLock chapters.take(keepUntil.coerceIn(0, chapters.size))
            }

            val fingerprint = bookFingerprintForResolved(resolved)
            val probe = withTimeoutOrNull(CATALOG_TAIL_TOTAL_TIMEOUT_MS) {
                locateCatalogTailBoundary(chapters, fingerprint)
            } ?: CatalogTailProbeResult(
                keepUntil = chapters.size,
                checkedCount = 0,
                method = "timeout"
            )
            val keepUntil = probe.keepUntil.coerceIn(0, chapters.size)

            if (keepUntil < chapters.size) {
                catalogTailTrimCache[cacheKey] = keepUntil
                sourceQualityRouter.recordCatalogTailTrimmed(resolved.book, keepUntil, chapters.size)
                traceTrimmedTailSamples(chapters, keepUntil, fingerprint)
                Log.w(
                    TAG,
                    "operation=catalogTailTrimmed provider=$providerName title=${resolved.detail.name} " +
                        "rawChapters=${chapters.size} kept=$keepUntil removed=${chapters.size - keepUntil} " +
                        "checked=${probe.checkedCount} method=${probe.method} " +
                        "lastKept=${chapters.getOrNull(keepUntil - 1)?.displayTitle} " +
                        "firstRemoved=${chapters.getOrNull(keepUntil)?.displayTitle}"
                )
                AiBridgeTrace.event(
                    "source_catalog_tail_trimmed",
                    resolved.detail.name,
                    "raw_${chapters.size}_kept_${keepUntil}_removed_${chapters.size - keepUntil}" +
                        "_method_${probe.method.debugToken()}_last_${chapters.getOrNull(keepUntil - 1)?.displayTitle.orEmpty().debugToken()}" +
                        "_firstRemoved_${chapters.getOrNull(keepUntil)?.displayTitle.orEmpty().debugToken()}"
                )
            } else {
                catalogTailTrimCache[cacheKey] = chapters.size
                sourceQualityRouter.recordCatalogResolved(resolved.book, chapters.size, chapters.size)
            }
            chapters.take(keepUntil)
        }
    }

    private suspend fun tailDisplayChapters(
        resolved: ResolvedSourceBook,
        waterfall: BookContentWaterfall
    ): List<CanonicalChapter> {
        val chapters = resolved.catalog.chapters
        if (chapters.isEmpty()) return chapters
        val cacheKey = tailTrimCacheKey(resolved) + "\ndisplay-trusted"
        catalogTailTrimCache[cacheKey]?.let { keepUntil ->
            return chapters.take(keepUntil.coerceIn(0, chapters.size))
        }
        val lock = synchronized(catalogTailTrimLocks) {
            catalogTailTrimLocks.getOrPut(cacheKey) { Mutex() }
        }
        return lock.withLock {
            catalogTailTrimCache[cacheKey]?.let { keepUntil ->
                return@withLock chapters.take(keepUntil.coerceIn(0, chapters.size))
            }
            val fingerprint = bookFingerprintForResolved(resolved)
            val probe = withTimeoutOrNull(CATALOG_TAIL_TOTAL_TIMEOUT_MS) {
                locateDisplayCatalogTailBoundary(chapters, waterfall, fingerprint)
            } ?: CatalogTailProbeResult(
                keepUntil = chapters.size,
                checkedCount = 0,
                method = "timeout"
            )
            val keepUntil = probe.keepUntil.coerceIn(0, chapters.size)
            catalogTailTrimCache[cacheKey] = keepUntil
            if (keepUntil < chapters.size) {
                Log.w(
                    TAG,
                    "operation=catalogDisplayTailTrimmed provider=$providerName title=${resolved.detail.name} " +
                        "rawChapters=${chapters.size} kept=$keepUntil removed=${chapters.size - keepUntil} " +
                        "checked=${probe.checkedCount} method=${probe.method} " +
                        "lastKept=${chapters.getOrNull(keepUntil - 1)?.displayTitle} " +
                        "firstRemoved=${chapters.getOrNull(keepUntil)?.displayTitle}"
                )
                AiBridgeTrace.event(
                    "source_catalog_display_tail_trimmed",
                    resolved.detail.name,
                    "raw_${chapters.size}_kept_${keepUntil}_removed_${chapters.size - keepUntil}" +
                        "_method_${probe.method.debugToken()}_last_${chapters.getOrNull(keepUntil - 1)?.displayTitle.orEmpty().debugToken()}" +
                        "_firstRemoved_${chapters.getOrNull(keepUntil)?.displayTitle.orEmpty().debugToken()}"
                )
            }
            chapters.take(keepUntil)
        }
    }

    private suspend fun traceTrimmedTailSamples(
        chapters: List<CanonicalChapter>,
        keepUntil: Int,
        fingerprint: BookContentFingerprint?
    ) {
        val start = (keepUntil + 1).coerceAtMost(chapters.size)
        chapters
            .subList(start, minOf(chapters.size, start + MAX_CATALOG_TAIL_REJECTION_TRACE_CHAPTERS))
            .forEach { canonical ->
                val chapter = canonical.sourceChapters.firstOrNull() ?: return@forEach
                val content = loadCleanContentWithTimeout(chapter, CATALOG_TAIL_CONTENT_TIMEOUT_MS, fingerprint)
                if (content == null) {
                    AiBridgeTrace.event(
                        "source_catalog_tail_probe_rejected",
                        chapter.book.name,
                        "chapter_${chapter.name.debugToken()}_content_null_fingerprint_${fingerprint != null}"
                    )
                } else if (!isReadableContent(content)) {
                    traceTailProbeRejected(chapter, content, fingerprint)
                } else {
                    AiBridgeTrace.event(
                        "source_catalog_tail_probe_sample_readable",
                        chapter.book.name,
                        "chapter_${chapter.name.debugToken()}_score_${content.report.qualityScore}" +
                            "_coherence_${content.report.coherenceScore}_fingerprint_${fingerprint != null}"
                    )
                }
            }
    }

    private suspend fun locateCatalogTailBoundary(
        chapters: List<CanonicalChapter>,
        fingerprint: BookContentFingerprint?
    ): CatalogTailProbeResult {
        val lengthSamples = mutableMapOf<Int, Int?>()
        return tailBoundaryLocator.locate(chapters.size) { index ->
            val sourceChapter = chapters[index].sourceChapters.firstOrNull()
            if (sourceChapter == null) {
                false
            } else {
                val content = loadCleanContentWithTimeout(sourceChapter, CATALOG_TAIL_CONTENT_TIMEOUT_MS, fingerprint)
                val readable = content?.let { isReadableContent(it) } == true
                if (content != null && !readable) {
                    traceTailProbeRejected(sourceChapter, content, fingerprint)
                }
                if (!readable || content == null) {
                    lengthSamples[index] = null
                    false
                } else {
                    val previousLengths = tailAverageLengthSamplesBefore(index, chapters, fingerprint, lengthSamples)
                    val tooShort = isTailChapterTooShortAgainstAverage(content.report.cleanedLength, previousLengths)
                    if (tooShort) {
                        traceTailProbeTooShort(sourceChapter, content, previousLengths)
                        lengthSamples[index] = null
                        false
                    } else {
                        lengthSamples[index] = content.report.cleanedLength
                        true
                    }
                }
            }
        }
    }

    private suspend fun locateDisplayCatalogTailBoundary(
        chapters: List<CanonicalChapter>,
        waterfall: BookContentWaterfall,
        fingerprint: BookContentFingerprint?
    ): CatalogTailProbeResult {
        val lengthSamples = mutableMapOf<Int, Int?>()
        return tailBoundaryLocator.locate(chapters.size) { index ->
            val sourceChapter = chapters[index].sourceChapters.firstOrNull()
            if (sourceChapter == null) {
                false
            } else {
                val content = loadCleanContentWithTimeout(sourceChapter, CATALOG_TAIL_CONTENT_TIMEOUT_MS, fingerprint)
                val readable = content?.let { isReadableContent(it) } == true
                if (!readable || content == null) {
                    if (content != null) traceTailProbeRejected(sourceChapter, content, fingerprint)
                    lengthSamples[index] = null
                    false
                } else {
                    val targetTitle = chapterNormalizer.normalize(sourceChapter.name)
                    val trusted = readFromBookContentTier(
                        waterfall,
                        sourceChapter,
                        targetTitle,
                        setOf(sourceBookKey(sourceChapter.book)),
                        CONTENT_FALLBACK_SUCCESS_LIMIT
                    ).ifEmpty {
                        readFromContentCandidates(
                            waterfall,
                            sourceChapter,
                            targetTitle,
                            setOf(sourceBookKey(sourceChapter.book)),
                            CONTENT_FALLBACK_SUCCESS_LIMIT
                        )
                    }
                    if (trusted.isEmpty()) {
                        traceTailProbeNoMatchingTrustedChapter(sourceChapter)
                        lengthSamples[index] = null
                        false
                    } else {
                        val previousLengths = tailAverageLengthSamplesBefore(index, chapters, fingerprint, lengthSamples)
                        val tooShort = isTailChapterTooShortAgainstAverage(content.report.cleanedLength, previousLengths)
                        if (tooShort) {
                            traceTailProbeTooShort(sourceChapter, content, previousLengths)
                            lengthSamples[index] = null
                            false
                        } else {
                            lengthSamples[index] = content.report.cleanedLength
                            true
                        }
                    }
                }
            }
        }
    }

    private suspend fun tailAverageLengthSamplesBefore(
        index: Int,
        chapters: List<CanonicalChapter>,
        fingerprint: BookContentFingerprint?,
        lengthSamples: MutableMap<Int, Int?>
    ): List<Int> {
        val start = (index - CATALOG_TAIL_LENGTH_AVERAGE_LOOKBACK_CHAPTERS).coerceAtLeast(0)
        return (start until index).mapNotNull { sampleIndex ->
            if (!lengthSamples.containsKey(sampleIndex)) {
                val chapter = chapters.getOrNull(sampleIndex)?.sourceChapters?.firstOrNull()
                val length = chapter
                    ?.let { loadCleanContentWithTimeout(it, CATALOG_TAIL_CONTENT_TIMEOUT_MS, fingerprint) }
                    ?.takeIf { content -> isReadableContent(content) }
                    ?.report
                    ?.cleanedLength
                lengthSamples[sampleIndex] = length
            }
            lengthSamples[sampleIndex]
        }
    }

    internal fun isTailChapterTooShortAgainstAverage(
        cleanedLength: Int,
        previousCleanedLengths: List<Int>
    ): Boolean {
        if (previousCleanedLengths.size < MIN_CATALOG_TAIL_LENGTH_AVERAGE_SAMPLES) return false
        val averageLength = previousCleanedLengths.average()
        return cleanedLength * CATALOG_TAIL_SHORT_LENGTH_DIVISOR <= averageLength
    }

    private fun traceTailProbeRejected(
        chapter: SourceChapter,
        content: CleanContent,
        fingerprint: BookContentFingerprint?
    ) {
        val markers = content.report.coherenceMarkers.joinToString().ifBlank { "none" }
        Log.w(
            TAG,
            "operation=catalogTailProbeRejected provider=$providerName title=${chapter.book.name} " +
                "chapter=${chapter.name} source=${sourceLabel(chapter.book)} url=${chapter.chapterUrl} " +
                "score=${content.report.qualityScore} " +
                "coherence=${content.report.coherenceScore} cleaned=${content.report.cleanedLength} " +
                "fingerprint=${fingerprint != null} markers=$markers"
        )
        AiBridgeTrace.event(
            "source_catalog_tail_probe_rejected",
            chapter.book.name,
            "chapter_${chapter.name.debugToken()}_score_${content.report.qualityScore}" +
            "_coherence_${content.report.coherenceScore}_cleaned_${content.report.cleanedLength}" +
                "_fingerprint_${fingerprint != null}_source_${sourceLabel(chapter.book).debugToken()}" +
                "_url_${chapter.chapterUrl.debugToken()}_markers_${markers.debugToken()}"
        )
    }

    private fun traceTailProbeTooShort(
        chapter: SourceChapter,
        content: CleanContent,
        previousCleanedLengths: List<Int>
    ) {
        val averageLength = previousCleanedLengths.average().toInt()
        Log.w(
            TAG,
            "operation=catalogTailProbeRejected provider=$providerName title=${chapter.book.name} " +
                "chapter=${chapter.name} score=${content.report.qualityScore} " +
                "coherence=${content.report.coherenceScore} cleaned=${content.report.cleanedLength} " +
                "average=$averageLength markers=tail-average-body-too-short"
        )
        AiBridgeTrace.event(
            "source_catalog_tail_probe_rejected",
            chapter.book.name,
            "chapter_${chapter.name.debugToken()}_score_${content.report.qualityScore}" +
                "_coherence_${content.report.coherenceScore}_cleaned_${content.report.cleanedLength}" +
                "_average_${averageLength}_markers_tail-average-body-too-short"
        )
    }

    private fun traceTailProbeNoMatchingTrustedChapter(chapter: SourceChapter) {
        Log.w(
            TAG,
            "operation=catalogTailProbeRejected provider=$providerName title=${chapter.book.name} " +
                "chapter=${chapter.name} source=${sourceLabel(chapter.book)} url=${chapter.chapterUrl} " +
                "markers=no-matching-trusted-chapter"
        )
        AiBridgeTrace.event(
            "source_catalog_tail_probe_rejected",
            chapter.book.name,
            "chapter_${chapter.name.debugToken()}_source_${sourceLabel(chapter.book).debugToken()}" +
                "_url_${chapter.chapterUrl.debugToken()}_markers_no-matching-trusted-chapter"
        )
    }

    private fun tailTrimCacheKey(resolved: ResolvedSourceBook): String {
        val chapters = resolved.catalog.chapters
        return listOf(
            sourceBookKey(resolved.book),
            chapters.size.toString(),
            chapters.firstOrNull()?.displayTitle.orEmpty(),
            chapters.lastOrNull()?.displayTitle.orEmpty()
        ).joinToString("\n")
    }

    private suspend fun loadCleanContentWithTimeout(
        chapter: SourceChapter,
        timeoutMs: Long,
        fingerprint: BookContentFingerprint? = null
    ): CleanContent? {
        return runDetachedWithTimeout(timeoutMs) {
            when (val value = engine.getCleanContent(chapter, bookFingerprint = fingerprint)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> null
            }
        }
    }

    internal suspend fun <T : Any> awaitFinishedValuesWithin(
        deferreds: List<Deferred<T?>>,
        timeoutMs: Long
    ): List<T> {
        if (deferreds.isEmpty()) return emptyList()
        val values = Collections.synchronizedList(ArrayList<T>())
        try {
            withTimeoutOrNull(timeoutMs) {
                supervisorScope {
                    deferreds.map { deferred ->
                        async {
                            try {
                                deferred.await()?.let { value -> values.add(value) }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            deferreds.forEach { deferred ->
                if (!deferred.isCompleted) deferred.cancel()
            }
        }
        return synchronized(values) { values.toList() }
    }

    internal suspend fun <T : Any> awaitFinishedValuesWithinLimit(
        deferreds: List<Deferred<T?>>,
        timeoutMs: Long,
        limit: Int
    ): List<T> {
        if (deferreds.isEmpty() || limit <= 0) return emptyList()
        val values = ArrayList<T>(limit)
        withTimeoutOrNull(timeoutMs) {
            supervisorScope {
                val watchers = deferreds.map { deferred ->
                    async {
                        try {
                            deferred.await()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }.toMutableList()
                try {
                    while (watchers.isNotEmpty() && values.size < limit) {
                        val result = select<Pair<Deferred<T?>, T?>> {
                            watchers.forEach { watcher ->
                                watcher.onAwait { value -> watcher to value }
                            }
                        }
                        watchers.remove(result.first)
                        result.second?.let { value -> values.add(value) }
                    }
                    if (values.size >= limit) {
                        deferreds.forEach { deferred ->
                            if (!deferred.isCompleted) deferred.cancel()
                        }
                    }
                } finally {
                    watchers.forEach { watcher ->
                        if (!watcher.isCompleted) watcher.cancel()
                    }
                    deferreds.forEach { deferred ->
                        if (!deferred.isCompleted) deferred.cancel()
                    }
                }
            }
        }
        return values.toList()
    }

    private data class ValidatedSearchCandidate(
        val ranked: RankedSearchBook,
        val book: SourceBook,
        val score: Int,
        val chapterCount: Int,
        val freshnessHint: Int,
        val duplicateCount: Int,
        val coverQuality: CoverQuality,
        val authorConsensus: Int,
        val validation: String,
        val resolved: ResolvedSourceBook?,
        val pageCatalog: Boolean
    ) {
        fun searchChapterSignal(): Int {
            return maxOf(chapterCount, freshnessHint)
        }

        fun debugLabel(): String {
                return "${book.name}/${book.author}/${book.source.sourceName}" +
                "/score=$score/sources=${ranked.sourceCount}/authorConsensus=$authorConsensus" +
                "/chapters=$chapterCount/hint=$freshnessHint" +
                "/cover=${coverQuality.usable}(${coverQuality.width}x${coverQuality.height},${coverQuality.reason})" +
                "/coverUrl=${book.coverUrl}/$validation" +
                "/pageCatalog=$pageCatalog"
        }
    }

    private data class SearchSourceRequestTrace(
        val source: String,
        val sourceIndex: Int,
        val query: String,
        val durationMs: Long,
        val success: Boolean,
        val resultCount: Int,
        val acceptedCount: Int,
        val firstAccepted: String
    ) {
        fun debugValue(): String {
            return "source_${token(source)}" +
                "_idx_${sourceIndex}" +
                "_query_${token(query)}" +
                "_durationMs_${durationMs}" +
                "_success_${success}" +
                "_results_${resultCount}" +
                "_accepted_${acceptedCount}" +
                "_first_${token(firstAccepted)}"
        }

        private fun token(value: String): String {
            return value.replace(Regex("""[\s=:/\\#]+"""), "_").take(180)
        }
    }

    private data class ReadingCandidateSignal(
        val candidate: ValidatedSearchCandidate,
        val readableChapterCount: Int,
        val lastReadableOrdinal: Int,
        val tailOrdinalGapCount: Int,
        val tailContinuityScore: Int
    )

    private data class CoverQuality(
        val usable: Boolean,
        val width: Int,
        val height: Int,
        val reason: String
    )

    private data class ResolvedSourceBook(
        val book: SourceBook,
        val detail: SourceBookDetail,
        val catalog: CanonicalChapterList,
        val routeId: String
    )

    private data class ReadableResolvedSourceBook(
        val order: Int,
        val resolved: ResolvedSourceBook,
        val readableChapterCount: Int,
        val lastReadableOrdinal: Int,
        val tailOrdinalGapCount: Int,
        val tailContinuityScore: Int
    )

    private data class DetailPreviewResolved(
        val resolved: ResolvedSourceBook,
        val mode: String
    )

    private sealed class DetailResolutionOutcome {
        data class Direct(val value: ReadableResolvedSourceBook?) : DetailResolutionOutcome()
        data class Fallback(val value: ReadableResolvedSourceBook?) : DetailResolutionOutcome()
    }

    private data class ContentFallback(
        val order: Int,
        val resolved: ResolvedSourceBook,
        val chapter: SourceChapter,
        val content: CleanContent
    )

    private data class TrustedChapterContent(
        val order: Int,
        val resolved: ResolvedSourceBook,
        val chapter: SourceChapter,
        val content: CleanContent
    )

    private data class BookContentWaterfall(
        val sourceBook: SourceBook,
        val candidates: MutableList<RankedSearchBook>,
        val resolvedBooks: MutableMap<String, ResolvedSourceBook> = Collections.synchronizedMap(mutableMapOf()),
        val verifiedBooks: MutableList<ResolvedSourceBook> = Collections.synchronizedList(ArrayList()),
        val failedBooks: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    )

    private data class CoverFallback(
        val order: Int,
        val ranked: RankedSearchBook,
        val coverUrl: String
    )

    companion object {
        private const val TAG = "BookContentProvider"
        private const val MAX_SEARCH_SOURCES = 400
        private const val MAX_SEARCH_RESULTS = 30
        private const val MAX_RESULTS_PER_SOURCE = 8
        private const val MAX_KEYWORD_SUGGESTIONS = 8
        private const val MIN_COMPLETION_QUERY_CHARS = 2
        private const val LONG_TITLE_PREFIX_QUERY_MIN_CHARS = 5
        private const val SHORT_PREFIX_QUERY_CHARS = 2
        private const val MAX_CONCURRENT_SEARCHES = 64
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val SEARCH_PROGRESSIVE_TOTAL_TIMEOUT_MS = 180_000L
        private const val SEARCH_PROGRESS_POLL_INTERVAL_MS = 500L
        private const val SEARCH_PROGRESS_RANK_TIMEOUT_MS = 30_000L
        private const val SEARCH_PROGRESS_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS = 10_000L
        private const val SEARCH_PROGRESS_EXACT_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS = 25_000L
        private const val SEARCH_PROGRESS_RESULT_TARGET = 1
        private const val FIRST_PROGRESS_MIN_CANDIDATES = 2
        private const val MIN_PROGRESSIVE_FIRST_DISPLAY_CHAPTERS = 100
        private const val MIN_EXACT_GROUP_ONLY_QUERY_CHARS = 4
        private const val MIN_PAGE_CATALOG_CHAPTERS = 20
        private const val MIN_PAGE_CATALOG_PERCENT = 80
        private const val DETAIL_PREVIEW_TIMEOUT_MS = 8_000L
        private const val DETAIL_DIRECT_TIMEOUT_MS = 20_000L
        private const val DETAIL_PROBE_TIMEOUT_MS = 15_000L
        private const val DETAIL_FALLBACK_SEARCH_TIMEOUT_MS = 25_000L
        private const val DETAIL_FALLBACK_PROBE_TIMEOUT_MS = 35_000L
        private const val DETAIL_FALLBACK_TAIL_RANK_TIMEOUT_MS = 90_000L
        private const val MAX_DETAIL_FALLBACK_SOURCES = 10_000
        private const val MAX_DETAIL_FALLBACK_CANDIDATES = 32
        private const val MAX_DETAIL_FALLBACK_RESULTS_PER_SOURCE = 16
        private const val MAX_DETAIL_FALLBACK_CONCURRENT_SEARCHES = 48
        private const val MAX_DETAIL_FALLBACK_CONCURRENT_PROBES = 32
        private const val MAX_DETAIL_FALLBACK_TAIL_RANK_CONCURRENT_PROBES = 8
        private const val MAX_DETAIL_FALLBACK_EARLY_TAIL_RANK_CANDIDATES = 3
        private const val MAX_CONTENT_FALLBACK_CONCURRENT_PROBES = 5
        private const val FIRST_DISPLAY_TRUSTED_SOURCE_COUNT = 2
        private const val FIRST_DISPLAY_TIER_FILL_TIMEOUT_MS = 30_000L
        private const val BOOK_CONTENT_TIER_TARGET_SIZE = 5
        private const val BOOK_CONTENT_TIER_FILL_TIMEOUT_MS = 45_000L
        private const val BOOK_CONTENT_TIER_FILL_BATCH_SIZE = 32
        private const val CONTENT_FALLBACK_BATCH_SIZE = 32
        private const val CONTENT_FALLBACK_SUCCESS_LIMIT = 1
        private const val CONTENT_FALLBACK_DETAIL_TIMEOUT_MS = 15_000L
        private const val CONTENT_FALLBACK_CONTENT_TIMEOUT_MS = 15_000L
        private const val CONTENT_FALLBACK_TOTAL_TIMEOUT_MS = 45_000L
        private const val MAX_COVER_FALLBACK_CANDIDATES = 24
        private const val MAX_COVER_FALLBACK_CONCURRENT_PROBES = 16
        private const val COVER_FALLBACK_DETAIL_TIMEOUT_MS = 10_000L
        private const val COVER_FALLBACK_TOTAL_TIMEOUT_MS = 20_000L
        private const val MAX_SEARCH_COVER_FILL_RESULTS = 4
        private const val MAX_SEARCH_COVER_FILL_CONCURRENT = 4
        private const val SEARCH_COVER_FILL_ITEM_TIMEOUT_MS = 10_000L
        private const val SEARCH_COVER_FILL_TOTAL_TIMEOUT_MS = 15_000L
        private const val MAX_BACKGROUND_COVER_REFRESH_RESULTS = 8
        private const val BACKGROUND_COVER_REFRESH_ITEM_TIMEOUT_MS = 10_000L
        private const val MAX_CATALOG_TAIL_BACKTRACK_CHAPTERS = 2048
        private const val CATALOG_TAIL_CONTENT_TIMEOUT_MS = 15_000L
        private const val CATALOG_TAIL_TOTAL_TIMEOUT_MS = 180_000L
        private const val MAX_CATALOG_TAIL_REJECTION_TRACE_CHAPTERS = 2
        private const val CATALOG_TAIL_LENGTH_AVERAGE_LOOKBACK_CHAPTERS = 4
        private const val MIN_CATALOG_TAIL_LENGTH_AVERAGE_SAMPLES = 2
        private const val CATALOG_TAIL_SHORT_LENGTH_DIVISOR = 4
        private const val MIN_FINGERPRINT_TRUSTED_CHAPTERS = 2
        private const val FINGERPRINT_TRUSTED_HEAD_CHAPTERS = 6
        private const val MAX_FINGERPRINT_TRUSTED_CHAPTERS = 16
        private const val FINGERPRINT_EXCLUDED_TAIL_CHAPTERS = 50
        private const val FINGERPRINT_MIN_EXCLUDED_TAIL_CHAPTERS = 3
        private const val MAX_FINGERPRINT_PROFILE_CONTENTS = 64
        private const val MAX_FINGERPRINT_CONCURRENT_CONTENT_PROBES = 8
        private const val FINGERPRINT_CONTENT_TIMEOUT_MS = 15_000L
        private const val FINGERPRINT_BUILD_TOTAL_TIMEOUT_MS = 60_000L
        private const val FINGERPRINT_PROFILE_RESOLVE_TIMEOUT_MS = 15_000L
        private const val READABLE_TAIL_CONTINUITY_WINDOW = 80
        private const val READABLE_TAIL_GAP_PENALTY_ORDINALS = 20
        private const val MIN_READABLE_CATALOG_CHAPTERS = 5
        private const val PREFERRED_CATALOG_CHAPTERS = 500
        private const val MAX_PREFERRED_TAIL_TRIM_CHAPTERS = 40
        private const val MIN_PREFERRED_READABLE_PERCENT = 92
        private const val MAX_VALIDATION_TITLE_GROUPS = 12
        private const val MAX_PROGRESS_VALIDATION_TITLE_GROUPS = 8
        private const val MAX_VALIDATION_PER_TITLE = 5
        private const val MAX_VALIDATION_PRIORITY_PER_TITLE = 12
        private const val MAX_VALIDATION_COVER_FALLBACK_PER_TITLE = 3
        private const val MAX_SEARCH_VALIDATION_CANDIDATES_PER_TITLE = 16
        private const val MAX_PROGRESS_SEARCH_VALIDATION_CANDIDATES_PER_TITLE = 6
        private const val MAX_CONCURRENT_VALIDATIONS = 24
        private const val SEARCH_VALIDATION_TIMEOUT_MS = 20_000L
        private const val SEARCH_TITLE_GROUP_VALIDATION_TIMEOUT_MS = 30_000L
        private const val SEARCH_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS = 45_000L
        private const val SEARCH_TAIL_CONTENT_TIMEOUT_MS = 5_000L
        private const val SEARCH_READABLE_TAIL_BONUS = 180
        private const val SEARCH_UNREADABLE_TAIL_PENALTY = 1_200
        private const val UNVALIDATED_RESULT_PENALTY = 300
        private const val DETAIL_FAILURE_PENALTY = 600
        private const val COVER_PRESENT_BONUS = 120
        private const val MISSING_COVER_PENALTY = -80
        private const val SHORT_CATALOG_PENALTY = -1_000
        private const val MISSING_CATALOG_PENALTY = -1_200
        private const val DETAIL_TITLE_MISMATCH_PENALTY = -800
        private const val MIN_CLEAN_CONTENT_CHARS = 200
        private const val MIN_CONTENT_QUALITY_SCORE = 70
        private const val MIN_CONTENT_COHERENCE_SCORE = 70
        private const val MIN_COVER_WIDTH = 80
        private const val MIN_COVER_HEIGHT = 100
        private const val MIN_COVER_RATIO = 0.45f
        private const val MAX_COVER_RATIO = 0.85f
        private const val MAX_SOURCE_SCORE = 10_000
        private const val SOURCE_ENGINE_TIER_FILE_NAME = ".source_engine_content_tier"
        private const val COVER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"
        private val CHAPTER_TITLE_ORDINAL_PATTERN =
            Regex("""第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*([章节回话卷])""")
        private val NUMERIC_CHAPTER_TITLE_PREFIX =
            Regex("""^\s*(?:chapter|chap\.?|ch\.?)?\s*[0-9０-９]+\s*[.、\-\s]*""", RegexOption.IGNORE_CASE)
        private val PAGE_CATALOG_TITLE_PATTERN =
            Regex("""^\s*(?:第\s*)?[0-9０-９]+\s*页\s*$""")
        private val SEARCH_NOISE_PARENTHESIS =
            Regex("""[（(][^（）()]{0,30}(推荐票|月票|求票|求推荐|第一更|第二更|第三更)[^（）()]{0,30}[）)]""")
        private val coverHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        private val rankedSearchComparator = compareByDescending<RankedSearchBook> { it.score }
            .thenBy { it.sourceIndex }
            .thenBy { it.resultIndex }
            .thenBy { it.book.name.length }
            .thenBy { it.book.name }
        private val validatedSearchComparator = compareByDescending<ValidatedSearchCandidate> { it.score }
            .thenByDescending { it.chapterCount }
            .thenBy { if (it.coverQuality.usable) 0 else 1 }
            .thenBy { it.ranked.sourceIndex }
            .thenBy { it.ranked.resultIndex }
            .thenBy { it.book.name.length }
            .thenBy { it.book.name }
        private val progressReadingCandidateComparator =
            compareByDescending<ValidatedSearchCandidate> { it.authorConsensus }
                .thenByDescending { it.chapterCount }
                .thenByDescending { it.freshnessHint }
                .thenByDescending { it.score }
                .thenBy { if (it.coverQuality.usable) 0 else 1 }
                .thenBy { it.ranked.sourceIndex }
                .thenBy { it.ranked.resultIndex }
                .thenBy { it.book.name.length }
                .thenBy { it.book.name }
        private const val AUTHOR_CONSENSUS_WEIGHT = 10
        private val readingCandidateComparator = compareByDescending<ValidatedSearchCandidate> { it.authorConsensus }
            .thenByDescending { it.chapterCount }
            .thenByDescending { it.freshnessHint }
            .thenByDescending { it.score }
            .thenBy { it.ranked.sourceIndex }
            .thenBy { it.ranked.resultIndex }
        private val readingCandidateSignalComparator = compareByDescending<ReadingCandidateSignal> {
            it.candidate.authorConsensus
        }.thenByDescending {
            it.tailContinuityScore
        }.thenByDescending {
            it.lastReadableOrdinal
        }.thenBy {
            it.tailOrdinalGapCount
        }.thenByDescending {
            it.readableChapterCount
        }.thenByDescending {
            it.candidate.chapterCount
        }.thenByDescending {
            it.candidate.freshnessHint
        }.thenByDescending {
            it.candidate.score
        }.thenBy {
            it.candidate.ranked.sourceIndex
        }.thenBy {
            it.candidate.ranked.resultIndex
        }
        private val coverCandidateComparator = compareByDescending<ValidatedSearchCandidate> {
            it.score
        }.thenBy { it.ranked.sourceIndex }
            .thenBy { it.ranked.resultIndex }
        private val contentFallbackComparator = compareByDescending<ContentFallback> {
            it.content.report.qualityScore
        }.thenByDescending {
            it.content.report.cleanedLength
        }.thenBy {
            it.order
        }
        private val trustedChapterContentComparator = compareByDescending<TrustedChapterContent> {
            it.content.report.qualityScore
        }.thenByDescending {
            it.content.report.cleanedLength
        }.thenBy {
            it.order
        }
        private val CHAPTER_HINT_PATTERNS = listOf(
            Regex("""第\s*(\d{1,5})\s*[章节回]""", RegexOption.IGNORE_CASE),
            Regex("""(?:chapter|chap\.?|ch\.?)\s*(\d{1,5})""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,5})\s*(?:chapter|chap\.?|ch\.?)""", RegexOption.IGNORE_CASE)
        )

        private val DEFAULT_HOT_WORDS = listOf(
            "斗破苍穹",
            "诡秘之主",
            "凡人修仙传",
            "遮天",
            "完美世界",
            "牧神记",
            "大奉打更人",
            "剑来",
            "雪中悍刀行",
            "庆余年",
            "将夜",
            "择天记",
            "全职高手",
            "盗墓笔记",
            "鬼吹灯",
            "斗罗大陆",
            "神印王座",
            "星辰变",
            "吞噬星空",
            "盘龙",
            "武动乾坤",
            "元尊",
            "大主宰",
            "一念永恒",
            "仙逆",
            "十日终焉",
            "我不是戏神",
            "我在精神病院学斩神",
            "灵境行者",
            "宿命之环",
            "万相之王",
            "夜无疆",
            "光阴之外",
            "谁让他修仙的",
            "天蚕土豆",
            "辰东",
            "猫腻",
            "烽火戏诸侯",
            "唐家三少",
            "我吃西红柿",
            "耳根",
            "忘语",
            "爱潜水的乌贼"
        )
    }
}
