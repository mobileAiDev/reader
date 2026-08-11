package com.ldp.reader.ui.activity

import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.source.SourceEngineCachedCatalogMigration
import com.ldp.reader.source.SourceEngineChapterContentCacheKey
import com.ldp.reader.source.SourceEngineV8ScanPhase
import com.ldp.reader.source.SourceEngineV8ScanProgress
import com.ldp.reader.source.SourceEngineWrongChapterScanPolicy
import com.ldp.reader.source.SourceContentTierMode
import com.ldp.reader.source.SourceContentTierPrepareResult
import com.ldp.reader.source.SourceRequestPriority
import com.ldp.reader.utils.LogUtils
import com.ldp.reader.widget.page.TxtChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Created by ldp on 17-5-16.
 */
class ReadViewModel : ViewModel() {
    data class CategoryResult(
        val bookChapterList: List<BookChapterBean>,
        val bookId: String,
        val isBiqugeLoaded: Boolean
    )

    data class V8AnalysisStatus(
        val running: Boolean,
        val trigger: String,
        val ready: Boolean? = null,
        val processed: Int = 0,
        val target: Int = 0,
        val wrong: Int = 0,
        val cacheHit: Boolean = false,
        val phase: SourceEngineV8ScanPhase? = null
    ) {
        companion object {
            internal fun fromProgress(
                trigger: String,
                ready: Boolean?,
                progress: SourceEngineV8ScanProgress?,
                defaultRunning: Boolean = false
            ): V8AnalysisStatus {
                return V8AnalysisStatus(
                    running = progress?.running ?: defaultRunning,
                    trigger = trigger,
                    ready = ready,
                    processed = progress?.processed ?: 0,
                    target = progress?.target ?: 0,
                    wrong = progress?.wrong ?: 0,
                    cacheHit = progress?.cacheHit == true,
                    phase = progress?.phase
                )
            }

            internal fun retainFullCatalogOutcome(
                previous: V8AnalysisStatus?,
                candidate: V8AnalysisStatus
            ): V8AnalysisStatus {
                val previousIsTerminal = when (previous?.phase) {
                    SourceEngineV8ScanPhase.COMPLETED,
                    SourceEngineV8ScanPhase.CACHE_HIT,
                    SourceEngineV8ScanPhase.FAILED,
                    SourceEngineV8ScanPhase.CANCELLED -> true
                    else -> false
                }
                return previous.takeIf { previousIsTerminal && candidate.phase == null } ?: candidate
            }
        }
    }

    private val _categories = MutableLiveData<CategoryResult>()
    private val _chapterFinishedEvents = MutableLiveData<Boolean>()
    private val _chapterErrorEvents = MutableLiveData<Int>()
    private val _v8AnalysisStatus = MutableLiveData<V8AnalysisStatus>()
    private var chapterErrorVersion = 0
    private var categoryJob: Job? = null
    private var currentChapterKey: String? = null
    private var currentChapterJob: Job? = null
    private val prefetchJobs = LinkedHashMap<String, Job>()
    private val pendingPrefetchChapters = LinkedHashMap<String, ChapterLoadRequest>()
    private var refreshChapterJob: Job? = null
    private var contentTierJob: Job? = null
    private var v8RefreshJob: Job? = null
    private var activeV8BookId: String? = null
    private var activeV8Book: CollBookBean? = null
    private val wrongChapterScanGeneration = ReaderWrongChapterScanGeneration()
    private var lastV8EnsureBookKey: String? = null
    private var lastV8EnsureAtMs: Long = 0L
    private var bookIdInBiquge: String? = ""
    private val catalogPublicationGate = ReaderCatalogPublicationGate()
    private var activeCatalogSession: ReaderCatalogPublicationGate.Session? = null

    val categories: LiveData<CategoryResult> = _categories
    val chapterFinishedEvents: LiveData<Boolean> = _chapterFinishedEvents
    val chapterErrorEvents: LiveData<Int> = _chapterErrorEvents
    val v8AnalysisStatus: LiveData<V8AnalysisStatus> = _v8AnalysisStatus

