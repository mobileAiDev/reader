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
import com.ldp.reader.utils.BookCoverUrl
import com.ldp.reader.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class BookSearchProgressState(
    val query: String,
    val phase: BookSearchProgressPhase,
    val resultCount: Int,
    val elapsedMs: Long,
    val attempt: Int = 0,
    val nextDelayMs: Long = 0L,
    val checkedCount: Int = 0,
    val targetCount: Int = 0
)

enum class BookSearchProgressPhase {
    SEARCHING,
    FOUND_SEARCHING,
    PREPARING_READING,
    CHECKING_READING_SOURCE,
    WAITING_READING_SOURCE,
    READY,
    FINISHED,
    EMPTY,
    ERROR
}

class SearchViewModel : ViewModel() {
    private val _hotWords = MutableLiveData<List<String>>()
    private val _keyWords = MutableLiveData<List<String>>()
    private val _books = MutableLiveData<List<BookSearchResult>>()
    private val _bookSearchProgress = MutableLiveData<BookSearchProgressState>()
    private val _bookSearchErrors = MutableLiveData<Int>()
    private var bookSearchErrorVersion = 0
    private var keywordRequestVersion = 0
    private var bookRequestVersion = 0
    private var activeBookQuery: String? = null
    private var keywordJob: Job? = null
    private var bookJob: Job? = null
    private var coverRefreshJob: Job? = null
    private var contentTierJob: Job? = null
    private var contentTierJobKey: String = ""
    private var latestBookResults: List<BookSearchResult> = emptyList()
    private var bookSearchStartedAtMs = 0L
    private var bookSearchFinal = false
    private var bookTierPreparing = false
    private var bookTierReady = false

