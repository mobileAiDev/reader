package com.ldp.reader.ui.activity

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SearchViewModel : ViewModel() {
    private val _hotWords = MutableLiveData<List<String>>()
    private val _keyWords = MutableLiveData<List<String>>()
    private val _books = MutableLiveData<List<BookSearchResult>>()
    private val _bookSearchErrors = MutableLiveData<Int>()
    private var bookSearchErrorVersion = 0
    private var keywordRequestVersion = 0
    private var bookRequestVersion = 0
    private var activeBookQuery: String? = null
    private var keywordJob: Job? = null
    private var bookJob: Job? = null
    private var coverRefreshJob: Job? = null
    private var contentTierJob: Job? = null
    private var latestBookResults: List<BookSearchResult> = emptyList()
    private var bookSearchStartedAtMs = 0L

    val hotWords: LiveData<List<String>> = _hotWords
    val keyWords: LiveData<List<String>> = _keyWords
    val books: LiveData<List<BookSearchResult>> = _books
    val bookSearchErrors: LiveData<Int> = _bookSearchErrors

    fun searchHotWord() {
        viewModelScope.launch {
            try {
                val bean = BookContentProviderRouter.searchHotWords()
                _hotWords.value = bean
                Log.d("+bean", bean.toString())
                LogUtils.e(bean)
            } catch (e: Throwable) {
                LogUtils.e(e)
            }
        }
    }

    fun searchKeyWord(query: String?) {
        val trimmedQuery = query?.trim().orEmpty()
        cancelActiveBookSearch("keyword-input")
        val requestVersion = ++keywordRequestVersion
        keywordJob?.cancel()
        keywordJob = viewModelScope.launch {
            try {
                val bean = BookContentProviderRouter.searchKeyWords(trimmedQuery)
                Log.d("+bean", bean.toString())
                if (requestVersion == keywordRequestVersion && activeBookQuery == null) {
                    _keyWords.value = bean
                }
                LogUtils.d("+bean", bean)
            } catch (e: Throwable) {
                LogUtils.e(e)
            }
        }
    }

    fun searchBook(query: String?) {
        val trimmedQuery = query?.trim().orEmpty()
        Log.d(TAG, "searchBook: $trimmedQuery")
        keywordJob?.cancel()
        keywordRequestVersion++
        cancelActiveBookSearch("new-search")
        activeBookQuery = trimmedQuery
        val requestVersion = ++bookRequestVersion
        latestBookResults = emptyList()
        bookSearchStartedAtMs = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_search_ui_started",
            trimmedQuery,
            AiBridgeTrace.fields("version" to requestVersion, "queryLength" to trimmedQuery.length)
        )
        AiBridgeTrace.event(
            "source_search_ui_cleared",
            trimmedQuery,
            AiBridgeTrace.fields("version" to requestVersion, "reason" to "new_search")
        )
        bookJob = viewModelScope.launch {
            try {
                val books = BookContentProviderRouter.searchBooksProgressively(trimmedQuery) { update ->
                    publishSearchBooks(trimmedQuery, update, requestVersion)
                }
                publishSearchBooks(trimmedQuery, books, requestVersion, final = true)
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                LogUtils.e(throwable)
                if (requestVersion == bookRequestVersion && activeBookQuery == trimmedQuery) {
                    _bookSearchErrors.postValue(++bookSearchErrorVersion)
                }
            }
        }
    }

    private fun publishSearchBooks(
        query: String,
        books: List<BookSearchResult>,
        requestVersion: Int,
        final: Boolean = false
    ) {
        if (requestVersion != bookRequestVersion || activeBookQuery != query) return
        val oldKey = searchResultsIdentityKey(latestBookResults)
        val newKey = searchResultsIdentityKey(books)
        if (newKey.isBlank() && !final) return
        if (newKey == oldKey && !(final && books.isEmpty())) return
        latestBookResults = books
        _books.postValue(books)
        val elapsedMs = (System.currentTimeMillis() - bookSearchStartedAtMs).coerceAtLeast(0)
        AiBridgeTrace.event(
            "source_search_ui_publish",
            query,
            "count_${books.size}_final_${final}_elapsedMs_${elapsedMs}" +
                "_top_${books.take(3).joinToString("_") { book ->
                    "${book.title.orEmpty()}/${book.author.orEmpty()}".traceToken()
                }}"
        )
        AiBridgeTrace.state(
            "source_search_ui_visible",
            query,
            "count_${books.size}_final_${final}_elapsedMs_${elapsedMs}_top_${books.take(3).joinToString("_") { book ->
                "${book.title.orEmpty()}/${book.author.orEmpty()}".traceToken()
            }}"
        )
        refreshSearchCovers(query, books, requestVersion)
        startSearchContentTierFill(query, books, requestVersion)
    }

    private fun startSearchContentTierFill(
        query: String,
        books: List<BookSearchResult>,
        requestVersion: Int
    ) {
        val sourceEngineBooks = books
            .filter { book -> SourceEngineBookRoute.isBookId(book.routeId) }
            .take(SEARCH_TIER_BACKGROUND_LIMIT)
        if (sourceEngineBooks.isEmpty()) {
            AiBridgeTrace.event(
                "source_search_tier_skipped",
                query,
                AiBridgeTrace.fields("reason" to "no_source_engine_books", "books" to books.size, "version" to requestVersion)
            )
            return
        }
        contentTierJob?.cancel()
        AiBridgeTrace.event(
            "source_search_tier_started",
            query,
            AiBridgeTrace.fields("books" to sourceEngineBooks.size, "version" to requestVersion)
        )
        contentTierJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var attempt = 0
            withTimeoutOrNull(SEARCH_TIER_BACKGROUND_TIMEOUT_MS) {
                var delayMs = CONTENT_TIER_INITIAL_BACKOFF_MS
                while (
                    requestVersion == bookRequestVersion &&
                    activeBookQuery == query
                ) {
                    attempt += 1
                    AiBridgeTrace.event(
                        "source_search_tier_attempt",
                        query,
                        AiBridgeTrace.fields(
                            "attempt" to attempt,
                            "books" to sourceEngineBooks.size,
                            "elapsedMs" to (System.currentTimeMillis() - startedAt)
                        )
                    )
                    val allReady = sourceEngineBooks.all { book ->
                        try {
                            BookContentProviderRouter.prepareBookContentTier(
                                book.routeId,
                                book.toCollBookBean(),
                                persist = false
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            LogUtils.e(error)
                            false
                        }
                    }
                    if (allReady) {
                        AiBridgeTrace.state(
                            "source_search_tier_ready",
                            query,
                            AiBridgeTrace.fields(
                                "attempt" to attempt,
                                "books" to sourceEngineBooks.size,
                                "durationMs" to (System.currentTimeMillis() - startedAt)
                            )
                        )
                        return@withTimeoutOrNull
                    }
                    AiBridgeTrace.event(
                        "source_search_tier_retry",
                        query,
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
            AiBridgeTrace.event(
                "source_search_tier_finished",
                query,
                AiBridgeTrace.fields(
                    "version" to requestVersion,
                    "attempts" to attempt,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
        }
    }

    private fun refreshSearchCovers(
        query: String,
        books: List<BookSearchResult>,
        requestVersion: Int
    ) {
        coverRefreshJob?.cancel()
        coverRefreshJob = viewModelScope.launch {
            try {
                val refreshed = BookContentProviderRouter.refreshSearchCovers(query, books)
                if (
                    requestVersion == bookRequestVersion &&
                    activeBookQuery == query &&
                    searchResultsIdentityKey(latestBookResults) == searchResultsIdentityKey(books) &&
                    searchResultsDisplayKey(refreshed) != searchResultsDisplayKey(books)
                ) {
                    latestBookResults = refreshed
                    _books.postValue(refreshed)
                }
            } catch (throwable: Throwable) {
                LogUtils.e(throwable)
            }
        }
    }

    private fun searchResultsIdentityKey(books: List<BookSearchResult>): String {
        return books.joinToString("\n") { book ->
            book.routeId ?: "${book.title.orEmpty()}\t${book.author.orEmpty()}"
        }
    }

    private fun searchResultsDisplayKey(books: List<BookSearchResult>): String {
        return books.joinToString("\n") { book ->
            listOf(
                book.routeId.orEmpty(),
                book.title.orEmpty(),
                book.author.orEmpty(),
                book.cover.orEmpty(),
                book.coverCandidates.orEmpty().joinToString("|"),
                book.desc.orEmpty()
            ).joinToString("\t")
        }
    }

    override fun onCleared() {
        cancelActiveBookWork()
        super.onCleared()
    }

    fun cancelActiveBookWork() {
        cancelActiveBookSearch("leave")
        keywordRequestVersion++
        keywordJob?.cancel()
        keywordJob = null
    }

    private fun cancelActiveBookSearch(reason: String) {
        val cancelledQuery = activeBookQuery
        if (cancelledQuery != null) {
            AiBridgeTrace.event(
                "source_search_ui_cancelled",
                cancelledQuery,
                AiBridgeTrace.fields(
                    "reason" to reason,
                    "nextVersion" to (bookRequestVersion + 1),
                    "bookActive" to (bookJob?.isActive == true),
                    "coverActive" to (coverRefreshJob?.isActive == true),
                    "tierActive" to (contentTierJob?.isActive == true)
                )
            )
        }
        activeBookQuery = null
        bookRequestVersion++
        cancelBookSearchJobs()
    }

    private fun cancelBookSearchJobs() {
        bookJob?.cancel()
        bookJob = null
        coverRefreshJob?.cancel()
        coverRefreshJob = null
        contentTierJob?.cancel()
        contentTierJob = null
        latestBookResults = emptyList()
    }

    private fun BookSearchResult.toCollBookBean(): CollBookBean {
        return CollBookBean().apply {
            set_id(routeId)
            bookIdInBiquge = routeId
            title = this@toCollBookBean.title
            author = this@toCollBookBean.author
            cover = this@toCollBookBean.cover
            shortIntro = this@toCollBookBean.desc
        }
    }

    private fun String.traceToken(): String {
        return replace(Regex("""[\s=:/\\#]+"""), "_").take(80)
    }

    companion object {
        private val TAG = SearchViewModel::class.java.simpleName
        private const val SEARCH_TIER_BACKGROUND_LIMIT = 30
        private const val SEARCH_TIER_BACKGROUND_TIMEOUT_MS = 180_000L
        private const val CONTENT_TIER_INITIAL_BACKOFF_MS = 2_000L
        private const val CONTENT_TIER_MAX_BACKOFF_MS = 30_000L
    }
}
