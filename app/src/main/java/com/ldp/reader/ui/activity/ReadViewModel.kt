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

    private val _categories = MutableLiveData<CategoryResult>()
    private val _chapterFinishedEvents = MutableLiveData<Boolean>()
    private val _chapterErrorEvents = MutableLiveData<Int>()
    private var chapterErrorVersion = 0
    private var categoryJob: Job? = null
    private var currentChapterKey: String? = null
    private var currentChapterJob: Job? = null
    private val prefetchJobs = LinkedHashMap<String, Job>()
    private val pendingPrefetchChapters = LinkedHashMap<String, ChapterLoadRequest>()
    private var refreshChapterJob: Job? = null
    private var contentTierJob: Job? = null
    private var v8RefreshJob: Job? = null
    private var bookIdInBiquge: String? = ""

    val categories: LiveData<CategoryResult> = _categories
    val chapterFinishedEvents: LiveData<Boolean> = _chapterFinishedEvents
    val chapterErrorEvents: LiveData<Int> = _chapterErrorEvents

    fun loadCategory(bookId: String?, collBookBean: CollBookBean) {
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
        val startedAt = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_read_catalog_started",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "bookId" to bookId.orEmpty(),
                "cached" to (collBookBean.getBookChapters()?.size ?: 0),
                "sourceRoute" to isSourceEngineBookRequest(bookId, collBookBean)
            )
        )
        categoryJob = viewModelScope.launch {
            publishReadingBootstrapCatalog(bookId, collBookBean, startedAt)
            if (isSourceEngineBookRequest(bookId, collBookBean) &&
                !collBookBean.getBookChapters().isNullOrEmpty()
            ) {
                startReadingContentTierFill(bookId, collBookBean)
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
                    val existingBookChapters = collBookBean.getBookChapters()
                    val bookChapterBeans = BookContentProviderRouter.getBookFolder(
                        bookId,
                        collBookBean,
                        triggerV8ForReading = isSourceEngineBookRequest(bookId, collBookBean)
                    )
                    val displayChapters = if (shouldRetainExistingSourceEngineCatalog(
                            bookId,
                            collBookBean,
                            existingBookChapters,
                            bookChapterBeans
                        )
                    ) {
                        AiBridgeTrace.state(
                            "source_read_catalog_existing_retained",
                            collBookBean.title.orEmpty(),
                            AiBridgeTrace.fields(
                                "incoming" to bookChapterBeans.size,
                                "existing" to (existingBookChapters?.size ?: 0),
                                "existingLast" to existingBookChapters?.lastOrNull()?.title.orEmpty()
                            )
                        )
                        existingBookChapters ?: bookChapterBeans
                    } else {
                        bookChapterBeans
                    }
                    collBookBean.bookChapters = displayChapters
                    collBookBean.chaptersCount = displayChapters.size
                    collBookBean.lastChapter = displayChapters.lastOrNull()?.title ?: collBookBean.lastChapter
                    Log.d(TAG, "accept: $displayChapters")
                    _categories.value = CategoryResult(displayChapters, bookId!!, true)
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

                    BookRepository.getInstance()
                        .saveCollBookWithAsync(collBookBean)
                    startReadingContentTierFill(bookId, collBookBean)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    LogUtils.e(e)
                    if (isSourceEngineBookRequest(bookId, collBookBean)) {
                        AiBridgeTrace.event(
                            "source_read_catalog_retry",
                            collBookBean.title.orEmpty(),
                            AiBridgeTrace.fields(
                                "attempt" to attempt,
                                "error" to e.javaClass.simpleName,
                                "elapsedMs" to (System.currentTimeMillis() - startedAt)
                            )
                        )
                        delay(SOURCE_ENGINE_RETRY_DELAY_MS)
                        continue
                    }
                    _chapterErrorEvents.value = ++chapterErrorVersion
                    return@launch
                }
            }
        }
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
            val bootstrapChapters = BookContentProviderRouter.getReadingBootstrapChapters(
                bookId,
                collBookBean,
                READING_BOOTSTRAP_CHAPTERS
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
        currentChapterTitle: String?
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
                "prefetchPending" to pendingPrefetchChapters.size
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
            val chapterKey = chapterLoadKey(sourceBook, titleInBiquge)
            val isCurrentReadRequest = titleInBiquge == currentChapterTitle
            val request = ChapterLoadRequest(
                bookId,
                sourceBook,
                bookChapter,
                titleInBiquge,
                isCurrentReadRequest
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
                    bookChapter.title,
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
        if (!isSourceEngineBookRequest(bookId, collBookBean)) return
        if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) return
        if (v8RefreshJob?.isActive == true) {
            AiBridgeTrace.event(
                "source_read_v8_refresh_skipped",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields("reason" to "already_active", "bookId" to bookId.orEmpty())
            )
            return
        }
        AiBridgeTrace.event(
            "source_read_v8_refresh_started",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "bookId" to bookId.orEmpty(),
                "cached" to (collBookBean.getBookChapters()?.size ?: 0)
            )
        )
        v8RefreshJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val ready = try {
                BookContentProviderRouter.prepareBookContentTier(
                    bookId,
                    collBookBean,
                    persist = true,
                    triggerV8 = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled(),
                    requestPriority = SourceRequestPriority.BACKGROUND
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LogUtils.e(error)
                false
            }
            AiBridgeTrace.state(
                "source_read_v8_refresh_finished",
                collBookBean.title.orEmpty(),
                AiBridgeTrace.fields(
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
        v8RefreshJob?.cancel()
        super.onCleared()
    }

    private fun startReadingContentTierFill(bookId: String?, collBookBean: CollBookBean) {
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
                "persist" to true,
                "bookId" to bookId.orEmpty(),
                "cached" to (collBookBean.getBookChapters()?.size ?: 0)
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
                        "persist" to true,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                val ready = try {
                    BookContentProviderRouter.prepareBookContentTier(
                        bookId,
                        collBookBean,
                        persist = true,
                        triggerV8 = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled(),
                        requestPriority = SourceRequestPriority.BACKGROUND
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LogUtils.e(error)
                    false
                }
                if (ready) {
                    promoteCatalogAfterTierReady(bookId, collBookBean, startedAt)
                    AiBridgeTrace.state(
                        "source_read_tier_ready",
                        collBookBean.title.orEmpty(),
                        AiBridgeTrace.fields("attempt" to attempt, "durationMs" to (System.currentTimeMillis() - startedAt))
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

    private suspend fun promoteCatalogAfterTierReady(
        bookId: String?,
        collBookBean: CollBookBean,
        startedAt: Long
    ) {
        val currentSize = _categories.value?.bookChapterList?.size ?: (collBookBean.getBookChapters()?.size ?: 0)
        val refreshed = try {
            BookContentProviderRouter.getBookFolder(
                bookId,
                collBookBean,
                triggerV8ForReading = false
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LogUtils.e(error)
            return
        }
        if (refreshed.size <= currentSize) return
        val resolvedBookId = bookId ?: collBookBean.bookIdInBiquge ?: collBookBean.get_id() ?: return
        collBookBean.bookChapters = refreshed
        collBookBean.chaptersCount = refreshed.size
        collBookBean.lastChapter = refreshed.lastOrNull()?.title ?: collBookBean.lastChapter
        _categories.value = CategoryResult(refreshed, resolvedBookId, true)
        AiBridgeTrace.state(
            "source_read_catalog_tier_promoted",
            collBookBean.title.orEmpty(),
            AiBridgeTrace.fields(
                "from" to currentSize,
                "to" to refreshed.size,
                "last" to refreshed.lastOrNull()?.title.orEmpty(),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        BookRepository.getInstance().saveCollBookWithAsync(collBookBean)
    }

    private fun shouldRetainExistingSourceEngineCatalog(
        bookId: String?,
        collBookBean: CollBookBean,
        existingChapters: List<BookChapterBean>?,
        incomingChapters: List<BookChapterBean>
    ): Boolean {
        if (!isSourceEngineBookRequest(bookId, collBookBean)) return false
        if (existingChapters.isNullOrEmpty() || incomingChapters.isEmpty()) return false
        return existingChapters.size > incomingChapters.size
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
                startReadingContentTierFill(request.bookId, request.sourceBook)
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
                        BookRepository.getInstance().saveChapterInfo(request.bookId, request.title, content)
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

    private fun chapterLoadKey(sourceBook: CollBookBean, title: String): String {
        return sourceBook.get_id().orEmpty() + "#" + title
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
    }
}

private fun String.traceToken(): String {
    return replace(Regex("""[\s=:/\\#]+"""), "_").take(100)
}
