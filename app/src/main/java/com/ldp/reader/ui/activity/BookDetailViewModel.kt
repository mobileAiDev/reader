package com.ldp.reader.ui.activity

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.bean.DirectSycBookShelfBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.remote.RemoteRepository
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.source.SourceEngineMetadataCleaner
import com.ldp.reader.ui.fragment.BookShelfViewModel
import com.ldp.reader.utils.BookCoverUrl
import com.ldp.reader.utils.BookIdentity
import com.ldp.reader.utils.LogUtils
import com.ldp.reader.utils.SharedPreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class BookDetailViewModel : ViewModel() {
    private val _bookDetails = MutableLiveData<BookDetailBeanInOwn>()
    private val _refreshErrors = MutableLiveData<Int>()
    private val _bookShelfAddWaitEvents = MutableLiveData<Int>()
    private val _bookShelfAddErrorEvents = MutableLiveData<Int>()
    private val _bookShelfAddSuccessEvents = MutableLiveData<Int>()
    private var refreshErrorVersion = 0
    private var bookShelfAddWaitVersion = 0
    private var bookShelfAddErrorVersion = 0
    private var bookShelfAddSuccessVersion = 0
    private var bookId: String? = null
    private var refreshJob: Job? = null
    private var addToShelfJob: Job? = null
    private var contentTierJob: Job? = null

    val bookDetails: LiveData<BookDetailBeanInOwn> = _bookDetails
    val refreshErrors: LiveData<Int> = _refreshErrors
    val bookShelfAddWaitEvents: LiveData<Int> = _bookShelfAddWaitEvents
    val bookShelfAddErrorEvents: LiveData<Int> = _bookShelfAddErrorEvents
    val bookShelfAddSuccessEvents: LiveData<Int> = _bookShelfAddSuccessEvents

    fun refreshBookDetail(bookId: String?) {
        this.bookId = bookId
        refreshBook()
    }

    fun addToBookShelf(collBook: CollBookBean?) {
        val collBookBean = collBook!!
        BookRepository.getInstance()
            .saveCollBookWithAsync(collBookBean)
        Log.d(TAG, "addToBookShelf: $bookId")
        _bookShelfAddWaitEvents.value = ++bookShelfAddWaitVersion
        addToShelfJob?.cancel()
        addToShelfJob = viewModelScope.launch {
            try {
                val bookChapterBeans = BookContentProviderRouter.getBookFolder(bookId, collBookBean)
                collBookBean.bookChapters = bookChapterBeans
                collBookBean.chaptersCount = bookChapterBeans.size
                BookRepository.getInstance()
                    .saveCollBookWithAsync(collBookBean)
                _bookShelfAddSuccessEvents.value = ++bookShelfAddSuccessVersion
                val collBookBeanResult = BookRepository.getInstance().getCollBook(collBookBean.get_id())
                Log.d(TAG, "addToBookShelf:collBookBeanResult $collBookBeanResult")

                synBookShelf()
            } catch (e: Throwable) {
                _bookShelfAddErrorEvents.value = ++bookShelfAddErrorVersion
            }
        }
    }

    private fun synBookShelf() {
        val collBooks = BookRepository.getInstance().collBooks
        val bookIds = BookShelfViewModel.onlineBookIdsFrom(collBooks)
        if ("password" == SharedPreUtils.getInstance().getString("loginType")) {
            setBookShelf(bookIds)
        } else {
            val mobile = SharedPreUtils.getInstance().getString("userName")
            val mobileToken = SharedPreUtils.getInstance().getString("token")
            setBookShelfByMobile(bookIds, mobile, mobileToken)
        }
    }

    private fun setBookShelf(bookIds: List<String>) {
        val body = Gson().toJson(BookShelfViewModel.normalizeServerBookIds(bookIds)).toRequestBody(JSON)
        val token = SharedPreUtils.getInstance().getString("token")
        viewModelScope.launch {
            try {
                RemoteRepository.getInstance().setBookShelf(token, body)
            } catch (e: Throwable) {
            }
        }
    }

    private fun setBookShelfByMobile(bookIds: List<String>, mobile: String?, mobileToken: String?) {
        val directSycBookShelfBean = DirectSycBookShelfBean()
        directSycBookShelfBean.bookIds = BookShelfViewModel.normalizeServerBookIds(bookIds)
        directSycBookShelfBean.mobile = mobile
        directSycBookShelfBean.mobileToken = mobileToken
        val body = Gson().toJson(directSycBookShelfBean).toRequestBody(JSON)
        viewModelScope.launch {
            try {
                RemoteRepository.getInstance().setBookShelfByMobile(body)
            } catch (e: Throwable) {
            }
        }
    }

    private fun refreshBook() {
        refreshJob?.cancel()
        contentTierJob?.cancel()
        val startedAt = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_detail_refresh_started",
            bookId.orEmpty(),
            AiBridgeTrace.fields("sourceRoute" to SourceEngineBookRoute.isBookId(bookId))
        )
        val preliminaryPublished = publishPreliminarySourceEngineDetail(bookId)
        refreshJob = viewModelScope.launch {
            try {
                val detail = BookContentProviderRouter.getBookInfo(bookId)
                preserveSourceEngineCachedIntro(detail)
                bookId = detail.routeId ?: bookId
                _bookDetails.value = detail
                AiBridgeTrace.state(
                    "source_detail_refresh_ready",
                    detail.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "author" to detail.author.orEmpty(),
                        "route" to detail.routeId.orEmpty(),
                        "cover" to BookCoverUrl.isLikelyImage(detail.cover),
                        "intro" to !detail.desc.isNullOrBlank(),
                        "chapters" to detail.chaptersCount,
                        "last" to detail.lastChapter.orEmpty(),
                        "durationMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                startDetailContentTierFill(detail)
            } catch (e: Throwable) {
                if (preliminaryPublished && SourceEngineBookRoute.isBookId(bookId)) {
                    AiBridgeTrace.state(
                        "source_detail_background_error",
                        _bookDetails.value?.title.orEmpty(),
                        AiBridgeTrace.fields(
                            "error" to e.javaClass.simpleName,
                            "elapsedMs" to (System.currentTimeMillis() - startedAt)
                        )
                    )
                    LogUtils.e(e)
                    return@launch
                }
                _refreshErrors.value = ++refreshErrorVersion
            }
        }
    }

    private fun publishPreliminarySourceEngineDetail(routeId: String?): Boolean {
        if (!SourceEngineBookRoute.isBookId(routeId)) return false
        val route = runCatching { SourceEngineBookRoute.decodeBookId(routeId!!) }.getOrNull() ?: return false
        val routeLastChapter = SourceEngineMetadataCleaner.cleanText(route.lastChapter).ifBlank { null }
        val detail = BookDetailBeanInOwn().apply {
            this.routeId = routeId
            shelfBookId = BookIdentity.sourceEngineShelfId(route.name, route.author)
            bookId = routeId.hashCode()
            title = route.name
            author = route.author
            cover = BookCoverUrl.clean(route.coverUrl)
            coverCandidates = SourceEngineBookRoute.coverCandidates(route)
            desc = stableSourceEngineIntro(
                shelfBookId = shelfBookId,
                title = route.name,
                author = route.author,
                incomingIntro = route.intro
            )
            lastChapter = routeLastChapter
            chaptersCount = 0
            updateTime = System.currentTimeMillis()
        }
        _bookDetails.value = detail
        AiBridgeTrace.state(
            "source_detail_preliminary",
            route.name,
            AiBridgeTrace.fields(
                "author" to route.author,
                "cover" to BookCoverUrl.isLikelyImage(detail.cover),
                "intro" to !detail.desc.isNullOrBlank(),
                "routeLast" to route.lastChapter,
                "lastPolicy" to if (routeLastChapter.isNullOrBlank()) "waiting_for_catalog" else "route_payload"
            )
        )
        return true
    }

    private fun preserveSourceEngineCachedIntro(detail: BookDetailBeanInOwn) {
        if (!SourceEngineBookRoute.isBookId(detail.routeId)) return
        val shelfId = detail.shelfBookId
            ?: BookIdentity.sourceEngineShelfId(detail.title.orEmpty(), detail.author.orEmpty())
        detail.desc = stableSourceEngineIntro(
            shelfBookId = shelfId,
            title = detail.title,
            author = detail.author,
            incomingIntro = detail.desc
        )
    }

    private fun stableSourceEngineIntro(
        shelfBookId: String?,
        title: String?,
        author: String?,
        incomingIntro: String?
    ): String {
        val cachedIntro = shelfBookId
            ?.let { id -> BookRepository.getInstance().getCollBook(id)?.shortIntro }
        val currentIntro = _bookDetails.value
            ?.takeIf { detail -> sameSourceEngineBook(detail, shelfBookId, title, author) }
            ?.desc
        return listOf(cachedIntro, currentIntro, incomingIntro)
            .map { intro -> cleanIntroForDisplay(intro) }
            .firstOrNull { intro -> intro.isNotBlank() }
            .orEmpty()
    }

    private fun sameSourceEngineBook(
        detail: BookDetailBeanInOwn,
        shelfBookId: String?,
        title: String?,
        author: String?
    ): Boolean {
        if (!shelfBookId.isNullOrBlank() && detail.shelfBookId == shelfBookId) return true
        return !title.isNullOrBlank() &&
            !author.isNullOrBlank() &&
            detail.title == title &&
            detail.author == author
    }

    private fun cleanIntroForDisplay(value: String?): String {
        return if (ReaderFeatureSwitches.isCleanIntroEnabled()) {
            SourceEngineMetadataCleaner.cleanIntro(value.orEmpty())
        } else {
            value.orEmpty().trim()
        }
    }

    private fun startDetailContentTierFill(detail: BookDetailBeanInOwn) {
        val routeId = detail.routeId ?: return
        if (!SourceEngineBookRoute.isBookId(routeId)) return
        val collBookBean = detail.collBookBean
        contentTierJob?.cancel()
        contentTierJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var attempt = 0
            var delayMs = CONTENT_TIER_INITIAL_BACKOFF_MS
            AiBridgeTrace.event(
                "source_detail_tier_started",
                detail.title.orEmpty(),
                AiBridgeTrace.fields("route" to routeId, "persist" to false)
            )
            while (bookId == routeId) {
                attempt += 1
                AiBridgeTrace.event(
                    "source_detail_tier_attempt",
                    detail.title.orEmpty(),
                    AiBridgeTrace.fields("attempt" to attempt, "elapsedMs" to (System.currentTimeMillis() - startedAt))
                )
                val ready = try {
                    BookContentProviderRouter.prepareBookContentTier(routeId, collBookBean, persist = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LogUtils.e(error)
                    false
                }
                if (ready) {
                    AiBridgeTrace.state(
                        "source_detail_tier_ready",
                        detail.title.orEmpty(),
                        AiBridgeTrace.fields("attempt" to attempt, "durationMs" to (System.currentTimeMillis() - startedAt))
                    )
                    refreshDetailAfterContentTier(routeId, detail, collBookBean)
                    return@launch
                }
                AiBridgeTrace.event(
                    "source_detail_tier_retry",
                    detail.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "attempt" to attempt,
                        "nextDelayMs" to delayMs,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(CONTENT_TIER_MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun refreshDetailAfterContentTier(
        routeId: String,
        detail: BookDetailBeanInOwn,
        collBookBean: CollBookBean
    ) {
        try {
            val startedAt = System.currentTimeMillis()
            AiBridgeTrace.event(
                "source_detail_verified_catalog_started",
                detail.title.orEmpty(),
                AiBridgeTrace.fields("route" to routeId)
            )
            val chapters = BookContentProviderRouter.getBookFolder(routeId, collBookBean)
            if (chapters.isEmpty()) return
            collBookBean.bookChapters = chapters
            collBookBean.chaptersCount = chapters.size
            collBookBean.lastChapter = chapters.lastOrNull()?.title ?: collBookBean.lastChapter
            detail.chaptersCount = chapters.size
            detail.lastChapter = SourceEngineMetadataCleaner.cleanText(collBookBean.lastChapter).ifBlank {
                detail.lastChapter
            }
            AiBridgeTrace.state(
                "source_detail_verified_catalog",
                detail.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapters" to chapters.size,
                    "first" to chapters.firstOrNull()?.title.orEmpty(),
                    "last" to detail.lastChapter.orEmpty(),
                    "route" to routeId,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            Log.i(
                TAG,
                "operation=detailVerifiedCatalog title=${detail.title} chapters=${chapters.size} " +
                    "lastChapter=${detail.lastChapter}"
            )
            _bookDetails.value = detail
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LogUtils.e(error)
        }
    }

    override fun onCleared() {
        cancelActiveBookWork()
        super.onCleared()
    }

    fun cancelActiveBookWork() {
        refreshJob?.cancel()
        addToShelfJob?.cancel()
        contentTierJob?.cancel()
    }

    companion object {
        private val TAG = BookDetailViewModel::class.java.simpleName
        private val JSON: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
        private const val CONTENT_TIER_INITIAL_BACKOFF_MS = 2_000L
        private const val CONTENT_TIER_MAX_BACKOFF_MS = 30_000L
    }
}

private fun String.traceToken(): String {
    return replace(Regex("""[\s=:/\\#]+"""), "_").take(100)
}
