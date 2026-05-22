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
import com.ldp.reader.source.SourceEngineBookRoute
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
    private var bookIdInBiquge: String? = ""

    val categories: LiveData<CategoryResult> = _categories
    val chapterFinishedEvents: LiveData<Boolean> = _chapterFinishedEvents
    val chapterErrorEvents: LiveData<Int> = _chapterErrorEvents

    fun loadCategory(bookId: String?, collBookBean: CollBookBean) {
        Log.d(TAG, "loadCategory: $bookId$collBookBean")
        categoryJob?.cancel()
        contentTierJob?.cancel()
        AiBridgeTrace.event(
            "source_read_catalog_started",
            collBookBean.title.orEmpty(),
            "bookId_${bookId.orEmpty().traceToken()}"
        )
        categoryJob = viewModelScope.launch {
            while (true) {
                try {
                    val bookChapterBeans = BookContentProviderRouter.getBookFolder(bookId, collBookBean)
                    collBookBean.bookChapters = bookChapterBeans
                    collBookBean.chaptersCount = bookChapterBeans.size
                    collBookBean.lastChapter = bookChapterBeans.lastOrNull()?.title ?: collBookBean.lastChapter
                    Log.d(TAG, "accept: $bookChapterBeans")
                    _categories.value = CategoryResult(bookChapterBeans, bookId!!, true)
                    AiBridgeTrace.state(
                        "source_read_catalog_ready",
                        collBookBean.title.orEmpty(),
                        "chapters_${bookChapterBeans.size}_last_${bookChapterBeans.lastOrNull()?.title.orEmpty().traceToken()}"
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
                            "error_${e.javaClass.simpleName.traceToken()}"
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

    @Synchronized
    fun loadChapter(
        bookId: String?,
        sourceBook: CollBookBean,
        bookChapterList: List<TxtChapter>,
        currentChapterTitle: String?
    ) {
        val size = bookChapterList.size
        Log.e(TAG, "loadChapter  列表大小$size $bookChapterList")

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
            val request = ChapterLoadRequest(bookId, sourceBook, bookChapter, titleInBiquge)
            if (titleInBiquge == currentChapterTitle) {
                AiBridgeTrace.event(
                    "source_read_current_chapter_queued",
                    sourceBook.title.orEmpty(),
                    "chapter_${titleInBiquge.traceToken()}_total_${bookChapterList.size}"
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
                val content = BookContentProviderRouter.getBookContent(
                    bookId,
                    sourceBook,
                    bookChapter,
                    sourceIndex
                )
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

    override fun onCleared() {
        categoryJob?.cancel()
        currentChapterJob?.cancel()
        currentChapterJob = null
        prefetchJobs.values.forEach { job -> job.cancel() }
        prefetchJobs.clear()
        pendingPrefetchChapters.clear()
        refreshChapterJob?.cancel()
        contentTierJob?.cancel()
        super.onCleared()
    }

    private fun startReadingContentTierFill(bookId: String?, collBookBean: CollBookBean) {
        if (!isSourceEngineBookRequest(bookId, collBookBean)) return
        contentTierJob?.cancel()
        AiBridgeTrace.event(
            "source_read_tier_started",
            collBookBean.title.orEmpty(),
            "persist_true_bookId_${bookId.orEmpty().traceToken()}"
        )
        contentTierJob = viewModelScope.launch {
            var delayMs = SOURCE_ENGINE_TIER_INITIAL_BACKOFF_MS
            while (true) {
                val ready = try {
                    BookContentProviderRouter.prepareBookContentTier(
                        bookId,
                        collBookBean,
                        persist = true
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LogUtils.e(error)
                    false
                }
                if (ready) return@launch
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(SOURCE_ENGINE_TIER_MAX_BACKOFF_MS)
            }
        }
    }

    private fun startCurrentChapterLoad(chapterKey: String, request: ChapterLoadRequest) {
        if (chapterKey == currentChapterKey && currentChapterJob?.isActive == true) {
            return
        }
        currentChapterJob?.cancel()
        contentTierJob?.cancel()
        currentChapterKey = chapterKey
        cancelPrefetchLoads()
        AiBridgeTrace.event(
            "source_read_current_chapter_started",
            request.sourceBook.title.orEmpty(),
            "chapter_${request.title.traceToken()}"
        )
        currentChapterJob = startChapterLoad(
            request = request,
            notifyError = true
        ) {
            if (currentChapterKey == chapterKey) {
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
            return
        }
        if (prefetchJobs.size + pendingPrefetchChapters.size >= PREFETCH_CHAPTER_LIMIT) {
            return
        }
        pendingPrefetchChapters[chapterKey] = request
        drainPrefetchQueue()
    }

    private fun drainPrefetchQueue() {
        if (currentChapterJob?.isActive == true) {
            return
        }
        while (prefetchJobs.size < MAX_PREFETCH_CONCURRENT_CHAPTERS && pendingPrefetchChapters.isNotEmpty()) {
            val entry = pendingPrefetchChapters.entries.first()
            pendingPrefetchChapters.remove(entry.key)
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
            try {
                while (true) {
                    try {
                        val content = BookContentProviderRouter.getBookContent(
                            request.bookId,
                            request.sourceBook,
                            request.bookChapter,
                            0
                        )
                        BookRepository.getInstance().saveChapterInfo(request.bookId, request.title, content)
                        Log.e("+chapterBody", "title${request.title} $content")
                        AiBridgeTrace.state(
                            "source_read_chapter_saved",
                            "${request.sourceBook.title.orEmpty()}#${request.title}",
                            "chapter_${request.title.traceToken()}_notify_${notifyError}_chars_${content.length}"
                        )
                        _chapterFinishedEvents.value = false
                        return@launch
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        LogUtils.e(t)
                        if (notifyError && SourceEngineBookRoute.isChapterId(request.bookChapter.link)) {
                            AiBridgeTrace.event(
                                "source_read_chapter_retry",
                                request.sourceBook.title.orEmpty(),
                                "chapter_${request.title.traceToken()}_error_${t.javaClass.simpleName.traceToken()}"
                            )
                            delay(SOURCE_ENGINE_RETRY_DELAY_MS)
                            continue
                        }
                        if (notifyError) {
                            AiBridgeTrace.state(
                                "source_read_chapter_error",
                                request.sourceBook.title.orEmpty(),
                                "chapter_${request.title.traceToken()}_error_${t.javaClass.simpleName.traceToken()}"
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

    private data class ChapterLoadRequest(
        val bookId: String?,
        val sourceBook: CollBookBean,
        val bookChapter: TxtChapter,
        val title: String
    )

    companion object {
        private val TAG = ReadViewModel::class.java.simpleName
        private const val PREFETCH_CHAPTER_LIMIT = 5
        private const val MAX_PREFETCH_CONCURRENT_CHAPTERS = 1
        private const val SOURCE_ENGINE_RETRY_DELAY_MS = 2_000L
        private const val SOURCE_ENGINE_TIER_INITIAL_BACKOFF_MS = 2_000L
        private const val SOURCE_ENGINE_TIER_MAX_BACKOFF_MS = 30_000L
    }
}

private fun String.traceToken(): String {
    return replace(Regex("""[\s=:/\\#]+"""), "_").take(100)
}