    fun loadCategory(
        bookId: String?,
        collBookBean: CollBookBean,
        persistToShelf: Boolean
    ) {
        Log.d(TAG, "loadCategory: $bookId$collBookBean")
        if (categoryJob?.isActive == true || contentTierJob?.isActive == true) {
            AiBridgeTrace.event(
                "source_read_catalog_previous_cancelled",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "categoryActive" to (categoryJob?.isActive == true),
                    "tierActive" to (contentTierJob?.isActive == true)
                )
            )
        }
        categoryJob?.cancel()
        contentTierJob?.cancel()
        val catalogSession = catalogPublicationGate.beginSession(
            catalogSessionKey(bookId, collBookBean)
        ).also { session ->
            activeCatalogSession = session
        }
        val startedAt = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_read_catalog_started",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "bookId" to bookId.orEmpty(),
                "cached" to (collBookBean.getBookChapters()?.size ?: 0),
                "sourceRoute" to isSourceEngineBookRequest(bookId, collBookBean),
                "persistToShelf" to persistToShelf
            )
        )
        categoryJob = viewModelScope.launch {
            val attachedBookChapters = collBookBean.getBookChapters().orEmpty()
            val sanitizedAttachedBookChapters = sanitizeSourceEngineCatalogBoundary(
                bookId = bookId,
                collBookBean = collBookBean,
                chapters = attachedBookChapters,
                boundary = "attached-existing"
            )
            if (sanitizedAttachedBookChapters !== attachedBookChapters) {
                collBookBean.bookChapters = sanitizedAttachedBookChapters
                collBookBean.chaptersCount = sanitizedAttachedBookChapters.size
                collBookBean.lastChapter = sanitizedAttachedBookChapters.lastOrNull()?.title
                    ?: collBookBean.lastChapter
            }
            val sessionCatalogPublished = publishCachedReadingCatalog(bookId, collBookBean, startedAt)
            if (!sessionCatalogPublished) {
                publishReadingBootstrapCatalog(bookId, collBookBean, startedAt)
            }
            if (isSourceEngineBookRequest(bookId, collBookBean) &&
                !collBookBean.getBookChapters().isNullOrEmpty()
            ) {
                startReadingContentTierFill(bookId, collBookBean, persistToShelf, catalogSession)
            }
            var attempt = 0
            while (true) {
                attempt += 1
                try {
                    AiBridgeTrace.event(
                        "source_read_catalog_request",
                        collBookBean.title.orEmpty(),
                        AiBridgeTrace.fields("attempt" to attempt, "elapsedMs" to (System.currentTimeMillis() - startedAt))
                    )
                    val existingBookChapters = sanitizeSourceEngineCatalogBoundary(
                        bookId = bookId,
                        collBookBean = collBookBean,
                        chapters = collBookBean.getBookChapters().orEmpty(),
                        boundary = "existing"
                    )
                    val sourceEngineRequest = isSourceEngineBookRequest(bookId, collBookBean)
                    val publicationAttempt = if (sourceEngineRequest) {
                        catalogPublicationGate.capture(catalogSession, existingBookChapters)
                    } else {
                        null
                    }
                    val incomingBookChapters = BookContentProviderRouter.getBookFolder(
                        bookId,
                        collBookBean,
                        triggerV8ForReading = sourceEngineRequest
                    )
                    val bookChapterBeans = sanitizeSourceEngineCatalogBoundary(
                        bookId = bookId,
                        collBookBean = collBookBean,
                        chapters = incomingBookChapters,
                        boundary = "incoming"
                    )
                    val latestCurrentChapters = sanitizeSourceEngineCatalogBoundary(
                        bookId = bookId,
                        collBookBean = collBookBean,
                        chapters = collBookBean.getBookChapters().orEmpty(),
                        boundary = "publish-current"
                    )
                    var displayChapters = latestCurrentChapters
                    val publishCatalog: (List<BookChapterBean>) -> Unit = { accepted ->
                        displayChapters = accepted
                        collBookBean.bookChapters = accepted
                        collBookBean.chaptersCount = accepted.size
                        collBookBean.lastChapter = accepted.lastOrNull()?.title ?: collBookBean.lastChapter
                        Log.d(TAG, "accept: $accepted")
                        _categories.value = CategoryResult(accepted, requireNotNull(bookId), true)
                    }
                    var publicationOutcome = if (publicationAttempt != null) {
                        catalogPublicationGate.publishMainResult(
                            attempt = publicationAttempt,
                            current = latestCurrentChapters,
                            candidate = bookChapterBeans,
                            publish = publishCatalog
                        )
                    } else {
                        publishCatalog(bookChapterBeans)
                        null
                    }
                    if (publicationOutcome == ReaderCatalogPublicationOutcome.STALE) {
                        AiBridgeTrace.event(
                            "source_read_catalog_main_recapture",
                            collBookBean.title.orEmpty(),
                            AiBridgeTrace.fields(
                                "baseline" to existingBookChapters.size,
                                "current" to latestCurrentChapters.size,
                                "incoming" to bookChapterBeans.size,
                                "attempt" to attempt
                            )
                        )
                        publicationOutcome = catalogPublicationGate.retryStaleMainResult(
                            attempt = requireNotNull(publicationAttempt),
                            current = latestCurrentChapters,
                            candidate = bookChapterBeans,
                            publish = publishCatalog
                        )
                    }
                    if (publicationOutcome == ReaderCatalogPublicationOutcome.STALE) {
                        AiBridgeTrace.event(
                            "source_read_catalog_main_stale",
                            collBookBean.title.orEmpty(),
                            AiBridgeTrace.fields(
                                "baseline" to existingBookChapters.size,
                                "current" to latestCurrentChapters.size,
                                "incoming" to bookChapterBeans.size,
                                "attempt" to attempt
                            )
                        )
                        return@launch
                    }
                    if (publicationOutcome == ReaderCatalogPublicationOutcome.RETAINED_CURRENT ||
                        publicationOutcome == ReaderCatalogPublicationOutcome.REJECTED
                    ) {
                        AiBridgeTrace.state(
                            "source_read_catalog_existing_retained",
                            collBookBean.title.orEmpty(),
                            AiBridgeTrace.fields(
                                "incoming" to bookChapterBeans.size,
                                "existing" to latestCurrentChapters.size,
                                "existingLast" to latestCurrentChapters.lastOrNull()?.title.orEmpty(),
                                "reason" to publicationOutcome.name.lowercase()
                            )
                        )
                    }
                    AiBridgeTrace.state(
                        "source_read_catalog_ready",
                        collBookBean.title.orEmpty(),
                        AiBridgeTrace.fields(
                            "chapters" to displayChapters.size,
                            "first" to displayChapters.firstOrNull()?.title.orEmpty(),
                            "last" to displayChapters.lastOrNull()?.title.orEmpty(),
                            "attempt" to attempt,
                            "durationMs" to (System.currentTimeMillis() - startedAt)
                        )
                    )

                    if (persistToShelf) {
                        BookRepository.getInstance()
                            .saveCollBookWithAsync(collBookBean)
                    }
                    startReadingContentTierFill(bookId, collBookBean, persistToShelf, catalogSession)
                    startFullCatalogWrongChapterScan(bookId, collBookBean, reason = "catalog-complete")
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    LogUtils.e(e)
                    when (ReaderCatalogRetryPolicy.afterFailure(
                        isSourceEngineRequest = isSourceEngineBookRequest(bookId, collBookBean),
                        failedAttempt = attempt
                    )) {
                        ReaderCatalogRetryAction.RETRY_AFTER_DELAY -> {
                            AiBridgeTrace.event(
                                "source_read_catalog_retry",
                                collBookBean.title.orEmpty(),
                                AiBridgeTrace.fields(
                                    "attempt" to attempt,
                                    "max" to ReaderCatalogRetryPolicy.MAX_TOTAL_ATTEMPTS,
                                    "error" to e.javaClass.simpleName,
                                    "elapsedMs" to (System.currentTimeMillis() - startedAt)
                                )
                            )
                            delay(SOURCE_ENGINE_RETRY_DELAY_MS)
                            continue
                        }

                        ReaderCatalogRetryAction.PUBLISH_ERROR -> {
                            startFullCatalogWrongChapterScan(
                                bookId,
                                collBookBean,
                                reason = "cached-catalog-after-refresh-failure"
                            )
                            AiBridgeTrace.state(
                                "source_read_catalog_retry_exhausted",
                                collBookBean.title.orEmpty(),
                                AiBridgeTrace.fields(
                                    "attempt" to attempt,
                                    "max" to ReaderCatalogRetryPolicy.MAX_TOTAL_ATTEMPTS,
                                    "error" to e.javaClass.simpleName,
                                    "sourceRoute" to isSourceEngineBookRequest(bookId, collBookBean),
                                    "elapsedMs" to (System.currentTimeMillis() - startedAt)
                                )
                            )
                            _chapterErrorEvents.value = ++chapterErrorVersion
                            return@launch
                        }
                    }
                }
            }
        }
    }

    private fun publishCachedReadingCatalog(
        bookId: String?,
        collBookBean: CollBookBean,
        startedAt: Long
    ): Boolean {
        if (!isSourceEngineBookRequest(bookId, collBookBean)) return false
        if (!collBookBean.getBookChapters().isNullOrEmpty()) {
            AiBridgeTrace.event(
                "source_read_session_catalog_skipped",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "reason" to "existing_catalog",
                    "chapters" to (collBookBean.getBookChapters()?.size ?: 0),
                    "elapsedMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return true
        }
        val rawCachedChapters = BookContentProviderRouter.getCachedReadingCatalog(bookId, collBookBean)
        val cachedChapters = sanitizeSourceEngineCatalogBoundary(
            bookId = bookId,
            collBookBean = collBookBean,
            chapters = rawCachedChapters,
            boundary = "session-cache"
        )
        if (cachedChapters.isEmpty()) return false
        collBookBean.bookChapters = cachedChapters
        collBookBean.chaptersCount = cachedChapters.size
        collBookBean.lastChapter = cachedChapters.lastOrNull()?.title ?: collBookBean.lastChapter
        val resolvedBookId = bookId ?: collBookBean.bookIdInBiquge ?: collBookBean.get_id() ?: return false
        _categories.value = CategoryResult(cachedChapters, resolvedBookId, true)
        AiBridgeTrace.state(
            "source_read_session_catalog_loaded",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapters" to cachedChapters.size,
                "first" to cachedChapters.firstOrNull()?.title.orEmpty(),
                "last" to cachedChapters.lastOrNull()?.title.orEmpty(),
                "elapsedMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        return true
    }

    private suspend fun publishReadingBootstrapCatalog(
        bookId: String?,
        collBookBean: CollBookBean,
        startedAt: Long
    ) {
        if (!isSourceEngineBookRequest(bookId, collBookBean)) return
        if (!collBookBean.getBookChapters().isNullOrEmpty()) return
        val bootstrapStartedAt = System.currentTimeMillis()
        try {
            val rawBootstrapChapters = BookContentProviderRouter.getReadingBootstrapChapters(
                bookId,
                collBookBean,
                READING_BOOTSTRAP_CHAPTERS
            )
            val bootstrapChapters = sanitizeSourceEngineCatalogBoundary(
                bookId = bookId,
                collBookBean = collBookBean,
                chapters = rawBootstrapChapters,
                boundary = "bootstrap"
            )
            if (bootstrapChapters.isEmpty()) {
                AiBridgeTrace.event(
                    "source_read_catalog_bootstrap_skipped",
                    collBookBean.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "reason" to "empty",
                        "durationMs" to (System.currentTimeMillis() - bootstrapStartedAt),
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                return
            }
            collBookBean.bookChapters = bootstrapChapters
            collBookBean.chaptersCount = bootstrapChapters.size
            _categories.value = CategoryResult(bootstrapChapters, requireNotNull(bookId), false)
            AiBridgeTrace.state(
                "source_read_catalog_bootstrap_ready",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapters" to bootstrapChapters.size,
                    "first" to bootstrapChapters.firstOrNull()?.title.orEmpty(),
                    "durationMs" to (System.currentTimeMillis() - bootstrapStartedAt),
                    "elapsedMs" to (System.currentTimeMillis() - startedAt)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AiBridgeTrace.event(
                "source_read_catalog_bootstrap_skipped",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "reason" to e.javaClass.simpleName,
                    "durationMs" to (System.currentTimeMillis() - bootstrapStartedAt),
                    "elapsedMs" to (System.currentTimeMillis() - startedAt)
                )
            )
        }
    }

    @Synchronized
    fun loadChapter(
        bookId: String?,
        sourceBook: CollBookBean,
        bookChapterList: List<TxtChapter>,
        currentChapterTitle: String?,
        persistToShelf: Boolean
    ) {
        val size = bookChapterList.size
        Log.e(TAG, "loadChapter  列表大小$size $bookChapterList")
        AiBridgeTrace.event(
            "source_read_chapter_batch_received",
            sourceBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "total" to size,
                "current" to currentChapterTitle.orEmpty(),
                "first" to bookChapterList.firstOrNull()?.title.orEmpty(),
                "sourceRoute" to isSourceEngineBookRequest(bookId, sourceBook),
                "activeCurrent" to (currentChapterJob?.isActive == true),
                "prefetchActive" to prefetchJobs.size,
                "prefetchPending" to pendingPrefetchChapters.size,
                "persistToShelf" to persistToShelf
            )
        )

        for (i in 0 until size) {
            val bookChapter = bookChapterList[i]
            if (bookChapter.title == null || TextUtils.isEmpty(bookChapter.title)) {
                continue
            }
            bookIdInBiquge = sourceBook.get_id()
            Log.d("+收到的章节笔趣阁Id", bookIdInBiquge!!)
            Log.d("+收到的章节ID", bookChapter.link!!)
            val titleInBiquge = bookChapter.title!!
            val chapterKey = chapterLoadKey(sourceBook, titleInBiquge, bookChapter.link)
            val isCurrentReadRequest = titleInBiquge == currentChapterTitle
            val request = ChapterLoadRequest(
                bookId,
                sourceBook,
                bookChapter,
                titleInBiquge,
                isCurrentReadRequest,
                persistToShelf
            )
            if (isCurrentReadRequest) {
                AiBridgeTrace.event(
                    "source_read_current_chapter_queued",
                    sourceBook.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "chapter" to titleInBiquge,
                        "indexInBatch" to i,
                        "total" to bookChapterList.size,
                        "chapterRoute" to SourceEngineBookRoute.isChapterId(bookChapter.link)
                    )
                )
                startCurrentChapterLoad(chapterKey, request)
            } else {
                enqueuePrefetchChapter(chapterKey, request)
            }
        }
    }

    @Synchronized
    fun refreshChapter(bookId: String?, sourceBook: CollBookBean, bookChapter: TxtChapter?, sourceIndex: Int) {
        requireNotNull(bookChapter)
        bookIdInBiquge = sourceBook.get_id()
        refreshChapterJob?.cancel()
        refreshChapterJob = viewModelScope.launch {
            try {
                val previousCurrentReadRequest = bookChapter.sourceEngineCurrentReadRequest
                val content = try {
                    bookChapter.sourceEngineCurrentReadRequest = true
                    BookContentProviderRouter.getBookContent(
                        bookId,
                        sourceBook,
                        bookChapter,
                        sourceIndex
                    )
                } finally {
                    bookChapter.sourceEngineCurrentReadRequest = previousCurrentReadRequest
                }
                BookRepository.getInstance().saveChapterInfo(
                    bookId,
                    chapterCacheFileName(bookId, bookChapter.title, bookChapter.link),
                    content
                )
                _chapterFinishedEvents.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _chapterErrorEvents.value = ++chapterErrorVersion
            }
        }
    }

    fun triggerV8ForChapterRefresh(bookId: String?, collBookBean: CollBookBean) {
        ensureV8ForCurrentReading(bookId, collBookBean, reason = "chapter-refresh", force = true)
    }

    private fun startFullCatalogWrongChapterScan(
        bookId: String?,
        collBookBean: CollBookBean,
        reason: String
    ) {
        val sourceBackedNovel = isSourceEngineBookRequest(bookId, collBookBean)
        val catalogSize = collBookBean.getBookChapters()?.size ?: 0
        if (!SourceEngineWrongChapterScanPolicy.shouldStart(
                featureEnabled = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled(),
                sourceBackedNovel = sourceBackedNovel,
                catalogSize = catalogSize
            )
        ) {
            return
        }
        val generation = replaceV8AnalysisWork(bookId, collBookBean, reason)
        v8RefreshJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var ready: Boolean? = null
            var terminalFailurePublished = false
            try {
                ready = BookContentProviderRouter.prepareBookContentTier(
                    bookId = bookId,
                    collBookBean = collBookBean,
                    persist = true,
                    triggerV8 = true,
                    requestPriority = SourceRequestPriority.BACKGROUND,
                    mode = SourceContentTierMode.CATALOG_FULL_SCAN
                )
                publishFullCatalogV8Progress(bookId, collBookBean, reason, ready)
                waitForFullCatalogV8Validation(bookId, collBookBean, reason, ready, startedAt)
            } catch (error: CancellationException) {
                if (wrongChapterScanGeneration.isCurrent(generation)) {
                    BookContentProviderRouter.cancelV8ValidationForBook(
                        bookId,
                        collBookBean,
                        reason = "view-model-cancelled"
                    )
                    publishFullCatalogV8Progress(bookId, collBookBean, reason, ready)
                }
                throw error
            } catch (error: Throwable) {
                LogUtils.e(error)
                ready = false
                if (wrongChapterScanGeneration.isCurrent(generation)) {
                    val progress = BookContentProviderRouter.fullCatalogV8ProgressForBook(bookId, collBookBean)
                    publishV8AnalysisStatus(v8StatusFromProgress(
                        trigger = reason,
                        ready = ready,
                        progress = progress?.copy(phase = SourceEngineV8ScanPhase.FAILED)
                            ?: SourceEngineV8ScanProgress(
                                phase = SourceEngineV8ScanPhase.FAILED,
                                processed = 0,
                                target = 0
                        ),
                        defaultRunning = false
                    ))
                    terminalFailurePublished = true
                }
            } finally {
                if (
                    wrongChapterScanGeneration.isCurrent(generation) &&
                    !terminalFailurePublished
                ) {
                    publishFullCatalogV8Progress(bookId, collBookBean, reason, ready)
                }
            }
            AiBridgeTrace.state(
                "source_read_v8_full_catalog_finished",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "ready" to ready,
                    "trigger" to reason,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
        }
    }

    private suspend fun waitForFullCatalogV8Validation(
        bookId: String?,
        collBookBean: CollBookBean,
        trigger: String,
        ready: Boolean?,
        startedAt: Long
    ) {
        var reportedWait = false
        while (true) {
            val progress = BookContentProviderRouter.fullCatalogV8ProgressForBook(bookId, collBookBean)
            val active = BookContentProviderRouter.hasActiveV8ValidationForBook(bookId, collBookBean)
            if (progress != null && !progress.running) break
            if (!active) break
            if (!reportedWait) {
                reportedWait = true
                AiBridgeTrace.event(
                    "source_read_v8_full_catalog_wait_started",
                    collBookBean.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "trigger" to trigger,
                        "ready" to ready,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
            }
            publishV8AnalysisStatus(v8StatusFromProgress(
                trigger = trigger,
                ready = ready,
                progress = progress,
                defaultRunning = progress?.running == true
            ))
            delay(V8_ANALYSIS_ACTIVE_POLL_MS)
        }
        publishFullCatalogV8Progress(bookId, collBookBean, trigger, ready)
    }

    private fun publishFullCatalogV8Progress(
        bookId: String?,
        collBookBean: CollBookBean,
        trigger: String,
        ready: Boolean?
    ) {
        val progress = BookContentProviderRouter.fullCatalogV8ProgressForBook(bookId, collBookBean)
        publishV8AnalysisStatus(v8StatusFromProgress(
            trigger = trigger,
            ready = ready,
            progress = progress,
            defaultRunning = false
        ))
    }

    private fun v8StatusFromProgress(
        trigger: String,
        ready: Boolean?,
        progress: SourceEngineV8ScanProgress?,
        defaultRunning: Boolean
    ): V8AnalysisStatus {
        return V8AnalysisStatus.fromProgress(
            trigger = trigger,
            ready = ready,
            progress = progress,
            defaultRunning = defaultRunning
        )
    }

    private fun publishV8AnalysisStatus(candidate: V8AnalysisStatus) {
        _v8AnalysisStatus.value = V8AnalysisStatus.retainFullCatalogOutcome(
            previous = _v8AnalysisStatus.value,
            candidate = candidate
        )
    }

    fun ensureV8ForCurrentReading(
        bookId: String?,
        collBookBean: CollBookBean,
        reason: String,
        force: Boolean = false
    ) {
        if (!isSourceEngineBookRequest(bookId, collBookBean)) return
        if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) return
        if (v8RefreshJob?.isActive == true) {
            AiBridgeTrace.event(
                "source_read_v8_ensure_skipped",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields("reason" to "already_active", "trigger" to reason, "bookId" to bookId.orEmpty())
            )
            return
        }
        val ensureKey = listOf(
            bookId.orEmpty(),
            collBookBean.bookIdInBiquge.orEmpty(),
            collBookBean.get_id().orEmpty()
        ).firstOrNull { value -> value.isNotBlank() }.orEmpty()
        val now = System.currentTimeMillis()
        if (!force && ensureKey == lastV8EnsureBookKey && now - lastV8EnsureAtMs < V8_READING_ENSURE_MIN_INTERVAL_MS) {
            AiBridgeTrace.event(
                "source_read_v8_ensure_skipped",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "reason" to "throttled",
                    "trigger" to reason,
                    "elapsedMs" to (now - lastV8EnsureAtMs)
                )
            )
            return
        }
        lastV8EnsureBookKey = ensureKey
        lastV8EnsureAtMs = now
        val generation = replaceV8AnalysisWork(bookId, collBookBean, reason)
        AiBridgeTrace.event(
            "source_read_v8_ensure_started",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "bookId" to bookId.orEmpty(),
                "cached" to (collBookBean.getBookChapters()?.size ?: 0),
                "trigger" to reason,
                "force" to force
            )
        )
        v8RefreshJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            publishV8AnalysisStatus(V8AnalysisStatus(running = true, trigger = reason))
            var ready: Boolean? = null
            try {
                ready = BookContentProviderRouter.prepareBookContentTier(
                    bookId,
                    collBookBean,
                    persist = true,
                    triggerV8 = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled(),
                    requestPriority = SourceRequestPriority.BACKGROUND,
                    mode = SourceContentTierMode.READING_LIGHT
                )
                waitForBackgroundV8Validation(bookId, collBookBean, reason, ready, startedAt)
            } catch (error: CancellationException) {
                if (wrongChapterScanGeneration.isCurrent(generation)) {
                    BookContentProviderRouter.cancelV8ValidationForBook(
                        bookId,
                        collBookBean,
                        reason = "view-model-cancelled"
                    )
                    publishV8AnalysisStatus(
                        V8AnalysisStatus(running = false, trigger = reason, ready = null)
                    )
                }
                throw error
            } catch (error: Throwable) {
                LogUtils.e(error)
                ready = false
            } finally {
                if (ready != null && wrongChapterScanGeneration.isCurrent(generation)) {
                    publishV8AnalysisStatus(
                        V8AnalysisStatus(running = false, trigger = reason, ready = ready)
                    )
                }
            }
            AiBridgeTrace.state(
                "source_read_v8_ensure_finished",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "ready" to ready,
                    "trigger" to reason,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
        }
    }

    private suspend fun waitForBackgroundV8Validation(
        bookId: String?,
        collBookBean: CollBookBean,
        trigger: String,
        ready: Boolean?,
        startedAt: Long
    ) {
        var reportedWait = false
        while (BookContentProviderRouter.hasActiveV8ValidationForBook(bookId, collBookBean)) {
            if (!reportedWait) {
                reportedWait = true
                AiBridgeTrace.event(
                    "source_read_v8_background_wait_started",
                    collBookBean.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "trigger" to trigger,
                        "ready" to ready,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
            }
            publishV8AnalysisStatus(
                V8AnalysisStatus(running = true, trigger = trigger, ready = ready)
            )
            delay(V8_ANALYSIS_ACTIVE_POLL_MS)
        }
        if (reportedWait) {
            AiBridgeTrace.state(
                "source_read_v8_background_wait_finished",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "trigger" to trigger,
                    "ready" to ready,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
        }
    }

    override fun onCleared() {
        categoryJob?.cancel()
        currentChapterJob?.cancel()
        currentChapterJob = null
        prefetchJobs.values.forEach { job -> job.cancel() }
        prefetchJobs.clear()
        pendingPrefetchChapters.clear()
        refreshChapterJob?.cancel()
        contentTierJob?.cancel()
        wrongChapterScanGeneration.invalidate()
        v8RefreshJob?.cancel()
        activeV8Book?.let { book ->
            BookContentProviderRouter.cancelV8ValidationForBook(
                activeV8BookId,
                book,
                reason = "view-model-cleared"
            )
        }
        activeV8Book = null
        activeV8BookId = null
        super.onCleared()
    }

    private fun replaceV8AnalysisWork(
        bookId: String?,
        collBookBean: CollBookBean,
        reason: String
    ): Long {
        val generation = wrongChapterScanGeneration.next()
        v8RefreshJob?.cancel()
        activeV8Book?.let { previousBook ->
            BookContentProviderRouter.cancelV8ValidationForBook(
                activeV8BookId,
                previousBook,
                reason = "replaced:$reason"
            )
        }
        activeV8BookId = bookId
        activeV8Book = collBookBean
        return generation
    }

    private fun startReadingContentTierFill(
        bookId: String?,
        collBookBean: CollBookBean,
        persistToShelf: Boolean,
        catalogSession: ReaderCatalogPublicationGate.Session? = activeCatalogSession
    ) {
        val session = catalogSession ?: return
        if (session.bookKey != catalogSessionKey(bookId, collBookBean)) return
        if (!isSourceEngineBookRequest(bookId, collBookBean)) return
        if (contentTierJob?.isActive == true) {
            AiBridgeTrace.event(
                "source_read_tier_skipped",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "reason" to "already_active",
                    "bookId" to bookId.orEmpty()
                )
            )
            return
        }
        contentTierJob?.cancel()
        AiBridgeTrace.event(
            "source_read_tier_started",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "persist" to persistToShelf,
                "bookId" to bookId.orEmpty(),
                "cached" to (collBookBean.getBookChapters()?.size ?: 0),
                "persistToShelf" to persistToShelf
            )
        )
        contentTierJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var attempt = 0
            var delayMs = SOURCE_ENGINE_TIER_INITIAL_BACKOFF_MS
            while (true) {
                attempt += 1
                AiBridgeTrace.event(
                    "source_read_tier_attempt",
                    collBookBean.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "attempt" to attempt,
                        "persist" to persistToShelf,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                val tierResult = try {
                    BookContentProviderRouter.prepareBookContentTierResult(
                        bookId,
                        collBookBean,
                        persist = persistToShelf,
                        triggerV8 = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled(),
                        requestPriority = SourceRequestPriority.BACKGROUND,
                        mode = SourceContentTierMode.READING_LIGHT
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LogUtils.e(error)
                    SourceContentTierPrepareResult.RETRY_LATER
                }
                val catalogPublicationOutcome = promoteCatalogAfterTierAttempt(
                    bookId,
                    collBookBean,
                    startedAt,
                    persistToShelf,
                    session
                )
                if (tierResult.isReady) {
                    AiBridgeTrace.state(
                        "source_read_tier_ready",
                        collBookBean.title.orEmpty(),
                        AiBridgeTrace.fields(
                            "attempt" to attempt,
                            "durationMs" to (System.currentTimeMillis() - startedAt),
                            "catalogPublication" to catalogPublicationOutcome.name.lowercase()
                        )
                    )
                    return@launch
                }
                if (!tierResult.shouldRetry) {
                    AiBridgeTrace.state(
                        "source_read_tier_exhausted",
                        collBookBean.title.orEmpty(),
                        AiBridgeTrace.fields("attempt" to attempt, "durationMs" to (System.currentTimeMillis() - startedAt))
                    )
                    return@launch
                }
                if (attempt >= SOURCE_ENGINE_READING_TIER_MAX_ATTEMPTS) {
                    AiBridgeTrace.state(
                        "source_read_tier_deferred",
                        collBookBean.title.orEmpty(),
                        AiBridgeTrace.fields(
                            "attempt" to attempt,
                            "durationMs" to (System.currentTimeMillis() - startedAt),
                            "reason" to "reading_light_attempt_limit"
                        )
                    )
                    return@launch
                }
                AiBridgeTrace.event(
                    "source_read_tier_retry",
                    collBookBean.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "attempt" to attempt,
                        "nextDelayMs" to delayMs,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(SOURCE_ENGINE_TIER_MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun promoteCatalogAfterTierAttempt(
        bookId: String?,
        collBookBean: CollBookBean,
        startedAt: Long,
        persistToShelf: Boolean,
        catalogSession: ReaderCatalogPublicationGate.Session
    ): ReaderCatalogPublicationOutcome {
        val rawCurrentChapters = collBookBean.getBookChapters().orEmpty()
        val currentChapters = sanitizeSourceEngineCatalogBoundary(
            bookId = bookId,
            collBookBean = collBookBean,
            chapters = rawCurrentChapters,
            boundary = "tier-current"
        )
        val publicationAttempt = catalogPublicationGate.capture(catalogSession, currentChapters)
        val currentSize = currentChapters.size
        val rawRefreshed = try {
            BookContentProviderRouter.getBookFolder(
                bookId,
                collBookBean,
                triggerV8ForReading = false
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LogUtils.e(error)
            AiBridgeTrace.event(
                "source_read_catalog_tier_not_promoted",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields("reason" to ReaderCatalogPublicationOutcome.FETCH_FAILED.name.lowercase())
            )
            return ReaderCatalogPublicationOutcome.FETCH_FAILED
        }
        val refreshed = sanitizeSourceEngineCatalogBoundary(
            bookId = bookId,
            collBookBean = collBookBean,
            chapters = rawRefreshed,
            boundary = "tier-refresh"
        )
        val latestCurrentChapters = sanitizeSourceEngineCatalogBoundary(
            bookId = bookId,
            collBookBean = collBookBean,
            chapters = collBookBean.getBookChapters().orEmpty(),
            boundary = "tier-publish-current"
        )
        val resolvedBookId = bookId ?: collBookBean.bookIdInBiquge ?: collBookBean.get_id()
        if (resolvedBookId == null) {
            AiBridgeTrace.event(
                "source_read_catalog_tier_not_promoted",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields("reason" to "missing_book_id")
            )
            return ReaderCatalogPublicationOutcome.REJECTED
        }
        var promotedChapters: List<BookChapterBean>? = null
        val publishCatalog: (List<BookChapterBean>) -> Unit = { accepted ->
            promotedChapters = accepted
            collBookBean.bookChapters = accepted
            collBookBean.chaptersCount = accepted.size
            collBookBean.lastChapter = accepted.lastOrNull()?.title ?: collBookBean.lastChapter
            _categories.value = CategoryResult(accepted, resolvedBookId, true)
        }
        var publicationOutcome = catalogPublicationGate.promoteTierResult(
            attempt = publicationAttempt,
            current = latestCurrentChapters,
            candidate = refreshed,
            publish = publishCatalog
        )
        if (publicationOutcome == ReaderCatalogPublicationOutcome.STALE) {
            AiBridgeTrace.event(
                "source_read_catalog_tier_recapture",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "baseline" to currentChapters.size,
                    "current" to latestCurrentChapters.size,
                    "refreshed" to refreshed.size
                )
            )
            publicationOutcome = catalogPublicationGate.retryStaleTierResult(
                attempt = publicationAttempt,
                current = latestCurrentChapters,
                candidate = refreshed,
                publish = publishCatalog
            )
        }
        if (publicationOutcome != ReaderCatalogPublicationOutcome.APPLIED_CHANGED) {
            AiBridgeTrace.event(
                "source_read_catalog_tier_not_promoted",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
                    "current" to latestCurrentChapters.size,
                    "refreshed" to refreshed.size,
                    "currentLast" to latestCurrentChapters.lastOrNull()?.title.orEmpty(),
                    "refreshedLast" to refreshed.lastOrNull()?.title.orEmpty(),
                    "reason" to publicationOutcome.name.lowercase()
                )
            )
            return publicationOutcome
        }
        val promoted = requireNotNull(promotedChapters)
        AiBridgeTrace.state(
            "source_read_catalog_tier_promoted",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "from" to currentSize,
                "to" to promoted.size,
                "last" to promoted.lastOrNull()?.title.orEmpty(),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        if (persistToShelf) {
            BookRepository.getInstance().saveCollBookWithAsync(collBookBean)
        }
        return publicationOutcome
    }

    private fun shouldPromoteRefreshedCatalog(
        current: List<BookChapterBean>,
        refreshed: List<BookChapterBean>
    ): Boolean {
        return ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed)
    }

    private fun shouldRetainExistingSourceEngineCatalog(
        bookId: String?,
        collBookBean: CollBookBean,
        existingChapters: List<BookChapterBean>?,
        incomingChapters: List<BookChapterBean>
    ): Boolean {
        if (!isSourceEngineBookRequest(bookId, collBookBean)) return false
        return ReaderCatalogRefreshPromotionPolicy.shouldRetainCurrentCatalog(
            existingChapters.orEmpty(),
            incomingChapters
        )
    }

    private fun startCurrentChapterLoad(chapterKey: String, request: ChapterLoadRequest) {
        if (chapterKey == currentChapterKey && currentChapterJob?.isActive == true) {
            AiBridgeTrace.event(
                "source_read_current_chapter_deduped",
                request.sourceBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapter" to request.title,
                    "queuedAgeMs" to (System.currentTimeMillis() - request.queuedAtMs)
                )
            )
            return
        }
        AiBridgeTrace.event(
            "source_read_current_chapter_cancel_background",
            request.sourceBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapter" to request.title,
                "oldCurrentActive" to (currentChapterJob?.isActive == true),
                "tierActive" to (contentTierJob?.isActive == true),
                "prefetchActive" to prefetchJobs.size,
                "prefetchPending" to pendingPrefetchChapters.size
            )
        )
        currentChapterJob?.cancel()
        contentTierJob?.cancel()
        currentChapterKey = chapterKey
        cancelPrefetchLoads()
        AiBridgeTrace.event(
            "source_read_current_chapter_started",
            request.sourceBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapter" to request.title,
                "queuedAgeMs" to (System.currentTimeMillis() - request.queuedAtMs),
                "prefetchCleared" to true
            )
        )
        currentChapterJob = startChapterLoad(
            request = request,
            notifyError = true
        ) {
            if (currentChapterKey == chapterKey) {
                AiBridgeTrace.event(
                    "source_read_current_chapter_finished",
                    request.sourceBook.title.orEmpty(),
                    AiBridgeTrace.fields("chapter" to request.title)
                )
                currentChapterKey = null
                currentChapterJob = null
                startReadingContentTierFill(request.bookId, request.sourceBook, request.persistToShelf)
                drainPrefetchQueue()
            }
        }
    }

    private fun enqueuePrefetchChapter(chapterKey: String, request: ChapterLoadRequest) {
        if (chapterKey == currentChapterKey ||
            chapterKey in prefetchJobs ||
            chapterKey in pendingPrefetchChapters
        ) {
            AiBridgeTrace.event(
                "source_read_prefetch_skipped",
                request.sourceBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapter" to request.title,
                    "reason" to "duplicate_or_current",
                    "active" to prefetchJobs.size,
                    "pending" to pendingPrefetchChapters.size
                )
            )
            return
        }
        if (prefetchJobs.size + pendingPrefetchChapters.size >= PREFETCH_CHAPTER_LIMIT) {
            AiBridgeTrace.event(
                "source_read_prefetch_skipped",
                request.sourceBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapter" to request.title,
                    "reason" to "limit",
                    "limit" to PREFETCH_CHAPTER_LIMIT,
                    "active" to prefetchJobs.size,
                    "pending" to pendingPrefetchChapters.size
                )
            )
            return
        }
        pendingPrefetchChapters[chapterKey] = request
        AiBridgeTrace.event(
            "source_read_prefetch_queued",
            request.sourceBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapter" to request.title,
                "active" to prefetchJobs.size,
                "pending" to pendingPrefetchChapters.size
            )
        )
        drainPrefetchQueue()
    }

    private fun drainPrefetchQueue() {
        if (currentChapterJob?.isActive == true) {
            AiBridgeTrace.event(
                "source_read_prefetch_waiting_current",
                currentChapterJob.toString(),
                AiBridgeTrace.fields("active" to prefetchJobs.size, "pending" to pendingPrefetchChapters.size)
            )
            return
        }
        while (prefetchJobs.size < MAX_PREFETCH_CONCURRENT_CHAPTERS && pendingPrefetchChapters.isNotEmpty()) {
            val entry = pendingPrefetchChapters.entries.first()
            pendingPrefetchChapters.remove(entry.key)
            AiBridgeTrace.event(
                "source_read_prefetch_started",
                entry.value.sourceBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapter" to entry.value.title,
                    "activeBefore" to prefetchJobs.size,
                    "pendingAfterPop" to pendingPrefetchChapters.size
                )
            )
            prefetchJobs[entry.key] = startChapterLoad(
                request = entry.value,
                notifyError = false
            ) {
                prefetchJobs.remove(entry.key)
                drainPrefetchQueue()
            }
        }
    }

    private fun cancelPrefetchLoads() {
        if (pendingPrefetchChapters.isNotEmpty() || prefetchJobs.isNotEmpty()) {
            AiBridgeTrace.event(
                "source_read_prefetch_cancelled",
                "reader",
                AiBridgeTrace.fields("active" to prefetchJobs.size, "pending" to pendingPrefetchChapters.size)
            )
        }
        pendingPrefetchChapters.clear()
        prefetchJobs.values.forEach { job -> job.cancel() }
        prefetchJobs.clear()
    }

    private fun startChapterLoad(
        request: ChapterLoadRequest,
        notifyError: Boolean,
        onFinished: () -> Unit
    ): Job {
        return viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var attempt = 0
            try {
                while (true) {
                    attempt += 1
                    try {
                        AiBridgeTrace.event(
                            "source_read_chapter_fetch_started",
                            request.sourceBook.title.orEmpty(),
                            AiBridgeTrace.fields(
                                "chapter" to request.title,
                                "notify" to notifyError,
                                "attempt" to attempt,
                                "chapterRoute" to SourceEngineBookRoute.isChapterId(request.bookChapter.link),
                                "queuedAgeMs" to (System.currentTimeMillis() - request.queuedAtMs)
                            )
                        )
                        val previousCurrentReadRequest = request.bookChapter.sourceEngineCurrentReadRequest
                        val content = try {
                            request.bookChapter.sourceEngineCurrentReadRequest = request.currentReadRequest
                            BookContentProviderRouter.getBookContent(
                                request.bookId,
                                request.sourceBook,
                                request.bookChapter,
                                0
                            )
                        } finally {
                            request.bookChapter.sourceEngineCurrentReadRequest = previousCurrentReadRequest
                        }
                        BookRepository.getInstance().saveChapterInfo(
                            request.bookId,
                            chapterCacheFileName(request.bookId, request.title, request.bookChapter.link),
                            content
                        )
                        Log.e("+chapterBody", "title${request.title} $content")
                        AiBridgeTrace.state(
                            "source_read_chapter_saved",
                            "${request.sourceBook.title.orEmpty()}#${request.title}",
                            AiBridgeTrace.fields(
                                "chapter" to request.title,
                                "notify" to notifyError,
                                "chars" to content.length,
                                "attempt" to attempt,
                                "durationMs" to (System.currentTimeMillis() - startedAt),
                                "queuedAgeMs" to (System.currentTimeMillis() - request.queuedAtMs)
                            )
                        )
                        _chapterFinishedEvents.value = false
                        return@launch
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        LogUtils.e(t)
                        if (notifyError && SourceEngineBookRoute.isChapterId(request.bookChapter.link)) {
                            val terminalSourceEngineFailure = isSourceEngineContentTerminalFailure(t)
                            if (!terminalSourceEngineFailure && attempt < SOURCE_ENGINE_CHAPTER_MAX_ATTEMPTS) {
                                AiBridgeTrace.event(
                                    "source_read_chapter_retry",
                                    request.sourceBook.title.orEmpty(),
                                    AiBridgeTrace.fields(
                                        "chapter" to request.title,
                                        "attempt" to attempt,
                                        "max" to SOURCE_ENGINE_CHAPTER_MAX_ATTEMPTS,
                                        "error" to t.javaClass.simpleName,
                                        "terminal" to terminalSourceEngineFailure,
                                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                                    )
                                )
                                delay(SOURCE_ENGINE_RETRY_DELAY_MS)
                                continue
                            }
                            AiBridgeTrace.state(
                                "source_read_chapter_retry_exhausted",
                                request.sourceBook.title.orEmpty(),
                                AiBridgeTrace.fields(
                                    "chapter" to request.title,
                                    "attempt" to attempt,
                                    "max" to SOURCE_ENGINE_CHAPTER_MAX_ATTEMPTS,
                                    "error" to t.javaClass.simpleName,
                                    "terminal" to terminalSourceEngineFailure,
                                    "elapsedMs" to (System.currentTimeMillis() - startedAt)
                                )
                            )
                            _chapterErrorEvents.value = ++chapterErrorVersion
                            return@launch
                        }
                        if (notifyError) {
                            AiBridgeTrace.state(
                                "source_read_chapter_error",
                                request.sourceBook.title.orEmpty(),
                                AiBridgeTrace.fields(
                                    "chapter" to request.title,
                                    "attempt" to attempt,
                                    "error" to t.javaClass.simpleName,
                                    "elapsedMs" to (System.currentTimeMillis() - startedAt)
                                )
                            )
                            _chapterErrorEvents.value = ++chapterErrorVersion
                        }
                        return@launch
                    }
                }
            } finally {
                onFinished()
            }
        }
    }

    private fun isSourceEngineBookRequest(bookId: String?, sourceBook: CollBookBean): Boolean {
        return SourceEngineBookRoute.isBookId(bookId) ||
            SourceEngineBookRoute.isBookId(sourceBook.bookIdInBiquge)
    }

    private fun catalogSessionKey(bookId: String?, sourceBook: CollBookBean): String {
        return listOf(
            sourceBook.get_id().orEmpty(),
            bookId.orEmpty(),
            sourceBook.bookIdInBiquge.orEmpty()
        ).joinToString("\n")
    }

    private fun chapterLoadKey(sourceBook: CollBookBean, title: String, link: String?): String {
        return sourceBook.get_id().orEmpty() + "#" +
            SourceEngineChapterContentCacheKey.fileName(sourceBook.get_id(), title, link).orEmpty()
    }

    private fun sanitizeSourceEngineCatalogBoundary(
        bookId: String?,
        collBookBean: CollBookBean,
        chapters: List<BookChapterBean>,
        boundary: String
    ): List<BookChapterBean> {
        val sourceEngineCatalog = isSourceEngineBookRequest(bookId, collBookBean) ||
            SourceEngineBookRoute.isShelfBookId(bookId) ||
            SourceEngineBookRoute.isShelfBookId(collBookBean.get_id())
        if (!sourceEngineCatalog || chapters.isEmpty()) return chapters
        val migration = SourceEngineCachedCatalogMigration.sanitize(chapters)
        if (!migration.changed) return chapters
        AiBridgeTrace.state(
            "source_read_catalog_navigation_migrated",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "boundary" to boundary,
                "before" to chapters.size,
                "after" to migration.chapters.size,
                "removed" to migration.removedCount,
                "first" to migration.chapters.firstOrNull()?.title.orEmpty(),
                "last" to migration.chapters.lastOrNull()?.title.orEmpty()
            )
        )
        return migration.chapters
    }

    private fun chapterCacheFileName(bookId: String?, title: String?, link: String?): String? {
        return SourceEngineChapterContentCacheKey.fileName(bookId, title, link)
    }

    private fun isSourceEngineContentTerminalFailure(t: Throwable): Boolean {
        return t is IllegalStateException &&
            t.message.orEmpty().startsWith("Source-engine content request failed:")
    }

    private data class ChapterLoadRequest(
        val bookId: String?,
        val sourceBook: CollBookBean,
        val bookChapter: TxtChapter,
        val title: String,
        val currentReadRequest: Boolean,
        val persistToShelf: Boolean,
        val queuedAtMs: Long = System.currentTimeMillis()
    )

    companion object {
        private val TAG = ReadViewModel::class.java.simpleName
        private const val READING_BOOTSTRAP_CHAPTERS = 10_000
        private const val PREFETCH_CHAPTER_LIMIT = 5
        private const val MAX_PREFETCH_CONCURRENT_CHAPTERS = 1
        private const val SOURCE_ENGINE_RETRY_DELAY_MS = 2_000L
        private const val SOURCE_ENGINE_CHAPTER_MAX_ATTEMPTS = 2
        private const val SOURCE_ENGINE_TIER_INITIAL_BACKOFF_MS = 2_000L
        private const val SOURCE_ENGINE_TIER_MAX_BACKOFF_MS = 30_000L
        private const val SOURCE_ENGINE_READING_TIER_MAX_ATTEMPTS = 2
        private const val V8_READING_ENSURE_MIN_INTERVAL_MS = 60_000L
        private const val V8_ANALYSIS_ACTIVE_POLL_MS = 1_000L
    }
}

private fun String.traceToken(): String {
    return replace(Regex("""[\s=:/\\#]+"""), "_").take(100)
}