    val hotWords: LiveData<List<String>> = _hotWords
    val keyWords: LiveData<List<String>> = _keyWords
    val books: LiveData<List<BookSearchResult>> = _books
    val bookSearchProgress: LiveData<BookSearchProgressState> = _bookSearchProgress
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
        bookSearchFinal = false
        bookTierPreparing = false
        bookTierReady = false
        publishBookSearchProgress(trimmedQuery, 0, requestVersion, final = false)
        AiBridgeTrace.event(
            "source_search_ui_started",
            trimmedQuery,
            AiBridgeTrace.fields(
                "tag" to "search.ui",
                "stage" to "started",
                "version" to requestVersion,
                "queryLength" to trimmedQuery.length
            )
        )
        AiBridgeTrace.event(
            "source_search_ui_cleared",
            trimmedQuery,
            AiBridgeTrace.fields(
                "tag" to "search.ui",
                "stage" to "cleared",
                "version" to requestVersion,
                "reason" to "new_search"
            )
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
        if (final) bookSearchFinal = true
        val oldKey = searchResultsIdentityKey(latestBookResults)
        val oldDisplayKey = searchResultsDisplayKey(latestBookResults)
        val visibleBooks = mergeVisibleSearchResults(latestBookResults, books)
        val newKey = searchResultsIdentityKey(visibleBooks)
        val newDisplayKey = searchResultsDisplayKey(visibleBooks)
        if (newKey.isBlank() && !final) return
        if (newKey == oldKey && newDisplayKey == oldDisplayKey && !final) return
        latestBookResults = visibleBooks
        _books.postValue(visibleBooks)
        val elapsedMs = (System.currentTimeMillis() - bookSearchStartedAtMs).coerceAtLeast(0)
        AiBridgeTrace.event(
            "source_search_ui_publish",
            query,
            "tag_search.ui_stage_publish_count_${visibleBooks.size}_final_${final}_elapsedMs_${elapsedMs}" +
                "_top_${visibleBooks.take(3).joinToString("_") { book ->
                    "${book.title.orEmpty()}/${book.author.orEmpty()}".traceToken()
                }}" +
                "_display_${visibleBooks.take(3).joinToString("_") { book ->
                    "${book.title.orEmpty()}" +
                        "/cover_${hasSearchResultCover(book)}" +
                        "/intro_${!book.desc.isNullOrBlank()}" +
                        "/candidates_${book.coverCandidates.orEmpty().size}".traceToken()
                }}"
        )
        AiBridgeTrace.state(
            "source_search_ui_visible",
            query,
            "count_${visibleBooks.size}_final_${final}_elapsedMs_${elapsedMs}_top_${visibleBooks.take(3).joinToString("_") { book ->
                "${book.title.orEmpty()}/${book.author.orEmpty()}".traceToken()
            }}"
        )
        refreshSearchCovers(query, visibleBooks, requestVersion)
        startSearchContentTierFill(query, visibleBooks, requestVersion)
        publishBookSearchProgress(query, visibleBooks.size, requestVersion, final)
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
            bookTierPreparing = false
            bookTierReady = false
            AiBridgeTrace.event(
                "source_search_tier_skipped",
                query,
                AiBridgeTrace.fields("reason" to "no_source_engine_books", "books" to books.size, "version" to requestVersion)
            )
            return
        }
        val tierKey = "$requestVersion\n${searchResultsIdentityKey(sourceEngineBooks)}"
        if (contentTierJobKey == tierKey) {
            AiBridgeTrace.event(
                "source_search_tier_skipped",
                query,
                AiBridgeTrace.fields("reason" to "same_result_batch", "books" to sourceEngineBooks.size, "version" to requestVersion)
            )
            return
        }
        bookTierPreparing = true
        bookTierReady = false
        contentTierJob?.cancel()
        contentTierJobKey = tierKey
        AiBridgeTrace.event(
            "source_search_tier_started",
            query,
            AiBridgeTrace.fields("books" to sourceEngineBooks.size, "version" to requestVersion)
        )
        contentTierJob = viewModelScope.launch {
            delay(CONTENT_TIER_BACKGROUND_START_DELAY_MS)
            val startedAt = System.currentTimeMillis()
            var attempt = 0
            withTimeoutOrNull(SEARCH_TIER_BACKGROUND_TIMEOUT_MS) {
                var delayMs = CONTENT_TIER_INITIAL_BACKOFF_MS
                while (
                    requestVersion == bookRequestVersion &&
                    activeBookQuery == query
                ) {
                    attempt += 1
                    publishBookSearchProgress(
                        query,
                        latestBookResults.size,
                        requestVersion,
                        bookSearchFinal,
                        phaseOverride = BookSearchProgressPhase.CHECKING_READING_SOURCE,
                        attempt = attempt,
                        targetCount = sourceEngineBooks.size
                    )
                    AiBridgeTrace.event(
                        "source_search_tier_attempt",
                        query,
                        AiBridgeTrace.fields(
                            "attempt" to attempt,
                            "books" to sourceEngineBooks.size,
                            "elapsedMs" to (System.currentTimeMillis() - startedAt)
                        )
                    )
                    var checkedBooks = 0
                    var allReady = true
                    for ((index, book) in sourceEngineBooks.withIndex()) {
                        publishBookSearchProgress(
                            query,
                            latestBookResults.size,
                            requestVersion,
                            bookSearchFinal,
                            phaseOverride = BookSearchProgressPhase.CHECKING_READING_SOURCE,
                            attempt = attempt,
                            checkedCount = index,
                            targetCount = sourceEngineBooks.size
                        )
                        val ready = try {
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
                        checkedBooks = index + 1
                        publishBookSearchProgress(
                            query,
                            latestBookResults.size,
                            requestVersion,
                            bookSearchFinal,
                            phaseOverride = BookSearchProgressPhase.CHECKING_READING_SOURCE,
                            attempt = attempt,
                            checkedCount = checkedBooks,
                            targetCount = sourceEngineBooks.size
                        )
                        if (!ready) {
                            allReady = false
                            break
                        }
                    }
                    if (allReady) {
                        bookTierPreparing = false
                        bookTierReady = true
                        publishBookSearchProgress(query, latestBookResults.size, requestVersion, bookSearchFinal)
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
                    publishBookSearchProgress(
                        query,
                        latestBookResults.size,
                        requestVersion,
                        bookSearchFinal,
                        phaseOverride = BookSearchProgressPhase.WAITING_READING_SOURCE,
                        attempt = attempt,
                        nextDelayMs = delayMs,
                        checkedCount = checkedBooks,
                        targetCount = sourceEngineBooks.size
                    )
                    delaySearchTierRetry(
                        query,
                        requestVersion,
                        delayMs,
                        attempt,
                        checkedBooks,
                        sourceEngineBooks.size
                    )
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
            if (!bookTierReady) {
                bookTierPreparing = false
                publishBookSearchProgress(query, latestBookResults.size, requestVersion, bookSearchFinal)
            }
        }
    }

    private suspend fun delaySearchTierRetry(
        query: String,
        requestVersion: Int,
        totalDelayMs: Long,
        attempt: Int,
        checkedCount: Int,
        targetCount: Int
    ) {
        var remainingMs = totalDelayMs
        while (
            remainingMs > 0L &&
            requestVersion == bookRequestVersion &&
            activeBookQuery == query &&
            bookTierPreparing &&
            !bookTierReady
        ) {
            val stepMs = remainingMs.coerceAtMost(SEARCH_PROGRESS_WAIT_UPDATE_MS)
            delay(stepMs)
            remainingMs -= stepMs
            if (
                remainingMs > 0L &&
                requestVersion == bookRequestVersion &&
                activeBookQuery == query &&
                bookTierPreparing &&
                !bookTierReady
            ) {
                publishBookSearchProgress(
                    query,
                    latestBookResults.size,
                    requestVersion,
                    bookSearchFinal,
                    phaseOverride = BookSearchProgressPhase.WAITING_READING_SOURCE,
                    attempt = attempt,
                    nextDelayMs = remainingMs,
                    checkedCount = checkedCount,
                    targetCount = targetCount
                )
            }
        }
    }

    private fun publishBookSearchProgress(
        query: String,
        resultCount: Int,
        requestVersion: Int,
        final: Boolean,
        phaseOverride: BookSearchProgressPhase? = null,
        attempt: Int = 0,
        nextDelayMs: Long = 0L,
        checkedCount: Int = 0,
        targetCount: Int = 0
    ) {
        if (requestVersion != bookRequestVersion || activeBookQuery != query) return
        val elapsedMs = (System.currentTimeMillis() - bookSearchStartedAtMs).coerceAtLeast(0)
        val phase = phaseOverride ?: when {
            resultCount <= 0 && final -> BookSearchProgressPhase.EMPTY
            resultCount <= 0 -> BookSearchProgressPhase.SEARCHING
            bookTierPreparing && !bookTierReady -> BookSearchProgressPhase.PREPARING_READING
            final -> BookSearchProgressPhase.FINISHED
            bookTierReady -> BookSearchProgressPhase.READY
            else -> BookSearchProgressPhase.FOUND_SEARCHING
        }
        _bookSearchProgress.postValue(
            BookSearchProgressState(
                query = query,
                phase = phase,
                resultCount = resultCount,
                elapsedMs = elapsedMs,
                attempt = attempt,
                nextDelayMs = nextDelayMs,
                checkedCount = checkedCount,
                targetCount = targetCount
            )
        )
        AiBridgeTrace.state(
            "source_search_ui_progress",
            query,
            AiBridgeTrace.fields(
                "phase" to phase.name.lowercase(),
                "count" to resultCount,
                "elapsedMs" to elapsedMs,
                "attempt" to attempt,
                "nextDelayMs" to nextDelayMs,
                "checkedCount" to checkedCount,
                "targetCount" to targetCount
            )
        )
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
                    "tag" to "search.ui",
                    "stage" to "cancelled",
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
        contentTierJobKey = ""
        latestBookResults = emptyList()
        bookSearchFinal = false
        bookTierPreparing = false
        bookTierReady = false
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
        private const val SEARCH_TIER_BACKGROUND_LIMIT = 32
        private const val SEARCH_PROGRESSIVE_VISIBLE_LIMIT = 30
        private const val SEARCH_TIER_BACKGROUND_TIMEOUT_MS = 180_000L
        private const val CONTENT_TIER_BACKGROUND_START_DELAY_MS = 1_500L
        private const val CONTENT_TIER_INITIAL_BACKOFF_MS = 2_000L
        private const val CONTENT_TIER_MAX_BACKOFF_MS = 30_000L
        private const val SEARCH_PROGRESS_WAIT_UPDATE_MS = 5_000L

        internal fun mergeVisibleSearchResults(
            previous: List<BookSearchResult>,
            next: List<BookSearchResult>
        ): List<BookSearchResult> {
            if (previous.isEmpty()) return rankVisibleSearchResults(next).take(SEARCH_PROGRESSIVE_VISIBLE_LIMIT)
            if (next.isEmpty()) return rankVisibleSearchResults(previous).take(SEARCH_PROGRESSIVE_VISIBLE_LIMIT)
            val previousByKey = LinkedHashMap<String, BookSearchResult>()
            previous.forEach { book ->
                previousByKey[progressiveSearchResultKey(book)] = book
            }
            val nextKeys = next.mapTo(LinkedHashSet()) { book -> progressiveSearchResultKey(book) }
            if (previousByKey.keys.none { key -> key in nextKeys }) {
                return rankVisibleSearchResults(previous + next).take(SEARCH_PROGRESSIVE_VISIBLE_LIMIT)
            }
            val merged = ArrayList<BookSearchResult>(previous.size + next.size)
            val consumed = LinkedHashSet<String>()
            next.forEach { book ->
                val key = progressiveSearchResultKey(book)
                merged.add(previousByKey[key]?.let { previousBook ->
                    stableVisibleSearchResult(previousBook, book)
                } ?: book)
                consumed.add(key)
            }
            previous.forEach { oldBook ->
                val key = progressiveSearchResultKey(oldBook)
                if (consumed.add(key)) {
                    merged.add(previousByKey[key] ?: oldBook)
                }
            }
            return rankVisibleSearchResults(merged).take(SEARCH_PROGRESSIVE_VISIBLE_LIMIT)
        }

        private fun rankVisibleSearchResults(books: List<BookSearchResult>): List<BookSearchResult> {
            return books
                .mapIndexed { index, book -> index to book }
                .sortedWith(
                    compareByDescending<Pair<Int, BookSearchResult>> { (_, book) -> book.sourceCount }
                        .thenBy { (index, _) -> index }
                )
                .map { (_, book) -> book }
        }

        private fun stableVisibleSearchResult(
            previous: BookSearchResult,
            incoming: BookSearchResult
        ): BookSearchResult {
            val stableCover = stableSearchCover(previous, incoming)
            val stableCandidates = stableSearchCoverCandidates(stableCover, previous, incoming)
            val stableDesc = incoming.desc?.takeIf { it.isNotBlank() }
                ?: previous.desc
            val incomingDowngradesDisplay = searchResultDisplayDowngraded(previous, incoming)
            return BookSearchResult().apply {
                routeId = if (incomingDowngradesDisplay) {
                    previous.routeId ?: incoming.routeId
                } else {
                    incoming.routeId ?: previous.routeId
                }
                title = incoming.title?.takeIf { it.isNotBlank() } ?: previous.title
                author = incoming.author?.takeIf { it.isNotBlank() } ?: previous.author
                cover = stableCover
                coverCandidates = stableCandidates
                desc = stableDesc
                sources = incoming.sources ?: previous.sources
                sourceCount = maxOf(previous.sourceCount, incoming.sourceCount)
            }
        }

        private fun searchResultDisplayDowngraded(
            previous: BookSearchResult,
            incoming: BookSearchResult
        ): Boolean {
            val coverDowngraded = searchResultCoverScore(incoming) < searchResultCoverScore(previous)
            val introDowngraded = incoming.desc.isNullOrBlank() && !previous.desc.isNullOrBlank()
            return coverDowngraded || introDowngraded
        }

        private fun stableSearchCover(
            previous: BookSearchResult,
            incoming: BookSearchResult
        ): String {
            val previousCover = BookCoverUrl.clean(previous.cover)
            val incomingCover = BookCoverUrl.clean(incoming.cover)
            return when {
                BookCoverUrl.isLikelyImage(previousCover) -> previousCover
                BookCoverUrl.isLikelyImage(incomingCover) -> incomingCover
                BookCoverUrl.isUsable(previousCover) -> previousCover
                BookCoverUrl.isUsable(incomingCover) -> incomingCover
                else -> ""
            }
        }

        private fun stableSearchCoverCandidates(
            stableCover: String,
            previous: BookSearchResult,
            incoming: BookSearchResult
        ): List<String> {
            return (
                listOf(stableCover, previous.cover, incoming.cover) +
                    previous.coverCandidates.orEmpty() +
                    incoming.coverCandidates.orEmpty()
                )
                .map { cover -> BookCoverUrl.clean(cover) }
                .filter { cover -> BookCoverUrl.isUsable(cover) }
                .distinct()
        }

        private fun searchResultCoverScore(book: BookSearchResult): Int {
            val covers = listOf(book.cover) + book.coverCandidates.orEmpty()
            return when {
                covers.any { cover -> BookCoverUrl.isLikelyImage(cover) } -> 2
                covers.any { cover -> BookCoverUrl.isUsable(cover) } -> 1
                else -> 0
            }
        }

        private fun hasSearchResultCover(book: BookSearchResult): Boolean {
            return searchResultCoverScore(book) > 0
        }

        private fun progressiveSearchResultKey(book: BookSearchResult): String {
            val title = normalizeProgressiveSearchToken(book.title)
            val author = normalizeProgressiveSearchToken(book.author)
            return if (title.isNotBlank() || author.isNotBlank()) {
                "$title\n$author"
            } else {
                book.routeId.orEmpty()
            }
        }

        private fun normalizeProgressiveSearchToken(value: String?): String {
            return value.orEmpty()
                .lowercase()
                .replace('靈', '灵')
                .replace('書', '书')
                .replace('霧', '雾')
                .replace(Regex("""作者[:：]\s*"""), "")
                .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+"""), "")
                .trim()
        }
    }
}
