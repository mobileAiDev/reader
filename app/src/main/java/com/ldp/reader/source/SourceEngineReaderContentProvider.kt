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
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.sourceengine.content.v8.V8ChapterInput
import com.ldp.reader.sourceengine.content.v8.V8ContentQualitySignal
import com.ldp.reader.sourceengine.content.v8.V8CatalogTitleClassifier
import com.ldp.reader.sourceengine.content.v8.V8DiagnosticSink
import com.ldp.reader.sourceengine.content.v8.V8SourceTextSimilarity
import com.ldp.reader.sourceengine.content.v8.V8SourceChapterValidator
import com.ldp.reader.sourceengine.content.v8.V8SourceRunRequest
import com.ldp.reader.sourceengine.content.v8.V8SourceRunResult
import com.ldp.reader.sourceengine.content.v8.V8ValidationChapter
import com.ldp.reader.sourceengine.content.v8.V8ValidationPlan
import com.ldp.reader.sourceengine.content.v8.V8ValidationPlanner
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
import com.ldp.reader.utils.BookIdentity
import com.ldp.reader.utils.BookManager
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.widget.page.TxtChapter
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
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
    private val sourceQualityRouter: SourceQualityRouter = SourceQualityRouter(),
    private val bookCacheFolderPath: (String?) -> String = { folderName -> BookManager.cacheFolderPath(folderName) },
    private val cleanIntroEnabled: () -> Boolean = { ReaderFeatureSwitches.isCleanIntroEnabled() }
) : ReaderContentProvider {
    override val providerName: String = "source-engine"
    private val searchRanker = BookSearchRanker()
    private val chapterNormalizer = ChapterNormalizer()
    private val bookContentFingerprinter = BookContentFingerprinter()
    private val tailBoundaryLocator = CatalogTailBoundaryLocator(MAX_CATALOG_TAIL_BACKTRACK_CHAPTERS)
    private val catalogTailProbeCache = Collections.synchronizedMap(mutableMapOf<String, CatalogTailProbeResult>())
    private val searchCoverCache = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val searchValidationCache = Collections.synchronizedMap(mutableMapOf<String, ValidatedSearchCandidate>())
    private val searchValidationLocks = Collections.synchronizedMap(mutableMapOf<String, Mutex>())
    private val bookFingerprintCache = Collections.synchronizedMap(mutableMapOf<String, BookContentFingerprintProfile>())
    private val bookFingerprintTrustedUpperCache = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val bookFingerprintBuildLocks = Collections.synchronizedMap(mutableMapOf<String, Mutex>())
    private val bookContentWaterfallCache = Collections.synchronizedMap(mutableMapOf<String, BookContentWaterfall>())
    private val bookIdentityProfileLock = Any()
    private val bookIdentityProfiles = ArrayList<BookIdentityProfile>()
    private val bookIdentityProfilesBySourceBookKey = Collections.synchronizedMap(mutableMapOf<String, BookIdentityProfile>())
    private val catalogTailProbeLocks = Collections.synchronizedMap(mutableMapOf<String, Mutex>())
    private val v8ValidationPlanner = V8ValidationPlanner()
    private val v8SourceValidator by lazy {
        V8SourceChapterValidator(SourceEngineV8BgeModelProvider.get())
    }
    private val v8MarkCache by lazy { SourceEngineV8MarkCache() }
    private val v8BackgroundScope = CoroutineScope(SourceNetworkDispatchers.background + SupervisorJob())
    private val v8ValidationSemaphore = Semaphore(V8_VALIDATION_MAX_CONCURRENT_EPOCHS)
    private val v8ValidationTracker = SourceEngineV8ValidationTracker()
    private val v8MaintenanceStarted = AtomicBoolean(false)
    private var v8MaintenanceJob: Job? = null
    private val v8ChapterMarks = Collections.synchronizedMap(mutableMapOf<String, Map<Int, V8ChapterMarkResult>>())
    private val contentLoadFailureCounts = Collections.synchronizedMap(mutableMapOf<String, AtomicInteger>())
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
        priority: SourceRequestPriority = SourceRequestPriority.FOREGROUND,
        block: suspend () -> T
    ): T {
        when (priority) {
            SourceRequestPriority.FOREGROUND -> SourceNetworkForegroundPriority.enter(operation, key)
            SourceRequestPriority.BACKGROUND,
            SourceRequestPriority.BACKGROUND_LOW -> waitForHigherPriorityNetworkIdle(operation, key, priority)
        }
        val scope = newSourceRequestScope(operation, key, parent = null, priority = priority)
        return try {
            withContext(sourceRequestContext(scope)) {
                block()
            }
        } finally {
            SourceNetworkTimingTracer.traceSummary(key.orEmpty(), "scope_end:$operation", scope)
            SourceNetworkTimingTracer.clear(scope)
            cancelSourceRequests(scope)
            if (priority == SourceRequestPriority.FOREGROUND) {
                SourceNetworkForegroundPriority.exit()
            }
        }
    }

    private suspend fun <T> withChildSourceRequestScope(
        operation: String,
        key: String?,
        priority: SourceRequestPriority,
        block: suspend () -> T
    ): T {
        waitForHigherPriorityNetworkIdle(operation, key, priority)
        val scope = newSourceRequestScope(operation, key, parent = currentSourceRequestScope(), priority = priority)
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
        parent: SourceRequestScope?,
        priority: SourceRequestPriority = parent?.priority ?: SourceRequestPriority.FOREGROUND
    ): SourceRequestScope {
        return SourceRequestScope(
            id = requestScopeIds.incrementAndGet(),
            name = "$operation:${key.orEmpty().debugToken()}",
            priority = priority,
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

    private fun activeSourceRequestDispatcher() =
        SourceNetworkDispatchers.forScope(currentSourceRequestScope())

    private fun cancelSourceRequests(scope: SourceRequestScope) {
        engineFetcher.cancel(scope)
        searchEngineFetcher.cancel(scope)
        detailProbeEngineFetcher.cancel(scope)
    }

    private suspend fun ensureSourceRequestActive() {
        coroutineContext.ensureActive()
        if (currentSourceRequestScope()?.isCancelledInChain() == true) {
            throw CancellationException("Source request scope cancelled.")
        }
    }

    private suspend fun waitForForegroundNetworkIdle(operation: String, key: String?) {
        waitForHigherPriorityNetworkIdle(operation, key, SourceRequestPriority.BACKGROUND)
    }

    private suspend fun waitForHigherPriorityNetworkIdle(
        operation: String,
        key: String?,
        priority: SourceRequestPriority
    ) {
        var waitedMs = 0L
        while (SourceNetworkPriorityGate.higherPriorityCount(priority) > 0) {
            if (waitedMs == 0L) {
                AiBridgeTrace.event(
                    "source_low_priority_network_wait",
                    key.orEmpty(),
                    AiBridgeTrace.fields(
                        "operation" to operation,
                        "priority" to priority.name.lowercase(),
                        "higherPriority" to SourceNetworkPriorityGate.higherPriorityCount(priority),
                        "foreground" to SourceNetworkPriorityGate.foregroundCount(),
                        "background" to SourceNetworkPriorityGate.backgroundCount()
                    )
                )
            }
            delay(LOW_PRIORITY_NETWORK_POLL_INTERVAL_MS)
            waitedMs += LOW_PRIORITY_NETWORK_POLL_INTERVAL_MS
        }
        if (waitedMs > 0L) {
            AiBridgeTrace.event(
                "source_low_priority_network_resumed",
                key.orEmpty(),
                AiBridgeTrace.fields(
                    "operation" to operation,
                    "priority" to priority.name.lowercase(),
                    "waitedMs" to waitedMs
                )
            )
        }
    }

    override suspend fun searchBooks(query: String?): List<BookSearchResult> = withSourceRequestScope("search", query) {
        withContext(activeSourceRequestDispatcher()) {
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
        withContext(activeSourceRequestDispatcher()) {
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
        AiBridgeTrace.event(
            "source_search_flow",
            keyword,
            "tag_search.flow_stage_start_progressive_${progressive}" +
                "_compatible_${compatibleSources.size}" +
                "_selected_${sources.size}" +
                "_maxSearchConcurrency_${MAX_CONCURRENT_SEARCHES}" +
                "_maxValidationConcurrency_${MAX_CONCURRENT_VALIDATIONS}"
        )
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
        val firstSourceResponseTrace = AtomicReference<SearchSourceRequestTrace?>()
        val firstAcceptedCandidateTrace = AtomicReference<SearchSourceRequestTrace?>()
        val firstExactCandidateTrace = AtomicReference<SearchSourceRequestTrace?>()
        val firstProgressPublishProfileLogged = AtomicBoolean(false)
        val semaphore = Semaphore(MAX_CONCURRENT_SEARCHES)
        val sourceWaves = searchSourceTierWaves(sources)
        var lastEmittedKey = ""
        var lastExactProgressKey = ""
        var lastBroadProgressCandidateCount = 0
        var lastOutput: List<BookSearchResult> = emptyList()
        var progressRankAttempts = 0
        var emptyProgressRankAttempts = 0
        val progressEmitMutex = Mutex()

        val searchExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_SEARCHES)
        val searchDispatcher = searchExecutor.asCoroutineDispatcher()
        val rootRequestScope = currentSourceRequestScope()
        val searchJobs = ArrayList<Deferred<Unit>>()
        val searchWaveRuntimes = ArrayList<SearchSourceWaveRuntime>()
        suspend fun maybeEmitProgressLocked() {
            ensureSourceRequestActive()
            val snapshot = synchronized(candidates) { candidates.toList() }
            if (
                snapshot.size < FIRST_PROGRESS_MIN_CANDIDATES ||
                !progressSnapshotReadyForRank(keyword, snapshot)
            ) {
                return
            }
            suspend fun publishProgressOutput(
                progressOutput: List<BookSearchResult>,
                rankAttemptMs: Long
            ) {
                val visibleProgressOutput = mergeProgressiveSearchOutput(lastOutput, progressOutput)
                val progressKey = searchOutputIdentityKey(visibleProgressOutput)
                if (visibleProgressOutput.isNotEmpty() && progressKey != lastEmittedKey) {
                    ensureSourceRequestActive()
                    lastOutput = visibleProgressOutput
                    lastEmittedKey = progressKey
                    if (firstProgressPublishProfileLogged.compareAndSet(false, true)) {
                        SourceNetworkTimingTracer.traceSummary(keyword, "first_publish", currentSourceRequestScope())
                        traceSearchFirstPublishProfile(
                            keyword = keyword,
                            startedAt = startedAt,
                            rawCandidates = snapshot.size,
                            outputCount = visibleProgressOutput.size,
                            rankAttempts = progressRankAttempts,
                            emptyRankAttempts = emptyProgressRankAttempts,
                            rankAttemptMs = rankAttemptMs,
                            startedSearchRequests = startedSearchRequests.get(),
                            completedSearchRequests = completedSearchRequests.get(),
                            successfulSearchRequests = successfulSearchRequests.get(),
                            traces = sourceRequestTraces,
                            firstSourceResponseTrace = firstSourceResponseTrace.get(),
                            firstAcceptedCandidateTrace = firstAcceptedCandidateTrace.get(),
                            firstExactCandidateTrace = firstExactCandidateTrace.get()
                        )
                    }
                    AiBridgeTrace.state(
                        "source_search_progress",
                        keyword,
                        "raw_${snapshot.size}_count_${visibleProgressOutput.size}" +
                            "_elapsedMs_${System.currentTimeMillis() - startedAt}"
                    )
                    onUpdate(visibleProgressOutput)
                }
            }
            val exactProgressKey = exactProgressSnapshotRankKey(keyword, snapshot)
            if (exactProgressKey.isNotBlank() && exactProgressKey != lastExactProgressKey) {
                lastExactProgressKey = exactProgressKey
                progressRankAttempts += 1
                val rankAttemptStartedAt = System.currentTimeMillis()
                val progressOutput = searchOutputForCandidates(
                    keyword = keyword,
                    candidateSnapshot = snapshot,
                    startedAt = startedAt,
                    stage = "progress_exact",
                    rankTimeoutMs = SEARCH_PROGRESS_EXACT_RANK_TIMEOUT_MS,
                    rankMode = SearchRankMode.EXACT_PROGRESS
                )
                val rankAttemptMs = System.currentTimeMillis() - rankAttemptStartedAt
                if (progressOutput.isEmpty()) {
                    emptyProgressRankAttempts += 1
                }
                AiBridgeTrace.state(
                    "source_search_progress_attempt_latest",
                    keyword,
                    "attempt_${progressRankAttempts}_raw_${snapshot.size}_output_${progressOutput.size}" +
                        "_rankAttemptMs_${rankAttemptMs}_started_${startedSearchRequests.get()}" +
                        "_completed_${completedSearchRequests.get()}_success_${successfulSearchRequests.get()}" +
                    "_elapsedMs_${System.currentTimeMillis() - startedAt}"
                )
                publishProgressOutput(progressOutput, rankAttemptMs)
                if (progressOutput.isNotEmpty()) return
            }
            if (snapshot.size == lastBroadProgressCandidateCount) return
            lastBroadProgressCandidateCount = snapshot.size
            progressRankAttempts += 1
            val rankAttemptStartedAt = System.currentTimeMillis()
            val progressOutput = searchOutputForCandidates(
                keyword = keyword,
                candidateSnapshot = snapshot,
                startedAt = startedAt,
                stage = "progress",
                rankTimeoutMs = SEARCH_PROGRESS_RANK_TIMEOUT_MS,
                rankMode = SearchRankMode.PROGRESS
            )
            val rankAttemptMs = System.currentTimeMillis() - rankAttemptStartedAt
            if (progressOutput.isEmpty()) {
                emptyProgressRankAttempts += 1
            }
            AiBridgeTrace.state(
                "source_search_progress_attempt_latest",
                keyword,
                "attempt_${progressRankAttempts}_raw_${snapshot.size}_output_${progressOutput.size}" +
                    "_rankAttemptMs_${rankAttemptMs}_started_${startedSearchRequests.get()}" +
                    "_completed_${completedSearchRequests.get()}_success_${successfulSearchRequests.get()}" +
                    "_elapsedMs_${System.currentTimeMillis() - startedAt}"
            )
            publishProgressOutput(progressOutput, rankAttemptMs)
        }
        suspend fun maybeEmitProgress() {
            if (!progressive) return
            progressEmitMutex.lock()
            try {
                maybeEmitProgressLocked()
            } finally {
                progressEmitMutex.unlock()
            }
        }
        suspend fun emitReadyOutputForCurrentCandidates(stage: String, rankTimeoutMs: Long) {
            ensureSourceRequestActive()
            if (!progressive) return
            val snapshot = synchronized(candidates) { candidates.toList() }
            if (snapshot.isEmpty()) return
            val readyOutput = searchOutputForCandidates(
                keyword = keyword,
                candidateSnapshot = snapshot,
                startedAt = startedAt,
                stage = stage,
                rankTimeoutMs = rankTimeoutMs,
                rankMode = SearchRankMode.PROGRESS
            )
            val visibleReadyOutput = mergeProgressiveSearchOutput(lastOutput, readyOutput)
            val readyKey = searchOutputIdentityKey(visibleReadyOutput)
            if (visibleReadyOutput.isNotEmpty() && readyKey != lastEmittedKey) {
                ensureSourceRequestActive()
                lastOutput = visibleReadyOutput
                lastEmittedKey = readyKey
                AiBridgeTrace.state(
                    "source_search_${stage}",
                    keyword,
                    "raw_${snapshot.size}_count_${visibleReadyOutput.size}" +
                        "_elapsedMs_${System.currentTimeMillis() - startedAt}"
                )
                onUpdate(visibleReadyOutput)
            }
        }

        fun launchSearchWave(wave: SearchSourceWave): SearchSourceWaveRuntime {
            val waveRequestScope = newSourceRequestScope(
                operation = "searchTier${wave.tier}",
                key = keyword,
                parent = rootRequestScope
            )
            val waveCoroutineScope = CoroutineScope(
                searchDispatcher + SupervisorJob() + sourceRequestContext(waveRequestScope)
            )
            val waveJobs = wave.sources.flatMap { indexedSource ->
                searchQueries.map { searchQuery ->
                    waveCoroutineScope.async {
                        ensureSourceRequestActive()
                        var acceptedForProgress = 0
                        semaphore.withPermit {
                            val requestStartedAt = System.currentTimeMillis()
                            var success = false
                            var resultCount = 0
                            var acceptedCount = 0
                            var firstAccepted = ""
                            startedSearchRequests.incrementAndGet()
                            val search = try {
                                when (val value = searchEngine.search(listOf(indexedSource.source), searchQuery, maxSources = 1)) {
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
                                        source = sourceLabel(indexedSource.source),
                                        sourceIndex = indexedSource.index,
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
                                    firstExactCandidateLogged = firstExactCandidateLogged,
                                    firstSourceResponseTrace = firstSourceResponseTrace,
                                    firstAcceptedCandidateTrace = firstAcceptedCandidateTrace,
                                    firstExactCandidateTrace = firstExactCandidateTrace
                                )
                                return@withPermit
                            }
                            resultCount = search.books.size
                            search.books.take(MAX_RESULTS_PER_SOURCE).forEachIndexed { resultIndex, book ->
                                val candidate = SearchCandidate(
                                    book = book,
                                    sourceIndex = indexedSource.index,
                                    resultIndex = resultIndex,
                                    searchQuery = searchQuery
                                )
                                if (searchCandidateAcceptedForCollection(keyword, candidate)) {
                                    acceptedCount += 1
                                    if (firstAccepted.isBlank()) {
                                        firstAccepted = "${book.name}/${book.author}"
                                    }
                                    synchronized(candidates) {
                                        candidates.add(candidate)
                                    }
                                }
                            }
                            acceptedForProgress = acceptedCount
                            recordSearchSourceTrace(
                                keyword = keyword,
                                trace = SearchSourceRequestTrace(
                                    source = sourceLabel(indexedSource.source),
                                    sourceIndex = indexedSource.index,
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
                                firstExactCandidateLogged = firstExactCandidateLogged,
                                firstSourceResponseTrace = firstSourceResponseTrace,
                                firstAcceptedCandidateTrace = firstAcceptedCandidateTrace,
                                firstExactCandidateTrace = firstExactCandidateTrace
                            )
                        }
                        if (acceptedForProgress > 0) {
                            maybeEmitProgress()
                        }
                    }
                }
            }
            searchJobs.addAll(waveJobs)
            val runtime = SearchSourceWaveRuntime(waveRequestScope, waveCoroutineScope, waveJobs)
            searchWaveRuntimes.add(runtime)
            return runtime
        }
        try {
            val deadline = startedAt + totalTimeoutMs
            for (wave in sourceWaves) {
                if (System.currentTimeMillis() >= deadline) break
                AiBridgeTrace.event(
                    "source_search_tier_wave_started",
                    keyword,
                    AiBridgeTrace.fields(
                        "tier" to wave.tier,
                        "sources" to wave.sources.size,
                        "queries" to searchQueries.size,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                val runtime = launchSearchWave(wave)
                val waveJobs = runtime.jobs
                val waveDeadline = minOf(deadline, System.currentTimeMillis() + searchTierSettleTimeoutMs(wave.tier))
                while (waveJobs.any { job -> !job.isCompleted } && System.currentTimeMillis() < waveDeadline) {
                    delay(SEARCH_PROGRESS_POLL_INTERVAL_MS)
                    maybeEmitProgress()
                }
                val activeJobs = waveJobs.count { job -> !job.isCompleted }
                if (activeJobs > 0) {
                    waveJobs.forEach { job ->
                        if (!job.isCompleted) job.cancel()
                    }
                    cancelSourceRequests(runtime.requestScope)
                    AiBridgeTrace.event(
                        "source_search_tier_wave_cancelled",
                        keyword,
                        AiBridgeTrace.fields(
                            "tier" to wave.tier,
                            "cancelledJobs" to activeJobs,
                            "elapsedMs" to (System.currentTimeMillis() - startedAt)
                        )
                    )
                }
                AiBridgeTrace.event(
                    "source_search_tier_wave_settled",
                    keyword,
                    AiBridgeTrace.fields(
                        "tier" to wave.tier,
                        "sources" to wave.sources.size,
                        "completedJobs" to waveJobs.count { job -> job.isCompleted },
                        "activeJobs" to waveJobs.count { job -> !job.isCompleted },
                        "started" to startedSearchRequests.get(),
                        "completed" to completedSearchRequests.get(),
                        "candidates" to synchronized(candidates) { candidates.size },
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                if (progressive && !progressOutputReadyToStop(keyword, lastOutput)) {
                    emitReadyOutputForCurrentCandidates(
                        stage = "tier${wave.tier}_ready",
                        rankTimeoutMs = searchTierReadyRankTimeoutMs(wave.tier)
                    )
                }
                if (progressive && progressOutputReadyToStop(keyword, lastOutput)) {
                    AiBridgeTrace.event(
                        "source_search_tier_waterfall_stopped",
                        keyword,
                        AiBridgeTrace.fields(
                            "reason" to "progress_ready",
                            "tier" to wave.tier,
                            "output" to lastOutput.size,
                            "started" to startedSearchRequests.get(),
                            "completed" to completedSearchRequests.get(),
                            "elapsedMs" to (System.currentTimeMillis() - startedAt)
                        )
                    )
                    break
                }
            }
            if (searchJobs.any { job -> !job.isCompleted }) {
                AiBridgeTrace.state(
                    "source_search_timeout",
                    keyword,
                    "timeoutMs_${totalTimeoutMs}_started_${startedSearchRequests.get()}" +
                        "_completed_${completedSearchRequests.get()}_candidates_${synchronized(candidates) { candidates.size }}"
                )
            }
            searchJobs.forEach { it.cancel() }
        } finally {
            searchWaveRuntimes.forEach { runtime ->
                runtime.coroutineScope.coroutineContext.cancelChildren()
                cancelSourceRequests(runtime.requestScope)
            }
            searchDispatcher.close()
            searchExecutor.shutdownNow()
        }

        ensureSourceRequestActive()
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
                rankMode = SearchRankMode.PROGRESS
            )
            val visibleReadyOutput = mergeProgressiveSearchOutput(lastOutput, readyOutput)
            val readyKey = searchOutputIdentityKey(visibleReadyOutput)
            if (visibleReadyOutput.isNotEmpty() && readyKey != lastEmittedKey) {
                ensureSourceRequestActive()
                lastOutput = visibleReadyOutput
                lastEmittedKey = readyKey
                AiBridgeTrace.state(
                    "source_search_completed_ready",
                    keyword,
                    "raw_${candidateCount}_count_${visibleReadyOutput.size}" +
                        "_elapsedMs_${System.currentTimeMillis() - startedAt}"
                )
                onUpdate(visibleReadyOutput)
            }
        }
        val output = searchOutputForCandidates(
            keyword = keyword,
            candidateSnapshot = candidateSnapshot,
            startedAt = startedAt,
            stage = "completed",
            rankTimeoutMs = if (progressive && lastOutput.isNotEmpty()) {
                SEARCH_VISIBLE_COMPLETED_RANK_TIMEOUT_MS
            } else {
                null
            },
            rankMode = SearchRankMode.COMPLETED
        )
        if (progressive) {
            val visibleOutput = mergeProgressiveSearchOutput(lastOutput, output)
            val outputKey = searchOutputIdentityKey(visibleOutput)
            if (visibleOutput.isNotEmpty() && outputKey != lastEmittedKey) {
                lastOutput = visibleOutput
                lastEmittedKey = outputKey
                onUpdate(visibleOutput)
            }
        }
        val finalOutput = if (progressive) {
            mergeProgressiveSearchOutput(lastOutput, output).ifEmpty { output }
        } else if (output.isNotEmpty() || lastOutput.isEmpty()) {
            output
        } else {
            lastOutput
        }
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

    private fun searchSourceTierWaves(sources: List<BookSource>): List<SearchSourceWave> {
        val indexedSources = sources.mapIndexed { index, source ->
            IndexedSearchSource(index, source)
        }
        return listOf(1, 2, 3).mapNotNull { tier ->
            val tierSources = indexedSources.filter { indexed ->
                sourceQualityRouter.sourceDebugSnapshot(indexed.source).tier == tier
            }
            tierSources.takeIf { it.isNotEmpty() }?.let { SearchSourceWave(tier, it) }
        }
    }

    private fun searchTierSettleTimeoutMs(tier: Int): Long {
        return when (tier) {
            1 -> SEARCH_TIER_ONE_SETTLE_TIMEOUT_MS
            2 -> SEARCH_TIER_TWO_SETTLE_TIMEOUT_MS
            else -> SEARCH_TIER_THREE_SETTLE_TIMEOUT_MS
        }
    }

    private fun searchTierReadyRankTimeoutMs(tier: Int): Long {
        return when (tier) {
            1 -> SEARCH_TIER_ONE_READY_RANK_TIMEOUT_MS
            2 -> SEARCH_TIER_TWO_READY_RANK_TIMEOUT_MS
            else -> SEARCH_TIER_THREE_READY_RANK_TIMEOUT_MS
        }
    }

    private fun recordSearchSourceTrace(
        keyword: String,
        trace: SearchSourceRequestTrace,
        traces: MutableList<SearchSourceRequestTrace>,
        firstSourceResponseLogged: AtomicBoolean,
        firstAcceptedCandidateLogged: AtomicBoolean,
        firstExactCandidateLogged: AtomicBoolean,
        firstSourceResponseTrace: AtomicReference<SearchSourceRequestTrace?>,
        firstAcceptedCandidateTrace: AtomicReference<SearchSourceRequestTrace?>,
        firstExactCandidateTrace: AtomicReference<SearchSourceRequestTrace?>
    ) {
        traces.add(trace)
        if (firstSourceResponseLogged.compareAndSet(false, true)) {
            firstSourceResponseTrace.set(trace)
            AiBridgeTrace.event(
                "source_search_first_source_response",
                keyword,
                trace.debugValue()
            )
        }
        if (trace.acceptedCount > 0 && firstAcceptedCandidateLogged.compareAndSet(false, true)) {
            firstAcceptedCandidateTrace.set(trace)
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
            firstExactCandidateTrace.set(trace)
            AiBridgeTrace.event(
                "source_search_first_exact_candidate",
                keyword,
                trace.debugValue()
            )
        }
    }

    private fun traceSearchFirstPublishProfile(
        keyword: String,
        startedAt: Long,
        rawCandidates: Int,
        outputCount: Int,
        rankAttempts: Int,
        emptyRankAttempts: Int,
        rankAttemptMs: Long,
        startedSearchRequests: Int,
        completedSearchRequests: Int,
        successfulSearchRequests: Int,
        traces: List<SearchSourceRequestTrace>,
        firstSourceResponseTrace: SearchSourceRequestTrace?,
        firstAcceptedCandidateTrace: SearchSourceRequestTrace?,
        firstExactCandidateTrace: SearchSourceRequestTrace?
    ) {
        val snapshot = synchronized(traces) { traces.toList() }
        val accepted = snapshot.filter { trace -> trace.acceptedCount > 0 }
        AiBridgeTrace.state(
            "source_search_first_publish_profile",
            keyword,
            "elapsedMs_${System.currentTimeMillis() - startedAt}" +
                "_raw_${rawCandidates}_output_${outputCount}" +
                "_rankAttempts_${rankAttempts}_emptyRankAttempts_${emptyRankAttempts}" +
                "_lastRankAttemptMs_${rankAttemptMs}" +
                "_requestsStarted_${startedSearchRequests}_completed_${completedSearchRequests}" +
                "_success_${successfulSearchRequests}_traces_${snapshot.size}_acceptedTraces_${accepted.size}" +
                "_firstResponse_${firstSourceResponseTrace?.debugValue() ?: "none"}" +
                "_firstAccepted_${firstAcceptedCandidateTrace?.debugValue() ?: "none"}" +
                "_firstExact_${firstExactCandidateTrace?.debugValue() ?: "none"}" +
                "_fastAccepted_${accepted.sortedBy { trace -> trace.durationMs }.take(5).joinToString("|") { trace ->
                    trace.debugValue()
                }}"
        )
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
        rankMode: SearchRankMode = SearchRankMode.COMPLETED
    ): List<BookSearchResult> {
        ensureSourceRequestActive()
        val stageStartedAt = System.currentTimeMillis()
        val progressiveRank = rankMode.progressive
        AiBridgeTrace.event(
            "source_search_rank_stage_started",
            keyword,
            "stage_${stage}_raw_${candidateSnapshot.size}_progressive_${progressiveRank}"
        )
        AiBridgeTrace.event(
            "source_search_flow",
            keyword,
            "tag_search.flow_stage_rank_start" +
                "_rankStage_${stage}" +
                "_raw_${candidateSnapshot.size}" +
                "_progressive_${progressiveRank}" +
                "_elapsedMs_${stageStartedAt - startedAt}"
        )
        SourceNetworkTimingTracer.traceSummary(keyword, "rank_start:$stage", currentSourceRequestScope())
        val effectiveRankTimeoutMs = rankTimeoutMs.takeUnless { progressiveRank }
        var rankTimedOut = false
        val ranked = if (effectiveRankTimeoutMs == null) {
            rankSearchCandidates(keyword, candidateSnapshot, rankMode)
        } else {
            withTimeoutOrNull(effectiveRankTimeoutMs) {
                rankSearchCandidates(keyword, candidateSnapshot, rankMode)
            } ?: run {
                rankTimedOut = true
                emptyList()
            }
        }
        if (rankTimedOut) {
            AiBridgeTrace.event(
                "source_search_rank_stage_timeout",
                keyword,
                "stage_${stage}_timeoutMs_${effectiveRankTimeoutMs}" +
                    "_raw_${candidateSnapshot.size}" +
                    "_progressive_${progressiveRank}" +
                    "_elapsedMs_${System.currentTimeMillis() - startedAt}"
            )
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
        AiBridgeTrace.event(
            "source_search_flow",
            keyword,
            "tag_search.flow_stage_rank_end" +
                "_rankStage_${stage}" +
                "_raw_${candidateSnapshot.size}" +
                "_ranked_${ranked.size}" +
                "_rankMs_${rankedAt - stageStartedAt}" +
                "_coverMs_${coverFilledAt - rankedAt}" +
                "_elapsedMs_${coverFilledAt - startedAt}"
        )
        SourceNetworkTimingTracer.traceSummary(keyword, "rank_end:$stage", currentSourceRequestScope())
        val output = coverFilledRanked.mapIndexed { index, rankedBook ->
            val book = rankedBook.book
            val sameBookCovers = sameSearchBookCoverCandidates(book, candidateSnapshot)
            val coverCandidates = coverCandidateUrls(book.coverUrl, rankedBook.coverCandidates + sameBookCovers)
            if (index < SEARCH_COVER_TRACE_RESULT_LIMIT && sameBookCovers.isNotEmpty()) {
                AiBridgeTrace.state(
                    "source_search_cover_candidates_merged",
                    keyword,
                    AiBridgeTrace.fields(
                        "stage" to stage,
                        "title" to searchRanker.displayTitle(book),
                        "author" to cleanAuthor(book.author),
                        "raw" to candidateSnapshot.size,
                        "sameBookCovers" to sameBookCovers.size,
                        "outputCovers" to coverCandidates.size
                    )
                )
            }
            BookSearchResult().apply {
                routeId = SourceEngineBookRoute.bookId(book, coverCandidates)
                title = searchRanker.displayTitle(book)
                author = cleanAuthor(book.author)
                cover = BookCoverUrl.clean(book.coverUrl).takeIf { BookCoverUrl.isLikelyImage(it) }.orEmpty()
                this.coverCandidates = coverCandidates
                desc = cleanIntro(book.intro)
                sourceCount = rankedBook.ranked.sourceCount
            }
        }
        return suppressRelatedSearchOutputWithoutExact(keyword, candidateSnapshot, output)
    }

    private fun suppressRelatedSearchOutputWithoutExact(
        keyword: String,
        candidateSnapshot: List<SearchCandidate>,
        output: List<BookSearchResult>
    ): List<BookSearchResult> {
        if (output.isEmpty()) return output
        val queryKey = normalizeHint(keyword)
        if (queryKey.length < MIN_EXACT_GROUP_ONLY_QUERY_CHARS) return output
        val hasExactCandidate = candidateSnapshot.any { candidate ->
            searchRanker.canonicalTitleKey(candidate.book) == queryKey
        }
        if (!hasExactCandidate) return output
        if (output.any { book -> normalizeHint(book.title.orEmpty()) == queryKey }) return output
        AiBridgeTrace.event(
            "source_search_related_output_suppressed",
            keyword,
            "query_${queryKey.debugToken()}_count_${output.size}_kept_0"
        )
        return emptyList()
    }

    private fun searchOutputIdentityKey(books: List<BookSearchResult>): String {
        return books.joinToString("\n") { book ->
            book.routeId ?: "${book.title.orEmpty()}\t${book.author.orEmpty()}"
        }
    }

    private fun progressOutputReadyToStop(
        keyword: String,
        books: List<BookSearchResult>
    ): Boolean {
        if (books.size < SEARCH_PROGRESS_RESULT_TARGET) return false
        val queryKey = normalizeHint(keyword)
        if (queryKey.length < MIN_EXACT_GROUP_ONLY_QUERY_CHARS) return true
        return books.any { book -> normalizeHint(book.title.orEmpty()) == queryKey }
    }

    private fun progressSnapshotReadyForRank(
        keyword: String,
        candidates: List<SearchCandidate>
    ): Boolean {
        val queryKey = normalizeHint(keyword)
        val scored = scoreSearchCandidatesForValidation(keyword, candidates)
        return searchTitleGroupsForValidation(queryKey, scored)
            .any { group ->
                (
                    if (queryKey.length >= MIN_EXACT_GROUP_ONLY_QUERY_CHARS) {
                        group.titleKey == queryKey
                    } else {
                        titleGroupMatchesKeyword(queryKey, group.titleKey)
                    }
                    ) &&
                    group.candidates.uniqueSearchSourceCount() >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
            }
    }

    private fun exactProgressSnapshotRankKey(
        keyword: String,
        candidates: List<SearchCandidate>
    ): String {
        val queryKey = normalizeHint(keyword)
        if (queryKey.length < MIN_COMPLETION_QUERY_CHARS) return ""
        val groups = searchTitleGroupsForValidation(
            queryKey,
            scoreSearchCandidatesForValidation(keyword, candidates)
        )
            .mapNotNull { group ->
                if (
                    group.titleKey == queryKey &&
                    searchIdentityHasAuthor(group.identityKey) &&
                    group.candidates.uniqueSearchSourceCount() >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
                ) {
                    group.identityKey to group.candidates
                } else {
                    null
                }
            }
        if (groups.isEmpty()) return ""
        val top = groups
            .sortedWith(
                compareByDescending<Pair<String, List<RankedSearchBook>>> { (_, group) ->
                    group.map { ranked -> sourceBookKey(ranked.book) }.toSet().size
                }.thenByDescending { (_, group) -> group.size }
                    .thenBy { (identityKey, _) -> identityKey }
            )
            .first()
        val sourceCount = top.second.map { ranked -> sourceBookKey(ranked.book) }.toSet().size
        return "${top.first}\t$sourceCount\t${top.second.size}"
    }

    private fun mergeProgressiveSearchOutput(
        previous: List<BookSearchResult>,
        next: List<BookSearchResult>
    ): List<BookSearchResult> {
        if (previous.isEmpty()) return next.take(MAX_SEARCH_RESULTS)
        if (next.isEmpty()) return previous.take(MAX_SEARCH_RESULTS)
        val previousByKey = LinkedHashMap<String, BookSearchResult>()
        previous.forEach { book ->
            previousByKey[progressiveSearchOutputKey(book)] = book
        }
        val merged = ArrayList<BookSearchResult>(previous.size + next.size)
        val consumed = LinkedHashSet<String>()
        next.forEach { book ->
            val key = progressiveSearchOutputKey(book)
            merged.add(book)
            consumed.add(key)
        }
        previous.forEach { oldBook ->
            val key = progressiveSearchOutputKey(oldBook)
            if (consumed.add(key)) {
                merged.add(previousByKey[key] ?: oldBook)
            }
        }
        return merged.take(MAX_SEARCH_RESULTS)
    }

    private fun progressiveSearchOutputKey(book: BookSearchResult): String {
        val title = book.title.orEmpty().trim()
        val author = book.author.orEmpty().trim()
        return if (title.isNotBlank() || author.isNotBlank()) {
            "$title\n$author"
        } else {
            book.routeId.orEmpty()
        }
    }

    override suspend fun refreshSearchCovers(
        query: String?,
        books: List<BookSearchResult>
    ): List<BookSearchResult> = withSourceRequestScope("searchCoverRefresh", query) {
        withContext(activeSourceRequestDispatcher()) {
            if (books.isEmpty()) return@withContext books
            val updated = books.toMutableList()
            supervisorScope {
                books.take(MAX_BACKGROUND_COVER_REFRESH_RESULTS).mapIndexed { index, book ->
                    async {
                        val cacheKey = searchCoverCacheKey(book)
                        if (BookCoverUrl.isLikelyImage(book.cover)) return@async null
                        val routeId = book.routeId ?: return@async null
                        val route = SourceEngineBookRoute.decodeBookId(routeId)
                        val sourceBook = runCatching {
                            SourceEngineBookRoute.toSourceBook(
                                sourceFinder(route.sourceUrl),
                                route
                            )
                        }.getOrNull() ?: return@async null
                        val cached = searchCoverCache[cacheKey]
                        if (BookCoverUrl.isLikelyImage(cached)) {
                            val cleaned = BookCoverUrl.clean(cached)
                            val candidates = coverCandidateUrls(
                                cleaned,
                                SourceEngineBookRoute.coverCandidates(route) + book.coverCandidates.orEmpty()
                            )
                            val refreshedRouteId = SourceEngineBookRoute.bookId(
                                sourceBook.copy(coverUrl = cleaned),
                                candidates
                            )
                            return@async index to copySearchResultWithCover(book, cleaned, candidates, refreshedRouteId)
                        }
                        val cover = withTimeoutOrNull(BACKGROUND_COVER_REFRESH_ITEM_TIMEOUT_MS) {
                            findCoverFallback(sourceBook)
                        }
                        if (!BookCoverUrl.isLikelyImage(cover)) return@async null
                        val cleaned = BookCoverUrl.clean(cover)
                        searchCoverCache[cacheKey] = cleaned
                        val candidates = coverCandidateUrls(
                            cleaned,
                            SourceEngineBookRoute.coverCandidates(route) + book.coverCandidates.orEmpty()
                        )
                        val refreshedRouteId = SourceEngineBookRoute.bookId(
                            sourceBook.copy(coverUrl = cleaned),
                            candidates
                        )
                        index to copySearchResultWithCover(book, cleaned, candidates, refreshedRouteId)
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
        cover: String,
        coverCandidates: List<String> = coverCandidateUrls(cover, listOfNotNull(book.cover) + book.coverCandidates.orEmpty()),
        routeId: String? = book.routeId
    ): BookSearchResult {
        return BookSearchResult().apply {
            this.cover = cover
            this.coverCandidates = coverCandidates
            title = book.title
            author = book.author
            desc = book.desc
            sources = book.sources
            sourceCount = book.sourceCount
            this.routeId = routeId
        }
    }

    private fun coverCandidateUrls(primary: String?, candidates: List<String> = emptyList()): List<String> {
        return (listOf(primary) + candidates)
            .map { url -> BookCoverUrl.clean(url) }
            .filter { url -> BookCoverUrl.isLikelyImage(url) }
            .distinct()
    }

    private fun sameSearchBookCoverCandidates(
        book: SourceBook,
        candidates: List<SearchCandidate>
    ): List<String> {
        val titleKey = searchRanker.canonicalTitleKey(book)
        if (titleKey.isBlank()) return emptyList()
        return candidates.asSequence()
            .map { candidate -> candidate.book }
            .filter { candidate ->
                searchRanker.canonicalTitleKey(candidate) == titleKey &&
                    BookIdentity.authorsCompatible(candidate.author, book.author)
            }
            .map { candidate -> BookCoverUrl.clean(candidate.coverUrl) }
            .filter { url -> BookCoverUrl.isLikelyImage(url) }
            .distinct()
            .toList()
    }

    private fun searchQueriesFor(keyword: String): List<String> {
        return listOf(keyword)
            .map { query -> query.trim() }
            .filter { query -> query.isNotBlank() }
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

    private fun searchCandidateAcceptedForCollection(
        keyword: String,
        candidate: SearchCandidate
    ): Boolean {
        if (searchRanker.score(keyword, candidate).score > 0) return true
        val queryKey = normalizeHint(keyword)
        val titleKey = searchRanker.canonicalTitleKey(candidate.book)
        return catalogAliasTitleMayNeedValidation(queryKey, titleKey)
    }

    private fun scoreSearchCandidatesForValidation(
        keyword: String,
        candidates: List<SearchCandidate>
    ): List<RankedSearchBook> {
        val scored = searchRanker.scoreCandidates(keyword, candidates)
        val scoredKeys = scored.mapTo(LinkedHashSet()) { rankedSearchCandidateKey(it) }
        val queryKey = normalizeHint(keyword)
        val aliasCandidates = candidates.mapNotNull { candidate ->
            if (rankedSearchCandidateKey(candidate) in scoredKeys) return@mapNotNull null
            val titleKey = searchRanker.canonicalTitleKey(candidate.book)
            if (!catalogAliasTitleMayNeedValidation(queryKey, titleKey)) return@mapNotNull null
            RankedSearchBook(
                book = candidate.book,
                score = SEARCH_CATALOG_ALIAS_VALIDATION_SCORE -
                    (candidate.sourceIndex + candidate.resultIndex).coerceAtMost(MAX_ORDER_PENALTY_FOR_ALIAS_SCORE),
                evidence = "title:catalog-alias",
                sourceIndex = candidate.sourceIndex,
                resultIndex = candidate.resultIndex
            )
        }
        return (scored + aliasCandidates).sortedWith(rankedSearchComparator)
    }

    private fun rankedSearchCandidateKey(ranked: RankedSearchBook): String {
        return rankedSearchCandidateKey(
            SearchCandidate(
                book = ranked.book,
                sourceIndex = ranked.sourceIndex,
                resultIndex = ranked.resultIndex
            )
        )
    }

    private fun rankedSearchCandidateKey(candidate: SearchCandidate): String {
        return candidate.book.source.sourceUrl + "\n" +
            candidate.book.bookUrl + "\n" +
            candidate.sourceIndex + "\n" +
            candidate.resultIndex
    }

    private fun searchTitleGroupsForValidation(
        queryKey: String,
        scored: List<RankedSearchBook>
    ): List<SearchTitleGroup> {
        val groups = ArrayList<MutableList<RankedSearchBook>>()
        val allowForeignContainedTitleAlias = !hasStrongExactAuthoredSearchConsensus(queryKey, scored)
        scored.sortedWith(rankedSearchComparator).forEach { ranked ->
            val existing = groups.firstOrNull { group ->
                group.any { current ->
                    sameSearchValidationGroup(
                        left = current.book,
                        right = ranked.book,
                        allowForeignContainedTitleAlias = allowForeignContainedTitleAlias
                    )
                }
            }
            if (existing == null) {
                groups.add(mutableListOf(ranked))
            } else {
                existing.add(ranked)
            }
        }
        return groups.map { group ->
            val sorted = group.sortedWith(rankedSearchComparator)
            val representative = representativeSearchBook(queryKey, sorted)
            val titleKey = searchRanker.canonicalTitleKey(representative)
            SearchTitleGroup(
                identityKey = searchIdentityKey(representative),
                titleKey = titleKey,
                candidates = sorted,
                authorConsensus = authorConsensusFor(sorted.map { ranked -> ranked.book })
            )
        }
    }

    private fun hasStrongExactAuthoredSearchConsensus(
        queryKey: String,
        scored: List<RankedSearchBook>
    ): Boolean {
        if (queryKey.isBlank()) return false
        val exactAuthored = scored.filter { ranked ->
            searchRanker.canonicalTitleKey(ranked.book) == queryKey &&
                normalizedAuthor(ranked.book.author).isNotBlank()
        }
        if (exactAuthored.isEmpty()) return false
        val groups = ArrayList<MutableList<RankedSearchBook>>()
        exactAuthored.forEach { ranked ->
            val existing = groups.firstOrNull { group ->
                group.any { current -> BookIdentity.authorsCompatible(current.book.author, ranked.book.author) }
            }
            if (existing == null) {
                groups.add(mutableListOf(ranked))
            } else {
                existing.add(ranked)
            }
        }
        return groups.any { group ->
            group.map { ranked -> sourceBookKey(ranked.book) }.toSet().size >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
        }
    }

    private fun sameSearchValidationGroup(
        left: SourceBook,
        right: SourceBook,
        allowForeignContainedTitleAlias: Boolean
    ): Boolean {
        val leftTitle = searchRanker.canonicalTitleKey(left)
        val rightTitle = searchRanker.canonicalTitleKey(right)
        if (leftTitle.isBlank() || rightTitle.isBlank()) return false
        val authorsCompatible = BookIdentity.authorsCompatible(left.author, right.author)
        if (leftTitle == rightTitle) return authorsCompatible
        if (sameOrContainedSearchKey(leftTitle, rightTitle)) {
            return authorsCompatible || allowForeignContainedTitleAlias
        }
        return authorsCompatible && catalogAliasTitleMayNeedValidation(leftTitle, rightTitle)
    }

    private fun representativeSearchBook(
        queryKey: String,
        group: List<RankedSearchBook>
    ): SourceBook {
        val selected = group.minWithOrNull(
            compareBy<RankedSearchBook> { ranked ->
                if (searchRanker.canonicalTitleKey(ranked.book) == queryKey) 0 else 1
            }.thenBy { ranked -> searchRanker.canonicalTitleKey(ranked.book).length }
                .then(rankedSearchComparator)
        ) ?: group.first()
        val displayAuthor = group
            .filter { ranked ->
                searchRanker.canonicalTitleKey(ranked.book) == searchRanker.canonicalTitleKey(selected.book) &&
                    BookIdentity.authorsCompatible(ranked.book.author, selected.book.author)
            }
            .map { ranked -> ranked.book.author }
            .fold(selected.book.author) { current, author ->
                BookIdentity.preferredDisplayAuthor(current, author)
            }
        return selected.book.copy(author = displayAuthor)
    }

    private fun catalogAliasTitleMayNeedValidation(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true
        if (sameOrContainedSearchKey(left, right)) return true
        val shorterLength = minOf(left.length, right.length)
        if (shorterLength < MIN_CATALOG_ALIAS_TITLE_CHARS) return false
        val commonPrefix = commonPrefixLength(left, right)
        return commonPrefix >= MIN_CATALOG_ALIAS_COMMON_PREFIX_CHARS &&
            commonPrefix * 100 >= shorterLength * MIN_CATALOG_ALIAS_COMMON_PREFIX_PERCENT
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        val max = minOf(left.length, right.length)
        var index = 0
        while (index < max && left[index] == right[index]) {
            index += 1
        }
        return index
    }

    private suspend fun rankSearchCandidates(
        keyword: String,
        candidates: List<SearchCandidate>,
        rankMode: SearchRankMode = SearchRankMode.COMPLETED
    ): List<ValidatedSearchCandidate> {
        val progressive = rankMode.progressive
        val scored = scoreSearchCandidatesForValidation(keyword, candidates)
        if (scored.isEmpty()) return emptyList()

        val queryKey = normalizeHint(keyword)
        val baseTitleGroups = searchTitleGroupsForValidation(queryKey, scored)
            .filter { group -> titleGroupMatchesKeyword(queryKey, group.titleKey) }
            .filter { group ->
                group.candidates.uniqueSearchSourceCount() >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT ||
                    (!progressive && queryKey.length >= MIN_EXACT_GROUP_ONLY_QUERY_CHARS && group.titleKey == queryKey)
            }
        val titleGroups = when (rankMode) {
            SearchRankMode.EXACT_PROGRESS -> exactProgressTitleGroups(queryKey, baseTitleGroups)
            SearchRankMode.PROGRESS -> prioritizeExactTitleGroups(queryKey, baseTitleGroups, progressive = true)
            SearchRankMode.COMPLETED -> prioritizeExactTitleGroups(queryKey, baseTitleGroups, progressive = false)
        }
            .sortedWith(searchTitleGroupComparator(queryKey))
            .let { groups -> validationTitleGroupsForMode(groups, rankMode) }
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
        val displayPreviewIdentityKeys = if (progressive) {
            preferredProgressIdentityKeys(queryKey, titleGroups)
        } else {
            emptySet()
        }
        return supervisorScope {
            val groupValidations = titleGroups.map { titleGroup ->
                async {
                    validateSearchTitleGroup(
                        titleGroup = titleGroup,
                        semaphore = semaphore,
                        allowEarlyReturn = progressive,
                        allowDisplayPreview = rankMode != SearchRankMode.COMPLETED &&
                            titleGroup.identityKey in displayPreviewIdentityKeys,
                        queryKey = queryKey
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
                    preferredProgressIdentityKeys(queryKey, titleGroups)
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
                AiBridgeTrace.event(
                    "source_search_flow",
                    keyword,
                    "tag_search.flow_stage_validation_start" +
                        "_mode_progress" +
                        "_groups_${titleGroups.size}" +
                        "_timeoutMs_${timeoutMs}" +
                        "_waitPreferred_${waitForPreferredExactGroup}"
                )
                SourceNetworkTimingTracer.traceSummary(keyword, "validation_start:progress", currentSourceRequestScope())
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
                    "mode_completed_groups_${titleGroups.size}_timeoutMs_group_$SEARCH_TITLE_GROUP_VALIDATION_TIMEOUT_MS"
                )
                AiBridgeTrace.event(
                    "source_search_flow",
                    keyword,
                    "tag_search.flow_stage_validation_start" +
                        "_mode_completed" +
                        "_groups_${titleGroups.size}" +
                        "_timeoutMs_group_$SEARCH_TITLE_GROUP_VALIDATION_TIMEOUT_MS"
                )
                SourceNetworkTimingTracer.traceSummary(keyword, "validation_start:completed", currentSourceRequestScope())
                groupValidations.awaitAll().filterNotNull()
            }
            AiBridgeTrace.event(
                "source_search_validation_stage_finished",
                keyword,
                "groups_${titleGroups.size}_completed_${validatedGroups.size}" +
                    "_candidates_${validatedGroups.flatten().size}" +
                    "_durationMs_${System.currentTimeMillis() - validationStageStartedAt}"
            )
            AiBridgeTrace.event(
                "source_search_flow",
                keyword,
                "tag_search.flow_stage_validation_end" +
                    "_groups_${titleGroups.size}" +
                    "_completed_${validatedGroups.size}" +
                    "_candidates_${validatedGroups.flatten().size}" +
                    "_durationMs_${System.currentTimeMillis() - validationStageStartedAt}"
            )
            SourceNetworkTimingTracer.traceSummary(keyword, "validation_end", currentSourceRequestScope())
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
            .sortedWith(validatedSearchComparatorForQuery(queryKey))
            .take(MAX_SEARCH_RESULTS)
    }

    private suspend fun mergeValidatedSearchGroups(
        keyword: String,
        candidates: List<ValidatedSearchCandidate>,
        fastForProgress: Boolean = false
    ): List<ValidatedSearchCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val queryKey = normalizeHint(keyword)
        val groups = catalogPrefixExpandedValidatedGroups(candidates)
            .mapNotNull { group ->
                mergeValidatedTitleGroup(
                    group = group.sortedWith(validatedSearchComparator),
                    fastForProgress = fastForProgress,
                    displayPreviewQueryKey = queryKey
                )
            }
        val mergeInputs = groups + exactQueryCatalogAnchorsForMerge(queryKey, candidates, groups)
        val mergedGroups = mergeCatalogPrefixEquivalentCandidates(keyword, mergeInputs)
            .let { merged -> filterMergedSearchGroupsForQuery(queryKey, merged, candidates) }
        AiBridgeTrace.state(
            "source_search_validated_groups",
            keyword,
            "groups_${mergedGroups.size}_rawGroups_${groups.size}_top_${mergedGroups.take(5).joinToString("_") { group ->
                "${group.book.name}@${group.book.author}/${group.chapterCount}".debugToken()
            }}"
        )
        return mergedGroups
    }

    private fun catalogPrefixExpandedValidatedGroups(
        candidates: List<ValidatedSearchCandidate>
    ): List<List<ValidatedSearchCandidate>> {
        return catalogPrefixExpandedValidatedGroupsWithoutQuery(candidates)
    }

    private fun exactQueryCatalogAnchorsForMerge(
        queryKey: String,
        candidates: List<ValidatedSearchCandidate>,
        mergedGroups: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        if (queryKey.length < MIN_EXACT_GROUP_ONLY_QUERY_CHARS || mergedGroups.isEmpty()) return emptyList()
        val mergedKeys = mergedGroups.mapTo(LinkedHashSet()) { candidate -> sourceBookKey(candidate.book) }
        return candidates
            .filter { candidate ->
                searchRanker.canonicalTitleKey(candidate.book) == queryKey &&
                    searchCandidateTrustedForReadingCatalog(candidate) &&
                    sourceBookKey(candidate.book) !in mergedKeys &&
                    mergedGroups.any { merged ->
                        !titleGroupMatchesKeyword(queryKey, searchRanker.canonicalTitleKey(merged.book)) &&
                            sameValidatedBookIdentity(candidate, merged)
                    }
            }
            .distinctBy { candidate -> sourceBookKey(candidate.book) }
    }

    private fun filterMergedSearchGroupsForQuery(
        queryKey: String,
        candidates: List<ValidatedSearchCandidate>,
        validatedCandidates: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        if (queryKey.length < MIN_EXACT_GROUP_ONLY_QUERY_CHARS) return candidates
        val exactAnchors = validatedCandidates.filter { candidate ->
            searchRanker.canonicalTitleKey(candidate.book) == queryKey &&
                searchCandidateTrustedForReadingCatalog(candidate)
        }
        if (exactAnchors.isEmpty()) return candidates
        return candidates.filter { candidate ->
            val titleKey = searchRanker.canonicalTitleKey(candidate.book)
            titleGroupMatchesKeyword(queryKey, titleKey) ||
                exactAnchors.any { exact -> sameValidatedBookIdentity(exact, candidate) }
        }
    }

    private fun catalogPrefixExpandedValidatedGroupsWithoutQuery(
        candidates: List<ValidatedSearchCandidate>
    ): List<List<ValidatedSearchCandidate>> {
        if (candidates.isEmpty()) return emptyList()
        val identityGroups = titleAuthorCompatibleValidatedGroups(candidates)
        var expanded = true
        while (expanded) {
            expanded = false
            var leftIndex = 0
            while (leftIndex < identityGroups.size) {
                var rightIndex = leftIndex + 1
                while (rightIndex < identityGroups.size) {
                    if (identityGroups[leftIndex].any { left ->
                            identityGroups[rightIndex].any { right -> sameValidatedBookIdentity(left, right) }
                        }
                    ) {
                        identityGroups[leftIndex].addAll(identityGroups.removeAt(rightIndex))
                        expanded = true
                    } else {
                        rightIndex += 1
                    }
                }
                leftIndex += 1
            }
        }
        return identityGroups
    }

    private fun titleAuthorCompatibleValidatedGroups(
        candidates: List<ValidatedSearchCandidate>
    ): MutableList<MutableList<ValidatedSearchCandidate>> {
        val groups = ArrayList<MutableList<ValidatedSearchCandidate>>()
        candidates.forEach { candidate ->
            val existing = groups.firstOrNull { group ->
                group.any { current -> sameValidatedTitleAuthorIdentity(current, candidate) }
            }
            if (existing == null) {
                groups.add(mutableListOf(candidate))
            } else {
                existing.add(candidate)
            }
        }
        return groups
    }

    private fun mergeCatalogPrefixEquivalentCandidates(
        keyword: String,
        candidates: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        if (candidates.size < 2) return candidates
        val queryKey = normalizeHint(keyword)
        val consumed = BooleanArray(candidates.size)
        val merged = ArrayList<ValidatedSearchCandidate>(candidates.size)
        candidates.indices.forEach { index ->
            if (consumed[index]) return@forEach
            consumed[index] = true
            val group = ArrayList<ValidatedSearchCandidate>()
            group.add(candidates[index])
            var expanded = true
            while (expanded) {
                expanded = false
                candidates.indices.forEach { otherIndex ->
                    if (!consumed[otherIndex] && group.any { candidate ->
                            sameValidatedBookIdentity(candidate, candidates[otherIndex])
                        }
                    ) {
                        consumed[otherIndex] = true
                        group.add(candidates[otherIndex])
                        expanded = true
                    }
                }
            }
            val selected = selectCatalogPrefixMergedCandidate(queryKey, group)
            if (group.size > 1) {
                AiBridgeTrace.event(
                    "source_search_catalog_prefix_merged",
                    keyword,
                    "selected_${selected.book.name}@${selected.book.author}".debugToken() +
                        "_count_${group.size}" +
                        "_items_${group.joinToString("|") { candidate ->
                            "${candidate.book.name}@${candidate.book.author}/${candidate.chapterCount}"
                        }.debugToken()}"
                )
            }
            merged.add(selected)
        }
        return merged
    }

    private fun selectCatalogPrefixMergedCandidate(
        queryKey: String,
        group: List<ValidatedSearchCandidate>
    ): ValidatedSearchCandidate {
        val selected = group.minWithOrNull(validatedSearchComparatorForQuery(queryKey)) ?: group.first()
        if (group.size < 2) return selected
        val coverCandidates = coverCandidateUrls(
            selected.book.coverUrl,
            group.flatMap { candidate -> listOf(candidate.book.coverUrl) + candidate.coverCandidates }
        )
        val merged = selected.copy(
            validation = selected.validation + "+catalog-prefix-merged",
            coverCandidates = coverCandidates
        )
        rememberSearchSessionEvidence(
            selectedBook = merged.book,
            group = group,
            trustedGroup = group.filter { candidate -> searchCandidateTrustedForReadingCatalog(candidate) }
        )
        return merged
    }

    private fun sameBookByCatalogPrefix(
        left: ValidatedSearchCandidate,
        right: ValidatedSearchCandidate
    ): Boolean {
        val leftResolved = left.resolved ?: return false
        val rightResolved = right.resolved ?: return false
        return sameBookByCatalogPrefix(
            leftBook = left.book,
            leftCatalog = leftResolved.catalog,
            rightBook = right.book,
            rightCatalog = rightResolved.catalog
        )
    }

    private fun sameBookByCatalogPrefix(
        leftBook: SourceBook,
        leftCatalog: CanonicalChapterList,
        rightBook: SourceBook,
        rightCatalog: CanonicalChapterList
    ): Boolean {
        val leftTitle = searchRanker.canonicalTitleKey(leftBook)
        val rightTitle = searchRanker.canonicalTitleKey(rightBook)
        val sameTitle = leftTitle.isNotBlank() && leftTitle == rightTitle
        val titleContained = !sameTitle && sameOrContainedSearchKey(leftTitle, rightTitle)
        val authorsCompatible = BookIdentity.authorsCompatible(leftBook.author, rightBook.author)
        if (sameTitle && !authorsCompatible) return false
        if (sameTitle) return true
        val titleAlias = !titleContained &&
            authorsCompatible &&
            catalogAliasTitleMayNeedValidation(leftTitle, rightTitle)
        if (!sameTitle && !titleContained && !titleAlias) return false
        if (!catalogPrefixesRoughlySimilar(leftCatalog, rightCatalog)) return false
        return catalogPrefixHasDistinctiveTitles(
            leftCatalog.chapters.take(SEARCH_CATALOG_PREFIX_COMPARE_CHAPTERS)
        )
    }

    private fun sameOrContainedSearchKey(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true
        return (left.length >= MIN_CONTAINED_SEARCH_KEY_CHARS && right.contains(left)) ||
            (right.length >= MIN_CONTAINED_SEARCH_KEY_CHARS && left.contains(right))
    }

    private fun catalogPrefixKeys(
        chapters: List<CanonicalChapter>,
        count: Int,
        distinctiveOnly: Boolean = false
    ): List<String> {
        return chapters.take(count)
            .filter { chapter -> !distinctiveOnly || catalogPrefixTitleIsDistinctive(chapter) }
            .mapNotNull { chapter -> catalogPrefixKey(chapter) }
    }

    private fun catalogPrefixKey(chapter: CanonicalChapter): String? {
        val normalized = chapterNormalizer.normalize(chapter.displayTitle)
        val titleKey = normalizeHint(normalized.displayTitle.ifBlank { chapter.displayTitle })
        if (titleKey.isBlank()) return null
        val ordinal = normalized.ordinal ?: chapter.ordinal ?: -1
        return "$ordinal:$titleKey"
    }

    private fun catalogPrefixHasDistinctiveTitles(chapters: List<CanonicalChapter>): Boolean {
        val distinctiveCount = chapters.count { chapter -> catalogPrefixTitleIsDistinctive(chapter) }
        return distinctiveCount >= MIN_SEARCH_CATALOG_PREFIX_DISTINCTIVE_TITLES
    }

    private fun catalogPrefixTitleIsDistinctive(chapter: CanonicalChapter): Boolean {
        val suffix = chapterTitleSuffixKey(chapter.displayTitle)
        return suffix.length >= MIN_SEARCH_CATALOG_PREFIX_DISTINCTIVE_SUFFIX_CHARS &&
            suffix !in GENERIC_SEARCH_CATALOG_PREFIX_SUFFIXES
    }

    private fun catalogPrefixesRoughlySimilar(
        leftCatalog: CanonicalChapterList,
        rightCatalog: CanonicalChapterList
    ): Boolean {
        val compareCount = minOf(
            SEARCH_CATALOG_PREFIX_COMPARE_CHAPTERS,
            leftCatalog.chapters.size,
            rightCatalog.chapters.size
        )
        if (compareCount < MIN_SEARCH_CATALOG_PREFIX_MATCH_CHAPTERS) return false
        val leftPrefix = catalogPrefixKeys(leftCatalog.chapters, compareCount, distinctiveOnly = true).toSet()
        val rightPrefix = catalogPrefixKeys(rightCatalog.chapters, compareCount, distinctiveOnly = true).toSet()
        val comparableCount = minOf(leftPrefix.size, rightPrefix.size)
        if (comparableCount < MIN_SEARCH_CATALOG_PREFIX_SIMILAR_MATCH_CHAPTERS) return false
        val overlap = leftPrefix.count { key -> key in rightPrefix }
        val required = maxOf(
            MIN_SEARCH_CATALOG_PREFIX_SIMILAR_MATCH_CHAPTERS.coerceAtMost(comparableCount),
            (comparableCount * MIN_SEARCH_CATALOG_PREFIX_SIMILAR_MATCH_PERCENT + 99) / 100
        )
        return overlap >= required
    }

    private fun shouldWaitForExactTitleAuthorGroups(
        queryKey: String,
        titleGroups: List<SearchTitleGroup>
    ): Boolean {
        return preferredProgressIdentityKeys(queryKey, titleGroups).isNotEmpty()
    }

    private fun preferredProgressIdentityKeys(
        queryKey: String,
        titleGroups: List<SearchTitleGroup>
    ): Set<String> {
        val exactGroups = titleGroups.filter { group -> group.titleKey == queryKey }
        if (exactGroups.isEmpty()) return emptySet()
        val maxSourceCount = exactGroups.maxOf { group -> group.candidates.uniqueSearchSourceCount() }
        if (maxSourceCount < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) return emptySet()
        return exactGroups
            .filter { group -> group.candidates.uniqueSearchSourceCount() == maxSourceCount }
            .mapTo(LinkedHashSet()) { group -> group.identityKey }
    }

    private fun exactProgressTitleGroups(
        queryKey: String,
        groups: List<SearchTitleGroup>
    ): List<SearchTitleGroup> {
        if (queryKey.length < MIN_COMPLETION_QUERY_CHARS) return emptyList()
        val exactAuthoredGroups = groups.filter { group ->
            group.titleKey == queryKey &&
                searchIdentityHasAuthor(group.identityKey) &&
                group.candidates.uniqueSearchSourceCount() >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
        }
        if (exactAuthoredGroups.isEmpty()) return emptyList()
        val maxSourceCount = exactAuthoredGroups.maxOf { group -> group.candidates.uniqueSearchSourceCount() }
        val selected = exactAuthoredGroups
            .filter { group -> group.candidates.uniqueSearchSourceCount() == maxSourceCount }
            .sortedWith(searchTitleGroupComparator(queryKey))
            .take(MAX_EXACT_PROGRESS_VALIDATION_TITLE_GROUPS)
        AiBridgeTrace.state(
            "source_search_exact_progress_groups",
            queryKey,
            "groups_${selected.size}_exact_${exactAuthoredGroups.size}_maxSources_${maxSourceCount}" +
                "_top_${selected.joinToString("_") { group ->
                    "${group.identityKey.replace('\n', '@')}/${group.candidates.uniqueSearchSourceCount()}".debugToken()
                }}"
        )
        return selected
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
                        if (visibleMerged.isNotEmpty() && preferredReady) {
                            return@withTimeoutOrNull
                        }
                        if (visibleMerged.size >= targetResultCount && preferredReady) {
                            return@withTimeoutOrNull
                        }
                    }
                }
            }
        } finally {
            val cancelled = pending.count { deferred -> !deferred.isCompleted }
            if (cancelled > 0) {
                AiBridgeTrace.event(
                    "source_search_progress_group_wait_cancelled",
                    keyword,
                    "timeoutMs_${timeoutMs}" +
                        "_completedGroups_${completed.size}" +
                        "_pendingGroups_${cancelled}" +
                        "_preferred_${preferredIdentityKeys.joinToString("|") { key -> key.replace('\n', '@').debugToken() }}"
                )
            }
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
        if (candidate.validation == SEARCH_DETAIL_DISPLAY_PREVIEW_VALIDATION) {
            AiBridgeTrace.event(
                "source_search_progress_candidate_ready",
                candidate.book.name,
                "source_${sourceLabel(candidate.book).debugToken()}" +
                    "_chapters_${candidate.chapterCount}" +
                    "_validation_${candidate.validation.debugToken()}" +
                    "_resolved_0"
            )
            return true
        }
        val resolved = candidate.resolved
        if (resolved == null) {
            AiBridgeTrace.event(
                "source_search_progress_candidate_deferred",
                candidate.book.name,
                "reason_missing_resolved_source_${sourceLabel(candidate.book).debugToken()}" +
                    "_chapters_${candidate.chapterCount}"
            )
            return false
        }
        AiBridgeTrace.event(
            "source_search_progress_candidate_ready",
            candidate.book.name,
            "source_${sourceLabel(candidate.book).debugToken()}" +
                "_chapters_${candidate.chapterCount}" +
                "_validation_${candidate.validation.debugToken()}" +
                "_resolved_${resolved.catalog.chapters.size}"
        )
        return true
    }

    private fun ValidatedSearchCandidate.lastCatalogTitle(): String {
        return resolved?.catalog?.chapters?.lastOrNull()?.displayTitle.orEmpty()
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
        val rawExactGroups = groups.filter { group -> group.titleKey == queryKey }
        if (rawExactGroups.isEmpty()) return groups
        val strongAuthoredExactGroups = rawExactGroups.filter { group ->
            searchIdentityHasAuthor(group.identityKey) &&
                group.candidates.uniqueSearchSourceCount() >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
        }
        val suppressedTitleOnlyKeys = if (progressive && strongAuthoredExactGroups.isNotEmpty()) {
            rawExactGroups
                .filter { group -> !searchIdentityHasAuthor(group.identityKey) }
                .mapTo(LinkedHashSet()) { group -> group.identityKey }
        } else {
            emptySet()
        }
        val filteredGroups = if (suppressedTitleOnlyKeys.isEmpty()) {
            groups
        } else {
            groups.filterNot { group -> group.identityKey in suppressedTitleOnlyKeys }
        }
        val exactGroups = filteredGroups.filter { group -> group.titleKey == queryKey }
        if (exactGroups.isEmpty()) return filteredGroups
        val relatedConsensusGroups = filteredGroups.filter { group ->
            group.titleKey != queryKey &&
                (
                    group.titleKey.length >= MIN_EXACT_GROUP_ONLY_QUERY_CHARS ||
                        (
                            queryKey.contains(group.titleKey) &&
                                group.titleKey.length >= MIN_CONTAINED_SEARCH_KEY_CHARS
                            )
                    ) &&
                group.candidates.uniqueSearchSourceCount() >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
        }
        val exactAuthorKeys = exactGroups
            .flatMap { group -> group.authorConsensus.keys }
            .filter { author -> author.isNotBlank() }
            .toSet()
        val relatedForeignLongerTitleOnly = relatedConsensusGroups.isNotEmpty() &&
            exactAuthorKeys.isNotEmpty() &&
            relatedConsensusGroups.all { group ->
                group.titleKey.contains(queryKey) &&
                    group.authorConsensus.keys.none { author -> author in exactAuthorKeys }
            }
        val exactOnly = exactTitleOnlyReady(queryKey, exactGroups, progressive) &&
            (
                relatedConsensusGroups.isEmpty() ||
                    (queryKey.length >= MIN_EXACT_GROUP_ONLY_QUERY_CHARS && relatedForeignLongerTitleOnly)
                )
        val exactMaxSources = exactGroups.maxOf { group -> group.candidates.uniqueSearchSourceCount() }
        AiBridgeTrace.state(
            "source_search_exact_title_groups",
            queryKey,
            "exact_${exactGroups.size}_maxSources_${exactMaxSources}" +
                "_relatedConsensus_${relatedConsensusGroups.size}" +
                "_relatedForeignLongerOnly_${relatedForeignLongerTitleOnly}" +
                "_suppressed_${if (exactOnly) groups.size - exactGroups.size else 0}" +
                "_suppressedTitleOnly_${suppressedTitleOnlyKeys.size}" +
                "_mode_${if (progressive) "progress" else "completed"}" +
                "_exactOnly_${exactOnly}"
        )
        return if (exactOnly) exactGroups else filteredGroups
    }

    private fun validationTitleGroupsForMode(
        groups: List<SearchTitleGroup>,
        rankMode: SearchRankMode
    ): List<SearchTitleGroup> {
        return when (rankMode) {
            SearchRankMode.EXACT_PROGRESS -> groups.take(MAX_EXACT_PROGRESS_VALIDATION_TITLE_GROUPS)
            SearchRankMode.PROGRESS -> groups.take(MAX_PROGRESS_VALIDATION_TITLE_GROUPS)
            SearchRankMode.COMPLETED -> groups
        }
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

    private fun searchIdentityHasAuthor(identityKey: String): Boolean {
        return identityKey.substringAfter('\n', "").isNotBlank()
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

    private fun validatedSearchComparatorForQuery(
        queryKey: String
    ): Comparator<ValidatedSearchCandidate> {
        return compareByDescending<ValidatedSearchCandidate> { candidate ->
            if (searchRanker.canonicalTitleKey(candidate.book) == queryKey) 1 else 0
        }.then(validatedSearchComparator)
    }

    private fun List<RankedSearchBook>.uniqueSearchSourceCount(): Int {
        return map { ranked -> ranked.book.source.sourceUrl.ifBlank { ranked.sourceIndex.toString() } }
            .toSet()
            .size
    }

    private suspend fun mergeValidatedTitleGroup(
        group: List<ValidatedSearchCandidate>,
        fastForProgress: Boolean = false,
        displayPreviewQueryKey: String? = null
    ): ValidatedSearchCandidate? {
        val catalogBackedGroup = group.filter { candidate ->
            searchCandidateTrustedForReadingCatalog(candidate)
        }
        val displayPreview = if (fastForProgress) {
            displayPreviewMergedCandidate(group, displayPreviewQueryKey)
        } else {
            null
        }
        val scopedCatalogBackedGroup = queryScopedCatalogBackedGroup(
            group = group,
            catalogBackedGroup = catalogBackedGroup,
            queryKey = displayPreviewQueryKey
        )
        val consensusCatalogGroup = trustedCatalogEquivalentConsensusGroup(scopedCatalogBackedGroup)
        val consensusSourceCount = consensusCatalogGroup.uniqueValidatedSearchSourceCount()
        if (consensusSourceCount < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) {
            if (displayPreview != null) return displayPreview
            traceSearchTitleGroupRejected(group, "insufficient-trusted")
            return null
        }
        val consensusAnchor = consensusCatalogGroup.firstOrNull { candidate ->
            normalizedAuthor(candidate.book.author).isNotBlank()
        } ?: consensusCatalogGroup.first()
        val consensusKey = validatedTitleAuthorConsensusKey(consensusAnchor).orEmpty()
        val metadataGroup = group.filter { candidate ->
            sameValidatedBookIdentity(candidate, consensusAnchor) &&
                searchCandidateTrustedForDisplayMetadata(candidate)
        }
        if (metadataGroup.isEmpty()) {
            traceSearchTitleGroupRejected(group, "missing-trusted-metadata")
            return null
        }
        val readingCandidates = readingCandidatesForConsensus(group, consensusKey, consensusAnchor)
        if (readingCandidates.isEmpty()) {
            if (displayPreview != null) return displayPreview
            traceSearchTitleGroupRejected(group, "missing-complete-reading-catalog")
            return null
        }
        val readingSelectionPool = publishableReadingCandidates(
            readingCandidates,
            if (fastForProgress) "progress" else "completed"
        )
        if (readingSelectionPool.isEmpty()) {
            if (displayPreview != null) return displayPreview
            traceSearchTitleGroupRejected(group, "insufficient-reading-catalog-consensus")
            return null
        }
        val readingCandidate = if (fastForProgress) {
            selectProgressReadingCandidate(readingSelectionPool)
        } else {
            selectReadingCandidate(readingSelectionPool)
        }
        val metadataCandidate = metadataGroup.minWithOrNull(metadataCandidateComparator)
            ?: consensusCatalogGroup.minWithOrNull(metadataCandidateComparator)
            ?: consensusCatalogGroup.first()
        val displayAnchor = displayAnchorCandidateForQuery(
            group = group,
            consensusAnchor = consensusAnchor,
            queryKey = displayPreviewQueryKey
        )
        val coverCandidate = if (hasTrustedSearchCover(readingCandidate)) {
            readingCandidate
        } else {
            metadataGroup
                .filter { candidate -> hasTrustedSearchCover(candidate) }
                .minWithOrNull(coverCandidateComparator)
        }
        val selectedCoverQuality = coverCandidate?.trustedSearchCoverQuality() ?: readingCandidate.coverQuality
        val consensusGroup = group.filter { candidate ->
            sameValidatedBookIdentity(candidate, consensusAnchor)
        }
        val displayAuthorGroup = consensusGroup.filter { candidate ->
            sameValidatedTitleAuthorIdentity(candidate, displayAnchor ?: consensusAnchor)
        }
        val displayAuthor = displayAuthorGroup
            .map { candidate -> candidate.book.author }
            .fold((displayAnchor ?: metadataCandidate).book.author) { current, author ->
                BookIdentity.preferredDisplayAuthor(current, author)
            }
        val selectedCoverCandidates = mergedSearchCoverCandidates(
            primary = coverCandidate?.book?.coverUrl ?: readingCandidate.book.coverUrl,
            group = consensusGroup + readingCandidate
        )
        val selectedBook = readingCandidate.book.copy(
            name = displayAnchor?.book?.name ?: readingCandidate.book.name,
            author = displayAuthor,
            intro = metadataCandidate.book.intro,
            coverUrl = coverCandidate?.book?.coverUrl ?: readingCandidate.book.coverUrl
        )
        val score = consensusGroup.maxOf { candidate -> candidate.ranked.score } +
            catalogScoreForCount(readingCandidate.chapterCount) +
            coverScore(selectedCoverQuality)
        val merged = readingCandidate.copy(
            book = selectedBook,
            score = score,
            coverQuality = selectedCoverQuality,
            validation = readingCandidate.validation + "+merged-cover",
            coverCandidates = selectedCoverCandidates
        )
        AiBridgeTrace.event(
            "source_search_group_merged",
            readingCandidate.book.name,
            "trusted_${consensusSourceCount}_metadata_${metadataGroup.uniqueValidatedSearchSourceCount()}" +
                "_readingPool_${readingCandidates.size}" +
                "_reading_${sourceLabel(readingCandidate.book).debugToken()}" +
                "_readingChapters_${readingCandidate.chapterCount}" +
                "_readingLast_${readingCandidate.lastCatalogTitle().debugToken()}" +
                "_cover_${sourceLabel(coverCandidate?.book ?: readingCandidate.book).debugToken()}"
        )
        val evidenceGroup = (consensusGroup + readingCandidate)
            .distinctBy { candidate -> sourceBookKey(candidate.book) }
        val trustedEvidenceGroup = (consensusCatalogGroup + readingCandidate)
            .distinctBy { candidate -> sourceBookKey(candidate.book) }
        rememberSearchSessionEvidence(merged.book, evidenceGroup, trustedEvidenceGroup)
        return merged
    }

    private fun displayAnchorCandidateForQuery(
        group: List<ValidatedSearchCandidate>,
        consensusAnchor: ValidatedSearchCandidate,
        queryKey: String?
    ): ValidatedSearchCandidate? {
        val normalizedQuery = queryKey.orEmpty()
        if (normalizedQuery.length < MIN_EXACT_GROUP_ONLY_QUERY_CHARS) return null
        return group
            .filter { candidate ->
                searchRanker.canonicalTitleKey(candidate.book) == normalizedQuery &&
                    sameValidatedBookIdentity(candidate, consensusAnchor)
            }
            .minWithOrNull(metadataCandidateComparator)
    }

    private fun queryScopedCatalogBackedGroup(
        group: List<ValidatedSearchCandidate>,
        catalogBackedGroup: List<ValidatedSearchCandidate>,
        queryKey: String?
    ): List<ValidatedSearchCandidate> {
        val normalizedQuery = queryKey.orEmpty()
        if (normalizedQuery.length < MIN_EXACT_GROUP_ONLY_QUERY_CHARS) return catalogBackedGroup
        val hasExactQueryCandidate = group.any { candidate ->
            searchRanker.canonicalTitleKey(candidate.book) == normalizedQuery
        }
        if (!hasExactQueryCandidate) return catalogBackedGroup
        val exactCatalogCandidates = catalogBackedGroup.filter { candidate ->
            searchRanker.canonicalTitleKey(candidate.book) == normalizedQuery
        }
        val scoped = if (exactCatalogCandidates.isEmpty()) {
            emptyList()
        } else {
            catalogBackedGroup.filter { candidate ->
                exactCatalogCandidates.any { exact -> sameValidatedBookIdentity(exact, candidate) }
            }
        }
        if (scoped.size != catalogBackedGroup.size) {
            AiBridgeTrace.event(
                "source_search_query_scoped_catalog",
                normalizedQuery,
                "before_${catalogBackedGroup.size}_after_${scoped.size}" +
                    "_exactCatalog_${exactCatalogCandidates.size}" +
                    "_exactCandidates_${group.count { candidate ->
                        searchRanker.canonicalTitleKey(candidate.book) == normalizedQuery
                    }}"
            )
        }
        return scoped
    }

    private fun displayPreviewMergedCandidate(
        group: List<ValidatedSearchCandidate>,
        queryKey: String?
    ): ValidatedSearchCandidate? {
        val previewGroup = group
            .filter { candidate -> searchCandidateTrustedForDisplayPreview(candidate) }
            .let { candidates ->
                if (queryKey.orEmpty().length >= MIN_EXACT_GROUP_ONLY_QUERY_CHARS) {
                    candidates.filter { candidate -> searchRanker.canonicalTitleKey(candidate.book) == queryKey }
                } else {
                    candidates
                }
            }
        val consensusGroup = trustedTitleAuthorConsensusGroup(previewGroup)
        val consensusSourceCount = consensusGroup.uniqueValidatedSearchSourceCount()
        if (consensusSourceCount < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) return null
        if (consensusGroup.none { candidate -> searchPreviewCandidateHasLongLastChapter(candidate) }) {
            return null
        }
        val metadataGroup = consensusGroup.filter { candidate ->
            cleanIntro(candidate.book.intro).isNotBlank() || hasTrustedSearchCover(candidate)
        }
        if (metadataGroup.isEmpty()) return null
        val metadataCandidate = metadataGroup.minWithOrNull(metadataCandidateComparator)
            ?: consensusGroup.minWithOrNull(metadataCandidateComparator)
            ?: consensusGroup.first()
        val coverCandidate = metadataGroup
            .filter { candidate -> hasTrustedSearchCover(candidate) }
            .minWithOrNull(coverCandidateComparator)
        val selectedCoverQuality = coverCandidate?.trustedSearchCoverQuality() ?: metadataCandidate.coverQuality
        val displayAuthor = consensusGroup
            .map { candidate -> candidate.book.author }
            .fold(metadataCandidate.book.author) { current, author ->
                BookIdentity.preferredDisplayAuthor(current, author)
            }
        val selectedCoverCandidates = mergedSearchCoverCandidates(
            primary = coverCandidate?.book?.coverUrl ?: metadataCandidate.book.coverUrl,
            group = consensusGroup
        )
        val selectedBook = metadataCandidate.book.copy(
            author = displayAuthor,
            intro = metadataCandidate.book.intro,
            coverUrl = coverCandidate?.book?.coverUrl ?: metadataCandidate.book.coverUrl
        )
        val score = consensusGroup.maxOf { candidate -> candidate.ranked.score } +
            coverScore(selectedCoverQuality) +
            sourceQualityRouter.routeScoreBoost(selectedBook)
        val merged = metadataCandidate.copy(
            book = selectedBook,
            score = score,
            chapterCount = 0,
            coverQuality = selectedCoverQuality,
            validation = SEARCH_DETAIL_DISPLAY_PREVIEW_VALIDATION,
            resolved = null,
            pageCatalog = false,
            coverCandidates = selectedCoverCandidates
        )
        AiBridgeTrace.event(
            "source_search_display_preview_merged",
            merged.book.name,
            "trusted_${consensusSourceCount}" +
                "_metadata_${metadataGroup.uniqueValidatedSearchSourceCount()}" +
                "_cover_${sourceLabel(coverCandidate?.book ?: metadataCandidate.book).debugToken()}" +
                "_sources_${consensusGroup.joinToString("|") { candidate ->
                    sourceLabel(candidate.book).debugToken()
                }}"
        )
        rememberSearchSessionEvidence(merged.book, consensusGroup, emptyList())
        return merged
    }

    private fun searchPreviewCandidateHasLongLastChapter(candidate: ValidatedSearchCandidate): Boolean {
        val ordinal = chapterNormalizer.normalize(candidate.book.lastChapter.orEmpty()).ordinal ?: 0
        return ordinal > MIN_SEARCH_LONG_CATALOG_CHAPTERS
    }

    private fun readingCandidatesForConsensus(
        group: List<ValidatedSearchCandidate>,
        consensusKey: String,
        consensusAnchor: ValidatedSearchCandidate
    ): List<ValidatedSearchCandidate> {
        val titleKey = consensusKey.substringBefore('\n')
        val authorKey = consensusKey.substringAfter('\n', "")
        return group.filter { candidate ->
            if (!searchCandidateTrustedForReadingCatalog(candidate)) return@filter false
            val sameIdentity = searchRanker.canonicalTitleKey(candidate.book) == titleKey &&
                (
                    normalizedAuthor(candidate.book.author) == authorKey ||
                        BookIdentity.authorsCompatible(candidate.book.author, consensusAnchor.book.author)
                    )
            sameIdentity || sameBookByCatalogPrefix(consensusAnchor, candidate)
        }
    }

    private fun singleSourceExactLongCatalogFallback(
        group: List<ValidatedSearchCandidate>,
        catalogBackedGroup: List<ValidatedSearchCandidate>,
        queryKey: String?
    ): ValidatedSearchCandidate? {
        if (queryKey.isNullOrBlank()) return null
        val exactIdentityGroup = group.filter { candidate ->
            searchRanker.canonicalTitleKey(candidate.book) == queryKey &&
                normalizedAuthor(candidate.book.author).isNotBlank()
        }
        if (exactIdentityGroup.uniqueValidatedSearchSourceCount() < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) {
            return null
        }
        return catalogBackedGroup
            .filter { candidate ->
                searchRanker.canonicalTitleKey(candidate.book) == queryKey &&
                    candidate.chapterCount > MIN_SEARCH_LONG_CATALOG_CHAPTERS
            }
            .minWithOrNull(validatedSearchComparatorForQuery(queryKey))
    }

    private fun trustedTitleAuthorConsensusGroup(
        candidates: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        val groups = ArrayList<MutableList<ValidatedSearchCandidate>>()
        candidates.filter { candidate -> searchRanker.canonicalTitleKey(candidate.book).isNotBlank() }
            .forEach { candidate ->
                val existing = groups.firstOrNull { group ->
                    group.any { current -> sameValidatedTitleAuthorIdentity(current, candidate) }
                }
                if (existing == null) {
                    groups.add(mutableListOf(candidate))
                } else {
                    existing.add(candidate)
                }
            }
        return groups.filter { group ->
            group.any { candidate -> normalizedAuthor(candidate.book.author).isNotBlank() }
        }.sortedWith(
            compareByDescending<List<ValidatedSearchCandidate>> { group -> group.uniqueValidatedSearchSourceCount() }
                .thenByDescending { group -> group.maxOf { candidate -> candidate.score } }
                .thenBy { group -> group.minOf { candidate -> candidate.ranked.sourceIndex } }
        )
            .firstOrNull()
            .orEmpty()
    }

    private fun trustedCatalogEquivalentConsensusGroup(
        candidates: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        val groups = ArrayList<MutableList<ValidatedSearchCandidate>>()
        candidates.filter { candidate -> searchRanker.canonicalTitleKey(candidate.book).isNotBlank() }
            .forEach { candidate ->
                val existing = groups.firstOrNull { group ->
                    group.any { current -> sameValidatedBookIdentity(current, candidate) }
                }
                if (existing == null) {
                    groups.add(mutableListOf(candidate))
                } else {
                    existing.add(candidate)
                }
            }
        return groups.filter { group ->
            group.any { candidate -> normalizedAuthor(candidate.book.author).isNotBlank() }
        }.sortedWith(
            compareByDescending<List<ValidatedSearchCandidate>> { group -> group.uniqueValidatedSearchSourceCount() }
                .thenByDescending { group -> group.maxOf { candidate -> candidate.score } }
                .thenBy { group -> group.minOf { candidate -> candidate.ranked.sourceIndex } }
        )
            .firstOrNull()
            .orEmpty()
    }

    private fun List<ValidatedSearchCandidate>.uniqueValidatedSearchSourceCount(): Int {
        return map { candidate -> candidate.book.source.sourceUrl.ifBlank { candidate.ranked.sourceIndex.toString() } }
            .toSet()
            .size
    }

    private fun mergedSearchCoverCandidates(
        primary: String?,
        group: List<ValidatedSearchCandidate>
    ): List<String> {
        val trusted = group
            .filter { candidate -> hasTrustedSearchCover(candidate) }
            .sortedWith(coverCandidateComparator)
            .flatMap { candidate -> listOf(candidate.book.coverUrl) + candidate.coverCandidates }
        return coverCandidateUrls(primary, trusted)
    }

    private fun validatedTitleAuthorConsensusKey(candidate: ValidatedSearchCandidate): String? {
        val titleKey = searchRanker.canonicalTitleKey(candidate.book)
        val authorKey = normalizedAuthor(candidate.book.author)
        if (titleKey.isBlank() || authorKey.isBlank()) return null
        return "$titleKey\n$authorKey"
    }

    private fun sameValidatedTitleAuthorIdentity(
        first: ValidatedSearchCandidate,
        second: ValidatedSearchCandidate
    ): Boolean {
        val firstTitle = searchRanker.canonicalTitleKey(first.book)
        val secondTitle = searchRanker.canonicalTitleKey(second.book)
        if (firstTitle.isBlank() || firstTitle != secondTitle) return false
        return BookIdentity.authorsCompatible(first.book.author, second.book.author)
    }

    private fun sameValidatedBookIdentity(
        first: ValidatedSearchCandidate,
        second: ValidatedSearchCandidate
    ): Boolean {
        return sameValidatedTitleAuthorIdentity(first, second) ||
            sameBookByCatalogPrefix(first, second)
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
                "_cover_${selected.coverQuality.usable}" +
                "_validation_${selected.validation.debugToken()}"
        )
        return selected
    }

    private suspend fun selectReadingCandidate(
        candidates: List<ValidatedSearchCandidate>
    ): ValidatedSearchCandidate {
        val signals = candidates.map { candidate ->
            val readableChapters = candidate.resolved?.let { resolved ->
                probeCatalogTail(resolved)
                resolved.catalog.chapters
            }.orEmpty()
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

    private fun publishableReadingCandidates(
        candidates: List<ValidatedSearchCandidate>,
        stage: String
    ): List<ValidatedSearchCandidate> {
        val longCatalogCandidates = candidates.filter { candidate ->
            candidate.chapterCount > MIN_SEARCH_LONG_CATALOG_CHAPTERS
        }
        if (longCatalogCandidates.isNotEmpty()) {
            val first = candidates.first()
            AiBridgeTrace.event(
                "source_search_reading_catalog_consensus",
                first.book.name,
                "stage_${stage}_mode_long_catalog" +
                    "_kept_${longCatalogCandidates.size}_total_${candidates.size}" +
                    "_sources_${longCatalogCandidates.uniqueValidatedSearchSourceCount()}" +
                    "_items_${longCatalogCandidates.joinToString("|") { candidate ->
                        "${sourceLabel(candidate.book).debugToken()}:${candidate.chapterCount}"
                    }}"
            )
            return longCatalogCandidates
        }
        val shortConsensus = shortCatalogConsensusCandidates(candidates)
        if (shortConsensus.isNotEmpty()) {
            val first = shortConsensus.first()
            AiBridgeTrace.event(
                "source_search_reading_catalog_consensus",
                first.book.name,
                "stage_${stage}_mode_short_four_source" +
                    "_kept_${shortConsensus.size}_total_${candidates.size}" +
                    "_sources_${shortConsensus.uniqueValidatedSearchSourceCount()}" +
                    "_items_${shortConsensus.joinToString("|") { candidate ->
                        "${sourceLabel(candidate.book).debugToken()}:${candidate.chapterCount}"
                    }}"
            )
            return shortConsensus
        }
        val first = candidates.firstOrNull() ?: return emptyList()
        AiBridgeTrace.event(
            "source_search_reading_catalog_consensus_deferred",
            first.book.name,
            "stage_${stage}_reason_short_catalog_needs_four_sources" +
                "_total_${candidates.size}" +
                "_sources_${candidates.uniqueValidatedSearchSourceCount()}" +
                "_items_${candidates.joinToString("|") { candidate ->
                    "${sourceLabel(candidate.book).debugToken()}:${candidate.chapterCount}"
                }}"
        )
        return emptyList()
    }

    private fun shortCatalogConsensusCandidates(
        candidates: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        return shortCatalogConsensusGroup(candidates)
            .takeIf { group ->
                group.uniqueValidatedSearchSourceCount() >= SHORT_CATALOG_CONSENSUS_SOURCE_COUNT
            }
            .orEmpty()
    }

    private fun shortCatalogConsensusGroup(
        candidates: List<ValidatedSearchCandidate>
    ): List<ValidatedSearchCandidate> {
        val shortCandidates = candidates.filter { candidate ->
            candidate.chapterCount in 1..MIN_SEARCH_LONG_CATALOG_CHAPTERS &&
                candidate.resolved?.catalog?.chapters?.isNotEmpty() == true
        }
        return shortCandidates
            .map { seed ->
                shortCandidates.filter { candidate -> shortCatalogsRoughlySimilar(seed, candidate) }
            }
            .maxWithOrNull(
                compareBy<List<ValidatedSearchCandidate>> { group -> group.uniqueValidatedSearchSourceCount() }
                    .thenBy { group -> group.maxOf { candidate -> candidate.chapterCount } }
                    .thenBy { group -> group.maxOf { candidate -> candidate.score } }
            )
            .orEmpty()
    }

    private fun shortCatalogsRoughlySimilar(
        left: ValidatedSearchCandidate,
        right: ValidatedSearchCandidate
    ): Boolean {
        if (left === right) return true
        val leftChapters = left.resolved?.catalog?.chapters ?: return false
        val rightChapters = right.resolved?.catalog?.chapters ?: return false
        val compareCount = minOf(
            SEARCH_SHORT_CATALOG_COMPARE_CHAPTERS,
            leftChapters.size,
            rightChapters.size
        )
        if (compareCount <= 0) return false
        val leftKeys = catalogPrefixKeys(leftChapters, compareCount).toSet()
        val rightKeys = catalogPrefixKeys(rightChapters, compareCount).toSet()
        if (leftKeys.isEmpty() || rightKeys.isEmpty()) return false
        val overlap = leftKeys.count { key -> key in rightKeys }
        val required = maxOf(
            MIN_SEARCH_SHORT_CATALOG_MATCH_CHAPTERS.coerceAtMost(compareCount),
            (compareCount * MIN_SEARCH_SHORT_CATALOG_MATCH_PERCENT + 99) / 100
        )
        return overlap >= required
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
        return (searchCandidateTrustedForReadingCatalog(candidate) || searchCandidateTrustedForDisplayPreview(candidate)) &&
            normalizedAuthor(candidate.book.author).isNotBlank()
    }

    private fun searchCandidateTrustedForReadingCatalog(candidate: ValidatedSearchCandidate): Boolean {
        return searchCatalogValidated(candidate.chapterCount, candidate.validation) &&
            !candidate.pageCatalog &&
            candidate.resolved != null
    }

    private fun searchCandidateTrustedForDisplayMetadata(candidate: ValidatedSearchCandidate): Boolean {
        return candidate.resolved != null &&
            candidate.chapterCount >= MIN_SEARCH_READABLE_CATALOG_CHAPTERS &&
            normalizedAuthor(candidate.book.author).isNotBlank()
    }

    private fun searchCandidateTrustedForDisplayPreview(candidate: ValidatedSearchCandidate): Boolean {
        return candidate.validation == SEARCH_DETAIL_DISPLAY_PREVIEW_VALIDATION &&
            normalizedAuthor(candidate.book.author).isNotBlank()
    }

    private fun rememberSearchSessionEvidence(
        selectedBook: SourceBook,
        group: List<ValidatedSearchCandidate>,
        trustedGroup: List<ValidatedSearchCandidate>
    ) {
        rememberBookIdentityProfile(selectedBook, group)
        val waterfall = rememberBookContentWaterfall(selectedBook, group.map { candidate -> candidate.ranked })
        trustedGroup.mapNotNull { candidate -> candidate.resolved }
            .forEach { resolved -> promoteResolvedBookInWaterfall(waterfall, resolved) }
        Log.i(
            TAG,
            "operation=searchSessionEvidence provider=$providerName title=${selectedBook.name} " +
                "author=${selectedBook.author} trusted=${verifiedBookCount(waterfall)} candidates=${group.size}"
        )
    }

    private fun rememberBookIdentityProfile(
        selectedBook: SourceBook,
        group: List<ValidatedSearchCandidate>
    ): BookIdentityProfile {
        val evidenceBooks = searchEvidenceBooks(selectedBook, group)
        val profile = synchronized(bookIdentityProfileLock) {
            val existing = evidenceBooks
                .asSequence()
                .mapNotNull { book -> bookIdentityProfilesBySourceBookKey[sourceBookKey(book)] }
                .firstOrNull()
                ?: bookIdentityProfiles.firstOrNull { profile ->
                    evidenceBooks.any { book -> bookMatchesIdentityProfile(profile, book) }
                }
            val current = existing ?: BookIdentityProfile(
                displayTitle = selectedBook.name,
                displayAuthor = cleanAuthor(selectedBook.author),
                waterfallKey = rawBookWaterfallKey(selectedBook)
            ).also { profile -> bookIdentityProfiles.add(profile) }
            evidenceBooks.forEach { book -> addBookToIdentityProfile(current, book) }
            current
        }
        AiBridgeTrace.event(
            "source_book_identity_profile",
            selectedBook.name,
            AiBridgeTrace.fields(
                "aliases" to synchronized(bookIdentityProfileLock) { profile.titleKeys.joinToString("|") },
                "authors" to synchronized(bookIdentityProfileLock) { profile.authorNames.joinToString("|") },
                "sources" to synchronized(bookIdentityProfileLock) { profile.sourceBookKeys.size },
                "waterfallKey" to profile.waterfallKey.debugToken()
            )
        )
        return profile
    }

    private fun searchEvidenceBooks(
        selectedBook: SourceBook,
        group: List<ValidatedSearchCandidate>
    ): List<SourceBook> {
        val books = ArrayList<SourceBook>()
        fun add(book: SourceBook?) {
            if (book == null) return
            if (books.none { existing -> sourceBookKey(existing) == sourceBookKey(book) && existing.name == book.name }) {
                books.add(book)
            }
        }
        add(selectedBook)
        group.forEach { candidate ->
            add(candidate.ranked.book)
            add(candidate.book)
            candidate.resolved?.let { resolved ->
                add(resolved.book)
                add(resolved.detail.book)
            }
        }
        return books
    }

    private fun addBookToIdentityProfile(profile: BookIdentityProfile, book: SourceBook) {
        val titleKey = searchRanker.canonicalTitleKey(book).ifBlank { normalizeHint(book.name) }
        if (titleKey.isNotBlank()) profile.titleKeys.add(titleKey)
        cleanAuthor(book.author).takeIf { author -> author.isNotBlank() }?.let { author ->
            profile.authorNames.add(author)
        }
        profile.sourceBookKeys.add(sourceBookKey(book))
        profile.rawWaterfallKeys.add(rawBookWaterfallKey(book))
        bookIdentityProfilesBySourceBookKey[sourceBookKey(book)] = profile
    }

    private fun bookIdentityProfileFor(book: SourceBook): BookIdentityProfile? {
        return synchronized(bookIdentityProfileLock) {
            bookIdentityProfilesBySourceBookKey[sourceBookKey(book)]?.let { return@synchronized it }
            val profile = bookIdentityProfiles.firstOrNull { candidate -> bookMatchesIdentityProfile(candidate, book) }
                ?: return@synchronized null
            addBookToIdentityProfile(profile, book)
            profile
        }
    }

    private fun bookMatchesIdentityProfile(profile: BookIdentityProfile, book: SourceBook): Boolean {
        val titleKey = searchRanker.canonicalTitleKey(book).ifBlank { normalizeHint(book.name) }
        if (titleKey.isBlank() || titleKey !in profile.titleKeys) return false
        val author = cleanAuthor(book.author)
        if (author.isBlank() || BookIdentity.isAnonymousAuthor(author)) return true
        val concreteAuthors = profile.authorNames.filter { candidate ->
            candidate.isNotBlank() && !BookIdentity.isAnonymousAuthor(candidate) && normalizedAuthor(candidate).isNotBlank()
        }
        if (concreteAuthors.isEmpty()) {
            return profile.authorNames.any { candidate -> BookIdentity.isAnonymousAuthor(candidate) }
        }
        return concreteAuthors.any { candidate -> BookIdentity.authorsCompatible(candidate, author) }
    }

    private suspend fun validateSearchTitleGroup(
        titleGroup: SearchTitleGroup,
        semaphore: Semaphore,
        allowEarlyReturn: Boolean,
        allowDisplayPreview: Boolean,
        queryKey: String
    ): List<ValidatedSearchCandidate>? = supervisorScope {
        val groupStartedAt = System.currentTimeMillis()
        val maxValidationCandidates = validationCandidateLimitForTitleGroup(
            titleGroup = titleGroup,
            queryKey = queryKey,
            allowEarlyReturn = allowEarlyReturn
        )
        val candidateBatches = validationCandidateBatchesForTitle(
            titleGroup = titleGroup,
            maxValidationCandidates = maxValidationCandidates,
            allowEarlyReturn = allowEarlyReturn,
            queryKey = queryKey
        )
        val selectedCandidates = candidateBatches.flatMap { batch -> batch.candidates }
        if (selectedCandidates.isEmpty()) return@supervisorScope null
        traceSearchValidationPlan(titleGroup, selectedCandidates)
        AiBridgeTrace.event(
            "source_search_validation_group_started",
            titleGroup.identityKey.replace('\n', '@'),
            "total_${titleGroup.candidates.size}_selected_${selectedCandidates.size}" +
                "_batches_${candidateBatches.joinToString("|") { batch -> "${batch.label}:${batch.candidates.size}" }}" +
                "_early_${allowEarlyReturn}" +
                "_displayPreview_${allowDisplayPreview}"
        )
        if (allowDisplayPreview) {
            validateSearchDisplayPreviewGroup(
                titleGroup = titleGroup,
                candidates = selectedCandidates,
                semaphore = semaphore,
                queryKey = queryKey
            )?.let { preview ->
                return@supervisorScope preview
            }
        }
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
            CoroutineScope(
                SourceNetworkDispatchers.forScope(groupRequestScope) +
                    SupervisorJob() +
                    sourceRequestContext(groupRequestScope)
            )
        }
        var pending = mutableSetOf<Deferred<ValidatedSearchCandidate>>()
        try {
            val deadline = System.currentTimeMillis() + SEARCH_TITLE_GROUP_VALIDATION_TIMEOUT_MS
            for (batch in candidateBatches) {
                if (System.currentTimeMillis() >= deadline) break
                val batchDeadline = minOf(
                    deadline,
                    System.currentTimeMillis() + searchValidationBatchTimeoutMs(
                        allowEarlyReturn = allowEarlyReturn,
                        exactTitle = titleGroup.titleKey == queryKey
                    )
                )
                AiBridgeTrace.event(
                    "source_search_validation_batch_started",
                    titleGroup.identityKey.replace('\n', '@'),
                    "label_${batch.label}_selected_${batch.candidates.size}" +
                        "_completedBefore_${completed.size}" +
                        "_batchTimeoutMs_${batchDeadline - System.currentTimeMillis()}" +
                        "_remainingMs_${deadline - System.currentTimeMillis()}"
                )
                pending = batch.candidates.map { ranked ->
                    validationScope.async {
                        validateSearchCandidateForTitle(
                            ranked = ranked,
                            titleGroup = titleGroup,
                            semaphore = semaphore,
                            fastForProgress = allowEarlyReturn
                        )
                    }
                }.toMutableSet()
                val completedBeforeBatch = completed.size
                var provisionalMerged: ValidatedSearchCandidate? = null
                var provisionalReadyAt = 0L
                while (pending.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val batchCompleted = completed.size - completedBeforeBatch
                    val minCompletedForSoftEarly = searchProgressValidationMinCompletedBeforeEarlyReturn(
                        allowEarlyReturn = allowEarlyReturn,
                        exactTitle = titleGroup.titleKey == queryKey,
                        batchCandidateCount = batch.candidates.size
                    )
                    if (
                        allowEarlyReturn &&
                        provisionalMerged != null &&
                        batchCompleted >= minCompletedForSoftEarly
                    ) {
                        break
                    }
                    val remainingMs = if (allowEarlyReturn && provisionalMerged != null) {
                        minOf(
                            batchDeadline - now,
                            provisionalReadyAt + SEARCH_PROGRESS_VALIDATION_SOFT_GRACE_MS - now
                        )
                    } else {
                        batchDeadline - now
                    }
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
                    val merged = mergeValidatedTitleGroup(
                        group = completed,
                        fastForProgress = allowEarlyReturn,
                        displayPreviewQueryKey = queryKey
                    )
                    if (
                        allowEarlyReturn &&
                        merged != null &&
                        hasStrongSearchCoverage(merged) &&
                        searchProgressCandidateReady(merged)
                    ) {
                        if (provisionalMerged == null) {
                            provisionalReadyAt = System.currentTimeMillis()
                            AiBridgeTrace.event(
                                "source_search_validation_group_provisional",
                                titleGroup.identityKey.replace('\n', '@'),
                                "batch_${batch.label}" +
                                    "_completed_${completed.size}" +
                                    "_pending_${pending.size}" +
                                    "_merged_${merged.book.name.debugToken()}/${merged.chapterCount}/${merged.validation}" +
                                    "_durationMs_${provisionalReadyAt - groupStartedAt}"
                            )
                        }
                        provisionalMerged = merged
                        val updatedBatchCompleted = completed.size - completedBeforeBatch
                        if (updatedBatchCompleted < minCompletedForSoftEarly && pending.isNotEmpty()) {
                            continue
                        }
                        AiBridgeTrace.event(
                            "source_search_validation_group_finished",
                            titleGroup.identityKey.replace('\n', '@'),
                            "outcome_soft_early_batch_${batch.label}" +
                                "_completed_${completed.size}" +
                                "_cancelled_${pending.size}" +
                                "_merged_${merged.book.name.debugToken()}/${merged.chapterCount}/${merged.validation}" +
                                "_durationMs_${System.currentTimeMillis() - groupStartedAt}"
                        )
                        return@supervisorScope completed.toList()
                    }
                }
                val batchOutcome = if (pending.isNotEmpty()) "timeout" else "completed"
                val cancelled = pending.size
                pending.forEach { it.cancel() }
                pending = mutableSetOf()
                val merged = mergeValidatedTitleGroup(
                    group = completed,
                    fastForProgress = allowEarlyReturn,
                    displayPreviewQueryKey = queryKey
                )
                AiBridgeTrace.event(
                    "source_search_validation_batch_finished",
                    titleGroup.identityKey.replace('\n', '@'),
                    "label_${batch.label}_outcome_${batchOutcome}" +
                        "_batchCompleted_${completed.size - completedBeforeBatch}" +
                        "_totalCompleted_${completed.size}" +
                        "_cancelled_${cancelled}" +
                        "_merged_${merged?.book?.name.orEmpty().debugToken()}/${merged?.chapterCount ?: 0}/${merged?.validation.orEmpty()}" +
                        "_durationMs_${System.currentTimeMillis() - groupStartedAt}"
                )
                if (merged != null && !allowEarlyReturn) {
                    AiBridgeTrace.event(
                        "source_search_validation_group_finished",
                        titleGroup.identityKey.replace('\n', '@'),
                        "outcome_ready_batch_${batch.label}" +
                            "_completed_${completed.size}" +
                            "_merged_${merged.book.name.debugToken()}/${merged.chapterCount}/${merged.validation}" +
                            "_durationMs_${System.currentTimeMillis() - groupStartedAt}"
                    )
                    return@supervisorScope completed.toList()
                }
            }
            groupRequestScope?.let { cancelSourceRequests(it) }
            val merged = mergeValidatedTitleGroup(
                group = completed,
                fastForProgress = allowEarlyReturn,
                displayPreviewQueryKey = queryKey
            )
            AiBridgeTrace.event(
                "source_search_validation_group_finished",
                titleGroup.identityKey.replace('\n', '@'),
                "outcome_exhausted_completed_${completed.size}" +
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

    private suspend fun validateSearchDisplayPreviewGroup(
        titleGroup: SearchTitleGroup,
        candidates: List<RankedSearchBook>,
        semaphore: Semaphore,
        queryKey: String
    ): List<ValidatedSearchCandidate>? = supervisorScope {
        val previewStartedAt = System.currentTimeMillis()
        val previewCandidates = candidates
            .distinctBy { ranked -> searchValidationSourceKey(ranked) }
            .take(MAX_SEARCH_DISPLAY_PREVIEW_VALIDATION_CANDIDATES)
        if (previewCandidates.size < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) return@supervisorScope null
        AiBridgeTrace.event(
            "source_search_display_preview_started",
            titleGroup.identityKey.replace('\n', '@'),
            "selected_${previewCandidates.size}" +
                "_sources_${previewCandidates.joinToString("|") { ranked ->
                    sourceLabel(ranked.book).debugToken()
                }}"
        )
        val completed = ArrayList<ValidatedSearchCandidate>()
        val pending = previewCandidates.map { ranked ->
            async {
                validateSearchCandidateForTitle(
                    ranked = ranked,
                    titleGroup = titleGroup,
                    semaphore = semaphore,
                    displayPreviewOnly = true
                )
            }
        }.toMutableSet()
        try {
            withTimeoutOrNull(SEARCH_DISPLAY_PREVIEW_TOTAL_TIMEOUT_MS) {
                while (pending.isNotEmpty()) {
                    val next = select<Pair<Deferred<ValidatedSearchCandidate>, ValidatedSearchCandidate>> {
                        pending.forEach { deferred ->
                            deferred.onAwait { value -> deferred to value }
                        }
                    }
                    pending.remove(next.first)
                    completed.add(next.second)
                    val merged = mergeValidatedTitleGroup(
                        group = completed,
                        fastForProgress = true,
                        displayPreviewQueryKey = queryKey
                    )
                    if (merged != null && searchProgressCandidateReady(merged)) {
                        AiBridgeTrace.event(
                            "source_search_display_preview_ready",
                            titleGroup.identityKey.replace('\n', '@'),
                            "completed_${completed.size}" +
                                "_pending_${pending.size}" +
                                "_merged_${merged.book.name.debugToken()}" +
                                "_durationMs_${System.currentTimeMillis() - previewStartedAt}"
                        )
                        return@withTimeoutOrNull
                    }
                }
            }
        } finally {
            val cancelled = pending.count { deferred -> !deferred.isCompleted }
            pending.forEach { deferred ->
                if (!deferred.isCompleted) deferred.cancel()
            }
            if (cancelled > 0) {
                AiBridgeTrace.event(
                    "source_search_display_preview_cancelled",
                    titleGroup.identityKey.replace('\n', '@'),
                    "completed_${completed.size}" +
                        "_cancelled_${cancelled}" +
                        "_durationMs_${System.currentTimeMillis() - previewStartedAt}"
                )
            }
        }
        val merged = mergeValidatedTitleGroup(
            group = completed,
            fastForProgress = true,
            displayPreviewQueryKey = queryKey
        )
        if (merged != null && searchProgressCandidateReady(merged)) {
            AiBridgeTrace.event(
                "source_search_validation_group_finished",
                titleGroup.identityKey.replace('\n', '@'),
                "outcome_display_preview" +
                    "_completed_${completed.size}" +
                    "_merged_${merged.book.name.debugToken()}/${merged.chapterCount}/${merged.validation}" +
                    "_durationMs_${System.currentTimeMillis() - previewStartedAt}"
            )
            completed
        } else {
            AiBridgeTrace.event(
                "source_search_display_preview_deferred",
                titleGroup.identityKey.replace('\n', '@'),
                "completed_${completed.size}" +
                    "_durationMs_${System.currentTimeMillis() - previewStartedAt}"
            )
            null
        }
    }

    private fun validationCandidateLimitForTitleGroup(
        titleGroup: SearchTitleGroup,
        queryKey: String,
        allowEarlyReturn: Boolean
    ): Int {
        if (!allowEarlyReturn) return Int.MAX_VALUE
        val sourceCount = titleGroup.candidates.uniqueSearchSourceCount()
        val isExactTitle = titleGroup.titleKey == queryKey
        return if (isExactTitle && sourceCount >= SEARCH_PROGRESS_EXPANDED_VALIDATION_MIN_SOURCES) {
            MAX_EXPANDED_PROGRESS_SEARCH_VALIDATION_CANDIDATES_PER_TITLE
        } else {
            MAX_PROGRESS_SEARCH_VALIDATION_CANDIDATES_PER_TITLE
        }
    }

    private fun searchValidationBatchTimeoutMs(
        allowEarlyReturn: Boolean,
        exactTitle: Boolean
    ): Long {
        return when {
            allowEarlyReturn && exactTitle -> SEARCH_PROGRESS_EXACT_VALIDATION_BATCH_TIMEOUT_MS
            allowEarlyReturn -> SEARCH_PROGRESS_VALIDATION_BATCH_TIMEOUT_MS
            else -> SEARCH_VALIDATION_BATCH_TIMEOUT_MS
        }
    }

    private fun searchProgressValidationMinCompletedBeforeEarlyReturn(
        allowEarlyReturn: Boolean,
        exactTitle: Boolean,
        batchCandidateCount: Int
    ): Int {
        val target = if (allowEarlyReturn && exactTitle) {
            FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
        } else {
            SEARCH_PROGRESS_VALIDATION_MIN_COMPLETED_BEFORE_EARLY_RETURN
        }
        return minOf(batchCandidateCount, target)
    }

    private suspend fun validateSearchCandidateForTitle(
        ranked: RankedSearchBook,
        titleGroup: SearchTitleGroup,
        semaphore: Semaphore,
        displayPreviewOnly: Boolean = false,
        fastForProgress: Boolean = false
    ): ValidatedSearchCandidate {
        val queuedAt = System.currentTimeMillis()
        var acquired = false
        return try {
            semaphore.acquire()
            acquired = true
            val permitWaitMs = System.currentTimeMillis() - queuedAt
            try {
                validateSearchCandidate(
                    ranked = ranked,
                    authorConsensus = titleGroup.authorConsensus[normalizedAuthor(ranked.book.author)] ?: 0,
                    permitWaitMs = permitWaitMs,
                    displayPreviewOnly = displayPreviewOnly,
                    skipInlineTailProbe = fastForProgress
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AiBridgeTrace.event(
                    "source_search_validation_error",
                    ranked.book.name,
                    "error_${error.javaClass.simpleName}_message_${error.message.orEmpty().debugToken()}" +
                        "_source_${sourceLabel(ranked.book).debugToken()}" +
                        "_permitWaitMs_${permitWaitMs}"
                )
                fallbackValidatedSearchCandidate(ranked)
            }
        } finally {
            if (acquired) semaphore.release()
        }
    }

    private fun hasStrongSearchCoverage(candidate: ValidatedSearchCandidate): Boolean {
        return searchCatalogValidated(candidate.chapterCount, candidate.validation) ||
            candidate.validation == SEARCH_DETAIL_DISPLAY_PREVIEW_VALIDATION
    }

    private fun validationCandidateBatchesForTitle(
        titleGroup: SearchTitleGroup,
        maxValidationCandidates: Int,
        allowEarlyReturn: Boolean,
        queryKey: String
    ): List<SearchValidationCandidateBatch> {
        return validationCandidateBatchesForTitle(
            group = titleGroup.candidates,
            maxValidationCandidates = maxValidationCandidates,
            allowEarlyReturn = allowEarlyReturn,
            titleKey = titleGroup.titleKey,
            authorKey = searchIdentityAuthorKey(titleGroup.identityKey),
            queryKey = queryKey
        )
    }

    private fun validationCandidateBatchesForTitle(
        group: List<RankedSearchBook>,
        maxValidationCandidates: Int,
        allowEarlyReturn: Boolean
    ): List<SearchValidationCandidateBatch> {
        return validationCandidateBatchesForTitle(
            group = group,
            maxValidationCandidates = maxValidationCandidates,
            allowEarlyReturn = allowEarlyReturn,
            titleKey = null,
            authorKey = null,
            queryKey = null
        )
    }

    private fun validationCandidateBatchesForTitle(
        group: List<RankedSearchBook>,
        maxValidationCandidates: Int,
        allowEarlyReturn: Boolean,
        titleKey: String?,
        authorKey: String?,
        queryKey: String?
    ): List<SearchValidationCandidateBatch> {
        val frontTierCandidates = group.filter { ranked -> searchValidationTier(ranked) <= 2 }
        val supplementTierCandidates = group.filter { ranked -> searchValidationTier(ranked) > 2 }
        val frontCandidates = normalizeValidationCandidatesForMode(
            orderedValidationCandidatesForTitle(
                group = frontTierCandidates,
                spreadLimit = maxValidationCandidates,
                titleKey = titleKey,
                authorKey = authorKey,
                queryKey = queryKey
            ),
            allowEarlyReturn
        )
        val frontKeys = frontCandidates.mapTo(LinkedHashSet()) { ranked -> searchValidationCandidateKey(ranked) }
        val supplementCandidates = normalizeValidationCandidatesForMode(
            orderedValidationCandidatesForTitle(
                group = supplementTierCandidates,
                spreadLimit = maxValidationCandidates,
                titleKey = titleKey,
                authorKey = authorKey,
                queryKey = queryKey
            ),
            allowEarlyReturn
        )
            .filterNot { ranked -> searchValidationCandidateKey(ranked) in frontKeys }
        return buildList {
            addValidationCandidateBatches("front-tier", frontCandidates, maxValidationCandidates)
            addValidationCandidateBatches("tier3-supplement", supplementCandidates, maxValidationCandidates)
        }
    }

    private fun MutableList<SearchValidationCandidateBatch>.addValidationCandidateBatches(
        label: String,
        candidates: List<RankedSearchBook>,
        batchSize: Int
    ) {
        if (candidates.isEmpty() || batchSize <= 0) return
        candidates.chunked(batchSize).forEachIndexed { index, batch ->
            val suffix = if (index == 0) "" else "-${index + 1}"
            add(SearchValidationCandidateBatch(label + suffix, batch))
        }
    }

    private fun normalizeValidationCandidatesForMode(
        candidates: List<RankedSearchBook>,
        allowEarlyReturn: Boolean
    ): List<RankedSearchBook> {
        return if (allowEarlyReturn) {
            candidates.distinctBy { ranked -> searchValidationSourceKey(ranked) }
        } else {
            candidates
        }
    }

    private fun validationCandidatesForTitle(group: List<RankedSearchBook>): List<RankedSearchBook> {
        return validationCandidatesForTitle(
            group = group,
            limit = MAX_SEARCH_VALIDATION_CANDIDATES_PER_TITLE
        )
    }

    private fun validationCandidatesForTitle(
        group: List<RankedSearchBook>,
        limit: Int
    ): List<RankedSearchBook> {
        return orderedValidationCandidatesForTitle(group, limit).take(limit)
    }

    private fun orderedValidationCandidatesForTitle(
        group: List<RankedSearchBook>,
        spreadLimit: Int,
        titleKey: String? = null,
        authorKey: String? = null,
        queryKey: String? = null
    ): List<RankedSearchBook> {
        if (group.isEmpty() || spreadLimit <= 0) return emptyList()
        val limit = maxOf(1, spreadLimit)
        val validationComparator = validationCandidateComparatorForTitle(titleKey, authorKey, queryKey)
        val orderedGroup = group.sortedWith(validationComparator)
        val spreadCandidates = spreadValidationCandidates(
            orderedGroup,
            limit
        )
        val priorityCandidates = orderedGroup.take(minOf(MAX_VALIDATION_PRIORITY_PER_TITLE, limit))
        return (spreadCandidates +
            priorityCandidates +
            orderedGroup.take(MAX_VALIDATION_PER_TITLE) +
            orderedGroup.filter { ranked -> BookCoverUrl.isLikelyImage(ranked.book.coverUrl) }
                .take(MAX_VALIDATION_COVER_FALLBACK_PER_TITLE) +
            orderedGroup)
            .distinctBy { ranked ->
                searchValidationCandidateKey(ranked)
            }
    }

    private fun validationCandidateComparatorForTitle(
        titleKey: String?,
        authorKey: String?,
        queryKey: String?
    ): Comparator<RankedSearchBook> {
        val targetTitle = titleKey.orEmpty()
        val targetAuthor = authorKey.orEmpty()
        val normalizedQuery = queryKey.orEmpty()
        return compareBy<RankedSearchBook> { ranked ->
            validationCandidateIdentityPriority(ranked, targetTitle, targetAuthor, normalizedQuery)
        }.thenBy { ranked -> sourcePriorityIndex(ranked.book.source, ranked.book.name) }
            .thenByDescending { ranked -> ranked.score }
            .thenBy { ranked -> ranked.sourceIndex }
            .thenBy { ranked -> ranked.resultIndex }
            .thenBy { ranked -> ranked.book.name.length }
            .thenBy { ranked -> ranked.book.name }
    }

    private fun validationCandidateIdentityPriority(
        ranked: RankedSearchBook,
        targetTitle: String,
        targetAuthor: String,
        queryKey: String
    ): Int {
        val candidateTitle = searchRanker.canonicalTitleKey(ranked.book)
        val authorCompatible = targetAuthor.isBlank() ||
            BookIdentity.authorsCompatible(ranked.book.author, targetAuthor)
        return when {
            targetTitle.isNotBlank() && candidateTitle == targetTitle && authorCompatible -> 0
            queryKey.isNotBlank() && candidateTitle == queryKey && authorCompatible -> 1
            targetTitle.isNotBlank() && candidateTitle == targetTitle -> 2
            targetTitle.isNotBlank() &&
                sameOrContainedSearchKey(targetTitle, candidateTitle) &&
                authorCompatible -> 3
            targetTitle.isNotBlank() &&
                catalogAliasTitleMayNeedValidation(targetTitle, candidateTitle) &&
                authorCompatible -> 4
            else -> 5
        }
    }

    private fun searchIdentityAuthorKey(identityKey: String): String {
        return if (identityKey.contains('\n')) {
            identityKey.substringAfter('\n', "")
        } else {
            ""
        }
    }

    private fun spreadValidationCandidates(
        group: List<RankedSearchBook>,
        limit: Int
    ): List<RankedSearchBook> {
        if (group.size <= limit || limit <= 1) return group.take(limit)
        val lastIndex = group.lastIndex
        val slots = limit - 1
        return (0 until limit)
            .map { slot -> group[(slot * lastIndex) / slots] }
            .distinctBy { ranked -> searchValidationCandidateKey(ranked) }
    }

    private fun searchValidationTier(ranked: RankedSearchBook): Int {
        return sourceQualityRouter.sourceDebugSnapshot(ranked.book.source).tier
    }

    private fun searchValidationCandidateKey(ranked: RankedSearchBook): String {
        return ranked.book.source.sourceUrl + "\n" + ranked.book.bookUrl
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
        authorConsensus: Int,
        permitWaitMs: Long,
        displayPreviewOnly: Boolean = false,
        skipInlineTailProbe: Boolean = false
    ): ValidatedSearchCandidate {
        val candidateStartedAt = System.currentTimeMillis()
        val cacheKey = searchValidationCacheKey(ranked.book)
        searchValidationCache[cacheKey]?.let { cached ->
            AiBridgeTrace.event(
                "source_search_validation_cache_hit",
                ranked.book.name,
                "source_${sourceLabel(ranked.book).debugToken()}_validation_${cached.validation}" +
                    "_chapters_${cached.chapterCount}_cover_${cached.coverQuality.usable}"
            )
            traceSearchValidationCandidateTiming(
                name = ranked.book.name,
                source = sourceLabel(ranked.book),
                outcome = "cache-hit",
                permitWaitMs = permitWaitMs,
                lockWaitMs = 0L,
                detailMs = 0L,
                catalogMs = 0L,
                tailMs = 0L,
                workMs = System.currentTimeMillis() - candidateStartedAt,
                validation = cached.validation,
                chapterCount = cached.chapterCount,
                tailContent = 0
            )
            return rebaseValidatedSearchCandidate(cached, ranked, authorConsensus)
        }
        val lock = synchronized(searchValidationLocks) {
            searchValidationLocks.getOrPut(cacheKey) { Mutex() }
        }
        val lockStartedAt = System.currentTimeMillis()
        return lock.withLock {
            val lockWaitMs = System.currentTimeMillis() - lockStartedAt
            searchValidationCache[cacheKey]?.let { cached ->
                AiBridgeTrace.event(
                    "source_search_validation_cache_hit",
                    ranked.book.name,
                    "source_${sourceLabel(ranked.book).debugToken()}_validation_${cached.validation}" +
                        "_chapters_${cached.chapterCount}_cover_${cached.coverQuality.usable}"
                )
                traceSearchValidationCandidateTiming(
                    name = ranked.book.name,
                    source = sourceLabel(ranked.book),
                    outcome = "cache-hit-after-lock",
                    permitWaitMs = permitWaitMs,
                    lockWaitMs = lockWaitMs,
                    detailMs = 0L,
                    catalogMs = 0L,
                    tailMs = 0L,
                    workMs = System.currentTimeMillis() - candidateStartedAt,
                    validation = cached.validation,
                    chapterCount = cached.chapterCount,
                    tailContent = 0
                )
                return@withLock rebaseValidatedSearchCandidate(cached, ranked, authorConsensus)
            }
            val validated = validateSearchCandidateUncached(
                ranked,
                authorConsensus,
                permitWaitMs,
                lockWaitMs,
                displayPreviewOnly,
                skipInlineTailProbe
            )
            if (!displayPreviewOnly && !skipInlineTailProbe && validated.cacheableSearchValidation()) {
                searchValidationCache[cacheKey] = validated
            }
            validated
        }
    }

    private suspend fun validateSearchCandidateUncached(
        ranked: RankedSearchBook,
        authorConsensus: Int,
        permitWaitMs: Long,
        lockWaitMs: Long,
        displayPreviewOnly: Boolean = false,
        skipInlineTailProbe: Boolean = false
    ): ValidatedSearchCandidate {
        val validationStartedAt = System.currentTimeMillis()
        val fallback = fallbackValidatedSearchCandidate(ranked, authorConsensus)
        val validated = withTimeoutOrNull(SEARCH_VALIDATION_TIMEOUT_MS) {
            val detailStartedAt = System.currentTimeMillis()
            val detailResult = if (displayPreviewOnly) {
                withTimeoutOrNull(SEARCH_DISPLAY_PREVIEW_DETAIL_TIMEOUT_MS) {
                    engine.getBookDetail(ranked.book)
                }
            } else {
                engine.getBookDetail(ranked.book)
            }
            val detail = when (val value = detailResult) {
                is EngineResult.Success -> {
                    value.value
                }
                null -> {
                    AiBridgeTrace.event(
                        "source_search_validation",
                        ranked.book.name,
                        "source_${sourceLabel(ranked.book).debugToken()}_validation_display-preview-timeout" +
                            "_detailMs_${System.currentTimeMillis() - detailStartedAt}" +
                            "_permitWaitMs_${permitWaitMs}_lockWaitMs_${lockWaitMs}" +
                            "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
                    )
                    traceSearchValidationCandidateTiming(
                        name = ranked.book.name,
                        source = sourceLabel(ranked.book),
                        outcome = "display-preview-timeout",
                        permitWaitMs = permitWaitMs,
                        lockWaitMs = lockWaitMs,
                        detailMs = System.currentTimeMillis() - detailStartedAt,
                        catalogMs = 0L,
                        tailMs = 0L,
                        workMs = System.currentTimeMillis() - validationStartedAt,
                        validation = "display-preview-timeout",
                        chapterCount = 0,
                        tailContent = 0
                    )
                    return@withTimeoutOrNull fallback.copy(validation = "display-preview-timeout")
                }
                is EngineResult.Failure -> {
                    AiBridgeTrace.event(
                        "source_search_validation",
                        ranked.book.name,
                        "source_${sourceLabel(ranked.book).debugToken()}_validation_detail-failed" +
                            "_detailMs_${System.currentTimeMillis() - detailStartedAt}" +
                            "_permitWaitMs_${permitWaitMs}_lockWaitMs_${lockWaitMs}" +
                            "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
                    )
                    traceSearchValidationCandidateTiming(
                        name = ranked.book.name,
                        source = sourceLabel(ranked.book),
                        outcome = "detail-failed",
                        permitWaitMs = permitWaitMs,
                        lockWaitMs = lockWaitMs,
                        detailMs = System.currentTimeMillis() - detailStartedAt,
                        catalogMs = 0L,
                        tailMs = 0L,
                        workMs = System.currentTimeMillis() - validationStartedAt,
                        validation = "detail-failed",
                        chapterCount = 0,
                        tailContent = 0
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
                        "_permitWaitMs_${permitWaitMs}_lockWaitMs_${lockWaitMs}" +
                        "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
                )
                traceSearchValidationCandidateTiming(
                    name = ranked.book.name,
                    source = sourceLabel(ranked.book),
                    outcome = "detail-title-mismatch",
                    permitWaitMs = permitWaitMs,
                    lockWaitMs = lockWaitMs,
                    detailMs = System.currentTimeMillis() - detailStartedAt,
                    catalogMs = 0L,
                    tailMs = 0L,
                    workMs = System.currentTimeMillis() - validationStartedAt,
                    validation = "detail-title-mismatch",
                    chapterCount = 0,
                    tailContent = 0
                )
                return@withTimeoutOrNull fallback.copy(
                    score = 0,
                    validation = "detail-title-mismatch"
                )
            }
            val detailMs = System.currentTimeMillis() - detailStartedAt
            val enrichedBook = detail.toSearchBook(ranked.book)
            val coverQuality = searchValidationCoverQuality(enrichedBook)
            val coverCandidates = coverCandidateUrls(enrichedBook.coverUrl, listOf(ranked.book.coverUrl))
            if (displayPreviewOnly) {
                val score = ranked.score +
                    coverScore(coverQuality) +
                    detailAgreementScore(ranked.book, detail) +
                    sourceQualityRouter.routeScoreBoost(enrichedBook)
                val validated = ValidatedSearchCandidate(
                    ranked = ranked,
                    book = enrichedBook,
                    score = score,
                    chapterCount = 0,
                    freshnessHint = 0,
                    duplicateCount = 0,
                    coverQuality = coverQuality,
                    authorConsensus = authorConsensus,
                    validation = SEARCH_DETAIL_DISPLAY_PREVIEW_VALIDATION,
                    resolved = null,
                    pageCatalog = false,
                    coverCandidates = coverCandidates
                )
                AiBridgeTrace.event(
                    "source_search_validation",
                    enrichedBook.name,
                    "source_${sourceLabel(enrichedBook).debugToken()}_validation_${validated.validation}" +
                        "_author_${normalizedAuthor(enrichedBook.author).debugToken()}" +
                        "_chapters_0_tailContent_0" +
                        "_cover_${coverQuality.usable}_${coverQuality.reason.debugToken()}" +
                        "_pageCatalog_false" +
                        "_intro_${cleanIntro(enrichedBook.intro).isNotBlank()}" +
                        "_detailMs_${detailMs}_catalogMs_0_tailMs_0" +
                        "_permitWaitMs_${permitWaitMs}_lockWaitMs_${lockWaitMs}" +
                        "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
                )
                traceSearchValidationCandidateTiming(
                    name = enrichedBook.name,
                    source = sourceLabel(enrichedBook),
                    outcome = "validated",
                    permitWaitMs = permitWaitMs,
                    lockWaitMs = lockWaitMs,
                    detailMs = detailMs,
                    catalogMs = 0L,
                    tailMs = 0L,
                    workMs = System.currentTimeMillis() - validationStartedAt,
                    validation = validated.validation,
                    chapterCount = 0,
                    tailContent = 0
                )
                return@withTimeoutOrNull validated
            }
            val catalogStartedAt = System.currentTimeMillis()
            val catalog = when (val value = engine.getCanonicalChapterList(detail)) {
                is EngineResult.Success -> {
                    value.value
                }
                is EngineResult.Failure -> {
                    null
                }
            }
            val catalogMs = System.currentTimeMillis() - catalogStartedAt
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
                    routeId = SourceEngineBookRoute.bookId(enrichedBook, coverCandidates),
                    coverCandidates = coverCandidates
                )
            }
            val catalogChapters = catalog?.chapters.orEmpty()
            val chapterCount = catalogChapters.size
            val pageCatalog = looksLikePageCatalog(catalogChapters)
            val tailStartedAt = System.currentTimeMillis()
            val shouldProbeTailContent = resolved != null &&
                !skipInlineTailProbe &&
                catalogChapters.isNotEmpty() &&
                detailMs + catalogMs <= SEARCH_INLINE_TAIL_PROBE_MAX_PREWORK_MS
            val readableTailContent = if (shouldProbeTailContent) {
                probeReadableSearchTailContent(catalogChapters)
            } else {
                SearchTailContentProbeResult(readableContent = 0, pending = true)
            }
            val readableTailContentCount = readableTailContent.readableContent
            val tailMs = System.currentTimeMillis() - tailStartedAt
            val score = ranked.score +
                coverScore(coverQuality) +
                catalogScore(catalog) +
                searchTailContentScore(readableTailContentCount) +
                detailAgreementScore(ranked.book, detail) +
                sourceQualityRouter.routeScoreBoost(enrichedBook)
            val validation = when {
                catalog == null -> "detail-only"
                pageCatalog -> "detail-catalog-page-list"
                readableTailContentCount > 0 -> "detail-catalog-tail-content"
                readableTailContent.pending -> "detail-catalog-tail-pending"
                else -> "detail-catalog-unreadable"
            }
            val validated = ValidatedSearchCandidate(
                ranked = ranked,
                book = enrichedBook,
                score = score,
                chapterCount = chapterCount,
                freshnessHint = 0,
                duplicateCount = catalog?.duplicateCount ?: 0,
                coverQuality = coverQuality,
                authorConsensus = authorConsensus,
                validation = validation,
                resolved = resolved,
                pageCatalog = pageCatalog,
                coverCandidates = coverCandidates
            )
            sourceQualityRouter.recordSearchValidation(
                book = enrichedBook,
                chapterCount = chapterCount,
                freshnessHint = 0,
                coverUsable = coverQuality.usable,
                validation = validated.validation
            )
            AiBridgeTrace.event(
                "source_search_validation",
                enrichedBook.name,
                "source_${sourceLabel(enrichedBook).debugToken()}_validation_${validated.validation}" +
                    "_author_${normalizedAuthor(enrichedBook.author).debugToken()}" +
                    "_chapters_${chapterCount}_tailContent_${readableTailContentCount}" +
                    "_cover_${coverQuality.usable}_${coverQuality.reason.debugToken()}" +
                    "_pageCatalog_${validated.pageCatalog}" +
                    "_intro_${cleanIntro(enrichedBook.intro).isNotBlank()}" +
                    "_detailMs_${detailMs}_catalogMs_${catalogMs}_tailMs_${tailMs}" +
                    "_permitWaitMs_${permitWaitMs}_lockWaitMs_${lockWaitMs}" +
                    "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
            )
            traceSearchValidationCandidateTiming(
                name = enrichedBook.name,
                source = sourceLabel(enrichedBook),
                outcome = "validated",
                permitWaitMs = permitWaitMs,
                lockWaitMs = lockWaitMs,
                detailMs = detailMs,
                catalogMs = catalogMs,
                tailMs = tailMs,
                workMs = System.currentTimeMillis() - validationStartedAt,
                validation = validated.validation,
                chapterCount = chapterCount,
                tailContent = readableTailContentCount
            )
            validated
        }
        if (validated == null) {
            AiBridgeTrace.event(
                "source_search_validation",
                ranked.book.name,
                "source_${sourceLabel(ranked.book).debugToken()}_validation_timeout" +
                    "_timeoutMs_${SEARCH_VALIDATION_TIMEOUT_MS}" +
                    "_permitWaitMs_${permitWaitMs}_lockWaitMs_${lockWaitMs}" +
                    "_durationMs_${System.currentTimeMillis() - validationStartedAt}"
            )
            traceSearchValidationCandidateTiming(
                name = ranked.book.name,
                source = sourceLabel(ranked.book),
                outcome = "timeout",
                permitWaitMs = permitWaitMs,
                lockWaitMs = lockWaitMs,
                detailMs = 0L,
                catalogMs = 0L,
                tailMs = 0L,
                workMs = System.currentTimeMillis() - validationStartedAt,
                validation = "timeout",
                chapterCount = 0,
                tailContent = 0
            )
        }
        return validated ?: fallback
    }

    private fun traceSearchValidationCandidateTiming(
        name: String,
        source: String,
        outcome: String,
        permitWaitMs: Long,
        lockWaitMs: Long,
        detailMs: Long,
        catalogMs: Long,
        tailMs: Long,
        workMs: Long,
        validation: String,
        chapterCount: Int,
        tailContent: Int
    ) {
        AiBridgeTrace.event(
            "source_search_validation_candidate_timing",
            name,
            "tag_search.validation.candidate" +
                "_source_${source.debugToken()}" +
                "_outcome_${outcome.debugToken()}" +
                "_validation_${validation.debugToken()}" +
                "_chapters_${chapterCount}" +
                "_tailContent_${tailContent}" +
                "_permitWaitMs_${permitWaitMs}" +
                "_lockWaitMs_${lockWaitMs}" +
                "_detailMs_${detailMs}" +
                "_catalogMs_${catalogMs}" +
                "_tailMs_${tailMs}" +
                "_workMs_${workMs}" +
                "_totalMs_${permitWaitMs + lockWaitMs + workMs}"
        )
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
        return validation != "unvalidated" &&
            validation != SEARCH_DETAIL_DISPLAY_PREVIEW_VALIDATION
    }

    private fun fallbackValidatedSearchCandidate(
        ranked: RankedSearchBook,
        authorConsensus: Int = 0
    ): ValidatedSearchCandidate {
        val hasCoverUrl = BookCoverUrl.isLikelyImage(ranked.book.coverUrl)
        val coverCandidates = coverCandidateUrls(ranked.book.coverUrl)
        val validated = ValidatedSearchCandidate(
            ranked = ranked,
            book = ranked.book,
            score = ranked.score - UNVALIDATED_RESULT_PENALTY + sourceQualityRouter.routeScoreBoost(ranked.book),
            chapterCount = 0,
            freshnessHint = 0,
            duplicateCount = 0,
            coverQuality = if (hasCoverUrl) {
                CoverQuality(true, MIN_COVER_WIDTH, MIN_COVER_HEIGHT, "url-only")
            } else {
                CoverQuality(false, 0, 0, "unvalidated")
            },
            authorConsensus = authorConsensus,
            validation = "unvalidated",
            resolved = null,
            pageCatalog = false,
            coverCandidates = coverCandidates
        )
        sourceQualityRouter.recordSearchValidation(
            book = ranked.book,
            chapterCount = 0,
            freshnessHint = 0,
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
        return BookIdentity.canonicalAuthorKey(cleanAuthor(value))
    }

    private fun cleanIntro(value: String): String = SourceEngineMetadataCleaner.cleanIntro(value)

    private fun displayIntro(value: String): String {
        return if (cleanIntroEnabled()) {
            cleanIntro(value)
        } else {
            value.trim()
        }
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
                            val coverCandidates = coverCandidateUrls(
                                fallback,
                                listOf(candidate.book.coverUrl) + candidate.coverCandidates
                            )
                            candidate.copy(
                                book = filledBook,
                                coverQuality = CoverQuality(
                                    usable = true,
                                    width = MIN_COVER_WIDTH,
                                    height = MIN_COVER_HEIGHT,
                                    reason = "search-fallback"
                                ),
                                validation = candidate.validation + "+search-cover",
                                coverCandidates = coverCandidates
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
        return if (readableTailContent > 0) SEARCH_READABLE_TAIL_BONUS else 0
    }

    private suspend fun probeReadableSearchTailContent(
        chapters: List<CanonicalChapter>
    ): SearchTailContentProbeResult {
        val chapter = chapters.lastOrNull()?.sourceChapters?.firstOrNull()
            ?: return SearchTailContentProbeResult(readableContent = 0, pending = false)
        val startedAt = System.currentTimeMillis()
        var failureReason: String? = null
        val content = runDetachedWithTimeout(SEARCH_TAIL_CONTENT_TIMEOUT_MS) {
            when (val value = engine.getCleanContent(chapter)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> {
                    failureReason = value.failure.toString()
                    null
                }
            }
        }
        if (content == null) {
            recordContentLoadFailure(
                chapter = chapter,
                purpose = "search-tail",
                timeoutMs = SEARCH_TAIL_CONTENT_TIMEOUT_MS,
                fingerprint = null,
                reason = failureReason ?: "timeout_or_empty",
                durationMs = System.currentTimeMillis() - startedAt
            )
            return SearchTailContentProbeResult(
                readableContent = 0,
                pending = failureReason == null
            )
        }
        return SearchTailContentProbeResult(
            readableContent = if (isReadableContent(content)) 1 else 0,
            pending = false
        )
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
        if (chapterCount < MIN_SEARCH_READABLE_CATALOG_CHAPTERS) return false
        return validation.startsWith("detail-catalog-tail-content") ||
            validation.startsWith("detail-catalog-tail-pending")
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
            .filter { candidate ->
                candidate.readableChapterCount >= minReadableChapterCountFor(candidate.resolved.catalog.chapters.size)
            }
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

        val probeScope = CoroutineScope(activeSourceRequestDispatcher() + SupervisorJob() + activeSourceRequestContext())
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
        probeCatalogTail(resolved)
        val readableChapters = resolved.catalog.chapters
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
        probeCatalogTail(resolved)
        return resolved.catalog.chapters.size
    }

    private val readableResolvedBookComparator = compareByDescending<ReadableResolvedSourceBook> {
        it.resolved.hasReadableCatalogHead()
    }.thenByDescending {
        it.readableChapterCount
    }.thenByDescending {
        it.tailContinuityScore
    }.thenByDescending {
        it.lastReadableOrdinal
    }.thenBy {
        it.tailOrdinalGapCount
    }.thenByDescending {
        it.resolved.catalog.chapters.size
    }.thenByDescending {
        sourceQualityRouter.bookSourceScore(it.resolved.book)
    }.thenBy {
        sourcePriorityIndex(it.resolved.book.source, it.resolved.book.name)
    }.thenBy {
        it.order
    }

    private val catalogAnchorSignalComparator = compareByDescending<CatalogAnchorSignal> {
        it.lastReadableOrdinal
    }.thenByDescending {
        it.readableChapterCount
    }.thenByDescending {
        it.tailContinuityScore
    }.thenBy {
        it.tailOrdinalGapCount
    }.thenByDescending {
        sourceQualityRouter.bookSourceScore(it.resolved.book)
    }.thenByDescending {
        it.resolved.catalog.chapters.size
    }.thenBy {
        sourcePriorityIndex(it.resolved.book.source, it.resolved.book.name)
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
        it.score
    }.thenBy {
        it.sourceIndex
    }.thenBy {
        it.resultIndex
    }

    private suspend fun fallbackCandidatesFor(
        sourceBook: SourceBook,
        policy: FallbackSearchPolicy = FallbackSearchPolicy.PERSONAL_THEN_GLOBAL
    ): List<RankedSearchBook> {
        val allSources = sourceProvider()
        val personalSources = sourceQualityRouter.personalWaterfallSourcesForBook(allSources, sourceBook.name)
        val personalCandidates = searchFallbackCandidatesForSources(
            sourceBook = sourceBook,
            sources = personalSources,
            stage = "personal"
        )
        if (personalCandidates.isNotEmpty() || policy == FallbackSearchPolicy.PERSONAL_ONLY) {
            return personalCandidates
        }
        return searchFallbackCandidatesForSources(
            sourceBook = sourceBook,
            sources = sourceQualityRouter.globalWaterfallSourcesForBook(allSources, sourceBook.name),
            stage = "global"
        )
    }

    private suspend fun searchFallbackCandidatesForSources(
        sourceBook: SourceBook,
        sources: List<BookSource>,
        stage: String
    ): List<RankedSearchBook> {
        val searchSources = sources.take(MAX_DETAIL_FALLBACK_SOURCES)
        if (searchSources.isEmpty()) {
            AiBridgeTrace.event(
                "source_content_fallback_search_stage",
                sourceBook.name,
                AiBridgeTrace.fields(
                    "stage" to stage,
                    "sources" to 0,
                    "candidates" to 0
                )
            )
            return emptyList()
        }
        val candidates = Collections.synchronizedList(ArrayList<SearchCandidate>())
        val semaphore = Semaphore(MAX_DETAIL_FALLBACK_CONCURRENT_SEARCHES)
        val searchScope = CoroutineScope(activeSourceRequestDispatcher() + SupervisorJob() + activeSourceRequestContext())
        try {
            val jobs = searchSources.mapIndexed { sourceIndex, source ->
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
        val ranked = searchRanker.scoreCandidates(sourceBook.name, candidateSnapshot)
            .filter { ranked ->
                ranked.book.source.sourceUrl != sourceBook.source.sourceUrl || ranked.book.bookUrl != sourceBook.bookUrl
            }
            .sortedWith(
                compareByDescending<RankedSearchBook> {
                    authorConfidenceScore(sourceBook, it.book, authorConsensus)
                }
                    .thenByDescending { sourceQualityRouter.bookSourceScore(it.book) }
                    .thenByDescending { it.score }
                    .thenBy { it.sourceIndex }
                    .thenBy { it.resultIndex }
            )
        AiBridgeTrace.event(
            "source_content_fallback_search_stage",
            sourceBook.name,
            AiBridgeTrace.fields(
                "stage" to stage,
                "sources" to searchSources.size,
                "raw" to candidateSnapshot.size,
                "candidates" to ranked.size,
                "first" to ranked.take(8).joinToString("|") { candidate -> sourceLabel(candidate.book).debugToken() }
            )
        )
        return ranked
    }

    private fun fallbackDebugLabel(ranked: RankedSearchBook): String {
        return "${ranked.book.source.sourceName}/${ranked.book.source.sourceUrl}" +
            "/score=${ranked.score}" +
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

    private suspend fun loadReadableBookInCurrentRequestWithTimeout(
        sourceBook: SourceBook,
        sourceEngine: LegadoSourceEngine,
        timeoutMs: Long
    ): ResolvedSourceBook? {
        return withTimeoutOrNull(timeoutMs) {
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
            if (catalog.chapters.size < minReadableChapterCountFor(catalog.chapters.size)) return null
            val enrichedBook = detail.toSearchBook(sourceBook)
            val coverCandidates = coverCandidateUrls(enrichedBook.coverUrl, listOf(sourceBook.coverUrl))
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
                routeId = SourceEngineBookRoute.bookId(enrichedBook, coverCandidates),
                coverCandidates = coverCandidates
            )
        }.getOrNull()
    }

    private suspend fun <T> runDetachedWithTimeout(timeoutMs: Long, block: () -> T?): T? {
        val parentScope = currentSourceRequestScope()
        val childScope = newSourceRequestScope("detached", timeoutMs.toString(), parentScope)
        val scope = CoroutineScope(
            SourceNetworkDispatchers.forScope(childScope) +
                SupervisorJob() +
                sourceRequestContext(childScope)
        )
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

    private fun sourceKey(source: BookSource): String {
        return source.sourceUrl.ifBlank { source.sourceName }
    }

    private fun String.debugToken(): String {
        return replace(Regex("""[\s=:/\\#]+"""), "_").take(180)
    }

    override suspend fun getBookInfo(bookId: String?): BookDetailBeanInOwn = withSourceRequestScope("detail", bookId) {
        withContext(activeSourceRequestDispatcher()) {
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
            val displayChapters = displayAnchorCatalogChapters(resolved)
            val previewCover = selectVerifiedCover(detail.coverUrl, resolved.book.coverUrl, sourceBook.coverUrl)
            val previewCoverCandidates = coverCandidateUrls(
                previewCover,
                SourceEngineBookRoute.coverCandidates(route) +
                    resolved.coverCandidates +
                    listOf(detail.coverUrl, resolved.book.coverUrl, sourceBook.coverUrl)
            )
            val displayBook = detailDisplayRouteBook(
                requestedBook = sourceBook,
                resolvedBook = resolved.book,
                detail = detail,
                coverUrl = previewCover,
                sessionValidated = preview.mode.startsWith("session-")
            )
            val detailRouteId = SourceEngineBookRoute.bookId(
                displayBook,
                previewCoverCandidates
            )
            val previewLastChapter = rawChapters.lastOrNull()?.displayTitle
                ?: SourceEngineMetadataCleaner.cleanText(detail.lastChapter).ifBlank {
                    SourceEngineMetadataCleaner.cleanText(route.lastChapter)
                }
            val detailBean = BookDetailBeanInOwn().apply {
                routeId = detailRouteId
                shelfBookId = SourceEngineBookRoute.shelfBookId(displayBook)
                this.bookId = detailRouteId.hashCode()
                title = displayBook.name
                author = cleanAuthor(displayBook.author)
                cover = previewCover
                coverCandidates = previewCoverCandidates
                desc = displayIntro(detail.intro)
                lastChapter = previewLastChapter.ifBlank { null }
                chaptersCount = displayChapters.size.takeIf { it > 0 } ?: rawChapters.size
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
                    "author_${author.orEmpty().debugToken()}_raw_${rawChapters.size}" +
                        "_last_${lastChapter.orEmpty().debugToken()}" +
                        "_cover_${!cover.isNullOrBlank()}_intro_${!desc.isNullOrBlank()}" +
                        "_mode_${preview.mode.debugToken()}_ms_${System.currentTimeMillis() - startedAt}" +
                        "_source_${sourceLabel(resolved.book).debugToken()}"
                )
            }
            val collBook = detailBean.collBookBean
            val chapterBeans = displayChapters.toBookChapterBeans(collBook)
            if (chapterBeans.isNotEmpty()) {
                detailBean.bookChapters = chapterBeans
                collBook.setBookChapters(chapterBeans)
                collBook.chaptersCount = chapterBeans.size
                collBook.lastChapter = chapterBeans.lastOrNull()?.title ?: detailBean.lastChapter
            }
            detailBean
        }
    }

    private fun detailDisplayRouteBook(
        requestedBook: SourceBook,
        resolvedBook: SourceBook,
        detail: SourceBookDetail,
        coverUrl: String,
        sessionValidated: Boolean
    ): SourceBook {
        val enrichedBook = detail.toSearchBook(resolvedBook)
        val preserveRequestedIdentity = shouldPreserveDetailDisplayIdentity(
            requestedBook = requestedBook,
            resolvedBook = enrichedBook,
            sessionValidated = sessionValidated
        )
        val displayName = if (preserveRequestedIdentity) {
            requestedBook.name.ifBlank { enrichedBook.name }
        } else {
            enrichedBook.name.ifBlank { requestedBook.name }
        }
        val displayAuthor = if (preserveRequestedIdentity) {
            preferredCompatibleDetailAuthor(
                requestedBook.author,
                enrichedBook.author,
                resolvedBook.author,
                detail.author
            )
        } else {
            preferredCompatibleDetailAuthor(
                enrichedBook.author,
                resolvedBook.author,
                detail.author,
                requestedBook.author
            )
        }
        return resolvedBook.copy(
            name = displayName,
            author = displayAuthor,
            coverUrl = coverUrl,
            intro = cleanIntro(detail.intro).ifBlank { cleanIntro(enrichedBook.intro).ifBlank { cleanIntro(resolvedBook.intro) } },
            kind = detail.kind.ifBlank { enrichedBook.kind.ifBlank { resolvedBook.kind } },
            lastChapter = detail.lastChapter.ifBlank { enrichedBook.lastChapter.ifBlank { resolvedBook.lastChapter } }
        )
    }

    private fun shouldPreserveDetailDisplayIdentity(
        requestedBook: SourceBook,
        resolvedBook: SourceBook,
        sessionValidated: Boolean
    ): Boolean {
        val requestedTitle = searchRanker.canonicalTitleKey(requestedBook)
        val resolvedTitle = searchRanker.canonicalTitleKey(resolvedBook)
        if (requestedTitle.isBlank() || resolvedTitle.isBlank()) return false
        val authorsCompatible = BookIdentity.authorsCompatible(requestedBook.author, resolvedBook.author)
        if (requestedTitle == resolvedTitle) return authorsCompatible
        if (!sessionValidated) return false
        if (sameOrContainedSearchKey(requestedTitle, resolvedTitle)) return true
        return authorsCompatible && catalogAliasTitleMayNeedValidation(requestedTitle, resolvedTitle)
    }

    private fun preferredCompatibleDetailAuthor(primary: String?, vararg candidates: String?): String {
        var selected = cleanAuthor(primary.orEmpty())
        candidates.forEach { candidate ->
            val author = cleanAuthor(candidate.orEmpty())
            if (author.isBlank()) return@forEach
            if (selected.isBlank() || BookIdentity.authorsCompatible(selected, author)) {
                selected = BookIdentity.preferredDisplayAuthor(selected, author)
            }
        }
        return selected
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
        val probeScope = CoroutineScope(activeSourceRequestDispatcher() + SupervisorJob() + activeSourceRequestContext())
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
        getBookFolder(bookId, collBookBean, triggerV8ForReading = false)

    suspend fun getBookFolder(
        bookId: String?,
        collBookBean: CollBookBean,
        triggerV8ForReading: Boolean
    ): List<BookChapterBean> =
        withSourceRequestScope("catalog", bookId) {
            val startedAt = System.currentTimeMillis()
            AiBridgeTrace.event(
                "source_catalog_provider_entered",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "bookId" to bookId.orEmpty(),
                    "cached" to (collBookBean.getBookChapters()?.size ?: 0)
                )
            )
            withContext(Dispatchers.Default) {
                fastCatalogBookChapterBeans(bookId, collBookBean, startedAt, triggerV8ForReading)
            }?.let { chapters ->
                return@withSourceRequestScope chapters
            }
            withContext(activeSourceRequestDispatcher()) {
                verifiedCatalogBookChapterBeans(bookId, collBookBean, startedAt, triggerV8ForReading)
            }
        }

    private suspend fun fastCatalogBookChapterBeans(
        bookId: String?,
        collBookBean: CollBookBean,
        startedAt: Long,
        triggerV8ForReading: Boolean
    ): List<BookChapterBean>? {
        val routeStartedAt = System.currentTimeMillis()
        val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
        val sourceStartedAt = System.currentTimeMillis()
        val source = sourceFinder(route.sourceUrl)
        val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
        val waterfallStartedAt = System.currentTimeMillis()
        val waterfall = rememberBookContentWaterfall(sourceBook)
        loadPersistedTierIntoWaterfall(waterfall, collBookBean.get_id())
        val sessionTrusted = verifiedBookCount(waterfall)
        val sessionHasCover = hasTrustedCover(waterfall)
        val fastResolved = resolveCatalogAnchorBook(sourceBook, waterfall, DETAIL_PREVIEW_TIMEOUT_MS)
        val fastReason = when {
            fastResolved != null && sessionTrusted >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT -> "trusted_session_catalog"
            fastResolved != null -> "single_anchor_catalog"
            sessionTrusted < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT -> "insufficient_trusted_sources"
            !sessionHasCover -> "missing_trusted_cover"
            else -> "no_readable_head_catalog"
        }
        AiBridgeTrace.event(
            "source_catalog_fast_probe",
            sourceBook.name,
            AiBridgeTrace.fields(
                "routeMs" to (sourceStartedAt - routeStartedAt),
                "sourceMs" to (waterfallStartedAt - sourceStartedAt),
                "waterfallMs" to (System.currentTimeMillis() - waterfallStartedAt),
                "trusted" to sessionTrusted,
                "cover" to sessionHasCover,
                "result" to (fastResolved != null),
                "reason" to fastReason,
                "elapsedMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        val resolved = fastResolved ?: return null
        val detail = resolved.detail
        val canonicalCatalog = resolved.catalog
        AiBridgeTrace.event(
            "source_catalog_anchor_selected",
            detail.name,
            AiBridgeTrace.fields(
                "rawChapters" to canonicalCatalog.chapters.size,
                "duplicates" to canonicalCatalog.duplicateCount,
                "trusted" to verifiedBookCount(waterfall),
                "reason" to fastReason,
                "elapsedMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        val trimStartedAt = System.currentTimeMillis()
        val chapters = displayAnchorCatalogChapters(resolved)
        val trimMs = System.currentTimeMillis() - trimStartedAt
        val trimmedCount = canonicalCatalog.chapters.size - chapters.size
        val mode = "anchor-fast"
        Log.i(
            TAG,
            "operation=catalogResolved provider=$providerName mode=$mode title=${detail.name} " +
                "chapters=${chapters.size} rawChapters=${canonicalCatalog.chapters.size} " +
                "trimmed=$trimmedCount duplicates=${canonicalCatalog.duplicateCount} trimMs=$trimMs " +
                "first=${chapters.firstOrNull()?.displayTitle} last=${chapters.lastOrNull()?.displayTitle} " +
                "durationMs=${System.currentTimeMillis() - startedAt}"
        )
        AiBridgeTrace.state(
            "source_catalog_anchor_displayed",
            detail.name,
            "chapters_${chapters.size}_raw_${canonicalCatalog.chapters.size}" +
                "_hidden_${trimmedCount}" +
                "_first_${chapters.firstOrNull()?.displayTitle.orEmpty().debugToken()}" +
                "_last_${chapters.lastOrNull()?.displayTitle.orEmpty().debugToken()}" +
                "_source_${sourceLabel(resolved.book).debugToken()}" +
                "_mode_${mode}_reason_${fastReason}_trusted_${verifiedBookCount(waterfall)}" +
                "_trimMs_${trimMs}" +
                "_durationMs_${System.currentTimeMillis() - startedAt}"
        )
        scheduleDisplayedCatalogV8IfNeeded(
            waterfall = waterfall,
            resolved = resolved,
            triggerV8ForReading = triggerV8ForReading,
            reason = "reading-catalog-anchor-fast"
        )
        return chapters.toBookChapterBeans(collBookBean)
    }

    private suspend fun verifiedCatalogBookChapterBeans(
        bookId: String?,
        collBookBean: CollBookBean,
        startedAt: Long,
        triggerV8ForReading: Boolean
    ): List<BookChapterBean> {
        val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
        val source = sourceFinder(route.sourceUrl)
        val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
        val waterfall = rememberBookContentWaterfall(sourceBook)
        loadPersistedTierIntoWaterfall(waterfall, collBookBean.get_id())
        val sessionTrusted = verifiedBookCount(waterfall)
        val sessionHasCover = hasTrustedCover(waterfall)
        AiBridgeTrace.event(
            "source_catalog_started",
            sourceBook.name,
            AiBridgeTrace.fields(
                "author" to sourceBook.author,
                "source" to sourceLabel(sourceBook),
                "sessionTrusted" to sessionTrusted,
                "sessionCover" to sessionHasCover,
                "fast" to false
            )
        )
        AiBridgeTrace.event(
            "source_catalog_decision",
            sourceBook.name,
            AiBridgeTrace.fields(
                "mode" to "verified_tail",
                "reason" to "fast_probe_failed",
                "sessionTrusted" to sessionTrusted,
                "candidates" to synchronized(waterfall.candidates) { waterfall.candidates.size }
            )
        )
        val resolved = resolveCatalogAnchorBook(sourceBook, waterfall, DETAIL_DIRECT_TIMEOUT_MS)
            ?: resolveReadableBook(sourceBook).also { anchor -> cacheResolvedBookInWaterfall(waterfall, anchor) }
        val detail = resolved.detail
        val canonicalCatalog = resolved.catalog
        val chapters = displayAnchorCatalogChapters(resolved)
        val trimmedCount = canonicalCatalog.chapters.size - chapters.size
        val mode = "anchor-direct"
        Log.i(
            TAG,
            "operation=catalogResolved provider=$providerName mode=$mode title=${detail.name} " +
                "chapters=${chapters.size} rawChapters=${canonicalCatalog.chapters.size} " +
                "trimmed=$trimmedCount duplicates=${canonicalCatalog.duplicateCount} " +
                "first=${chapters.firstOrNull()?.displayTitle} last=${chapters.lastOrNull()?.displayTitle} " +
                "durationMs=${System.currentTimeMillis() - startedAt}"
        )
        AiBridgeTrace.state(
            "source_catalog_resolved",
            detail.name,
            "chapters_${chapters.size}_raw_${canonicalCatalog.chapters.size}" +
                "_trimmed_${trimmedCount}" +
                "_first_${chapters.firstOrNull()?.displayTitle.orEmpty().debugToken()}" +
                "_last_${chapters.lastOrNull()?.displayTitle.orEmpty().debugToken()}" +
                "_source_${sourceLabel(resolved.book).debugToken()}" +
                "_mode_${mode}_reason_fast_probe_failed_trusted_${verifiedBookCount(waterfall)}" +
                "_durationMs_${System.currentTimeMillis() - startedAt}"
        )
        scheduleDisplayedCatalogV8IfNeeded(
            waterfall = waterfall,
            resolved = resolved,
            triggerV8ForReading = triggerV8ForReading,
            reason = "reading-catalog-anchor-direct"
        )
        return chapters.toBookChapterBeans(collBookBean)
    }

    private fun scheduleDisplayedCatalogV8IfNeeded(
        waterfall: BookContentWaterfall,
        resolved: ResolvedSourceBook,
        triggerV8ForReading: Boolean,
        reason: String
    ) {
        if (!triggerV8ForReading) return
        if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) return
        scheduleV8ValidationForResolvedBooks(
            waterfall = waterfall,
            resolvedBooks = listOf(resolved),
            reason = reason,
            priority = V8ValidationPriority.BACKGROUND
        )
    }

    suspend fun getReadingBootstrapChapters(
        bookId: String?,
        collBookBean: CollBookBean,
        limit: Int
    ): List<BookChapterBean> = withSourceRequestScope("catalog-bootstrap", bookId) {
        withContext(Dispatchers.Default) {
            val startedAt = System.currentTimeMillis()
            val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
            val source = sourceFinder(route.sourceUrl)
            val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
            val waterfall = rememberBookContentWaterfall(sourceBook)
            val resolved = resolveCatalogAnchorBook(sourceBook, waterfall, DETAIL_PREVIEW_TIMEOUT_MS)
            val reason = when {
                resolved != null && verifiedBookCount(waterfall) >= FIRST_DISPLAY_TRUSTED_SOURCE_COUNT -> "trusted_session_catalog"
                resolved != null -> "single_anchor_catalog"
                verifiedBookCount(waterfall) < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT -> "insufficient_trusted_sources"
                !hasTrustedCover(waterfall) -> "missing_trusted_cover"
                else -> "no_readable_head_catalog"
            }
            val chapters = resolved
                ?.catalog
                ?.chapters
                ?.let { chapters -> dropLeadingCatalogFrontMatter(sourceBook.name, chapters) }
                ?.take(limit.coerceAtLeast(1))
                .orEmpty()
            AiBridgeTrace.event(
                "source_catalog_bootstrap",
                sourceBook.name,
                AiBridgeTrace.fields(
                    "chapters" to chapters.size,
                    "limit" to limit,
                    "raw" to (resolved?.catalog?.chapters?.size ?: 0),
                    "trusted" to verifiedBookCount(waterfall),
                    "cover" to hasTrustedCover(waterfall),
                    "reason" to reason,
                    "display" to false,
                    "first" to chapters.firstOrNull()?.displayTitle.orEmpty(),
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            chapters.toBookChapterBeans(collBookBean)
        }
    }

    fun getCachedReadingCatalog(
        bookId: String?,
        collBookBean: CollBookBean
    ): List<BookChapterBean> {
        val startedAt = System.currentTimeMillis()
        val route = runCatching { SourceEngineBookRoute.decodeBookId(requireNotNull(bookId)) }
            .getOrElse { error ->
                AiBridgeTrace.event(
                    "source_catalog_session_cache_skipped",
                    collBookBean.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "reason" to error.javaClass.simpleName,
                        "durationMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                return emptyList()
            }
        val sourceBook = runCatching {
            SourceEngineBookRoute.toSourceBook(sourceFinder(route.sourceUrl), route)
        }.getOrElse { error ->
            AiBridgeTrace.event(
                "source_catalog_session_cache_skipped",
                route.name,
                AiBridgeTrace.fields(
                    "reason" to error.javaClass.simpleName,
                    "source" to route.sourceUrl,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return emptyList()
        }
        val waterfall = bookContentWaterfallCache[bookWaterfallKey(sourceBook)] ?: run {
            AiBridgeTrace.event(
                "source_catalog_session_cache_skipped",
                sourceBook.name,
                AiBridgeTrace.fields(
                    "reason" to "missing_waterfall",
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return emptyList()
        }
        val resolved = cachedReadingResolvedBook(sourceBook, waterfall) ?: run {
            AiBridgeTrace.event(
                "source_catalog_session_cache_skipped",
                sourceBook.name,
                AiBridgeTrace.fields(
                    "reason" to "missing_resolved",
                    "trusted" to verifiedBookCount(waterfall),
                    "resolved" to synchronized(waterfall.resolvedBooks) { waterfall.resolvedBooks.size },
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return emptyList()
        }
        val chapters = displayAnchorCatalogChapters(resolved)
        if (chapters.isEmpty()) {
            AiBridgeTrace.event(
                "source_catalog_session_cache_skipped",
                sourceBook.name,
                AiBridgeTrace.fields(
                    "reason" to "empty_catalog",
                    "raw" to resolved.catalog.chapters.size,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return emptyList()
        }
        AiBridgeTrace.state(
            "source_catalog_session_cache_hit",
            sourceBook.name,
            AiBridgeTrace.fields(
                "chapters" to chapters.size,
                "raw" to resolved.catalog.chapters.size,
                "trusted" to verifiedBookCount(waterfall),
                "resolved" to synchronized(waterfall.resolvedBooks) { waterfall.resolvedBooks.size },
                "source" to sourceLabel(resolved.book),
                "last" to chapters.lastOrNull()?.displayTitle.orEmpty(),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        return chapters.toBookChapterBeans(collBookBean)
    }

    override suspend fun getBookContent(
        bookId: String?,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        sourceIndex: Int
    ): String = withSourceRequestScope("content", bookChapter.link) {
        withContext(activeSourceRequestDispatcher()) {
            val route = SourceEngineBookRoute.decodeChapterId(requireNotNull(bookChapter.link))
            val source = sourceFinder(route.sourceUrl)
            val chapter = SourceEngineBookRoute.toSourceChapter(source, route)
            val startedAt = System.currentTimeMillis()
            var completed = false
            AiBridgeTrace.event(
                "source_content_request",
                sourceBook.title ?: chapter.book.name,
                "chapter_${bookChapter.title.orEmpty().debugToken()}_index_${chapter.index}" +
                    "_source_${sourceLabel(chapter.book).debugToken()}"
            )
            val text = withTimeoutOrNull(CONTENT_REQUEST_TOTAL_TIMEOUT_MS) {
                readFastRoutedChapterContent(chapter, sourceBook, bookChapter)?.let { content ->
                    completed = true
                    return@withTimeoutOrNull content.cleanedContent
                }
                val content = readFirstDisplayChapterContent(chapter, sourceBook, bookChapter)
                completed = true
                content?.cleanedContent
            }
            if (text != null) return@withContext text
            val reason = if (completed) "trusted_content_missing" else "timeout"
            AiBridgeTrace.state(
                "source_content_request_failed",
                sourceBook.title ?: chapter.book.name,
                AiBridgeTrace.fields(
                    "chapter" to bookChapter.title.orEmpty(),
                    "reason" to reason,
                    "timeoutMs" to CONTENT_REQUEST_TOTAL_TIMEOUT_MS,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            error("Source-engine content request failed: $reason ${chapter.book.name} ${chapter.name}")
        }
    }

    private fun List<CanonicalChapter>.toBookChapterBeans(collBookBean: CollBookBean): List<BookChapterBean> {
        return mapIndexedNotNull { index, canonicalChapter ->
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

    private suspend fun fastCatalogResolvedBook(waterfall: BookContentWaterfall): ResolvedSourceBook? {
        if (!isTrustedTierReady(waterfall, FIRST_DISPLAY_TRUSTED_SOURCE_COUNT)) return null
        return verifiedBooksSnapshot(waterfall)
            .filter { resolved ->
                resolved.catalog.chapters.size >= minReadableChapterCountFor(resolved.catalog.chapters.size) &&
                    resolved.hasReadableCatalogHead()
            }
            .map { resolved -> catalogAnchorSignal(resolved) }
            .minWithOrNull(catalogAnchorSignalComparator)
            ?.resolved
    }

    private suspend fun resolveCatalogAnchorBook(
        sourceBook: SourceBook,
        waterfall: BookContentWaterfall,
        directTimeoutMs: Long
    ): ResolvedSourceBook? {
        val trustedResolved = fastCatalogResolvedBook(waterfall)
        val cachedResolved = cachedResolvedBook(waterfall, sourceBook)
        if (trustedResolved != null && shouldPreferTrustedCatalogAnchor(trustedResolved, cachedResolved)) {
            return trustedResolved
        }
        cachedResolved?.let { resolved -> return resolved }
        val directResolved = loadReadableBookWithTimeout(sourceBook, engine, directTimeoutMs)
            ?.also { resolved -> traceCatalogAnchorLoaded(waterfall, sourceBook, resolved) }
            ?: return trustedResolved
        return if (trustedResolved != null && shouldPreferTrustedCatalogAnchor(trustedResolved, directResolved)) {
            trustedResolved
        } else {
            directResolved
        }
    }

    private fun traceCatalogAnchorLoaded(
        waterfall: BookContentWaterfall,
        sourceBook: SourceBook,
        resolved: ResolvedSourceBook
    ) {
        cacheResolvedBookInWaterfall(waterfall, resolved)
        AiBridgeTrace.event(
            "source_catalog_anchor_loaded",
            sourceBook.name,
            AiBridgeTrace.fields(
                "source" to sourceLabel(resolved.book),
                "chapters" to resolved.catalog.chapters.size,
                "trusted" to verifiedBookCount(waterfall)
            )
        )
    }

    private suspend fun shouldPreferTrustedCatalogAnchor(
        trustedResolved: ResolvedSourceBook,
        cachedResolved: ResolvedSourceBook?
    ): Boolean {
        val cachedSignal = cachedResolved?.let { catalogAnchorSignal(it) } ?: return true
        val trustedSignal = catalogAnchorSignal(trustedResolved)
        return trustedSignal.lastReadableOrdinal > cachedSignal.lastReadableOrdinal ||
            trustedSignal.readableChapterCount > cachedSignal.readableChapterCount
    }

    private suspend fun catalogAnchorSignal(resolved: ResolvedSourceBook): CatalogAnchorSignal {
        val readableChapters = catalogAnchorReadableChapters(resolved)
        val lastReadableOrdinal = lastChapterOrdinal(readableChapters)
        val tailOrdinalGapCount = tailOrdinalGapCount(readableChapters)
        return CatalogAnchorSignal(
            resolved = resolved,
            readableChapterCount = readableChapters.size,
            lastReadableOrdinal = lastReadableOrdinal,
            tailOrdinalGapCount = tailOrdinalGapCount,
            tailContinuityScore = tailContinuityScore(lastReadableOrdinal, tailOrdinalGapCount)
        )
    }

    private suspend fun catalogAnchorReadableChapters(resolved: ResolvedSourceBook): List<CanonicalChapter> {
        val signalChapters = readableAnchorCatalogChapters(resolved)
        if (signalChapters.size > SMALL_CATALOG_CONTIGUITY_SCAN_CHAPTERS) return signalChapters
        val prefix = ArrayList<CanonicalChapter>(signalChapters.size)
        for (chapter in signalChapters) {
            val sourceChapter = chapter.sourceChapters.firstOrNull() ?: break
            val content = loadCleanContentWithTimeout(sourceChapter, CATALOG_ANCHOR_CONTENT_TIMEOUT_MS) ?: break
            if (!isReadableContent(content)) break
            prefix.add(chapter)
        }
        return prefix
    }

    private fun displayAnchorCatalogChapters(resolved: ResolvedSourceBook): List<CanonicalChapter> {
        return dropLeadingCatalogFrontMatter(
            resolved.detail.name,
            resolved.catalog.chapters
        )
    }

    private fun cachedReadingResolvedBook(
        sourceBook: SourceBook,
        waterfall: BookContentWaterfall
    ): ResolvedSourceBook? {
        verifiedBooksSnapshot(waterfall)
            .firstOrNull { resolved ->
                resolved.hasReadableCatalogHead() && displayAnchorCatalogChapters(resolved).isNotEmpty()
            }
            ?.let { return it }
        cachedResolvedBook(waterfall, sourceBook)
            ?.takeIf { resolved ->
                resolved.hasReadableCatalogHead() && displayAnchorCatalogChapters(resolved).isNotEmpty()
            }
            ?.let { return it }
        return synchronized(waterfall.resolvedBooks) {
            waterfall.resolvedBooks.values.toList()
        }.sortedWith(
            compareByDescending<ResolvedSourceBook> { sourceQualityRouter.bookSourceScore(it.book) }
                .thenByDescending { it.catalog.chapters.size }
                .thenBy { sourcePriorityIndex(it.book.source, it.book.name) }
        ).firstOrNull { resolved ->
            resolved.hasReadableCatalogHead() && displayAnchorCatalogChapters(resolved).isNotEmpty()
        }
    }

    private fun readableAnchorCatalogChapters(resolved: ResolvedSourceBook): List<CanonicalChapter> {
        return dropLeadingCatalogFrontMatter(
            resolved.detail.name,
            resolved.catalog.chapters
        )
    }

    private fun isReadableContent(content: CleanContent): Boolean {
        return content.report.cleanedLength >= MIN_CLEAN_CONTENT_CHARS &&
            content.report.qualityScore >= MIN_CONTENT_QUALITY_SCORE &&
            content.report.coherenceScore >= MIN_CONTENT_COHERENCE_SCORE
    }

    private fun hasDisplayableContent(content: CleanContent): Boolean {
        return content.cleanedContent.isNotBlank() && content.report.cleanedLength > 0
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
            if (isTrustedTierReady(cached, FIRST_DISPLAY_TRUSTED_SOURCE_COUNT)) {
                bestFirstDisplayBook(cached)?.let { resolved ->
                    probeCatalogTail(resolved)
                    Log.i(
                        TAG,
                        "operation=firstDisplayFromSession provider=$providerName title=${sourceBook.name} " +
                            "author=${sourceBook.author} trusted=${verifiedBookCount(cached)}"
                    )
                    return resolved
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
        if (!isTrustedTierReady(waterfall, FIRST_DISPLAY_TRUSTED_SOURCE_COUNT)) {
            error("Source-engine first display trusted source count too low: ${sourceBook.name}")
        }
        return bestFirstDisplayBook(waterfall) ?: resolved
    }

    suspend fun prepareBookContentTier(
        bookId: String?,
        collBookBean: CollBookBean?,
        persist: Boolean = false,
        triggerV8: Boolean = false,
        requestPriority: SourceRequestPriority = SourceRequestPriority.FOREGROUND,
        maintenanceOnly: Boolean = false
    ): Boolean {
        return prepareBookContentTierResult(
            bookId = bookId,
            collBookBean = collBookBean,
            persist = persist,
            triggerV8 = triggerV8,
            requestPriority = requestPriority,
            maintenanceOnly = maintenanceOnly
        ).isReady
    }

    suspend fun prepareBookContentTierResult(
        bookId: String?,
        collBookBean: CollBookBean?,
        persist: Boolean = false,
        triggerV8: Boolean = false,
        requestPriority: SourceRequestPriority = SourceRequestPriority.FOREGROUND,
        maintenanceOnly: Boolean = false
    ): SourceContentTierPrepareResult =
        withSourceRequestScope("contentTier", bookId, requestPriority) {
            withContext(activeSourceRequestDispatcher()) {
                val startedAt = System.currentTimeMillis()
                val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
                val source = sourceFinder(route.sourceUrl)
                val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
                val waterfall = rememberBookContentWaterfall(sourceBook)
                AiBridgeTrace.event(
                    "source_content_tier_prepare_started",
                    sourceBook.name,
                    AiBridgeTrace.fields(
                        "target" to BOOK_CONTENT_TIER_TARGET_SIZE,
                        "persist" to persist,
                        "trustedBefore" to verifiedBookCount(waterfall),
                        "candidates" to synchronized(waterfall.candidates) { waterfall.candidates.size }
                    )
                )
                if (persist) {
                    loadPersistedTierIntoWaterfall(waterfall, collBookBean?.get_id())
                }
                val v8Scheduled = AtomicBoolean(false)
                val scheduledV8Jobs = ArrayList<Job>()
                fun scheduleV8Once(stage: String) {
                    if (!triggerV8 || verifiedBookCount(waterfall) <= 0) return
                    if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) return
                    if (!v8Scheduled.compareAndSet(false, true)) return
                    scheduledV8Jobs += schedulePrimaryV8Validation(
                        waterfall,
                        stage,
                        V8ValidationPriority.BACKGROUND
                    )
                }
                scheduleV8Once("reading-tier-persisted")
                refreshBookContentWaterfall(sourceBook, FallbackSearchPolicy.PERSONAL_ONLY)
                loadReadableBookWithTimeout(sourceBook, detailProbeEngine, DETAIL_PROBE_TIMEOUT_MS)
                    ?.let { resolved ->
                        promoteTrustedResolvedBookInWaterfall(waterfall, resolved)
                        scheduleV8Once("reading-tier-readable")
                    }
                val personalResult = fillBookContentTierOnce(
                    waterfall,
                    BOOK_CONTENT_TIER_TARGET_SIZE,
                    BOOK_CONTENT_TIER_FILL_TIMEOUT_MS,
                    policy = FallbackSearchPolicy.PERSONAL_ONLY
                ) {
                    scheduleV8Once("reading-tier-first-trusted")
                }
                scheduleV8Once("reading-tier-personal")
                val result = if (personalResult.isReady) {
                    SourceContentTierPrepareResult.READY
                } else if (maintenanceOnly) {
                    personalResult
                } else if (requestPriority == SourceRequestPriority.FOREGROUND) {
                    refreshBookContentWaterfall(sourceBook, FallbackSearchPolicy.PERSONAL_THEN_GLOBAL)
                    fillBookContentTierOnce(
                        waterfall,
                        BOOK_CONTENT_TIER_TARGET_SIZE,
                        BOOK_CONTENT_TIER_FILL_TIMEOUT_MS,
                        policy = FallbackSearchPolicy.PERSONAL_THEN_GLOBAL
                    ) {
                        scheduleV8Once("reading-tier-global")
                    }
                } else {
                    withChildSourceRequestScope(
                        "contentTierGlobal",
                        bookId,
                        SourceRequestPriority.BACKGROUND_LOW
                    ) {
                        withContext(activeSourceRequestDispatcher()) {
                            refreshBookContentWaterfall(sourceBook, FallbackSearchPolicy.PERSONAL_THEN_GLOBAL)
                            fillBookContentTierOnce(
                                waterfall,
                                BOOK_CONTENT_TIER_TARGET_SIZE,
                                BOOK_CONTENT_TIER_FILL_TIMEOUT_MS,
                                policy = FallbackSearchPolicy.PERSONAL_THEN_GLOBAL
                            ) {
                                scheduleV8Once("reading-tier-global-low")
                            }
                        }
                    }
                }
                if (persist) {
                    persistVerifiedTier(waterfall, collBookBean?.get_id())
                }
                scheduleV8Once("reading-tier")
                if (maintenanceOnly) {
                    scheduledV8Jobs.forEach { job -> job.join() }
                }
                AiBridgeTrace.state(
                    "source_content_tier_prepare_finished",
                    sourceBook.name,
                    AiBridgeTrace.fields(
                        "ready" to result.isReady,
                        "status" to result.name.lowercase(),
                        "persist" to persist,
                        "trustedAfter" to verifiedBookCount(waterfall),
                        "hasCover" to hasTrustedCover(waterfall),
                        "durationMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                result
            }
        }

    fun startLowPriorityV8Maintenance(collectedBooksProvider: () -> List<CollBookBean>) {
        if (!v8MaintenanceStarted.compareAndSet(false, true)) return
        val job = v8BackgroundScope.launch(start = CoroutineStart.LAZY) {
            AiBridgeTrace.event(
                "source_catalog_v8_maintenance_started",
                "global",
                AiBridgeTrace.fields(
                    "intervalMs" to V8_MAINTENANCE_INTERVAL_MS,
                    "networkPriority" to SourceRequestPriority.BACKGROUND.name.lowercase()
                )
            )
            try {
                delay(V8_MAINTENANCE_INITIAL_DELAY_MS)
                while (true) {
                    if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) {
                        delay(V8_MAINTENANCE_INTERVAL_MS)
                        continue
                    }
                    val retryBooks = runCatching {
                        runLowPriorityV8MaintenanceCycle(collectedBooksProvider)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "operation=v8MaintenanceFailed provider=$providerName", error)
                        AiBridgeTrace.event(
                            "source_catalog_v8_maintenance_failed",
                            "global",
                            AiBridgeTrace.fields("reason" to error.javaClass.simpleName)
                        )
                    }.getOrDefault(0)
                    AiBridgeTrace.event(
                        "source_catalog_v8_maintenance_next_delay",
                        "global",
                        AiBridgeTrace.fields(
                            "retryBooks" to retryBooks,
                            "delayMs" to V8_MAINTENANCE_INTERVAL_MS
                        )
                    )
                    delay(V8_MAINTENANCE_INTERVAL_MS)
                }
            } finally {
                if (v8MaintenanceJob === coroutineContext[Job]) {
                    v8MaintenanceStarted.set(false)
                    v8MaintenanceJob = null
                }
            }
        }
        v8MaintenanceJob = job
        job.start()
    }

    fun stopLowPriorityV8Maintenance(reason: String) {
        val job = v8MaintenanceJob ?: return
        if (!job.isActive) return
        AiBridgeTrace.event(
            "source_catalog_v8_maintenance_stopped",
            "global",
            AiBridgeTrace.fields("reason" to reason)
        )
        job.cancel(CancellationException("stopLowPriorityV8Maintenance:$reason"))
        v8MaintenanceStarted.set(false)
        if (v8MaintenanceJob === job) {
            v8MaintenanceJob = null
        }
    }

    private suspend fun runLowPriorityV8MaintenanceCycle(collectedBooksProvider: () -> List<CollBookBean>): Int {
        if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) return 0
        val candidates = selectedLowPriorityV8MaintenanceBooks(collectedBooksProvider())
        AiBridgeTrace.event(
            "source_catalog_v8_maintenance_cycle",
            "global",
            AiBridgeTrace.fields(
                "books" to candidates.size,
                "stale" to candidates.count { candidate -> candidate.cacheState == V8MaintenanceCacheState.STALE },
                "missing" to candidates.count { candidate -> candidate.cacheState == V8MaintenanceCacheState.MISSING },
                "current" to candidates.count { candidate -> candidate.cacheState == V8MaintenanceCacheState.CURRENT }
            )
        )
        var retryBooks = 0
        val coldCandidates = candidates.filterNot { candidate -> candidate.cacheState == V8MaintenanceCacheState.CURRENT }
        for (candidate in coldCandidates) {
            retryBooks += runLowPriorityV8MaintenanceBook(candidate)
        }
        val currentCacheCandidates = candidates.filter { candidate -> candidate.cacheState == V8MaintenanceCacheState.CURRENT }
        currentCacheCandidates.chunked(V8_MAINTENANCE_CACHED_BOOK_CONCURRENCY).forEach { chunk ->
            val retries = supervisorScope {
                chunk.map { candidate ->
                    async { runLowPriorityV8MaintenanceBook(candidate) }
                }.awaitAll()
            }
            retryBooks += retries.sum()
        }
        AiBridgeTrace.event(
            "source_catalog_v8_maintenance_cycle_finished",
            "global",
            AiBridgeTrace.fields(
                "books" to candidates.size,
                "retryBooks" to retryBooks
            )
        )
        return retryBooks
    }

    private suspend fun runLowPriorityV8MaintenanceBook(candidate: V8MaintenanceBook): Int {
        val book = candidate.book
        val routeId = sourceEngineRouteBookId(book) ?: return 0
        AiBridgeTrace.event(
            "source_catalog_v8_maintenance_book_started",
            book.title.orEmpty(),
            AiBridgeTrace.fields(
                "route" to routeId,
                "author" to book.author.orEmpty(),
                "cacheState" to candidate.cacheState.name.lowercase(),
                "cacheCatalog" to candidate.cacheCatalogSize,
                "bookCatalog" to book.chaptersCount,
                "lastChapter" to book.lastChapter.orEmpty()
            )
        )
        if (candidate.cacheState == V8MaintenanceCacheState.CURRENT) {
            val restored = candidate.cache.cacheIdentity
                ?.let { identity -> v8MarkCache.load(identity) }
                ?.let { cached -> recordCachedV8Marks(cached, "maintenance-current-cache", "maintenance") }
                ?: false
            AiBridgeTrace.event(
                "source_catalog_v8_maintenance_book_finished",
                book.title.orEmpty(),
                AiBridgeTrace.fields(
                    "ready" to restored,
                    "timeout" to false,
                    "route" to routeId,
                    "cacheState" to candidate.cacheState.name.lowercase(),
                    "cachedRestored" to restored
                )
            )
            delay(V8_MAINTENANCE_BETWEEN_BOOK_DELAY_MS)
            return if (restored) 0 else 1
        }
        SourceEngineContentCachePolicy.ensureFresh(book)
        waitForForegroundNetworkIdle("v8-maintenance", book.title)
        val ready = withTimeoutOrNull(V8_MAINTENANCE_BOOK_TIMEOUT_MS) {
            prepareBookContentTier(
                bookId = routeId,
                collBookBean = book,
                persist = true,
                triggerV8 = true,
                requestPriority = SourceRequestPriority.BACKGROUND,
                maintenanceOnly = true
            )
        }
        AiBridgeTrace.event(
            "source_catalog_v8_maintenance_book_finished",
            book.title.orEmpty(),
            AiBridgeTrace.fields(
                "ready" to (ready == true),
                "timeout" to (ready == null),
                "route" to routeId,
                "cacheState" to candidate.cacheState.name.lowercase()
            )
        )
        delay(V8_MAINTENANCE_BETWEEN_BOOK_DELAY_MS)
        return if (ready == true) 0 else 1
    }

    private fun selectedLowPriorityV8MaintenanceBooks(books: List<CollBookBean>): List<V8MaintenanceBook> {
        val cacheSummaries = v8MarkCache.summaries()
        return books
            .filter { book -> !book.isLocal() && sourceEngineRouteBookId(book) != null }
            .distinctBy { book -> BookIdentity.sourceEngineIdentityKey(book.title, book.author) }
            .map { book -> V8MaintenanceBook(book, v8MaintenanceCacheState(book, cacheSummaries)) }
            .sortedWith(
                compareBy<V8MaintenanceBook> { candidate -> candidate.cacheState.sortOrder }
                    .thenByDescending { candidate -> candidate.book.lastRead.orEmpty() }
                    .thenByDescending { candidate -> candidate.book.updated.orEmpty() }
                    .thenBy { candidate -> candidate.book.title.orEmpty() }
            )
    }

    private fun v8MaintenanceCacheState(
        book: CollBookBean,
        cacheSummaries: List<SourceEngineV8MarkCache.Summary>
    ): V8MaintenanceCacheSnapshot {
        val bookName = normalizedMaintenanceTitle(book.title)
        val author = normalizedMaintenanceTitle(book.author)
        val summaries = cacheSummaries.filter { summary ->
            normalizedMaintenanceTitle(summary.identity.bookName) == bookName &&
                (author.isBlank() || normalizedMaintenanceTitle(summary.identity.author) == author)
        }
        if (summaries.isEmpty()) return V8MaintenanceCacheSnapshot(V8MaintenanceCacheState.MISSING)
        val current = summaries.firstOrNull { summary ->
            summary.identity.catalogSize == book.chaptersCount &&
                normalizedMaintenanceTitle(summary.identity.lastTitle) == normalizedMaintenanceTitle(book.lastChapter)
        }
        if (current != null) {
            return V8MaintenanceCacheSnapshot(
                state = V8MaintenanceCacheState.CURRENT,
                cacheCatalogSize = current.identity.catalogSize,
                cacheLastTitle = current.identity.lastTitle,
                cacheCreatedAtMs = current.createdAtMs,
                cacheIdentity = current.identity
            )
        }
        val newest = summaries.maxByOrNull { summary -> summary.createdAtMs }
        return V8MaintenanceCacheSnapshot(
            state = V8MaintenanceCacheState.STALE,
            cacheCatalogSize = newest?.identity?.catalogSize,
            cacheLastTitle = newest?.identity?.lastTitle,
            cacheCreatedAtMs = newest?.createdAtMs ?: 0L
        )
    }

    private fun normalizedMaintenanceTitle(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("""\s+"""), "")
            .orEmpty()
    }

    private fun sourceEngineRouteBookId(book: CollBookBean): String? {
        return when {
            SourceEngineBookRoute.isBookId(book.bookIdInBiquge) -> book.bookIdInBiquge
            SourceEngineBookRoute.isBookId(book.get_id()) -> book.get_id()
            else -> null
        }
    }

    private fun scheduleV8ValidationForTrustedSources(
        waterfall: BookContentWaterfall,
        reason: String
    ) {
        schedulePrimaryV8Validation(waterfall, reason, V8ValidationPriority.BACKGROUND)
    }

    private fun schedulePrimaryV8Validation(
        waterfall: BookContentWaterfall,
        reason: String,
        priority: V8ValidationPriority
    ): List<Job> {
        val primary = verifiedBooksSnapshot(waterfall).firstOrNull()
        if (primary == null) {
            return scheduleV8ValidationForResolvedBooks(waterfall, emptyList(), reason, priority = priority)
        }
        return scheduleV8ValidationForResolvedBooks(waterfall, listOf(primary), reason, priority = priority)
    }

    private fun scheduleV8ValidationForResolvedBooks(
        waterfall: BookContentWaterfall,
        resolvedBooks: List<ResolvedSourceBook>,
        reason: String,
        allowSecondaryProbe: Boolean = true,
        priority: V8ValidationPriority = V8ValidationPriority.BACKGROUND
    ): List<Job> {
        if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) {
            AiBridgeTrace.event(
                "source_catalog_v8_schedule_skipped",
                waterfall.sourceBook.name,
                AiBridgeTrace.fields("reason" to "disabled", "trigger" to reason)
            )
            return emptyList()
        }
        if (resolvedBooks.isEmpty()) {
            AiBridgeTrace.event(
                "source_catalog_v8_schedule_skipped",
                waterfall.sourceBook.name,
                AiBridgeTrace.fields("reason" to "empty_resolved_books", "trigger" to reason)
            )
            return emptyList()
        }
        val scheduled = ArrayList<Job>()
        resolvedBooks.distinctBy { resolved -> v8ValidationKey(resolved) }
            .take(BOOK_CONTENT_TIER_TARGET_SIZE)
            .forEach { resolved ->
                val validationKey = v8ValidationKey(resolved)
                restoreCachedV8MarksForResolvedBook(resolved, reason, "schedule")
                val v8RequestScope = newSourceRequestScope(
                    "v8-${priority.name.lowercase()}",
                    reason,
                    parent = currentSourceRequestScope(),
                    priority = SourceRequestPriority.BACKGROUND
                )
                val job = v8BackgroundScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val completed = try {
                            withContext(sourceRequestContext(v8RequestScope)) {
                                waitForForegroundNetworkIdle("v8-validation", resolved.detail.name)
                                withTimeoutOrNull(V8_VALIDATION_TOTAL_TIMEOUT_MS) {
                                    AiBridgeTrace.event(
                                        "source_catalog_v8_epoch_running",
                                        resolved.detail.name,
                                        AiBridgeTrace.fields(
                                            "trigger" to reason,
                                            "source" to sourceLabel(resolved.book),
                                            "secondaryProbe" to allowSecondaryProbe,
                                            "maxConcurrent" to V8_VALIDATION_MAX_CONCURRENT_EPOCHS,
                                            "priority" to priority.name.lowercase(),
                                            "strategy" to "dynamic",
                                            "networkPriority" to SourceRequestPriority.BACKGROUND.name.lowercase()
                                        )
                                    )
                                    runV8ValidationForResolvedBook(
                                        waterfall,
                                        resolved,
                                        reason,
                                        allowSecondaryProbe
                                    )
                                }
                            }
                        } catch (error: CancellationException) {
                            AiBridgeTrace.event(
                                "source_catalog_v8_epoch_cancelled",
                                resolved.detail.name,
                                AiBridgeTrace.fields(
                                    "trigger" to reason,
                                    "source" to sourceLabel(resolved.book),
                                    "priority" to priority.name.lowercase()
                                )
                            )
                            return@launch
                        } catch (error: Throwable) {
                            Log.w(
                                TAG,
                                "operation=sourceV8ValidationFailed provider=$providerName " +
                                    "title=${resolved.detail.name} source=${sourceLabel(resolved.book)}",
                                error
                            )
                            AiBridgeTrace.event(
                                "source_catalog_v8_epoch_failed",
                                resolved.detail.name,
                                AiBridgeTrace.fields(
                                    "reason" to error.javaClass.simpleName,
                                    "trigger" to reason,
                                    "source" to sourceLabel(resolved.book),
                                    "priority" to priority.name.lowercase(),
                                    *runtimeHeapTraceFields()
                                )
                            )
                            return@launch
                        }
                        if (completed == null) {
                            Log.w(
                                TAG,
                                "operation=sourceV8ValidationTimeout provider=$providerName " +
                                    "title=${resolved.detail.name} source=${sourceLabel(resolved.book)}"
                            )
                            AiBridgeTrace.event(
                                "source_catalog_v8_epoch_failed",
                                resolved.detail.name,
                                AiBridgeTrace.fields(
                                    "reason" to "timeout",
                                    "trigger" to reason,
                                    "source" to sourceLabel(resolved.book),
                                    "priority" to priority.name.lowercase()
                                )
                            )
                        }
                    } finally {
                        cancelSourceRequests(v8RequestScope)
                        v8ValidationTracker.finish(validationKey)
                    }
                }
                if (!v8ValidationTracker.start(validationKey, job)) {
                    job.cancel(CancellationException("Duplicate V8 validation."))
                    v8ValidationTracker.activeJob(validationKey)?.let { activeJob ->
                        scheduled += activeJob
                    }
                    AiBridgeTrace.event(
                        "source_catalog_v8_schedule_skipped",
                        resolved.detail.name,
                        AiBridgeTrace.fields(
                            "reason" to "already_started",
                            "trigger" to reason,
                            "source" to sourceLabel(resolved.book),
                            "priority" to priority.name.lowercase()
                        )
                    )
                    return@forEach
                }
                AiBridgeTrace.event(
                    "source_catalog_v8_epoch_started",
                    resolved.detail.name,
                    AiBridgeTrace.fields(
                        "trigger" to reason,
                        "source" to sourceLabel(resolved.book),
                        "chapters" to resolved.catalog.chapters.size,
                        "secondaryProbe" to allowSecondaryProbe,
                        "priority" to priority.name.lowercase(),
                        "networkPriority" to SourceRequestPriority.BACKGROUND.name.lowercase()
                    )
                )
                job.start()
                scheduled += job
            }
        return scheduled
    }

    private suspend fun runV8ValidationForResolvedBook(
        waterfall: BookContentWaterfall,
        resolved: ResolvedSourceBook,
        reason: String,
        allowSecondaryProbe: Boolean
    ) {
        val validationKey = v8ValidationKey(resolved)
        val sourceKey = sourceLabel(resolved.book)
        val rawSourceChapters = resolved.catalog.chapters.mapNotNull { chapter ->
            chapter.sourceChapters.firstOrNull()
        }
        val sourceChapters = rawSourceChapters.filter { chapter ->
            V8CatalogTitleClassifier.isStoryChapterTitle(chapter.name)
        }
        val skippedCatalogRows = rawSourceChapters.size - sourceChapters.size
        if (skippedCatalogRows > 0) {
            AiBridgeTrace.event(
                "source_catalog_v8_catalog_title_filtered",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "source" to sourceKey,
                    "raw" to rawSourceChapters.size,
                    "kept" to sourceChapters.size,
                    "skipped" to skippedCatalogRows,
                    "firstSkipped" to rawSourceChapters
                        .firstOrNull { chapter -> !V8CatalogTitleClassifier.isStoryChapterTitle(chapter.name) }
                        ?.name.orEmpty()
                )
            )
        }
        if (sourceChapters.isEmpty()) {
            AiBridgeTrace.event(
                "source_catalog_v8_epoch_failed",
                resolved.detail.name,
                AiBridgeTrace.fields("reason" to "empty_catalog", "trigger" to reason, "source" to sourceKey)
            )
            return
        }
        val rawContentByChapterIndex = LinkedHashMap<Int, V8ProbeContent>()
        val diagnosticSink = V8DiagnosticSink { line -> traceV8Diagnostic(resolved, line) }
        val v8Fingerprint = bookFingerprintForResolved(resolved)?.takeIf { fingerprint -> fingerprint.usable }
        suspend fun probeContentFor(position: Int): V8ProbeContent {
            val sourceChapter = sourceChapters.getOrNull(position) ?: return V8ProbeContent()
            rawContentByChapterIndex[sourceChapter.index]?.let { content -> return content }
            val probe = loadV8ProbeContentWithTimeout(sourceChapter, resolved, v8Fingerprint)
            rawContentByChapterIndex[sourceChapter.index] = probe
            return probe
        }
        suspend fun rawContentFor(position: Int): String {
            return probeContentFor(position).rawContent
        }
        val validationChapters = sourceChapters.map { chapter ->
            V8ValidationChapter(chapter.index, chapter.name)
        }
        val plan = v8ValidationPlanner.selectChapters(
            chapters = validationChapters,
            diagnosticSink = diagnosticSink
        ) { position, _ ->
            runBlocking { rawContentFor(position) }
        }
        if (plan.usableContext < V8ValidationPlanner.MIN_USABLE_CONTEXT_CHAPTERS) {
            AiBridgeTrace.event(
                "source_catalog_v8_epoch_failed",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "reason" to "insufficient_book_memory",
                    "trigger" to reason,
                    "source" to sourceKey,
                    "usableContext" to plan.usableContext
                )
            )
            return
        }
        val cacheIdentity = v8MarkCacheIdentity(resolved)
        val cachedMarks = v8MarkCache.load(cacheIdentity)
        val cachedReplayCandidates by lazy(LazyThreadSafetyMode.NONE) {
            val candidates = ArrayList<SourceEngineV8MarkCache.CachedMarks>()
            cachedMarks?.let { cached -> candidates += cached }
            candidates += v8MarkCache.replayCandidates(cacheIdentity)
                .filterNot { cached -> cached.identity == cacheIdentity }
            candidates
        }
        suspend fun runV8Epoch(
            phase: String,
            targetIndexes: Set<Int>
        ): V8ValidationEpoch {
            val v8AnalysisPositions = v8ValidationAnalysisPositions(plan, sourceChapters, targetIndexes)
            val inputs = v8AnalysisPositions.mapNotNull { position ->
                val sourceChapter = sourceChapters.getOrNull(position) ?: return@mapNotNull null
                val probe = probeContentFor(position)
                V8ChapterInput(
                    index = sourceChapter.index,
                    title = sourceChapter.name,
                    content = probe.rawContent,
                    contentQualitySignal = probe.qualitySignal
                )
            }
            AiBridgeTrace.event(
                "source_catalog_v8_validate_input_ready",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "trigger" to reason,
                    "source" to sourceKey,
                    "phase" to phase,
                    "strategy" to "dynamic",
                    "analysis" to inputs.size,
                    "targets" to targetIndexes.size,
                    "plannedTargets" to plan.targetIndexes.size,
                    "memory" to plan.usableContext,
                    "chars" to inputs.sumOf { input -> input.content.length },
                    *runtimeHeapTraceFields()
                )
            )
            val contentDigest = v8ValidationContentDigest(inputs, targetIndexes)
            val inputFingerprintsByChapterIndex = SourceEngineV8ValidationDigest.computeInputFingerprints(inputs)
            if (
                cachedMarks != null &&
                cachedMarks.contentDigest == contentDigest &&
                cachedMarks.targetChapterIndexes == targetIndexes.sorted()
            ) {
                AiBridgeTrace.event(
                    "source_catalog_v8_cache_content_hit",
                    resolved.detail.name,
                    AiBridgeTrace.fields(
                        "trigger" to reason,
                        "source" to cachedMarks.sourceLabel,
                        "phase" to phase,
                        "marks" to cachedMarks.marks.size,
                        "digest" to contentDigest.take(12)
                    )
                )
                return V8ValidationEpoch(
                    result = V8SourceRunResult(
                        title = resolved.detail.name,
                        author = resolved.detail.author,
                        sourceKey = cachedMarks.sourceLabel,
                        marks = cachedMarks.marks,
                        planningMarks = cachedMarks.marks
                    ),
                    inputs = inputs,
                    targetIndexes = targetIndexes,
                    contentDigest = contentDigest,
                    replayedFromCache = true
                )
            }
            SourceEngineV8ReplayCachePolicy.findReplay(
                identity = cacheIdentity,
                targetIndexes = targetIndexes,
                inputFingerprintsByChapterIndex = inputFingerprintsByChapterIndex,
                candidates = cachedReplayCandidates
            )?.let { replay ->
                AiBridgeTrace.event(
                    "source_catalog_v8_cache_replay_hit",
                    resolved.detail.name,
                    AiBridgeTrace.fields(
                        "trigger" to reason,
                        "source" to sourceKey,
                        "cachedSource" to replay.cached.sourceLabel,
                        "phase" to phase,
                        "reason" to replay.reason,
                        "targets" to targetIndexes.size,
                        "cachedTargets" to replay.cached.targetChapterIndexes.size,
                        "marks" to replay.cached.marks.size,
                        "compared" to replay.comparedInputs,
                        "minSimilarity" to "%.4f".format(replay.minSimilarity),
                        "avgSimilarity" to "%.4f".format(replay.averageSimilarity)
                    )
                )
                return V8ValidationEpoch(
                    result = V8SourceRunResult(
                        title = resolved.detail.name,
                        author = resolved.detail.author,
                        sourceKey = sourceKey,
                        marks = replay.cached.marks,
                        planningMarks = replay.cached.marks
                    ),
                    inputs = inputs,
                    targetIndexes = replay.cached.targetChapterIndexes.toSet(),
                    contentDigest = contentDigest,
                    replayedFromCache = true
                )
            }
            val validateStartedAtMs = System.currentTimeMillis()
            val result = v8ValidationSemaphore.withPermit {
                v8SourceValidator.validate(
                    V8SourceRunRequest(
                        title = resolved.detail.name,
                        author = resolved.detail.author,
                        sourceKey = sourceKey,
                        chapters = inputs,
                        markableChapterIndexes = targetIndexes,
                        contextChapterIndexes = plan.contextIndexes,
                        diagnosticSink = diagnosticSink
                    )
                )
            }
            val validateMs = System.currentTimeMillis() - validateStartedAtMs
            val resultCounts = result.marks.groupingBy { mark -> mark.state }.eachCount()
            AiBridgeTrace.event(
                "source_catalog_v8_validate_finished",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "trigger" to reason,
                    "source" to sourceKey,
                    "phase" to phase,
                    "strategy" to "dynamic",
                    "analysis" to inputs.size,
                    "targets" to targetIndexes.size,
                    "marks" to result.marks.size,
                    "planningMarks" to result.planningMarks.size,
                    "normal" to (resultCounts[V8ChapterMarkState.NORMAL] ?: 0),
                    "wrong" to (resultCounts[V8ChapterMarkState.WRONG] ?: 0),
                    "inconclusive" to (resultCounts[V8ChapterMarkState.INCONCLUSIVE] ?: 0),
                    "badTail" to result.firstBadTailOrdinal,
                    "durationMs" to validateMs,
                    "digest" to contentDigest.take(12),
                    *runtimeHeapTraceFields()
                )
            )
            return V8ValidationEpoch(result, inputs, targetIndexes, contentDigest)
        }

        val initialTargetIndexes = v8ValidationPlanner.initialTargetIndexes(plan, validationChapters)
        var epoch = runV8Epoch("initial", initialTargetIndexes)
        var expandRound = 1
        while (true) {
            val expandedTargetIndexes = v8ValidationPlanner.expandedTargetIndexes(
                chapters = validationChapters,
                currentTargetIndexes = epoch.targetIndexes,
                marks = epoch.result.planningMarks
            )
            if (expandedTargetIndexes.size <= epoch.targetIndexes.size) break
            AiBridgeTrace.event(
                "source_catalog_v8_validate_expand",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "trigger" to reason,
                    "source" to sourceKey,
                    "strategy" to "dynamic",
                    "round" to expandRound,
                    "fromTargets" to epoch.targetIndexes.size,
                    "toTargets" to expandedTargetIndexes.size
                )
            )
            epoch = runV8Epoch("expanded_$expandRound", expandedTargetIndexes)
            expandRound++
        }
        val result = epoch.result
        val inputs = epoch.inputs
        val v8TargetIndexes = epoch.targetIndexes
        rawContentByChapterIndex.clear()
        v8ChapterMarks[validationKey] = result.marks.associateBy { mark -> mark.chapterIndex }
        SourceEngineCatalogMarkRegistry.record(
            sourceBookKey(resolved.book),
            sourceKey,
            resolved.book.source.sourceUrl,
            resolved.detail.name,
            resolved.detail.author,
            result.marks,
            catalogIdentity = v8CatalogMarkIdentity(resolved)
        )
        val resultCounts = result.marks.groupingBy { mark -> mark.state }.eachCount()
        val shouldCommitQuality = result.marks.any { mark -> mark.state != V8ChapterMarkState.NORMAL }
        if (shouldCommitQuality) {
            sourceQualityRouter.recordV8ChapterMarks(
                book = resolved.book,
                latestObservedOrdinal = result.latestObservedOrdinal,
                latestNormalOrdinal = result.latestNormalOrdinal,
                firstBadTailOrdinal = result.firstBadTailOrdinal,
                normalCount = resultCounts[V8ChapterMarkState.NORMAL] ?: 0,
                wrongCount = resultCounts[V8ChapterMarkState.WRONG] ?: 0,
                nonStoryCount = resultCounts[V8ChapterMarkState.NON_STORY] ?: 0,
                badExtractionCount = resultCounts[V8ChapterMarkState.BAD_EXTRACTION] ?: 0,
                inconclusiveCount = resultCounts[V8ChapterMarkState.INCONCLUSIVE] ?: 0
            )
        } else {
            AiBridgeTrace.event(
                "source_catalog_v8_quality_commit_skipped",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "source" to sourceKey,
                    "reason" to "v8_probe_clean_partial",
                    "marks" to result.marks.size
                )
            )
        }
        val inputLengthsByChapterIndex = inputs.associate { input -> input.index to input.content.length }
        val inputFingerprintsByChapterIndex = SourceEngineV8ValidationDigest.computeInputFingerprints(inputs)
        val fragileInconclusiveIndexes = SourceEngineV8MarkCachePolicy.fragileThinInconclusiveIndexes(
            result.marks,
            inputLengthsByChapterIndex
        )
        val cacheableMarks = SourceEngineV8MarkCachePolicy.cacheableMarks(
            result.marks,
            inputLengthsByChapterIndex
        )
        val shouldSaveV8Marks = SourceEngineV8MarkCachePolicy.shouldSave(
            result.marks,
            inputLengthsByChapterIndex
        )
        val cacheSaved = if (epoch.replayedFromCache) {
            AiBridgeTrace.event(
                "source_catalog_v8_cache_save_skipped",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "source" to sourceKey,
                    "reason" to "replay_hit"
                )
            )
            false
        } else if (shouldSaveV8Marks && cacheableMarks.isNotEmpty()) {
            if (fragileInconclusiveIndexes.isNotEmpty()) {
                AiBridgeTrace.event(
                    "source_catalog_v8_cache_fragile_marks_retained",
                    resolved.detail.name,
                    AiBridgeTrace.fields(
                        "source" to sourceKey,
                        "chapters" to fragileInconclusiveIndexes.joinToString(","),
                        "stableMarks" to cacheableMarks.size,
                        "savedMarks" to result.marks.size
                    )
                )
            }
            v8MarkCache.save(
                cacheIdentity,
                sourceKey,
                result.marks,
                epoch.contentDigest,
                v8TargetIndexes.sorted(),
                inputFingerprintsByChapterIndex
            )
        } else {
            AiBridgeTrace.event(
                "source_catalog_v8_cache_save_skipped",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "source" to sourceKey,
                    "reason" to if (shouldSaveV8Marks) "thin_inconclusive_probe" else "v8_probe_clean_partial",
                    "chapters" to fragileInconclusiveIndexes.joinToString(",")
                )
            )
            false
        }
        AiBridgeTrace.event(
            "source_catalog_v8_cache_saved",
            resolved.detail.name,
            AiBridgeTrace.fields(
                "source" to sourceKey,
                "saved" to cacheSaved,
                "marks" to if (cacheSaved) result.marks.size else cacheableMarks.size,
                "catalog" to resolved.catalog.chapters.size
            )
        )
        AiBridgeTrace.state(
            "source_catalog_v8_epoch_committed",
            resolved.detail.name,
            AiBridgeTrace.fields(
                "trigger" to reason,
                "source" to sourceKey,
                "analysis" to inputs.size,
                "memory" to plan.usableContext,
                "normal" to result.marks.count { mark -> mark.state == V8ChapterMarkState.NORMAL },
                "wrong" to result.marks.count { mark -> mark.state == V8ChapterMarkState.WRONG },
                "nonStory" to result.marks.count { mark -> mark.state == V8ChapterMarkState.NON_STORY },
                "badExtraction" to result.marks.count { mark -> mark.state == V8ChapterMarkState.BAD_EXTRACTION },
                "inconclusive" to result.marks.count { mark -> mark.state == V8ChapterMarkState.INCONCLUSIVE }
            )
        )
        if (allowSecondaryProbe) {
            maybeScheduleDissimilarSecondaryV8Validation(
                waterfall = waterfall,
                primary = resolved,
                primarySourceChapters = sourceChapters,
                primaryContentByIndex = inputs.associate { input -> input.index to input.content },
                badChapterIndexes = result.marks
                    .filter { mark ->
                        mark.chapterIndex in plan.targetIndexes &&
                            mark.chapterIndex in v8TargetIndexes &&
                            SourceEngineV8SecondaryProbePolicy.shouldProbeTailSecondary(mark)
                    }
                    .map { mark -> mark.chapterIndex },
                reason = reason
            )
        }
    }

    private suspend fun maybeScheduleDissimilarSecondaryV8Validation(
        waterfall: BookContentWaterfall,
        primary: ResolvedSourceBook,
        primarySourceChapters: List<SourceChapter>,
        primaryContentByIndex: Map<Int, String>,
        badChapterIndexes: List<Int>,
        reason: String
    ) {
        val primaryKey = sourceBookKey(primary.book)
        val anchor = badChapterIndexes.minOrNull()
        if (anchor == null) {
            AiBridgeTrace.event(
                "source_catalog_v8_secondary_skipped",
                primary.detail.name,
                AiBridgeTrace.fields(
                    "reason" to "no_bad_target_marks",
                    "trigger" to reason,
                    "source" to sourceLabel(primary.book)
                )
            )
            return
        }
        val primaryChapterByIndex = primarySourceChapters.associateBy { chapter -> chapter.index }
        val sampleIndexes = ((anchor - V8_SECONDARY_SIMILARITY_RADIUS)..(anchor + V8_SECONDARY_SIMILARITY_RADIUS))
            .filter { index -> index >= 0 }
        val candidates = verifiedBooksSnapshot(waterfall)
            .filter { candidate -> sourceBookKey(candidate.book) != primaryKey }
            .filter { candidate -> !v8ValidationTracker.isActive(v8ValidationKey(candidate)) }
            .take(V8_SECONDARY_SOURCE_CANDIDATE_LIMIT)
        if (candidates.isEmpty()) {
            AiBridgeTrace.event(
                "source_catalog_v8_secondary_skipped",
                primary.detail.name,
                AiBridgeTrace.fields(
                    "reason" to "no_candidate",
                    "trigger" to reason,
                    "source" to sourceLabel(primary.book),
                    "anchor" to anchor
                )
            )
            return
        }

        for (candidate in candidates) {
            restoreCachedV8MarksForResolvedBook(candidate, reason, "secondary-candidate")
            val pairs = ArrayList<Pair<String, String>>()
            val matchedIndexes = ArrayList<Int>()
            for (index in sampleIndexes) {
                val primaryChapter = primaryChapterByIndex[index] ?: continue
                val primaryContent = primaryContentByIndex[index]?.takeIf { content -> content.isNotBlank() }
                    ?: loadV8ProbeContentWithTimeout(primaryChapter, primary, fingerprint = null).rawContent
                        .takeIf { content -> content.isNotBlank() }
                    ?: continue
                val candidateChapter = matchingChapter(candidate.catalog, chapterNormalizer.normalize(primaryChapter.name))
                    ?: continue
                val candidateContent = loadV8ProbeContentWithTimeout(candidateChapter, candidate, fingerprint = null).rawContent
                    .takeIf { content -> content.isNotBlank() }
                    ?: continue
                pairs.add(primaryContent to candidateContent)
                matchedIndexes.add(index)
            }
            val decision = V8SourceTextSimilarity.allClearlyDissimilar(pairs)
            AiBridgeTrace.event(
                "source_catalog_v8_secondary_similarity",
                primary.detail.name,
                AiBridgeTrace.fields(
                    "trigger" to reason,
                    "primary" to sourceLabel(primary.book),
                    "candidate" to sourceLabel(candidate.book),
                    "anchor" to anchor,
                    "samples" to decision.sampleCount,
                    "indexes" to matchedIndexes.joinToString(","),
                    "max" to "%.4f".format(decision.maxScore),
                    "avg" to "%.4f".format(decision.averageScore),
                    "dissimilar" to decision.clearlyDissimilar
                )
            )
            if (decision.clearlyDissimilar) {
                scheduleV8ValidationForResolvedBooks(
                    waterfall = waterfall,
                    resolvedBooks = listOf(candidate),
                    reason = "v8-cross-source-dissimilar",
                    allowSecondaryProbe = false
                )
                return
            }
        }

        AiBridgeTrace.event(
            "source_catalog_v8_secondary_skipped",
            primary.detail.name,
            AiBridgeTrace.fields(
                "reason" to "similar_or_insufficient_samples",
                "trigger" to reason,
                "source" to sourceLabel(primary.book),
                "anchor" to anchor,
                "candidates" to candidates.size
            )
        )
    }

    private suspend fun loadV8ProbeContentWithTimeout(
        chapter: SourceChapter,
        resolved: ResolvedSourceBook,
        fingerprint: BookContentFingerprint?
    ): V8ProbeContent {
        val startedAt = System.currentTimeMillis()
        val content = loadCleanContentWithTimeout(chapter, V8_VALIDATION_CONTENT_TIMEOUT_MS, fingerprint, "v8")
        AiBridgeTrace.event(
            "source_catalog_v8_probe_fetched",
            resolved.detail.name,
            AiBridgeTrace.fields(
                "source" to sourceLabel(resolved.book),
                "chapter" to chapter.name,
                "index" to chapter.index,
                "ok" to (content != null),
                "rawLength" to (content?.rawContent?.length ?: 0),
                "cleanedLength" to (content?.report?.cleanedLength ?: 0),
                "score" to (content?.report?.qualityScore ?: 0),
                "coherence" to (content?.report?.coherenceScore ?: 0),
                "warnings" to content?.report?.warnings?.joinToString(",").orEmpty(),
                "fingerprint" to (fingerprint != null),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        if (content == null) {
            Log.w(
                TAG,
                "operation=sourceV8ProbeFetchFailed provider=$providerName title=${resolved.detail.name} " +
                    "source=${sourceLabel(resolved.book)} chapter=${chapter.name} index=${chapter.index} " +
                    "durationMs=${System.currentTimeMillis() - startedAt}"
            )
            AiBridgeTrace.event(
                "source_catalog_v8_probe_fetch_failed",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "source" to sourceLabel(resolved.book),
                    "chapter" to chapter.name,
                    "index" to chapter.index,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
        }
        return V8ProbeContent(
            rawContent = content?.rawContent.orEmpty(),
            qualitySignal = content?.report?.let { report ->
                V8ContentQualitySignal(
                    qualityScore = report.qualityScore,
                    coherenceScore = report.coherenceScore,
                    cleanedLength = report.cleanedLength,
                    warnings = report.warnings
                )
            }
        )
    }

    private fun traceV8Diagnostic(resolved: ResolvedSourceBook, line: String) {
        Log.i(
            TAG,
            "operation=sourceV8Validation provider=$providerName title=${resolved.detail.name} " +
                "source=${sourceLabel(resolved.book)} $line"
        )
        AiBridgeTrace.event(
            "source_catalog_v8_diagnostic",
            resolved.detail.name,
            "source_${sourceLabel(resolved.book).debugToken()}_${line.debugToken()}"
        )
    }

    private fun runtimeHeapTraceFields(): Array<Pair<String, Any?>> {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MIB
        val totalMb = runtime.totalMemory() / BYTES_PER_MIB
        val maxMb = runtime.maxMemory() / BYTES_PER_MIB
        return arrayOf(
            "heapUsedMb" to usedMb,
            "heapTotalMb" to totalMb,
            "heapMaxMb" to maxMb
        )
    }

    private fun v8ValidationContentDigest(
        inputs: List<V8ChapterInput>,
        targetIndexes: Set<Int>
    ): String {
        return SourceEngineV8ValidationDigest.compute(inputs, targetIndexes)
    }

    private fun v8ValidationAnalysisPositions(
        plan: V8ValidationPlan,
        sourceChapters: List<SourceChapter>,
        targetIndexes: Set<Int>
    ): List<Int> {
        if (sourceChapters.isEmpty() || targetIndexes.isEmpty()) return emptyList()
        val selected = LinkedHashSet<Int>()
        selected.addAll(plan.contextPositions)
        sourceChapters.forEachIndexed { position, chapter ->
            if (chapter.index !in targetIndexes) return@forEachIndexed
            val previousStart = (position - V8_VALIDATION_REFERENCE_NEIGHBOR_CHAPTERS).coerceAtLeast(0)
            for (previous in previousStart until position) selected.add(previous)
            selected.add(position)
            for (future in 1..V8_VALIDATION_FUTURE_NEIGHBOR_CHAPTERS) {
                val futurePosition = position + future
                if (futurePosition in sourceChapters.indices) selected.add(futurePosition)
            }
        }
        return selected.filter { position -> position in sourceChapters.indices }.sorted()
    }

    private fun v8MarkCacheIdentity(resolved: ResolvedSourceBook): SourceEngineV8MarkCache.Identity {
        val chapters = resolved.catalog.chapters
        val tailTitleDigest = v8CatalogTailTitleDigest(chapters)
        return SourceEngineV8MarkCache.Identity(
            sourceBookKey = sourceBookKey(resolved.book),
            sourceUrl = resolved.book.source.sourceUrl,
            bookUrl = resolved.book.bookUrl,
            bookName = resolved.detail.name,
            author = resolved.detail.author,
            catalogSize = chapters.size,
            firstTitle = chapters.firstOrNull()?.displayTitle.orEmpty(),
            lastTitle = chapters.lastOrNull()?.displayTitle.orEmpty(),
            tailTitleDigest = tailTitleDigest
        )
    }

    private fun v8CatalogMarkIdentity(resolved: ResolvedSourceBook): SourceEngineCatalogMarkRegistry.CatalogIdentity {
        val chapters = displayAnchorCatalogChapters(resolved)
        return SourceEngineCatalogMarkRegistry.CatalogIdentity(
            catalogSize = chapters.size,
            firstTitle = chapters.firstOrNull()?.displayTitle.orEmpty(),
            lastTitle = chapters.lastOrNull()?.displayTitle.orEmpty(),
            tailTitleDigest = v8CatalogTailTitleDigest(chapters)
        )
    }

    private fun restoreCachedV8MarksForResolvedBook(
        resolved: ResolvedSourceBook,
        reason: String,
        phase: String
    ): Boolean {
        val cached = v8MarkCache.load(v8MarkCacheIdentity(resolved)) ?: return false
        return recordCachedV8Marks(cached, reason, phase)
    }

    private fun recordCachedV8Marks(
        cached: SourceEngineV8MarkCache.CachedMarks,
        reason: String,
        phase: String
    ): Boolean {
        if (cached.marks.isEmpty()) return false
        SourceEngineCatalogMarkRegistry.record(
            cached.identity.sourceBookKey,
            cached.sourceLabel,
            cached.identity.sourceUrl,
            cached.identity.bookName,
            cached.identity.author,
            cached.marks,
            catalogIdentity = SourceEngineCatalogMarkRegistry.CatalogIdentity(
                catalogSize = cached.identity.catalogSize,
                firstTitle = cached.identity.firstTitle,
                lastTitle = cached.identity.lastTitle,
                tailTitleDigest = cached.identity.tailTitleDigest
            )
        )
        AiBridgeTrace.event(
            "source_catalog_v8_cache_marks_restored",
            cached.identity.bookName,
            AiBridgeTrace.fields(
                "trigger" to reason,
                "phase" to phase,
                "source" to cached.sourceLabel,
                "marks" to cached.marks.size,
                "catalog" to cached.identity.catalogSize,
                "createdAtMs" to cached.createdAtMs
            )
        )
        return true
    }

    fun restoreCachedV8MarksForBook(
        bookId: String?,
        collBookBean: CollBookBean?,
        reason: String
    ): Int {
        if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) return 0
        val route = runCatching {
            SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
        }.getOrNull()
        val bookName = route?.name ?: collBookBean?.title
        val author = route?.author ?: collBookBean?.author
        val cachedMarks = v8MarkCache.summariesForBook(bookName, author)
            .mapNotNull { summary -> v8MarkCache.load(summary.identity) }
        var restored = 0
        cachedMarks.forEach { cached ->
            if (recordCachedV8Marks(cached, reason, "book-cache-replay")) {
                restored += 1
            }
        }
        AiBridgeTrace.event(
            "source_catalog_v8_book_cache_replayed",
            bookName.orEmpty(),
            AiBridgeTrace.fields(
                "trigger" to reason,
                "restored" to restored,
                "candidates" to cachedMarks.size,
                "author" to author.orEmpty()
            )
        )
        return restored
    }

    private fun v8ValidationKey(resolved: ResolvedSourceBook): String {
        val chapters = resolved.catalog.chapters
        return listOf(
            sourceBookKey(resolved.book),
            chapters.size.toString(),
            chapters.firstOrNull()?.displayTitle.orEmpty(),
            chapters.lastOrNull()?.displayTitle.orEmpty(),
            v8CatalogTailTitleDigest(chapters)
        ).joinToString("\n")
    }

    private fun v8CatalogTailTitleDigest(chapters: List<CanonicalChapter>): String {
        val tailTitles = chapters.takeLast(V8ValidationPlanner.TAIL_RISK_WINDOW_CHAPTERS)
            .joinToString("\n") { chapter -> chapter.displayTitle }
        return MD5Utils.strToMd5By32(tailTitles).orEmpty()
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

        trusted.addAll(
            readFromBookContentTier(
                waterfall,
                chapter,
                targetTitle,
                emptySet(),
                FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
            )
        )
        val initialDistinctTrusted = trusted.distinctBy { item -> sourceBookKey(item.resolved.book) }
        if (!shouldDeferSingleTrustedContentForHiddenMark(bookChapter, initialDistinctTrusted)) {
            immediateSingleTrustedContent(
                waterfall,
                sourceBook,
                bookChapter,
                chapter,
                initialDistinctTrusted
            )?.let { return it }
        } else {
            traceSingleTrustedContentDeferred(sourceBook, bookChapter, chapter, initialDistinctTrusted.single())
        }

        if (trusted.size < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) {
            val existingCandidateCount = synchronized(waterfall.candidates) { waterfall.candidates.size }
            if (existingCandidateCount > 0) {
                AiBridgeTrace.event(
                    "source_content_existing_candidates_started",
                    sourceBook.title ?: chapter.book.name,
                    AiBridgeTrace.fields(
                        "chapter" to bookChapter.title.orEmpty(),
                        "candidates" to existingCandidateCount,
                        "trusted" to trusted.size,
                        "required" to FIRST_DISPLAY_TRUSTED_SOURCE_COUNT
                    )
                )
                (
                    readFastDisplayFromContentCandidates(
                        waterfall,
                        chapter,
                        bookChapter,
                        targetTitle,
                        trusted.mapTo(mutableSetOf()) { item -> sourceBookKey(item.resolved.book) },
                        FallbackSearchPolicy.PERSONAL_ONLY
                    ) ?: readFastDisplayFromContentCandidates(
                        waterfall,
                        chapter,
                        bookChapter,
                        targetTitle,
                        trusted.mapTo(mutableSetOf()) { item -> sourceBookKey(item.resolved.book) },
                        FallbackSearchPolicy.PERSONAL_THEN_GLOBAL
                    )
                    )?.let { return it }
                trusted.addAll(
                    readFromContentCandidates(
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
            }
        }

        if (trusted.size < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT && !isRouteChapterRequest(bookChapter)) {
            readDirectChapterContent(waterfall, sourceBook, bookChapter, chapter)?.let { direct ->
                direct.displayable?.let { return it }
                direct.trusted?.let { trusted.add(it) }
            }
            val directDistinctTrusted = trusted.distinctBy { item -> sourceBookKey(item.resolved.book) }
            if (!shouldDeferSingleTrustedContentForHiddenMark(bookChapter, directDistinctTrusted)) {
                immediateSingleTrustedContent(
                    waterfall,
                    sourceBook,
                    bookChapter,
                    chapter,
                    directDistinctTrusted
                )?.let { return it }
            } else {
                traceSingleTrustedContentDeferred(sourceBook, bookChapter, chapter, directDistinctTrusted.single())
            }
        }

        if (trusted.size < FIRST_DISPLAY_TRUSTED_SOURCE_COUNT) {
            val existingCandidateCount = synchronized(waterfall.candidates) { waterfall.candidates.size }
            if (existingCandidateCount > 0) {
                AiBridgeTrace.event(
                    "source_content_global_refresh_skipped",
                    sourceBook.title ?: chapter.book.name,
                    AiBridgeTrace.fields(
                        "chapter" to bookChapter.title.orEmpty(),
                        "reason" to "existing_candidates_exhausted",
                        "candidates" to existingCandidateCount,
                        "trusted" to trusted.size
                    )
                )
            } else {
                refreshBookContentWaterfall(chapter.book, FallbackSearchPolicy.PERSONAL_ONLY)
                (
                    readFastDisplayFromContentCandidates(
                        waterfall,
                        chapter,
                        bookChapter,
                        targetTitle,
                        trusted.mapTo(mutableSetOf()) { item -> sourceBookKey(item.resolved.book) },
                        FallbackSearchPolicy.PERSONAL_ONLY
                    ) ?: readFastDisplayFromContentCandidates(
                        waterfall,
                        chapter,
                        bookChapter,
                        targetTitle,
                        trusted.mapTo(mutableSetOf()) { item -> sourceBookKey(item.resolved.book) },
                        FallbackSearchPolicy.PERSONAL_THEN_GLOBAL
                    )
                    )?.let { return it }
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
        recordDisplayedContentSource(
            waterfall,
            bookChapter,
            best.resolved,
            best.chapter,
            "reading-current-content"
        )
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
        markDisplayedChapterReadable(
            sourceBook,
            bookChapter,
            best.content,
            sourceLabel(best.resolved.book),
            distinctTrusted.size
        )
        return best.content
    }

    private suspend fun readFastRoutedChapterContent(
        chapter: SourceChapter,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter
    ): CleanContent? {
        val displayIndex = bookChapter.start
        val firstChapterFast = displayIndex <= 0L || chapter.index <= FIRST_CHAPTER_FAST_MAX_INDEX
        val routeChapterFast = isRouteChapterRequest(bookChapter)
        val currentReadFast = bookChapter.sourceEngineCurrentReadRequest
        if (!firstChapterFast && !routeChapterFast && !currentReadFast) return null
        val startedAt = System.currentTimeMillis()
        val eventPrefix = when {
            currentReadFast -> "source_content_current_chapter_fast"
            routeChapterFast -> "source_content_route_chapter_fast"
            else -> "source_content_first_chapter_fast"
        }
        AiBridgeTrace.event(
            "${eventPrefix}_started",
            sourceBook.title ?: chapter.book.name,
            "chapter_${bookChapter.title.orEmpty().debugToken()}_index_${chapter.index}" +
                "_displayIndex_${displayIndex}" +
                "_source_${sourceLabel(chapter.book).debugToken()}"
        )
        val content = loadCleanContentInCurrentRequestWithTimeout(
            chapter,
            FIRST_CHAPTER_FAST_CONTENT_TIMEOUT_MS,
            fingerprint = null
        ) ?: run {
            AiBridgeTrace.event(
                "${eventPrefix}_rejected",
                sourceBook.title ?: chapter.book.name,
                "chapter_${bookChapter.title.orEmpty().debugToken()}_reason_null" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        if (!hasDisplayableContent(content)) {
            AiBridgeTrace.event(
                "${eventPrefix}_rejected",
                sourceBook.title ?: chapter.book.name,
                "chapter_${bookChapter.title.orEmpty().debugToken()}_reason_empty_content" +
                    "_score_${content.report.qualityScore}_coherence_${content.report.coherenceScore}" +
                    "_cleaned_${content.report.cleanedLength}" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        val heading = if (routeChapterFast || currentReadFast && !firstChapterFast) {
            leadingChapterHeading(bookChapter.title, content.cleanedContent)
        } else {
            LeadingChapterHeading.Match(bookChapter.title.orEmpty())
        }
        if (heading is LeadingChapterHeading.Conflict) {
            AiBridgeTrace.event(
                "${eventPrefix}_rejected",
                sourceBook.title ?: chapter.book.name,
                AiBridgeTrace.fields(
                    "chapter" to bookChapter.title.orEmpty(),
                    "reason" to "heading_conflict",
                    "heading" to heading.line,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return null
        }
        val readable = isReadableContent(content)
        if (heading is LeadingChapterHeading.None && !readable) {
            AiBridgeTrace.event(
                "${eventPrefix}_rejected",
                sourceBook.title ?: chapter.book.name,
                "chapter_${bookChapter.title.orEmpty().debugToken()}_reason_missing_heading_low_quality" +
                    "_score_${content.report.qualityScore}_coherence_${content.report.coherenceScore}" +
                    "_cleaned_${content.report.cleanedLength}" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        if (!readable) {
            AiBridgeTrace.event(
                "${eventPrefix}_quality_diagnostic",
                sourceBook.title ?: chapter.book.name,
                "chapter_${bookChapter.title.orEmpty().debugToken()}_score_${content.report.qualityScore}" +
                    "_coherence_${content.report.coherenceScore}_cleaned_${content.report.cleanedLength}" +
                    "_warnings_${content.report.warnings.joinToString(",").debugToken()}" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
        }
        sourceQualityRouter.recordContentResolved(chapter, content)
        AiBridgeTrace.event(
            "${eventPrefix}_trusted",
            sourceBook.title ?: chapter.book.name,
            "chapter_${bookChapter.title.orEmpty().debugToken()}_score_${content.report.qualityScore}" +
                "_coherence_${content.report.coherenceScore}_cleaned_${content.report.cleanedLength}" +
                "_displayIndex_${displayIndex}" +
                "_durationMs_${System.currentTimeMillis() - startedAt}"
        )
        return content
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
        recordDisplayedContentSource(
            waterfall,
            bookChapter,
            singleTrusted.resolved,
            singleTrusted.chapter,
            "reading-single-trusted-content"
        )
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
        markDisplayedChapterReadable(
            sourceBook,
            bookChapter,
            singleTrusted.content,
            sourceLabel(singleTrusted.resolved.book),
            trusted.size
        )
        return singleTrusted.content
    }

    private fun shouldDeferSingleTrustedContentForHiddenMark(
        bookChapter: TxtChapter,
        trusted: List<TrustedChapterContent>
    ): Boolean {
        if (trusted.size != 1) return false
        if (!bookChapter.hasHiddenSourceIntegrityMark()) return false
        val content = trusted.single().content
        if (!hasDisplayableContent(content)) return false
        val heading = leadingChapterHeading(bookChapter.title, content.cleanedContent)
        return heading !is LeadingChapterHeading.Match
    }

    private fun traceSingleTrustedContentDeferred(
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        chapter: SourceChapter,
        trusted: TrustedChapterContent
    ) {
        AiBridgeTrace.event(
            "source_content_single_trusted_deferred",
            sourceBook.title ?: chapter.book.name,
            AiBridgeTrace.fields(
                "chapter" to bookChapter.title.orEmpty(),
                "source" to sourceLabel(trusted.resolved.book),
                "reason" to "hidden-mark-needs-second-source",
                "score" to trusted.content.report.qualityScore,
                "coherence" to trusted.content.report.coherenceScore,
                "cleaned" to trusted.content.report.cleanedLength
            )
        )
    }

    private fun recordDisplayedContentSource(
        waterfall: BookContentWaterfall,
        bookChapter: TxtChapter,
        resolved: ResolvedSourceBook,
        chapter: SourceChapter,
        reason: String
    ) {
        val changed = SourceEngineCatalogMarkRegistry.recordDisplayedContentSource(
            bookChapter,
            chapter,
            sourceLabel(resolved.book)
        )
        val analysisEnabled = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()
        val restored = if (analysisEnabled) {
            restoreCachedV8MarksForResolvedBook(resolved, reason, "displayed-content")
        } else {
            false
        }
        if (analysisEnabled && !restored) {
            scheduleV8ValidationForResolvedBooks(
                waterfall = waterfall,
                resolvedBooks = listOf(resolved),
                reason = reason,
                priority = V8ValidationPriority.BACKGROUND
            )
        }
        AiBridgeTrace.event(
            "source_content_display_source_recorded",
            resolved.detail.name,
            AiBridgeTrace.fields(
                "chapter" to bookChapter.title.orEmpty(),
                "source" to sourceLabel(resolved.book),
                "changed" to changed,
                "cachedMarksRestored" to restored,
                "trigger" to reason
            )
        )
    }

    private fun markDisplayedChapterReadable(
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        content: CleanContent,
        resolvedSourceLabel: String,
        trustedEvidenceCount: Int
    ) {
        if (!hasDisplayableContent(content)) return
        val heading = leadingChapterHeading(bookChapter.title, content.cleanedContent)
        val skipReason = when {
            !bookChapter.hasHiddenSourceIntegrityMark() -> "not-hidden-mark"
            heading is LeadingChapterHeading.Match -> null
            heading is LeadingChapterHeading.Conflict -> "heading-conflict-without-strong-evidence"
            else -> "missing-heading"
        }
        if (skipReason != null) {
            AiBridgeTrace.event(
                "source_content_chapter_mark_verify_skipped",
                sourceBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapter" to bookChapter.title.orEmpty(),
                    "reason" to skipReason,
                    "state" to bookChapter.sourceIntegrityState.orEmpty(),
                    "source" to resolvedSourceLabel,
                    "score" to content.report.qualityScore,
                    "coherence" to content.report.coherenceScore,
                    "cleaned" to content.report.cleanedLength,
                    "trustedEvidence" to trustedEvidenceCount
                )
            )
            return
        }
        val changed = SourceEngineCatalogMarkRegistry.recordReadableContent(bookChapter)
        AiBridgeTrace.event(
            "source_content_chapter_mark_verified",
            sourceBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapter" to bookChapter.title.orEmpty(),
                "changed" to changed,
                "state" to bookChapter.sourceIntegrityState.orEmpty(),
                "source" to resolvedSourceLabel,
                "score" to content.report.qualityScore,
                "coherence" to content.report.coherenceScore,
                "cleaned" to content.report.cleanedLength,
                "trustedEvidence" to trustedEvidenceCount
            )
        )
    }

    private fun leadingChapterHeading(expectedTitle: String?, contentText: String): LeadingChapterHeading {
        val expected = chapterNormalizer.normalize(expectedTitle.orEmpty())
        if (expected.displayTitle.isBlank()) return LeadingChapterHeading.None
        val expectedSuffix = chapterTitleSuffixKey(expected.displayTitle)
        val leadingLines = contentText
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .take(READABLE_MARK_HEADING_SCAN_LINES)
        for (line in leadingLines) {
            val candidate = line.take(READABLE_MARK_HEADING_SCAN_CHARS).trim()
            if (!CHAPTER_TITLE_ORDINAL_PATTERN.containsMatchIn(candidate)) continue
            val actual = chapterNormalizer.normalize(candidate)
            val sameKey = actual.key == expected.key
            val sameOrdinal = expected.ordinal != null && actual.ordinal == expected.ordinal
            if (sameKey || sameOrdinal && chapterTitleSuffixCompatible(expectedSuffix, actual.displayTitle)) {
                return LeadingChapterHeading.Match(candidate)
            }
            if (actual.ordinal != null || expected.ordinal != null) {
                return LeadingChapterHeading.Conflict(candidate)
            }
        }
        return LeadingChapterHeading.None
    }

    private suspend fun readDirectChapterContent(
        waterfall: BookContentWaterfall,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        chapter: SourceChapter
    ): DirectChapterContent? {
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
        val trustedBook = isTrustedResolvedBook(resolved)
        if (!trustedBook && !shouldReadDirectCurrentChapter(bookChapter)) {
            markResolvedBookFailed(waterfall, resolved.book)
            AiBridgeTrace.event(
                "source_content_direct_rejected",
                chapter.book.name,
                "chapter_${chapter.name.debugToken()}_reason_untrusted_book" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        val fingerprint = if (trustedBook) contentFingerprintForResolved(resolved) else null
        if (trustedBook && fingerprint == null && !canReadWithoutBookFingerprint(resolved)) {
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
        if (!hasDisplayableContent(content)) {
            AiBridgeTrace.event(
                "source_content_direct_rejected",
                chapter.book.name,
                "chapter_${chapter.name.debugToken()}_reason_empty_content_score_${content.report.qualityScore}" +
                    "_coherence_${content.report.coherenceScore}_warnings_${content.report.warnings.joinToString(",").debugToken()}" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
            return null
        }
        if (!trustedBook) {
            val heading = leadingChapterHeading(bookChapter.title, content.cleanedContent)
            if (heading !is LeadingChapterHeading.Match) {
                markResolvedBookFailed(waterfall, resolved.book)
                AiBridgeTrace.event(
                    "source_content_direct_rejected",
                    chapter.book.name,
                    AiBridgeTrace.fields(
                        "chapter" to bookChapter.title.orEmpty(),
                        "reason" to "untrusted_heading",
                        "heading" to when (heading) {
                            is LeadingChapterHeading.Conflict -> heading.line
                            is LeadingChapterHeading.Match -> heading.line
                            LeadingChapterHeading.None -> ""
                        },
                        "durationMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                return null
            }
            if (!isReadableContent(content)) {
                AiBridgeTrace.event(
                    "source_content_direct_quality_diagnostic",
                    chapter.book.name,
                    "chapter_${chapter.name.debugToken()}_score_${content.report.qualityScore}" +
                        "_coherence_${content.report.coherenceScore}_cleaned_${content.report.cleanedLength}" +
                        "_warnings_${content.report.warnings.joinToString(",").debugToken()}" +
                        "_durationMs_${System.currentTimeMillis() - startedAt}"
                )
            }
            AiBridgeTrace.event(
                "source_content_direct_current_display",
                sourceBook.title ?: chapter.book.name,
                AiBridgeTrace.fields(
                    "chapter" to bookChapter.title.orEmpty(),
                    "source" to sourceLabel(resolved.book),
                    "score" to content.report.qualityScore,
                    "coherence" to content.report.coherenceScore,
                    "cleaned" to content.report.cleanedLength,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return DirectChapterContent(displayable = content)
        }
        if (!isReadableContent(content)) {
            AiBridgeTrace.event(
                "source_content_direct_quality_diagnostic",
                chapter.book.name,
                "chapter_${chapter.name.debugToken()}_score_${content.report.qualityScore}" +
                    "_coherence_${content.report.coherenceScore}_cleaned_${content.report.cleanedLength}" +
                    "_warnings_${content.report.warnings.joinToString(",").debugToken()}" +
                    "_durationMs_${System.currentTimeMillis() - startedAt}"
            )
        }
        promoteResolvedBookInWaterfall(waterfall, resolved)
        recordDisplayedContentSource(
            waterfall,
            bookChapter,
            resolved,
            chapter,
            "reading-direct-trusted-content"
        )
        AiBridgeTrace.event(
            "source_content_direct_trusted",
            chapter.book.name,
            "chapter_${chapter.name.debugToken()}_score_${content.report.qualityScore}" +
                "_coherence_${content.report.coherenceScore}_cleaned_${content.report.cleanedLength}" +
                "_durationMs_${System.currentTimeMillis() - startedAt}"
        )
        return DirectChapterContent(trusted = TrustedChapterContent(0, resolved, chapter, content))
    }

    private fun shouldReadDirectCurrentChapter(bookChapter: TxtChapter): Boolean {
        return bookChapter.sourceEngineCurrentReadRequest
    }

    private fun isRouteChapterRequest(bookChapter: TxtChapter): Boolean {
        return SourceEngineBookRoute.isChapterId(bookChapter.link)
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
        val probeScope = CoroutineScope(activeSourceRequestDispatcher() + SupervisorJob() + activeSourceRequestContext())
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
                        val fingerprint = contentFingerprintForResolved(resolved)
                        if (fingerprint == null && !canReadWithoutBookFingerprint(resolved)) return@withPermit null
                        val content = runDetachedWithTimeout(CONTENT_FALLBACK_CONTENT_TIMEOUT_MS) {
                            when (val value = engine.getCleanContent(fallbackChapter, bookFingerprint = fingerprint)) {
                                is EngineResult.Success -> value.value
                                is EngineResult.Failure -> null
                            }
                        } ?: return@withPermit null
                        if (!hasDisplayableContent(content)) return@withPermit null
                        if (!isReadableContent(content)) {
                            traceContentFallbackQualityDiagnostic(
                                "source_content_fallback_quality_diagnostic",
                                chapter,
                                resolved.book,
                                content
                            )
                        }
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

    private suspend fun ensureBookContentWaterfall(
        sourceBook: SourceBook,
        policy: FallbackSearchPolicy = FallbackSearchPolicy.PERSONAL_THEN_GLOBAL
    ): BookContentWaterfall {
        val key = bookWaterfallKey(sourceBook)
        bookContentWaterfallCache[key]?.let { return it }
        val candidates = fallbackCandidatesFor(sourceBook, policy)
        return rememberBookContentWaterfall(sourceBook, candidates)
    }

    private suspend fun refreshBookContentWaterfall(
        sourceBook: SourceBook,
        policy: FallbackSearchPolicy = FallbackSearchPolicy.PERSONAL_THEN_GLOBAL
    ): BookContentWaterfall {
        val candidates = fallbackCandidatesFor(sourceBook, policy)
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
            absorbIdentityProfileWaterfalls(sourceBook, existing)
            return existing
        }
        val waterfall = BookContentWaterfall(sourceBook, ArrayList(candidates))
        bookContentWaterfallCache[key] = waterfall
        absorbIdentityProfileWaterfalls(sourceBook, waterfall)
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

    private fun absorbIdentityProfileWaterfalls(
        sourceBook: SourceBook,
        waterfall: BookContentWaterfall
    ) {
        val profile = bookIdentityProfileFor(sourceBook) ?: return
        val aliasKeys = synchronized(bookIdentityProfileLock) {
            profile.rawWaterfallKeys.toList()
        }
        aliasKeys.forEach { aliasKey ->
            if (aliasKey == profile.waterfallKey) return@forEach
            val aliasWaterfall = bookContentWaterfallCache.remove(aliasKey) ?: return@forEach
            if (aliasWaterfall === waterfall) return@forEach
            mergeBookContentWaterfall(waterfall, aliasWaterfall)
            AiBridgeTrace.event(
                "source_content_waterfall_profile_absorbed",
                sourceBook.name,
                AiBridgeTrace.fields(
                    "aliasKey" to aliasKey.debugToken(),
                    "canonicalKey" to profile.waterfallKey.debugToken(),
                    "candidates" to synchronized(waterfall.candidates) { waterfall.candidates.size },
                    "trusted" to verifiedBookCount(waterfall)
                )
            )
        }
    }

    private fun mergeBookContentWaterfall(
        target: BookContentWaterfall,
        incoming: BookContentWaterfall
    ) {
        mergeWaterfallCandidates(
            target,
            synchronized(incoming.candidates) { incoming.candidates.toList() }
        )
        synchronized(incoming.resolvedBooks) {
            incoming.resolvedBooks.values.toList()
        }.forEach { resolved -> cacheResolvedBookInWaterfall(target, resolved) }
        synchronized(incoming.verifiedBooks) {
            incoming.verifiedBooks.toList()
        }.forEach { resolved -> promoteResolvedBookInWaterfall(target, resolved) }
        val failed = synchronized(incoming.failedBooks) { incoming.failedBooks.toSet() }
        synchronized(target.failedBooks) {
            target.failedBooks.addAll(failed)
        }
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
        timeoutMs: Long,
        policy: FallbackSearchPolicy = FallbackSearchPolicy.PERSONAL_THEN_GLOBAL,
        onTrustedResolved: (ResolvedSourceBook) -> Unit = {}
    ): SourceContentTierPrepareResult {
        val startedAt = System.currentTimeMillis()
        if (isTrustedTierReady(waterfall, targetSize)) {
            AiBridgeTrace.event(
                "source_content_tier_fill_skipped",
                waterfall.sourceBook.name,
                AiBridgeTrace.fields(
                    "reason" to "already_ready",
                    "target" to targetSize,
                    "trusted" to verifiedBookCount(waterfall),
                    "hasCover" to hasTrustedCover(waterfall)
                )
            )
            return SourceContentTierPrepareResult.READY
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var round = 0
        var exhausted = false
        while (!isTrustedTierReady(waterfall, targetSize)) {
            round += 1
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0) break
            val needed = targetSize - verifiedBookCount(waterfall)
            val candidates = contentFallbackCandidatesFor(waterfall.sourceBook, waterfall, policy)
                .filter { candidate -> !hasVerifiedBook(waterfall, candidate.book) }
                .take(BOOK_CONTENT_TIER_FILL_BATCH_SIZE)
            AiBridgeTrace.event(
                "source_content_tier_fill_round",
                waterfall.sourceBook.name,
                AiBridgeTrace.fields(
                    "round" to round,
                    "policy" to policy.name.lowercase(),
                    "target" to targetSize,
                    "trusted" to verifiedBookCount(waterfall),
                    "needed" to needed,
                    "batch" to candidates.size,
                    "remainingMs" to remainingMs
                )
            )
            if (candidates.isEmpty()) {
                exhausted = true
                break
            }
            val probeScope = CoroutineScope(activeSourceRequestDispatcher() + SupervisorJob() + activeSourceRequestContext())
            val semaphore = Semaphore(MAX_CONTENT_FALLBACK_CONCURRENT_PROBES)
            try {
                val probes = candidates.map { candidate ->
                    probeScope.async {
                        semaphore.withPermit {
                            val resolved = resolveContentFallbackBook(waterfall, candidate) ?: return@withPermit null
                            if (!promoteTrustedResolvedBookInWaterfall(waterfall, resolved)) return@withPermit null
                            onTrustedResolved(resolved)
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
        val ready = isTrustedTierReady(waterfall, targetSize)
        val result = when {
            ready -> SourceContentTierPrepareResult.READY
            exhausted -> SourceContentTierPrepareResult.EXHAUSTED
            else -> SourceContentTierPrepareResult.RETRY_LATER
        }
        AiBridgeTrace.state(
            "source_content_tier_fill_finished",
            waterfall.sourceBook.name,
            AiBridgeTrace.fields(
                "ready" to ready,
                "status" to result.name.lowercase(),
                "policy" to policy.name.lowercase(),
                "target" to targetSize,
                "trusted" to verifiedBookCount(waterfall),
                "hasCover" to hasTrustedCover(waterfall),
                "rounds" to round,
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        return result
    }

    private fun contentFallbackCandidatesFor(
        sourceBook: SourceBook,
        waterfall: BookContentWaterfall,
        policy: FallbackSearchPolicy = FallbackSearchPolicy.PERSONAL_THEN_GLOBAL
    ): List<RankedSearchBook> {
        val currentBookKey = sourceBookKey(sourceBook)
        val personalSourceKeys = if (policy == FallbackSearchPolicy.PERSONAL_ONLY) {
            personalSourceKeysForBook(sourceBook)
        } else {
            emptySet()
        }
        if (policy == FallbackSearchPolicy.PERSONAL_ONLY && personalSourceKeys.isEmpty()) {
            AiBridgeTrace.event(
                "source_content_waterfall_candidates",
                sourceBook.name,
                AiBridgeTrace.fields(
                    "policy" to policy.name.lowercase(),
                    "raw" to synchronized(waterfall.candidates) { waterfall.candidates.size },
                    "usable" to 0,
                    "first" to ""
                )
            )
            return emptyList()
        }
        val candidates = synchronized(waterfall.candidates) { waterfall.candidates.toList() }
            .filter { candidate -> sourceBookKey(candidate.book) != currentBookKey }
            .filter { candidate -> !hasFailedBook(waterfall, candidate.book) }
            .filter { candidate -> personalSourceKeys.isEmpty() || sourceKey(candidate.book.source) in personalSourceKeys }
            .sortedWith(contentWaterfallComparator)
        AiBridgeTrace.event(
            "source_content_waterfall_candidates",
            sourceBook.name,
            AiBridgeTrace.fields(
                "policy" to policy.name.lowercase(),
                "raw" to synchronized(waterfall.candidates) { waterfall.candidates.size },
                "usable" to candidates.size,
                "first" to candidates.take(8).joinToString("|") { candidate -> sourceLabel(candidate.book).debugToken() }
            )
        )
        return candidates
    }

    private fun personalSourceKeysForBook(sourceBook: SourceBook): Set<String> {
        return sourceQualityRouter.personalWaterfallSourcesForBook(sourceProvider(), sourceBook.name)
            .mapTo(mutableSetOf()) { source -> sourceKey(source) }
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
        AiBridgeTrace.event(
            "source_content_tier_lookup_started",
            waterfall.sourceBook.name,
            AiBridgeTrace.fields(
                "chapter" to currentChapter.name,
                "resolvedBooks" to resolvedBooks.size,
                "limit" to limit,
                "excluded" to excludedBookKeys.size
            )
        )
        for (resolved in resolvedBooks) {
            val fallbackChapter = matchingChapter(resolved.catalog, targetTitle)
            if (fallbackChapter == null) {
                traceContentFallbackRejected("source_content_tier_rejected", currentChapter, resolved.book, "missing_chapter")
                continue
            }
            val fingerprint = contentFingerprintForResolved(resolved)
            if (fingerprint == null && !canReadWithoutBookFingerprint(resolved)) {
                traceContentFallbackRejected("source_content_tier_rejected", currentChapter, resolved.book, "fingerprint")
                continue
            }
            val content = loadCleanContentWithTimeout(
                fallbackChapter,
                CONTENT_FALLBACK_CONTENT_TIMEOUT_MS,
                fingerprint
            )
            if (content == null) {
                traceContentFallbackRejected("source_content_tier_rejected", currentChapter, resolved.book, "content_null")
                continue
            }
            if (!hasDisplayableContent(content)) {
                traceContentFallbackRejected(
                    "source_content_tier_rejected",
                    currentChapter,
                    resolved.book,
                    "empty_content",
                    content
                )
                continue
            }
            if (!isReadableContent(content)) {
                traceContentFallbackQualityDiagnostic(
                    "source_content_tier_quality_diagnostic",
                    currentChapter,
                    resolved.book,
                    content
                )
            }
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
        AiBridgeTrace.state(
            "source_content_tier_lookup_finished",
            waterfall.sourceBook.name,
            AiBridgeTrace.fields(
                "chapter" to currentChapter.name,
                "trusted" to trusted.size,
                "limit" to limit,
                "resolvedBooks" to resolvedBooks.size
            )
        )
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
            .take(MAX_CONTENT_FALLBACK_CANDIDATES)
        AiBridgeTrace.event(
            "source_content_candidate_lookup_started",
            currentChapter.book.name,
            AiBridgeTrace.fields(
                "chapter" to currentChapter.name,
                "candidates" to candidates.size,
                "limit" to limit,
                "excluded" to excludedBookKeys.size
            )
        )
        candidates.chunked(CONTENT_FALLBACK_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            if (trusted.size >= limit) return@forEachIndexed
            AiBridgeTrace.event(
                "source_content_candidate_batch_started",
                currentChapter.book.name,
                AiBridgeTrace.fields(
                    "chapter" to currentChapter.name,
                    "batch" to batchIndex,
                    "size" to batch.size,
                    "trusted" to trusted.size
                )
            )
            val probeScope = CoroutineScope(activeSourceRequestDispatcher() + SupervisorJob() + activeSourceRequestContext())
            val semaphore = Semaphore(MAX_CONTENT_FALLBACK_CONCURRENT_PROBES)
            try {
                val probes = batch.mapIndexed { order, candidate ->
                    probeScope.async {
                        semaphore.withPermit {
                            val resolved = resolveContentFallbackBook(waterfall, candidate) ?: run {
                                traceContentFallbackRejected(
                                    "source_content_candidate_rejected",
                                    currentChapter,
                                    candidate.book,
                                    "detail"
                                )
                                return@withPermit null
                            }
                            if (!promoteTrustedResolvedBookInWaterfall(waterfall, resolved)) {
                                traceContentFallbackRejected(
                                    "source_content_candidate_rejected",
                                    currentChapter,
                                    resolved.book,
                                    "untrusted_book"
                                )
                                return@withPermit null
                            }
                            val fallbackChapter = matchingChapter(resolved.catalog, targetTitle)
                                ?: run {
                                    traceContentFallbackRejected(
                                        "source_content_candidate_rejected",
                                        currentChapter,
                                        resolved.book,
                                        "missing_chapter"
                                    )
                                    return@withPermit null
                                }
                            val fingerprint = contentFingerprintForResolved(resolved)
                            if (fingerprint == null && !canReadWithoutBookFingerprint(resolved)) {
                                traceContentFallbackRejected(
                                    "source_content_candidate_rejected",
                                    currentChapter,
                                    resolved.book,
                                    "fingerprint"
                                )
                                return@withPermit null
                            }
                            val content = loadCleanContentWithTimeout(
                                fallbackChapter,
                                CONTENT_FALLBACK_CONTENT_TIMEOUT_MS,
                                fingerprint
                            ) ?: run {
                                traceContentFallbackRejected(
                                    "source_content_candidate_rejected",
                                    currentChapter,
                                    resolved.book,
                                    "content_null"
                                )
                                return@withPermit null
                            }
                            if (!hasDisplayableContent(content)) {
                                traceContentFallbackRejected(
                                    "source_content_candidate_rejected",
                                    currentChapter,
                                    resolved.book,
                                    "empty_content",
                                    content
                                )
                                return@withPermit null
                            }
                            if (!isReadableContent(content)) {
                                traceContentFallbackQualityDiagnostic(
                                    "source_content_candidate_quality_diagnostic",
                                    currentChapter,
                                    resolved.book,
                                    content
                                )
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
                AiBridgeTrace.state(
                    "source_content_candidate_batch_finished",
                    currentChapter.book.name,
                    AiBridgeTrace.fields(
                        "chapter" to currentChapter.name,
                        "batch" to batchIndex,
                        "trusted" to trusted.size,
                        "limit" to limit
                    )
                )
            } finally {
                probeScope.coroutineContext.cancelChildren()
            }
        }
        AiBridgeTrace.state(
            "source_content_candidate_lookup_finished",
            currentChapter.book.name,
            AiBridgeTrace.fields(
                "chapter" to currentChapter.name,
                "trusted" to trusted.size,
                "candidates" to candidates.size,
                "limit" to limit
            )
        )
        return trusted
    }

    private suspend fun readFastDisplayFromContentCandidates(
        waterfall: BookContentWaterfall,
        currentChapter: SourceChapter,
        bookChapter: TxtChapter,
        targetTitle: NormalizedChapterTitle,
        excludedBookKeys: Set<String>,
        policy: FallbackSearchPolicy
    ): CleanContent? {
        if (!bookChapter.sourceEngineCurrentReadRequest && !isRouteChapterRequest(bookChapter)) return null
        val candidates = contentFallbackCandidatesFor(currentChapter.book, waterfall, policy)
            .filter { candidate ->
                val key = sourceBookKey(candidate.book)
                key !in excludedBookKeys && !hasVerifiedBook(waterfall, candidate.book)
            }
            .take(MAX_FAST_DISPLAY_CANDIDATES)
        if (candidates.isEmpty()) return null
        AiBridgeTrace.event(
            "source_content_candidate_fast_display_started",
            currentChapter.book.name,
            AiBridgeTrace.fields(
                "chapter" to currentChapter.name,
                "policy" to policy.name.lowercase(),
                "candidates" to candidates.size,
                "excluded" to excludedBookKeys.size
            )
        )
        val probeScope = CoroutineScope(activeSourceRequestDispatcher() + SupervisorJob() + activeSourceRequestContext())
        val semaphore = Semaphore(MAX_FAST_DISPLAY_CONCURRENT_PROBES)
        return try {
            val probes = candidates.mapIndexed { order, candidate ->
                probeScope.async {
                    semaphore.withPermit {
                        val resolved = resolveContentFallbackBookInCurrentRequest(waterfall, candidate) ?: run {
                            traceContentFallbackRejected(
                                "source_content_candidate_fast_display_rejected",
                                currentChapter,
                                candidate.book,
                                "detail"
                            )
                            return@withPermit null
                        }
                        val fallbackChapter = matchingChapter(resolved.catalog, targetTitle)
                            ?: run {
                                traceContentFallbackRejected(
                                    "source_content_candidate_fast_display_rejected",
                                    currentChapter,
                                    resolved.book,
                                    "missing_chapter"
                                )
                                return@withPermit null
                            }
                        val content = loadCleanContentInCurrentRequestWithTimeout(
                            fallbackChapter,
                            FAST_DISPLAY_CONTENT_TIMEOUT_MS,
                            fingerprint = null,
                            purpose = "fast-display"
                        ) ?: run {
                            traceContentFallbackRejected(
                                "source_content_candidate_fast_display_rejected",
                                currentChapter,
                                resolved.book,
                                "content_null"
                            )
                            return@withPermit null
                        }
                        if (!hasDisplayableContent(content)) {
                            traceContentFallbackRejected(
                                "source_content_candidate_fast_display_rejected",
                                currentChapter,
                                resolved.book,
                                "empty_content",
                                content
                            )
                            return@withPermit null
                        }
                        val expectedTitle = bookChapter.title.takeUnless { title -> title.isNullOrBlank() }
                            ?: currentChapter.name
                        val heading = leadingChapterHeading(expectedTitle, content.cleanedContent)
                        if (heading is LeadingChapterHeading.Conflict) {
                            traceContentFallbackRejected(
                                "source_content_candidate_fast_display_rejected",
                                currentChapter,
                                resolved.book,
                                "heading_conflict",
                                content
                            )
                            return@withPermit null
                        }
                        val readable = isReadableContent(content)
                        if (heading is LeadingChapterHeading.None && !readable) {
                            traceContentFallbackRejected(
                                "source_content_candidate_fast_display_rejected",
                                currentChapter,
                                resolved.book,
                                "missing_heading_low_quality",
                                content
                            )
                            return@withPermit null
                        }
                        if (!readable) {
                            traceContentFallbackQualityDiagnostic(
                                "source_content_candidate_fast_display_quality_diagnostic",
                                currentChapter,
                                resolved.book,
                                content
                            )
                        }
                        ContentFallback(order, resolved, fallbackChapter, content)
                    }
                }
            }
            val best = awaitFinishedValuesWithinLimit(
                probes,
                FAST_DISPLAY_TOTAL_TIMEOUT_MS,
                FAST_DISPLAY_SUCCESS_LIMIT
            ).sortedWith(contentFallbackComparator).firstOrNull()
            if (best == null) {
                AiBridgeTrace.event(
                    "source_content_candidate_fast_display_missing",
                    currentChapter.book.name,
                    AiBridgeTrace.fields(
                        "chapter" to currentChapter.name,
                        "policy" to policy.name.lowercase(),
                        "candidates" to candidates.size
                    )
                )
                null
            } else {
                recordDisplayedContentSource(
                    waterfall,
                    bookChapter,
                    best.resolved,
                    best.chapter,
                    "fast-display-content"
                )
                AiBridgeTrace.event(
                    "source_content_candidate_fast_display",
                    currentChapter.book.name,
                    AiBridgeTrace.fields(
                        "chapter" to currentChapter.name,
                        "policy" to policy.name.lowercase(),
                        "source" to sourceLabel(best.resolved.book),
                        "score" to best.content.report.qualityScore,
                        "coherence" to best.content.report.coherenceScore,
                        "cleaned" to best.content.report.cleanedLength
                    )
                )
                best.content
            }
        } finally {
            probeScope.coroutineContext.cancelChildren()
        }
    }

    private fun traceContentFallbackRejected(
        eventName: String,
        currentChapter: SourceChapter,
        candidateBook: SourceBook,
        reason: String,
        content: CleanContent? = null
    ) {
        AiBridgeTrace.event(
            eventName,
            currentChapter.book.name,
            AiBridgeTrace.fields(
                "chapter" to currentChapter.name,
                "source" to sourceLabel(candidateBook),
                "reason" to reason,
                "score" to content?.report?.qualityScore,
                "coherence" to content?.report?.coherenceScore,
                "cleaned" to content?.report?.cleanedLength,
                "warnings" to content?.report?.warnings?.joinToString(",").orEmpty()
            )
        )
    }

    private fun traceContentFallbackQualityDiagnostic(
        eventName: String,
        currentChapter: SourceChapter,
        candidateBook: SourceBook,
        content: CleanContent
    ) {
        AiBridgeTrace.event(
            eventName,
            currentChapter.book.name,
            AiBridgeTrace.fields(
                "chapter" to currentChapter.name,
                "source" to sourceLabel(candidateBook),
                "score" to content.report.qualityScore,
                "coherence" to content.report.coherenceScore,
                "cleaned" to content.report.cleanedLength,
                "warnings" to content.report.warnings.joinToString(",")
            )
        )
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

    private suspend fun resolveContentFallbackBookInCurrentRequest(
        waterfall: BookContentWaterfall,
        candidate: RankedSearchBook
    ): ResolvedSourceBook? {
        val key = sourceBookKey(candidate.book)
        synchronized(waterfall.resolvedBooks) {
            if (waterfall.resolvedBooks.containsKey(key)) {
                return waterfall.resolvedBooks[key]
            }
        }
        val resolved = loadReadableBookInCurrentRequestWithTimeout(
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
        probeCatalogTail(resolved)
        val chapterCount = resolved.catalog.chapters.size
        if (chapterCount < MIN_SEARCH_READABLE_CATALOG_CHAPTERS) return false
        if (canReadWithoutBookFingerprint(resolved)) return true
        return chapterCount >= minReadableChapterCountFor(resolved.catalog.chapters.size) &&
            bookFingerprintForResolved(resolved) != null
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
        return isTrustedTierReady(waterfall, targetSize) && hasTrustedCover(waterfall)
    }

    private fun isTrustedTierReady(waterfall: BookContentWaterfall, targetSize: Int): Boolean {
        return verifiedBookCount(waterfall) >= targetSize
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
        val file = persistedTierFile(shelfBookId) ?: run {
            AiBridgeTrace.event(
                "source_content_tier_persisted_load",
                waterfall.sourceBook.name,
                AiBridgeTrace.fields("loaded" to 0, "reason" to "missing_shelf_id")
            )
            return
        }
        if (!file.exists()) {
            AiBridgeTrace.event(
                "source_content_tier_persisted_load",
                waterfall.sourceBook.name,
                AiBridgeTrace.fields("loaded" to 0, "reason" to "missing_file", "shelf" to shelfBookId.orEmpty())
            )
            return
        }
        val beforeCandidates = synchronized(waterfall.candidates) { waterfall.candidates.size }
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
        AiBridgeTrace.event(
            "source_content_tier_persisted_load",
            waterfall.sourceBook.name,
            AiBridgeTrace.fields(
                "loaded" to candidates.size,
                "shelf" to shelfBookId.orEmpty(),
                "candidatesBefore" to beforeCandidates,
                "candidatesAfter" to synchronized(waterfall.candidates) { waterfall.candidates.size },
                "sources" to candidates.take(5).joinToString("|") { candidate -> sourceLabel(candidate.book) }
            )
        )
    }

    private fun persistVerifiedTier(
        waterfall: BookContentWaterfall,
        shelfBookId: String?
    ) {
        val file = persistedTierFile(shelfBookId) ?: return
        val resolvedBooks = verifiedBooksSnapshot(waterfall).take(BOOK_CONTENT_TIER_TARGET_SIZE)
        val routeIds = resolvedBooks
            .take(BOOK_CONTENT_TIER_TARGET_SIZE)
            .map { resolved -> SourceEngineBookRoute.bookId(resolved.book) }
            .distinct()
        if (routeIds.isEmpty()) return
        file.parentFile?.mkdirs()
        val existingRouteIds = if (file.exists()) {
            file.readLines()
                .map { line -> line.trim() }
                .filter { routeId -> SourceEngineBookRoute.isBookId(routeId) }
        } else {
            emptyList()
        }
        val mergedRouteIds = (routeIds + existingRouteIds)
            .distinct()
            .take(BOOK_CONTENT_TIER_TARGET_SIZE)
        file.writeText(mergedRouteIds.joinToString("\n"))
        AiBridgeTrace.event(
            "source_content_tier_persisted_save",
            waterfall.sourceBook.name,
            AiBridgeTrace.fields(
                "saved" to mergedRouteIds.size,
                "current" to routeIds.size,
                "existing" to existingRouteIds.size,
                "shelf" to shelfBookId.orEmpty(),
                "trusted" to verifiedBookCount(waterfall),
                "sources" to resolvedBooks.take(5).joinToString("|") { resolved -> sourceLabel(resolved.book) }
            )
        )
    }

    private fun persistedTierFile(shelfBookId: String?): File? {
        if (shelfBookId.isNullOrBlank()) return null
        return File(bookCacheFolderPath(shelfBookId), SOURCE_ENGINE_TIER_FILE_NAME)
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
        bookIdentityProfileFor(book)?.let { profile -> return profile.waterfallKey }
        return rawBookWaterfallKey(book)
    }

    private fun rawBookWaterfallKey(book: SourceBook): String {
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

    private suspend fun contentFingerprintForResolved(resolved: ResolvedSourceBook): BookContentFingerprint? {
        return if (canReadWithoutBookFingerprint(resolved)) {
            null
        } else {
            bookFingerprintForResolved(resolved)
        }
    }

    private fun canReadWithoutBookFingerprint(resolved: ResolvedSourceBook): Boolean {
        return resolved.catalog.chapters.size >= MIN_SEARCH_READABLE_CATALOG_CHAPTERS &&
            trustedFingerprintUpperExclusive(resolved.catalog.chapters.size) < MIN_USABLE_FINGERPRINT_TRUSTED_CHAPTERS
    }

    private fun minReadableChapterCountFor(catalogChapterCount: Int): Int {
        return if (catalogChapterCount < MIN_READABLE_CATALOG_CHAPTERS) {
            MIN_SEARCH_READABLE_CATALOG_CHAPTERS
        } else {
            MIN_READABLE_CATALOG_CHAPTERS
        }
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
            val startedAt = System.currentTimeMillis()
            val chapters = trustedFingerprintChapters(resolved.catalog.chapters)
            AiBridgeTrace.event(
                "source_book_fingerprint_build_started",
                resolved.detail.name,
                AiBridgeTrace.fields(
                    "source" to sourceLabel(resolved.book),
                    "catalog" to resolved.catalog.chapters.size,
                    "trustedWindow" to chapters.size,
                    "trustedUpperExclusive" to trustedUpperExclusive
                )
            )
            val contents = withTimeoutOrNull(FINGERPRINT_BUILD_TOTAL_TIMEOUT_MS) {
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
            }
            contents?.let { profile.addTrustedContents(it) }
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
                fingerprintTraceValue(profile.snapshot) +
                    "_inputChapters_${chapters.size}_contents_${contents?.size ?: 0}" +
                    "_timeout_${contents == null}_durationMs_${System.currentTimeMillis() - startedAt}"
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

    private suspend fun probeCatalogTail(
        resolved: ResolvedSourceBook
    ): CatalogTailProbeResult {
        val chapters = resolved.catalog.chapters
        if (chapters.isEmpty()) {
            return CatalogTailProbeResult(keepUntil = 0, checkedCount = 0, method = "empty")
        }
        val cacheKey = tailProbeCacheKey(resolved)
        catalogTailProbeCache[cacheKey]?.let { result -> return result }
        val lock = synchronized(catalogTailProbeLocks) {
            catalogTailProbeLocks.getOrPut(cacheKey) { Mutex() }
        }
        return lock.withLock {
            catalogTailProbeCache[cacheKey]?.let { result ->
                return@withLock result
            }

            val fingerprint = contentFingerprintForResolved(resolved)
            val probe = withTimeoutOrNull(CATALOG_TAIL_TOTAL_TIMEOUT_MS) {
                locateCatalogTailBoundary(chapters, fingerprint)
            } ?: CatalogTailProbeResult(
                keepUntil = chapters.size,
                checkedCount = 0,
                method = "timeout"
            )
            val keepUntil = probe.keepUntil.coerceIn(0, chapters.size)
            val result = probe.copy(keepUntil = keepUntil)

            if (keepUntil < chapters.size) {
                traceTailProbeSamples(chapters, keepUntil, fingerprint)
                Log.w(
                    TAG,
                    "operation=catalogTailProbeDetected provider=$providerName title=${resolved.detail.name} " +
                        "rawChapters=${chapters.size} suggestedKeepUntil=$keepUntil " +
                        "suspect=${chapters.size - keepUntil} " +
                        "checked=${probe.checkedCount} method=${probe.method} " +
                        "lastTrusted=${chapters.getOrNull(keepUntil - 1)?.displayTitle} " +
                        "firstSuspect=${chapters.getOrNull(keepUntil)?.displayTitle}"
                )
                AiBridgeTrace.event(
                    "source_catalog_tail_probe_detected",
                    resolved.detail.name,
                    "raw_${chapters.size}_suggestedKeepUntil_${keepUntil}_suspect_${chapters.size - keepUntil}" +
                        "_method_${probe.method.debugToken()}_lastTrusted_${chapters.getOrNull(keepUntil - 1)?.displayTitle.orEmpty().debugToken()}" +
                        "_firstSuspect_${chapters.getOrNull(keepUntil)?.displayTitle.orEmpty().debugToken()}" +
                        "_action_probe_only"
                )
            } else {
                if (probe.checkedCount > 0) {
                    AiBridgeTrace.event(
                        "source_catalog_tail_probe_clean",
                        resolved.detail.name,
                        "raw_${chapters.size}_checked_${probe.checkedCount}_method_${probe.method.debugToken()}" +
                            "_action_probe_only"
                    )
                }
            }
            catalogTailProbeCache[cacheKey] = result
            result
        }
    }

    private fun dropLeadingCatalogFrontMatter(
        bookName: String,
        chapters: List<CanonicalChapter>
    ): List<CanonicalChapter> {
        if (chapters.size < 2) return chapters
        if (!isCatalogFrontMatterTitle(chapters.first().displayTitle)) return chapters
        val firstStoryIndex = chapters.indexOfFirst { chapter ->
            isStoryCatalogTitle(chapter.displayTitle)
        }
        if (firstStoryIndex <= 0) return chapters
        val frontMatter = chapters.take(firstStoryIndex)
        AiBridgeTrace.event(
            "source_catalog_display_head_trimmed",
            bookName,
            "raw_${chapters.size}_kept_${chapters.size - firstStoryIndex}_removed_${firstStoryIndex}" +
                "_firstRemoved_${frontMatter.firstOrNull()?.displayTitle.orEmpty().debugToken()}" +
                "_firstKept_${chapters.getOrNull(firstStoryIndex)?.displayTitle.orEmpty().debugToken()}"
        )
        return chapters.drop(firstStoryIndex)
    }

    private fun isStoryCatalogTitle(title: String): Boolean {
        val trimmed = title.trim()
        return CHAPTER_TITLE_ORDINAL_PATTERN.containsMatchIn(trimmed) ||
            trimmed.startsWith("序章") ||
            trimmed.startsWith("楔子")
    }

    private fun isCatalogFrontMatterTitle(title: String): Boolean {
        val normalized = normalizeHint(title)
        return normalized == "资料" ||
            normalized.matches(CATALOG_FRONT_MATTER_DATA_PATTERN) ||
            normalized in CATALOG_FRONT_MATTER_TITLES
    }

    private suspend fun traceTailProbeSamples(
        chapters: List<CanonicalChapter>,
        keepUntil: Int,
        fingerprint: BookContentFingerprint?
    ) {
        val start = keepUntil.coerceAtMost(chapters.size)
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
                if (!readable) {
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

    private fun tailProbeCacheKey(resolved: ResolvedSourceBook): String {
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
        fingerprint: BookContentFingerprint? = null,
        purpose: String = "content"
    ): CleanContent? {
        val startedAt = System.currentTimeMillis()
        var failureReason: String? = null
        val content = runDetachedWithTimeout(timeoutMs) {
            when (val value = engine.getCleanContent(chapter, bookFingerprint = fingerprint)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> {
                    failureReason = value.failure.toString()
                    null
                }
            }
        }
        if (content == null) {
            recordContentLoadFailure(
                chapter = chapter,
                purpose = purpose,
                timeoutMs = timeoutMs,
                fingerprint = fingerprint,
                reason = failureReason ?: "timeout_or_empty",
                durationMs = System.currentTimeMillis() - startedAt
            )
        }
        return content
    }

    private suspend fun loadCleanContentInCurrentRequestWithTimeout(
        chapter: SourceChapter,
        timeoutMs: Long,
        fingerprint: BookContentFingerprint? = null,
        purpose: String = "current-content"
    ): CleanContent? {
        val startedAt = System.currentTimeMillis()
        var failureReason: String? = null
        val content = withTimeoutOrNull(timeoutMs) {
            when (val value = engine.getCleanContent(chapter, bookFingerprint = fingerprint)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> {
                    failureReason = value.failure.toString()
                    null
                }
            }
        }
        if (content == null) {
            recordContentLoadFailure(
                chapter = chapter,
                purpose = purpose,
                timeoutMs = timeoutMs,
                fingerprint = fingerprint,
                reason = failureReason ?: "timeout_or_empty",
                durationMs = System.currentTimeMillis() - startedAt
            )
        }
        return content
    }

    private fun recordContentLoadFailure(
        chapter: SourceChapter,
        purpose: String,
        timeoutMs: Long,
        fingerprint: BookContentFingerprint?,
        reason: String,
        durationMs: Long
    ) {
        val key = listOf(sourceBookKey(chapter.book), chapter.index.toString(), purpose).joinToString("\n")
        val count = synchronized(contentLoadFailureCounts) {
            contentLoadFailureCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        }
        Log.w(
            TAG,
            "operation=sourceContentLoadFailed provider=$providerName purpose=$purpose " +
                "book=${chapter.book.name} source=${sourceLabel(chapter.book)} chapter=${chapter.name} " +
                "index=${chapter.index} count=$count timeoutMs=$timeoutMs durationMs=$durationMs reason=$reason"
        )
        AiBridgeTrace.event(
            "source_content_load_failed",
            chapter.book.name,
            AiBridgeTrace.fields(
                "purpose" to purpose,
                "source" to sourceLabel(chapter.book),
                "chapter" to chapter.name,
                "index" to chapter.index,
                "count" to count,
                "timeoutMs" to timeoutMs,
                "durationMs" to durationMs,
                "fingerprint" to (fingerprint != null),
                "reason" to reason
            )
        )
        if (count >= CONTENT_LOAD_REPEATED_FAILURE_THRESHOLD) {
            AiBridgeTrace.state(
                "source_content_load_repeated_failed",
                chapter.book.name,
                AiBridgeTrace.fields(
                    "purpose" to purpose,
                    "source" to sourceLabel(chapter.book),
                    "chapter" to chapter.name,
                    "index" to chapter.index,
                    "count" to count,
                    "timeoutMs" to timeoutMs
                )
            )
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
        val pageCatalog: Boolean,
        val coverCandidates: List<String> = emptyList()
    ) {
        fun debugLabel(): String {
                return "${book.name}/${book.author}/${book.source.sourceName}" +
                "/score=$score/sources=${ranked.sourceCount}/authorConsensus=$authorConsensus" +
                "/chapters=$chapterCount" +
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

    private data class IndexedSearchSource(
        val index: Int,
        val source: BookSource
    )

    private data class SearchSourceWave(
        val tier: Int,
        val sources: List<IndexedSearchSource>
    )

    private data class SearchSourceWaveRuntime(
        val requestScope: SourceRequestScope,
        val coroutineScope: CoroutineScope,
        val jobs: List<Deferred<Unit>>
    )

    private data class SearchValidationCandidateBatch(
        val label: String,
        val candidates: List<RankedSearchBook>
    )

    private enum class SearchRankMode(val progressive: Boolean) {
        EXACT_PROGRESS(true),
        PROGRESS(true),
        COMPLETED(false)
    }

    private data class SearchTailContentProbeResult(
        val readableContent: Int,
        val pending: Boolean
    )

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
        val routeId: String,
        val coverCandidates: List<String> = emptyList()
    )

    private data class ReadableResolvedSourceBook(
        val order: Int,
        val resolved: ResolvedSourceBook,
        val readableChapterCount: Int,
        val lastReadableOrdinal: Int,
        val tailOrdinalGapCount: Int,
        val tailContinuityScore: Int
    )

    private data class CatalogAnchorSignal(
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

    private data class DirectChapterContent(
        val trusted: TrustedChapterContent? = null,
        val displayable: CleanContent? = null
    )

    private data class BookIdentityProfile(
        val displayTitle: String,
        val displayAuthor: String,
        val waterfallKey: String,
        val titleKeys: MutableSet<String> = linkedSetOf(),
        val authorNames: MutableSet<String> = linkedSetOf(),
        val sourceBookKeys: MutableSet<String> = linkedSetOf(),
        val rawWaterfallKeys: MutableSet<String> = linkedSetOf()
    )

    private sealed class LeadingChapterHeading {
        data class Match(val line: String) : LeadingChapterHeading()
        data class Conflict(val line: String) : LeadingChapterHeading()
        object None : LeadingChapterHeading()
    }

    private data class V8ProbeContent(
        val rawContent: String = "",
        val qualitySignal: V8ContentQualitySignal? = null
    )

    private data class V8ValidationEpoch(
        val result: V8SourceRunResult,
        val inputs: List<V8ChapterInput>,
        val targetIndexes: Set<Int>,
        val contentDigest: String,
        val replayedFromCache: Boolean = false
    )

    private data class V8MaintenanceBook(
        val book: CollBookBean,
        val cache: V8MaintenanceCacheSnapshot
    ) {
        val cacheState: V8MaintenanceCacheState = cache.state
        val cacheCatalogSize: Int? = cache.cacheCatalogSize
    }

    private data class V8MaintenanceCacheSnapshot(
        val state: V8MaintenanceCacheState,
        val cacheCatalogSize: Int? = null,
        val cacheLastTitle: String? = null,
        val cacheCreatedAtMs: Long = 0L,
        val cacheIdentity: SourceEngineV8MarkCache.Identity? = null
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

    private enum class V8ValidationPriority {
        BACKGROUND
    }

    private enum class V8MaintenanceCacheState(val sortOrder: Int) {
        STALE(0),
        MISSING(1),
        CURRENT(2)
    }

    private enum class FallbackSearchPolicy {
        PERSONAL_ONLY,
        PERSONAL_THEN_GLOBAL
    }

    companion object {
        private const val TAG = "BookContentProvider"
        private const val MAX_SEARCH_SOURCES = 400
        private const val MAX_SEARCH_RESULTS = 30
        private const val MAX_RESULTS_PER_SOURCE = 24
        private const val MAX_KEYWORD_SUGGESTIONS = 8
        private const val MIN_COMPLETION_QUERY_CHARS = 2
        private const val MAX_CONCURRENT_SEARCHES = 64
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val SEARCH_PROGRESSIVE_TOTAL_TIMEOUT_MS = 180_000L
        private const val SEARCH_PROGRESS_POLL_INTERVAL_MS = 500L
        private const val SEARCH_PROGRESS_EXACT_RANK_TIMEOUT_MS = 8_000L
        private const val SEARCH_PROGRESS_RANK_TIMEOUT_MS = 6_000L
        private const val SEARCH_VISIBLE_COMPLETED_RANK_TIMEOUT_MS = 20_000L
        private const val SEARCH_PROGRESS_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS = 5_000L
        private const val SEARCH_PROGRESS_EXACT_GROUPS_VALIDATION_TOTAL_TIMEOUT_MS = 15_000L
        private const val SEARCH_TIER_ONE_SETTLE_TIMEOUT_MS = 10_000L
        private const val SEARCH_TIER_TWO_SETTLE_TIMEOUT_MS = 60_000L
        private const val SEARCH_TIER_THREE_SETTLE_TIMEOUT_MS = 60_000L
        private const val SEARCH_TIER_ONE_READY_RANK_TIMEOUT_MS = 12_000L
        private const val SEARCH_TIER_TWO_READY_RANK_TIMEOUT_MS = 20_000L
        private const val SEARCH_TIER_THREE_READY_RANK_TIMEOUT_MS = 30_000L
        private const val SEARCH_PROGRESS_RESULT_TARGET = 2
        private const val FIRST_PROGRESS_MIN_CANDIDATES = 2
        private const val MIN_SEARCH_LONG_CATALOG_CHAPTERS = 50
        private const val SHORT_CATALOG_CONSENSUS_SOURCE_COUNT = 4
        private const val SEARCH_SHORT_CATALOG_COMPARE_CHAPTERS = 8
        private const val MIN_SEARCH_SHORT_CATALOG_MATCH_CHAPTERS = 2
        private const val MIN_SEARCH_SHORT_CATALOG_MATCH_PERCENT = 60
        private const val MIN_SEARCH_READABLE_CATALOG_CHAPTERS = 1
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
        private const val MAX_FAST_DISPLAY_CONCURRENT_PROBES = 3
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val FIRST_DISPLAY_TRUSTED_SOURCE_COUNT = 2
        private const val SMALL_CATALOG_CONTIGUITY_SCAN_CHAPTERS = 30
        private const val FIRST_DISPLAY_TIER_FILL_TIMEOUT_MS = 30_000L
        private const val BOOK_CONTENT_TIER_TARGET_SIZE = 32
        private const val BOOK_CONTENT_TIER_FILL_TIMEOUT_MS = 45_000L
        private const val BOOK_CONTENT_TIER_FILL_BATCH_SIZE = 32
        private const val CONTENT_FALLBACK_BATCH_SIZE = 32
        private const val MAX_CONTENT_FALLBACK_CANDIDATES = 32
        private const val MAX_FAST_DISPLAY_CANDIDATES = 8
        private const val FAST_DISPLAY_SUCCESS_LIMIT = 1
        private const val CONTENT_FALLBACK_SUCCESS_LIMIT = 1
        private const val READABLE_MARK_HEADING_SCAN_LINES = 4
        private const val READABLE_MARK_HEADING_SCAN_CHARS = 120
        private const val CONTENT_FALLBACK_DETAIL_TIMEOUT_MS = 15_000L
        private const val CONTENT_FALLBACK_CONTENT_TIMEOUT_MS = 15_000L
        private const val CATALOG_ANCHOR_CONTENT_TIMEOUT_MS = 8_000L
        private const val CONTENT_FALLBACK_TOTAL_TIMEOUT_MS = 45_000L
        private const val FAST_DISPLAY_CONTENT_TIMEOUT_MS = 8_000L
        private const val FAST_DISPLAY_TOTAL_TIMEOUT_MS = 12_000L
        private const val CONTENT_REQUEST_TOTAL_TIMEOUT_MS = 45_000L
        private const val V8_VALIDATION_MAX_CONCURRENT_EPOCHS = 1
        private const val V8_VALIDATION_CONTENT_TIMEOUT_MS = 15_000L
        private const val V8_VALIDATION_TOTAL_TIMEOUT_MS = 1_800_000L
        private const val V8_MAINTENANCE_INITIAL_DELAY_MS = 30_000L
        private const val V8_MAINTENANCE_INTERVAL_MS = 15 * 60_000L
        private const val V8_MAINTENANCE_BOOK_TIMEOUT_MS = 30 * 60_000L
        private const val V8_MAINTENANCE_BETWEEN_BOOK_DELAY_MS = 2_000L
        private const val V8_MAINTENANCE_CACHED_BOOK_CONCURRENCY = 4
        private const val LOW_PRIORITY_NETWORK_POLL_INTERVAL_MS = 500L
        private const val V8_VALIDATION_REFERENCE_NEIGHBOR_CHAPTERS = 3
        private const val V8_VALIDATION_FUTURE_NEIGHBOR_CHAPTERS = 1
        private const val V8_SECONDARY_SOURCE_CANDIDATE_LIMIT = 2
        private const val V8_SECONDARY_SIMILARITY_RADIUS = 2
        private const val CONTENT_LOAD_REPEATED_FAILURE_THRESHOLD = 2
        private const val FIRST_CHAPTER_FAST_MAX_INDEX = 0
        private const val FIRST_CHAPTER_FAST_CONTENT_TIMEOUT_MS = 8_000L
        private const val MAX_COVER_FALLBACK_CANDIDATES = 24
        private const val MAX_COVER_FALLBACK_CONCURRENT_PROBES = 16
        private const val COVER_FALLBACK_DETAIL_TIMEOUT_MS = 10_000L
        private const val COVER_FALLBACK_TOTAL_TIMEOUT_MS = 20_000L
        private const val MAX_SEARCH_COVER_FILL_RESULTS = 4
        private const val MAX_SEARCH_COVER_FILL_CONCURRENT = 4
        private const val SEARCH_COVER_FILL_ITEM_TIMEOUT_MS = 10_000L
        private const val SEARCH_COVER_FILL_TOTAL_TIMEOUT_MS = 15_000L
        private const val SEARCH_COVER_TRACE_RESULT_LIMIT = 5
        private const val MAX_BACKGROUND_COVER_REFRESH_RESULTS = 8
        private const val BACKGROUND_COVER_REFRESH_ITEM_TIMEOUT_MS = 10_000L
        private const val MAX_CATALOG_TAIL_BACKTRACK_CHAPTERS = 2048
        private const val CATALOG_TAIL_CONTENT_TIMEOUT_MS = 15_000L
        private const val CATALOG_TAIL_TOTAL_TIMEOUT_MS = 180_000L
        private const val MAX_CATALOG_TAIL_REJECTION_TRACE_CHAPTERS = 2
        private const val CATALOG_TAIL_LENGTH_AVERAGE_LOOKBACK_CHAPTERS = 4
        private const val MIN_CATALOG_TAIL_LENGTH_AVERAGE_SAMPLES = 2
        private const val CATALOG_TAIL_SHORT_LENGTH_DIVISOR = 4
        private const val MIN_USABLE_FINGERPRINT_TRUSTED_CHAPTERS = 5
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
        private const val MAX_PROGRESS_VALIDATION_TITLE_GROUPS = 8
        private const val MAX_EXACT_PROGRESS_VALIDATION_TITLE_GROUPS = 1
        private const val MAX_VALIDATION_PER_TITLE = 5
        private const val MAX_VALIDATION_PRIORITY_PER_TITLE = 12
        private const val MAX_VALIDATION_COVER_FALLBACK_PER_TITLE = 3
        private const val MAX_SEARCH_VALIDATION_CANDIDATES_PER_TITLE = 16
        private const val MAX_PROGRESS_SEARCH_VALIDATION_CANDIDATES_PER_TITLE = 6
        private const val MAX_EXPANDED_PROGRESS_SEARCH_VALIDATION_CANDIDATES_PER_TITLE = 8
        private const val SEARCH_PROGRESS_EXPANDED_VALIDATION_MIN_SOURCES = 12
        private const val SEARCH_CATALOG_PREFIX_COMPARE_CHAPTERS = 12
        private const val MIN_SEARCH_CATALOG_PREFIX_MATCH_CHAPTERS = 6
        private const val MIN_SEARCH_CATALOG_PREFIX_SIMILAR_MATCH_CHAPTERS = 3
        private const val MIN_SEARCH_CATALOG_PREFIX_SIMILAR_MATCH_PERCENT = 50
        private const val MIN_SEARCH_CATALOG_PREFIX_DISTINCTIVE_TITLES = 2
        private const val MIN_SEARCH_CATALOG_PREFIX_DISTINCTIVE_SUFFIX_CHARS = 2
        private const val MIN_CONTAINED_SEARCH_KEY_CHARS = 2
        private const val MIN_CATALOG_ALIAS_TITLE_CHARS = 4
        private const val MIN_CATALOG_ALIAS_COMMON_PREFIX_CHARS = 3
        private const val MIN_CATALOG_ALIAS_COMMON_PREFIX_PERCENT = 75
        private const val SEARCH_CATALOG_ALIAS_VALIDATION_SCORE = 8_900
        private const val MAX_ORDER_PENALTY_FOR_ALIAS_SCORE = 200
        private const val SEARCH_PROGRESS_VALIDATION_MIN_COMPLETED_BEFORE_EARLY_RETURN = 4
        private const val SEARCH_PROGRESS_VALIDATION_SOFT_GRACE_MS = 1_500L
        private const val MAX_CONCURRENT_VALIDATIONS = 24
        private const val MAX_SEARCH_DISPLAY_PREVIEW_VALIDATION_CANDIDATES = 6
        private const val SEARCH_DISPLAY_PREVIEW_TOTAL_TIMEOUT_MS = 4_000L
        private const val SEARCH_DISPLAY_PREVIEW_DETAIL_TIMEOUT_MS = 3_000L
        private const val SEARCH_PROGRESS_VALIDATION_BATCH_TIMEOUT_MS = 5_000L
        private const val SEARCH_PROGRESS_EXACT_VALIDATION_BATCH_TIMEOUT_MS = 15_000L
        private const val SEARCH_VALIDATION_BATCH_TIMEOUT_MS = 20_000L
        private const val SEARCH_VALIDATION_TIMEOUT_MS = 25_000L
        private const val SEARCH_TITLE_GROUP_VALIDATION_TIMEOUT_MS = 45_000L
        private const val SEARCH_DETAIL_DISPLAY_PREVIEW_VALIDATION = "detail-display-preview"
        private const val SEARCH_TAIL_CONTENT_TIMEOUT_MS = 2_000L
        private const val SEARCH_INLINE_TAIL_PROBE_MAX_PREWORK_MS = 3_000L
        private const val SEARCH_READABLE_TAIL_BONUS = 180
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
        private val CATALOG_FRONT_MATTER_DATA_PATTERN =
            Regex("""^资料[0-9０-９一二三四五六七八九十]*$""")
        private val CATALOG_FRONT_MATTER_TITLES = setOf(
            "相关资料",
            "作品相关",
            "作者的话",
            "写在前面",
            "内容简介",
            "简介"
        )
        private val SEARCH_NOISE_PARENTHESIS =
            Regex("""[（(][^（）()]{0,30}(推荐票|月票|求票|求推荐|第一更|第二更|第三更)[^（）()]{0,30}[）)]""")
        private val GENERIC_SEARCH_CATALOG_PREFIX_SUFFIXES = setOf(
            "正文",
            "章节",
            "内容",
            "新书",
            "序章",
            "楔子"
        )
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
                .thenByDescending { it.score }
                .thenBy { if (it.coverQuality.usable) 0 else 1 }
                .thenBy { it.ranked.sourceIndex }
                .thenBy { it.ranked.resultIndex }
                .thenBy { it.book.name.length }
                .thenBy { it.book.name }
        private const val AUTHOR_CONSENSUS_WEIGHT = 10
        private val readingCandidateComparator = compareByDescending<ValidatedSearchCandidate> { it.authorConsensus }
            .thenByDescending { it.chapterCount }
            .thenByDescending { it.score }
            .thenBy { it.ranked.sourceIndex }
            .thenBy { it.ranked.resultIndex }
        private val metadataCandidateComparator = compareByDescending<ValidatedSearchCandidate> {
            it.score
        }.thenBy {
            it.ranked.sourceIndex
        }.thenBy {
            it.ranked.resultIndex
        }
        private val readingCandidateSignalComparator = compareByDescending<ReadingCandidateSignal> {
            it.candidate.authorConsensus
        }.thenByDescending {
            it.lastReadableOrdinal
        }.thenByDescending {
            it.tailContinuityScore
        }.thenByDescending {
            it.readableChapterCount
        }.thenBy {
            it.tailOrdinalGapCount
        }.thenByDescending {
            it.candidate.chapterCount
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

internal object SourceEngineV8ValidationDigest {
    fun compute(
        inputs: List<V8ChapterInput>,
        targetIndexes: Set<Int>
    ): String {
        val digest = MessageDigest.getInstance("MD5")
        updateDigest(digest, "targets")
        targetIndexes.sorted().forEach { index -> updateDigest(digest, index.toString()) }
        updateDigest(digest, "inputs")
        inputs.sortedBy { input -> input.index }.forEach { input ->
            updateInputDigest(digest, input)
        }
        return digest.toHex()
    }

    fun computeInputFingerprints(inputs: List<V8ChapterInput>): Map<Int, SourceEngineV8MarkCache.InputFingerprint> {
        return inputs.associate { input ->
            val normalized = normalizedFingerprintText(input.content)
            input.index to SourceEngineV8MarkCache.InputFingerprint(
                inputDigest = computeInputDigest(input),
                normalizedLength = normalized.length,
                tokenHashes = fingerprintTokenHashes(normalized)
            )
        }
    }

    private fun computeInputDigest(input: V8ChapterInput): String {
        val digest = MessageDigest.getInstance("MD5")
        updateInputDigest(digest, input)
        return digest.toHex()
    }

    private fun updateInputDigest(digest: MessageDigest, input: V8ChapterInput) {
        updateDigest(digest, input.index.toString())
        updateDigest(digest, input.title)
        updateDigest(digest, input.content.length.toString())
        updateDigest(digest, input.content)
        updateDigest(digest, "quality")
        val signal = input.contentQualitySignal
        if (signal == null) {
            updateDigest(digest, "null")
        } else {
            updateDigest(digest, signal.qualityScore.toString())
            updateDigest(digest, signal.coherenceScore.toString())
            updateDigest(digest, signal.cleanedLength.toString())
            signal.warnings.sorted().forEach { warning -> updateDigest(digest, warning) }
        }
    }

    private fun updateDigest(digest: MessageDigest, value: String) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    private fun MessageDigest.toHex(): String {
        return digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizedFingerprintText(value: String): String {
        val compact = buildString(value.length) {
            value.lowercase(Locale.ROOT).forEach { char ->
                when {
                    char in '\u4e00'..'\u9fff' -> append(char)
                    char in 'a'..'z' -> append(char)
                    char in '0'..'9' -> append(char)
                }
            }
        }
        if (compact.length <= MAX_FINGERPRINT_CHARS) return compact
        val segment = MAX_FINGERPRINT_CHARS / 3
        val middleStart = (compact.length / 2 - segment / 2).coerceAtLeast(0)
        return compact.take(segment) +
            compact.substring(middleStart, (middleStart + segment).coerceAtMost(compact.length)) +
            compact.takeLast(segment)
    }

    private fun fingerprintTokenHashes(normalized: String): List<String> {
        if (normalized.length < MIN_FINGERPRINT_CHARS) return emptyList()
        val tokens = LinkedHashSet<String>()
        var index = 0
        while (index + FINGERPRINT_SHINGLE_SIZE <= normalized.length) {
            tokens += MD5Utils.strToMd5By32(normalized.substring(index, index + FINGERPRINT_SHINGLE_SIZE))
                .orEmpty()
                .take(12)
            index += FINGERPRINT_SHINGLE_STRIDE
        }
        return tokens.take(MAX_FINGERPRINT_TOKENS)
    }

    private const val MIN_FINGERPRINT_CHARS = 260
    private const val MAX_FINGERPRINT_CHARS = 6_000
    private const val FINGERPRINT_SHINGLE_SIZE = 10
    private const val FINGERPRINT_SHINGLE_STRIDE = 5
    private const val MAX_FINGERPRINT_TOKENS = 512
}

internal object SourceEngineV8ReplayCachePolicy {
    fun findReplay(
        identity: SourceEngineV8MarkCache.Identity,
        targetIndexes: Set<Int>,
        inputFingerprintsByChapterIndex: Map<Int, SourceEngineV8MarkCache.InputFingerprint>,
        candidates: List<SourceEngineV8MarkCache.CachedMarks>
    ): ReplayMatch? {
        if (targetIndexes.isEmpty() || inputFingerprintsByChapterIndex.isEmpty()) return null
        candidates.forEach { candidate ->
            replayMatch(identity, targetIndexes, inputFingerprintsByChapterIndex, candidate)?.let { return it }
        }
        return null
    }

    private fun replayMatch(
        identity: SourceEngineV8MarkCache.Identity,
        targetIndexes: Set<Int>,
        inputFingerprintsByChapterIndex: Map<Int, SourceEngineV8MarkCache.InputFingerprint>,
        candidate: SourceEngineV8MarkCache.CachedMarks
    ): ReplayMatch? {
        if (!catalogReplayCompatible(identity, candidate.identity)) return null
        if (!candidate.targetChapterIndexes.toSet().containsAll(targetIndexes)) return null
        if (candidate.inputFingerprintsByChapterIndex.isEmpty()) return null

        val sameSource = identity.sourceBookKey == candidate.identity.sourceBookKey
        val scores = ArrayList<Double>()
        inputFingerprintsByChapterIndex.forEach { (index, current) ->
            val cached = candidate.inputFingerprintsByChapterIndex[index] ?: return null
            if (sameSource) {
                if (current.inputDigest != cached.inputDigest) return null
                scores += 1.0
            } else {
                val score = similarityScore(current, cached) ?: return null
                if (score < MIN_CROSS_SOURCE_INPUT_SIMILARITY) return null
                scores += score
            }
        }

        if (sameSource) {
            return ReplayMatch(candidate, "same_source_exact", scores.size, 1.0, 1.0)
        }
        if (scores.size < MIN_CROSS_SOURCE_SIMILAR_INPUTS) return null
        val min = scores.minOrNull() ?: return null
        val average = scores.average()
        if (average < MIN_CROSS_SOURCE_AVERAGE_SIMILARITY) return null
        return ReplayMatch(candidate, "cross_source_similar", scores.size, min, average)
    }

    private fun similarityScore(
        left: SourceEngineV8MarkCache.InputFingerprint,
        right: SourceEngineV8MarkCache.InputFingerprint
    ): Double? {
        if (left.inputDigest == right.inputDigest) return 1.0
        if (left.normalizedLength < MIN_COMPARABLE_CHARS || right.normalizedLength < MIN_COMPARABLE_CHARS) return null
        val leftTokens = left.tokenHashes.toSet()
        val rightTokens = right.tokenHashes.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return null
        val intersection = leftTokens.count { token -> token in rightTokens }
        val union = leftTokens.size + rightTokens.size - intersection
        return if (union <= 0) null else intersection.toDouble() / union
    }

    private fun catalogReplayCompatible(
        left: SourceEngineV8MarkCache.Identity,
        right: SourceEngineV8MarkCache.Identity
    ): Boolean {
        return normalizedIdentityPart(left.bookName) == normalizedIdentityPart(right.bookName) &&
            (
                normalizedIdentityPart(left.author).isBlank() ||
                    normalizedIdentityPart(right.author).isBlank() ||
                    normalizedIdentityPart(left.author) == normalizedIdentityPart(right.author)
                ) &&
            left.catalogSize == right.catalogSize &&
            normalizedIdentityPart(left.firstTitle) == normalizedIdentityPart(right.firstTitle) &&
            normalizedIdentityPart(left.lastTitle) == normalizedIdentityPart(right.lastTitle) &&
            left.tailTitleDigest == right.tailTitleDigest
    }

    private fun normalizedIdentityPart(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("""\s+"""), "")
            .orEmpty()
    }

    data class ReplayMatch(
        val cached: SourceEngineV8MarkCache.CachedMarks,
        val reason: String,
        val comparedInputs: Int,
        val minSimilarity: Double,
        val averageSimilarity: Double
    )

    private const val MIN_COMPARABLE_CHARS = 260
    private const val MIN_CROSS_SOURCE_SIMILAR_INPUTS = 8
    private const val MIN_CROSS_SOURCE_INPUT_SIMILARITY = 0.82
    private const val MIN_CROSS_SOURCE_AVERAGE_SIMILARITY = 0.90

}
