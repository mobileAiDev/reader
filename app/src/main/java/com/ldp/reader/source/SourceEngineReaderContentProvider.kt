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
import com.ldp.reader.sourceengine.legado.JdkHttpFetcher
import com.ldp.reader.sourceengine.legado.LegadoSourceEngine
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.CanonicalChapter
import com.ldp.reader.sourceengine.model.CanonicalChapterList
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceBookDetail
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.sourceengine.search.BookSearchRanker
import com.ldp.reader.sourceengine.search.RankedSearchBook
import com.ldp.reader.sourceengine.search.SearchCandidate
import com.ldp.reader.utils.BookCoverUrl
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.widget.page.TxtChapter
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit

class SourceEngineReaderContentProvider : ReaderContentProvider {
    override val providerName: String = "source-engine"
    private val engine = LegadoSourceEngine(fetcher = JdkHttpFetcher(3000, 5000))
    private val searchEngine = LegadoSourceEngine(fetcher = JdkHttpFetcher(1200, 2200))
    private val detailProbeEngine = LegadoSourceEngine(fetcher = JdkHttpFetcher(1200, 2200))
    private val searchRanker = BookSearchRanker()
    private val chapterNormalizer = ChapterNormalizer()
    private val tailBoundaryLocator = CatalogTailBoundaryLocator(MAX_CATALOG_TAIL_BACKTRACK_CHAPTERS)
    private val catalogTailTrimCache = Collections.synchronizedMap(mutableMapOf<String, Int>())

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

    override suspend fun searchBooks(query: String?): List<BookSearchResult> = withContext(Dispatchers.IO) {
        val keyword = query?.trim().orEmpty()
        if (keyword.isBlank()) return@withContext emptyList()
        val startedAt = System.currentTimeMillis()
        val candidates = Collections.synchronizedList(ArrayList<SearchCandidate>())
        val sources = prioritizedSearchSources(SourceEngineRuntime.compatibleSources()).take(MAX_SEARCH_SOURCES)
        val semaphore = Semaphore(MAX_CONCURRENT_SEARCHES)

        val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val searchQueries = searchQueriesFor(keyword)
            val jobs = sources.flatMapIndexed { sourceIndex, source ->
                searchQueries.map { searchQuery ->
                    searchScope.async {
                        semaphore.withPermit {
                            val search = when (val value = searchEngine.search(listOf(source), searchQuery, maxSources = 1)) {
                                is EngineResult.Success -> value.value
                                is EngineResult.Failure -> return@withPermit
                            }
                            search.books.take(MAX_RESULTS_PER_SOURCE).forEachIndexed { resultIndex, book ->
                                val candidate = SearchCandidate(
                                    book = book,
                                    sourceIndex = sourceIndex,
                                    resultIndex = resultIndex,
                                    searchQuery = searchQuery
                                )
                                if (searchRanker.score(keyword, candidate).score > 0) {
                                    synchronized(candidates) {
                                        candidates.add(candidate)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val completed = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                jobs.awaitAll()
                true
            } ?: false
            if (!completed) {
                jobs.forEach { it.cancel() }
            }
        } finally {
            searchScope.coroutineContext.cancelChildren()
        }

        val candidateSnapshot = synchronized(candidates) { candidates.toList() }
        val candidateCount = candidateSnapshot.size
        Log.i(
            TAG,
            "operation=searchCandidates provider=$providerName key=$keyword " +
                "rawCandidates=$candidateCount elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        val ranked = rankSearchCandidates(keyword, candidateSnapshot)
        Log.i(
            TAG,
            "operation=searchRanked provider=$providerName key=$keyword " +
                "ranked=${ranked.size} elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        val output = ranked.map { rankedBook ->
            val book = rankedBook.book
            BookSearchResult().apply {
                routeId = SourceEngineBookRoute.bookId(book)
                title = searchRanker.displayTitle(book)
                author = cleanAuthor(book.author)
                cover = BookCoverUrl.clean(book.coverUrl).takeIf { BookCoverUrl.isLikelyImage(it) }.orEmpty()
                desc = cleanIntro(book.intro)
            }
        }
        Log.i(
            TAG,
            "operation=searchCompleted provider=$providerName key=$keyword " +
                "rawCandidates=$candidateCount count=${output.size} durationMs=${System.currentTimeMillis() - startedAt} " +
                "top=${ranked.take(5).joinToString(" | ") { it.debugLabel() }}"
        )
        output
    }

    private fun searchQueriesFor(keyword: String): List<String> {
        return buildList {
            add(keyword)
            addAll(titleAliasQueries(keyword))
            addAll(completedTitleQueries(keyword).take(MAX_COMPLETION_SEARCH_QUERIES))
            addAll(completedAuthorTitleQueries(keyword).take(MAX_AUTHOR_COMPLETION_SEARCH_QUERIES))
        }.distinct()
    }

    private fun titleAliasQueries(keyword: String): List<String> {
        val raw = keyword.trim()
        val normalizedKeyword = normalizeHint(raw)
        return buildList {
            if (raw.contains("仙路")) {
                add(raw.replace("仙路", "仙途"))
            }
            KNOWN_TITLE_ALIAS_SEARCHES[normalizedKeyword]?.let { addAll(it) }
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

    private fun completedAuthorTitleQueries(keyword: String): List<String> {
        val normalizedKeyword = normalizeHint(keyword)
        if (normalizedKeyword.length < MIN_COMPLETION_QUERY_CHARS) return emptyList()
        return KNOWN_BOOK_AUTHORS
            .filter { (_, author) -> normalizeHint(author) == normalizedKeyword }
            .keys
            .toList()
    }

    private fun normalizeHint(value: String): String {
        return value
            .lowercase()
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+"""), "")
            .trim()
    }

    private fun knownAuthorMatchScore(book: SourceBook): Int {
        val expectedAuthor = KNOWN_BOOK_AUTHORS[normalizeHint(book.name)] ?: return 0
        return if (normalizeHint(book.author).contains(normalizeHint(expectedAuthor))) 1 else 0
    }

    private suspend fun rankSearchCandidates(
        keyword: String,
        candidates: List<SearchCandidate>
    ): List<ValidatedSearchCandidate> {
        val scored = searchRanker.scoreCandidates(keyword, candidates)
        if (scored.isEmpty()) return emptyList()

        val topTitleGroups = scored
            .groupBy { ranked -> searchRanker.canonicalTitleKey(ranked.book) }
            .values
            .map { group -> group.sortedWith(rankedSearchComparator) }
            .sortedWith(compareByDescending<List<RankedSearchBook>> { group -> group.first().score }
                .thenBy { group -> group.first().book.name.length })
            .take(MAX_VALIDATION_TITLE_GROUPS)

        val validated = topTitleGroups
            .flatMap { group -> validationCandidatesForTitle(group) }
            .map { ranked -> fallbackValidatedSearchCandidate(ranked) }
        return validated
            .groupBy { candidate -> searchRanker.canonicalTitleKey(candidate.book) }
            .values
            .map { group -> mergeValidatedTitleGroup(group) }
            .sortedWith(validatedSearchComparator)
            .take(MAX_SEARCH_RESULTS)
    }

    private fun mergeValidatedTitleGroup(group: List<ValidatedSearchCandidate>): ValidatedSearchCandidate {
        val readingCandidate = group.minWith(
            compareByDescending<ValidatedSearchCandidate> { knownAuthorMatchScore(it.book) }
                .then(readingCandidateComparator)
        )
        val coverCandidate = if (readingCandidate.coverQuality.usable) {
            readingCandidate
        } else {
            group
                .filter { candidate -> candidate.coverQuality.usable }
                .minWithOrNull(coverCandidateComparator)
        }
        val selectedCoverQuality = coverCandidate?.coverQuality ?: readingCandidate.coverQuality
        val selectedBook = if (coverCandidate != null && coverCandidate.book.coverUrl != readingCandidate.book.coverUrl) {
            readingCandidate.book.copy(coverUrl = coverCandidate.book.coverUrl)
        } else {
            readingCandidate.book
        }
        val score = group.maxOf { candidate -> candidate.ranked.score } +
            catalogScoreForCount(readingCandidate.chapterCount) +
            coverScore(selectedCoverQuality)
        return readingCandidate.copy(
            book = selectedBook,
            score = score,
            coverQuality = selectedCoverQuality,
            validation = readingCandidate.validation + "+merged-cover"
        )
    }

    private fun validationCandidatesForTitle(group: List<RankedSearchBook>): List<RankedSearchBook> {
        return (group.take(MAX_VALIDATION_PER_TITLE) +
            group.filter { ranked -> BookCoverUrl.isLikelyImage(ranked.book.coverUrl) }
                .take(MAX_VALIDATION_COVER_FALLBACK_PER_TITLE))
            .distinctBy { ranked ->
                ranked.book.source.sourceUrl + "\n" + ranked.book.bookUrl
            }
    }

    private suspend fun validateSearchCandidate(ranked: RankedSearchBook): ValidatedSearchCandidate {
        val fallback = fallbackValidatedSearchCandidate(ranked)
        return withTimeoutOrNull(SEARCH_VALIDATION_TIMEOUT_MS) {
            val detail = when (val value = engine.getBookDetail(ranked.book)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> return@withTimeoutOrNull fallback.copy(
                    score = fallback.score - DETAIL_FAILURE_PENALTY,
                    validation = "detail-failed"
                )
            }
            val catalog = when (val value = engine.getCanonicalChapterList(detail)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> null
            }
            val enrichedBook = detail.toSearchBook(ranked.book)
            val chapterCount = catalog?.chapters?.size ?: 0
            val coverQuality = inspectCoverQuality(enrichedBook)
            val score = ranked.score +
                coverScore(coverQuality) +
                catalogScore(catalog) +
                detailAgreementScore(ranked.book, detail)
            ValidatedSearchCandidate(
                ranked = ranked,
                book = enrichedBook,
                score = score,
                chapterCount = chapterCount,
                duplicateCount = catalog?.duplicateCount ?: 0,
                coverQuality = coverQuality,
                validation = if (catalog == null) "detail-only" else "detail-catalog"
            )
        } ?: fallback
    }

    private fun fallbackValidatedSearchCandidate(ranked: RankedSearchBook): ValidatedSearchCandidate {
        val hasCoverUrl = BookCoverUrl.isLikelyImage(ranked.book.coverUrl)
        return ValidatedSearchCandidate(
            ranked = ranked,
            book = ranked.book,
            score = ranked.score - UNVALIDATED_RESULT_PENALTY,
            chapterCount = 0,
            duplicateCount = 0,
            coverQuality = if (hasCoverUrl) {
                CoverQuality(true, MIN_COVER_WIDTH, MIN_COVER_HEIGHT, "url-only")
            } else {
                CoverQuality(false, 0, 0, "unvalidated")
            },
            validation = "unvalidated"
        )
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
            .replace(Regex("""^作者[:：]?\s*"""), "")
            .trim()
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

    private fun catalogScore(catalog: CanonicalChapterList?): Int {
        val chapterCount = catalog?.chapters?.size ?: 0
        return catalogScoreForCount(chapterCount)
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
        sources: List<BookSource>
    ): List<BookSource> {
        return sources.sortedWith(
            compareBy<BookSource> { source ->
                sourcePriorityIndex(source)
            }.thenBy { source ->
                SOURCE_NEGATIVE_MARKERS.count { marker ->
                    source.sourceName.contains(marker) || source.sourceGroup.orEmpty().contains(marker)
                }
            }.thenBy { source ->
                source.sourceName.length
            }
        )
    }

    private suspend fun resolveReadableBook(sourceBook: SourceBook): ResolvedSourceBook {
        val direct = loadReadableBookWithTimeout(sourceBook, engine, DETAIL_DIRECT_TIMEOUT_MS)
        if (direct != null && direct.catalog.chapters.size >= PREFERRED_CATALOG_CHAPTERS) {
            return direct
        }
        val fallback = findReadableFallback(sourceBook)
        val best = listOfNotNull(direct, fallback).sortedWith(resolvedBookComparator).firstOrNull()
        if (best != null) {
            if (best.routeId != direct?.routeId) {
                Log.i(
                    TAG,
                    "operation=detailFallbackResolved provider=$providerName " +
                        "title=${sourceBook.name} from=${sourceLabel(sourceBook)} " +
                        "to=${sourceLabel(best.book)} chapters=${best.catalog.chapters.size}"
                )
            }
            return best
        }
        error("Source-engine readable source failed: ${sourceBook.name} ${sourceBook.bookUrl}")
    }

    private suspend fun findReadableFallback(sourceBook: SourceBook): ResolvedSourceBook? {
        val ranked = fallbackCandidatesFor(sourceBook).take(MAX_DETAIL_FALLBACK_CANDIDATES)
        if (ranked.isEmpty()) return null
        Log.i(
            TAG,
            "operation=detailFallbackCandidates provider=$providerName title=${sourceBook.name} " +
                "count=${ranked.size} top=${ranked.take(8).joinToString(" | ") { fallbackDebugLabel(it) }}"
        )

        val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
            val resolved = withTimeoutOrNull(DETAIL_FALLBACK_PROBE_TIMEOUT_MS) {
                probes.awaitAll().filterNotNull()
            }.orEmpty()
            Log.i(
                TAG,
                "operation=detailFallbackProbes provider=$providerName title=${sourceBook.name} " +
                    "resolved=${resolved.size} top=${resolved.take(8).joinToString(" | ") { (_, book) -> resolvedDebugLabel(book) }}"
            )
            resolved
                .sortedWith(
                    compareByDescending<Pair<Int, ResolvedSourceBook>> { it.second.catalog.chapters.size }
                        .thenByDescending { knownAuthorMatchScore(it.second.book) }
                        .thenBy { sourcePriorityIndex(it.second.book.source) }
                        .thenBy { it.first }
                )
                .firstOrNull()
                ?.second
        } finally {
            probeScope.coroutineContext.cancelChildren()
        }
    }

    private val resolvedBookComparator = compareByDescending<ResolvedSourceBook> { it.catalog.chapters.size }
        .thenByDescending { knownAuthorMatchScore(it.book) }
        .thenBy { sourcePriorityIndex(it.book.source) }

    private fun sourcePriorityIndex(source: BookSource): Int {
        return SOURCE_NAME_PRIORITY.indexOfFirst { marker -> source.sourceName.contains(marker) }
            .let { index -> if (index >= 0) index else SOURCE_NAME_PRIORITY.size }
    }

    private suspend fun fallbackCandidatesFor(sourceBook: SourceBook): List<RankedSearchBook> {
        val sources = prioritizedSearchSources(SourceEngineRuntime.compatibleSources()).take(MAX_DETAIL_FALLBACK_SOURCES)
        val candidates = Collections.synchronizedList(ArrayList<SearchCandidate>())
        val semaphore = Semaphore(MAX_DETAIL_FALLBACK_CONCURRENT_SEARCHES)
        val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val jobs = sources.mapIndexed { sourceIndex, source ->
                searchScope.async {
                    semaphore.withPermit {
                        val search = when (val value = searchEngine.search(listOf(source), sourceBook.name, maxSources = 1)) {
                            is EngineResult.Success -> value.value
                            is EngineResult.Failure -> return@withPermit
                        }
                        search.books.take(MAX_DETAIL_FALLBACK_RESULTS_PER_SOURCE).forEachIndexed { resultIndex, book ->
                            if (isSameBookCandidate(sourceBook, book)) {
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
            searchScope.coroutineContext.cancelChildren()
        }
        return searchRanker.scoreCandidates(sourceBook.name, synchronized(candidates) { candidates.toList() })
            .filter { ranked ->
                ranked.book.source.sourceUrl != sourceBook.source.sourceUrl || ranked.book.bookUrl != sourceBook.bookUrl
            }
            .sortedWith(
                compareByDescending<RankedSearchBook> { knownAuthorMatchScore(it.book) }
                    .thenByDescending { estimatedChapterOrdinal(it.book) }
                    .thenByDescending { it.score }
                    .thenBy { it.sourceIndex }
                    .thenBy { it.resultIndex }
            )
    }

    private fun estimatedChapterOrdinal(book: SourceBook): Int {
        return listOf(book.lastChapter, book.kind, book.intro)
            .asSequence()
            .map { text -> chapterNormalizer.normalize(text).ordinal ?: numericChapterHint(text) }
            .filter { it in 1..10_000 }
            .maxOrNull() ?: 0
    }

    private fun numericChapterHint(text: String): Int {
        return Regex("""(?<![\d.])(\d{2,5})(?![\d.])""")
            .findAll(text)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
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

    private fun isSameBookCandidate(target: SourceBook, candidate: SourceBook): Boolean {
        val targetTitle = searchRanker.canonicalTitleKey(target)
        if (targetTitle.isBlank() || searchRanker.canonicalTitleKey(candidate) != targetTitle) {
            return false
        }
        val expectedAuthor = normalizeHint(KNOWN_BOOK_AUTHORS[targetTitle] ?: target.author)
        if (expectedAuthor.isBlank()) return true
        val candidateAuthor = normalizeHint(candidate.author)
        return candidateAuthor.isBlank() ||
            candidateAuthor.contains(expectedAuthor) ||
            expectedAuthor.contains(candidateAuthor)
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
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val deferred = scope.async { block() }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
            scope.coroutineContext.cancelChildren()
        }
    }

    private fun sourceLabel(book: SourceBook): String {
        return "${book.source.sourceName}@${book.source.sourceUrl}"
    }

    override suspend fun getBookInfo(bookId: String?): BookDetailBeanInOwn = withContext(Dispatchers.IO) {
        val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
        val source = SourceEngineRuntime.findSource(route.sourceUrl)
        val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
            val resolved = resolveReadableBook(sourceBook)
            val detail = resolved.detail
            val canonicalChapters = tailReadableChapters(resolved)
            BookDetailBeanInOwn().apply {
            routeId = resolved.routeId
            shelfBookId = SourceEngineBookRoute.shelfBookId(resolved.book)
            this.bookId = resolved.routeId.hashCode()
            title = detail.name
            author = cleanAuthor(detail.author)
            cover = selectVerifiedCover(detail.coverUrl, resolved.book.coverUrl)
            desc = cleanIntro(detail.intro)
            lastChapter = canonicalChapters.lastOrNull()?.displayTitle ?: detail.lastChapter
            chaptersCount = canonicalChapters.size
            updateTime = System.currentTimeMillis()
            Log.i(
                TAG,
                "operation=detailResolved provider=$providerName title=$title author=$author " +
                    "chapters=${canonicalChapters.size} cover=${!cover.isNullOrBlank()} " +
                    "lastChapter=$lastChapter"
            )
        }
    }

    private fun selectVerifiedCover(primary: String?, fallback: String?): String {
        val primaryUrl = BookCoverUrl.clean(primary)
        if (inspectCoverUrl(primaryUrl).usable) return primaryUrl
        val fallbackUrl = BookCoverUrl.clean(fallback)
        if (inspectCoverUrl(fallbackUrl).usable) return fallbackUrl
        return BookCoverUrl.bestLikelyImage(primaryUrl, fallbackUrl)
    }

    override suspend fun getBookFolder(bookId: String?, collBookBean: CollBookBean): List<BookChapterBean> =
        withContext(Dispatchers.IO) {
            val route = SourceEngineBookRoute.decodeBookId(requireNotNull(bookId))
            val source = SourceEngineRuntime.findSource(route.sourceUrl)
            val sourceBook = SourceEngineBookRoute.toSourceBook(source, route)
            val resolved = resolveReadableBook(sourceBook)
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

    override suspend fun getBookContent(
        bookId: String?,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        sourceIndex: Int
    ): String = withContext(Dispatchers.IO) {
        val route = SourceEngineBookRoute.decodeChapterId(requireNotNull(bookChapter.link))
        val source = SourceEngineRuntime.findSource(route.sourceUrl)
        val chapter = SourceEngineBookRoute.toSourceChapter(source, route)
        when (val value = engine.getCleanContent(chapter)) {
            is EngineResult.Success -> {
                val content = value.value
                if (!isReadableContent(content)) {
                    val fallback = findReadableContentFallback(chapter, sourceBook, bookChapter, content)
                    if (fallback != null) return@withContext fallback.cleanedContent
                    failContentQuality(content)
                }
                Log.i(
                    TAG,
                    "operation=contentResolved provider=$providerName book=${sourceBook.title} " +
                        "chapter=${bookChapter.title} score=${content.report.qualityScore} " +
                        "coherence=${content.report.coherenceScore} cleaned=${content.report.cleanedLength} " +
                        "markers=${content.report.coherenceMarkers.joinToString().ifBlank { "none" }}"
                )
                content.cleanedContent
            }
            is EngineResult.Failure -> {
                val fallback = findReadableContentFallback(chapter, sourceBook, bookChapter, null)
                if (fallback != null) return@withContext fallback.cleanedContent
                error("Source-engine content failed: ${value.failure}")
            }
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

    private suspend fun findReadableContentFallback(
        chapter: SourceChapter,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        rejectedContent: CleanContent?
    ): CleanContent? {
        val candidates = fallbackCandidatesFor(chapter.book).take(MAX_CONTENT_FALLBACK_CANDIDATES)
        if (candidates.isEmpty()) return null
        val targetTitle = chapterNormalizer.normalize(chapter.name)
        Log.i(
            TAG,
            "operation=contentFallbackCandidates provider=$providerName book=${sourceBook.title} " +
                "chapter=${bookChapter.title} rejectedScore=${rejectedContent?.report?.qualityScore} " +
                "rejectedCoherence=${rejectedContent?.report?.coherenceScore} candidates=${candidates.size}"
        )
        val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val semaphore = Semaphore(MAX_CONTENT_FALLBACK_CONCURRENT_PROBES)
        return try {
            val probes = candidates.mapIndexed { order, candidate ->
                probeScope.async {
                    semaphore.withPermit {
                        val resolved = loadReadableBookWithTimeout(
                            candidate.book,
                            detailProbeEngine,
                            CONTENT_FALLBACK_DETAIL_TIMEOUT_MS
                        ) ?: return@withPermit null
                        val fallbackChapter = matchingChapter(resolved.catalog, targetTitle.key, targetTitle.ordinal)
                            ?: return@withPermit null
                        val content = runDetachedWithTimeout(CONTENT_FALLBACK_CONTENT_TIMEOUT_MS) {
                            when (val value = engine.getCleanContent(fallbackChapter)) {
                                is EngineResult.Success -> value.value
                                is EngineResult.Failure -> null
                            }
                        } ?: return@withPermit null
                        if (!isReadableContent(content)) return@withPermit null
                        ContentFallback(order, resolved, fallbackChapter, content)
                    }
                }
            }
            val resolved = withTimeoutOrNull(CONTENT_FALLBACK_TOTAL_TIMEOUT_MS) {
                probes.awaitAll().filterNotNull()
            }.orEmpty()
            val best = resolved.sortedWith(contentFallbackComparator).firstOrNull()
            if (best != null) {
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

    private fun matchingChapter(
        catalog: CanonicalChapterList,
        targetKey: String,
        targetOrdinal: Int?
    ): SourceChapter? {
        val byKey = catalog.chapters.firstOrNull { chapter -> chapter.key == targetKey }
        if (byKey != null) return byKey.sourceChapters.firstOrNull()
        if (targetOrdinal != null) {
            return catalog.chapters.firstOrNull { chapter -> chapter.ordinal == targetOrdinal }
                ?.sourceChapters
                ?.firstOrNull()
        }
        return null
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

        val probe = withTimeoutOrNull(CATALOG_TAIL_TOTAL_TIMEOUT_MS) {
            locateCatalogTailBoundary(chapters)
        } ?: CatalogTailProbeResult(
            keepUntil = chapters.size,
            checkedCount = 0,
            method = "timeout"
        )
        val keepUntil = probe.keepUntil.coerceIn(0, chapters.size)

        if (keepUntil < chapters.size) {
            catalogTailTrimCache[cacheKey] = keepUntil
            Log.w(
                TAG,
                "operation=catalogTailTrimmed provider=$providerName title=${resolved.detail.name} " +
                    "rawChapters=${chapters.size} kept=$keepUntil removed=${chapters.size - keepUntil} " +
                    "checked=${probe.checkedCount} method=${probe.method} " +
                    "lastKept=${chapters.getOrNull(keepUntil - 1)?.displayTitle} " +
                    "firstRemoved=${chapters.getOrNull(keepUntil)?.displayTitle}"
            )
        } else {
            catalogTailTrimCache[cacheKey] = chapters.size
        }
        return chapters.take(keepUntil)
    }

    private suspend fun locateCatalogTailBoundary(chapters: List<CanonicalChapter>): CatalogTailProbeResult {
        return tailBoundaryLocator.locate(chapters.size) { index ->
            val sourceChapter = chapters[index].sourceChapters.firstOrNull()
            sourceChapter != null &&
                loadCleanContentWithTimeout(sourceChapter, CATALOG_TAIL_CONTENT_TIMEOUT_MS)
                    ?.let { isReadableContent(it) } == true
        }
    }

    private fun tailTrimCacheKey(resolved: ResolvedSourceBook): String {
        val chapters = resolved.catalog.chapters
        return listOf(
            resolved.routeId,
            chapters.size.toString(),
            chapters.lastOrNull()?.displayTitle.orEmpty()
        ).joinToString("\n")
    }

    private suspend fun loadCleanContentWithTimeout(
        chapter: SourceChapter,
        timeoutMs: Long
    ): CleanContent? {
        return runDetachedWithTimeout(timeoutMs) {
            when (val value = engine.getCleanContent(chapter)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> null
            }
        }
    }

    private data class ValidatedSearchCandidate(
        val ranked: RankedSearchBook,
        val book: SourceBook,
        val score: Int,
        val chapterCount: Int,
        val duplicateCount: Int,
        val coverQuality: CoverQuality,
        val validation: String
    ) {
        fun debugLabel(): String {
            return "${book.name}/${book.author}/${book.source.sourceName}" +
                "/score=$score/sources=${ranked.sourceCount}/chapters=$chapterCount" +
                "/cover=${coverQuality.usable}(${coverQuality.width}x${coverQuality.height},${coverQuality.reason})" +
                "/coverUrl=${book.coverUrl}/$validation"
        }
    }

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

    private data class ContentFallback(
        val order: Int,
        val resolved: ResolvedSourceBook,
        val chapter: SourceChapter,
        val content: CleanContent
    )

    companion object {
        private const val TAG = "BookContentProvider"
        private const val MAX_SEARCH_SOURCES = 48
        private const val MAX_SEARCH_RESULTS = 20
        private const val MAX_RESULTS_PER_SOURCE = 8
        private const val MAX_KEYWORD_SUGGESTIONS = 8
        private const val MAX_COMPLETION_SEARCH_QUERIES = 2
        private const val MAX_AUTHOR_COMPLETION_SEARCH_QUERIES = 4
        private const val MIN_COMPLETION_QUERY_CHARS = 2
        private const val MAX_CONCURRENT_SEARCHES = 16
        private const val SEARCH_TIMEOUT_MS = 10_000L
        private const val DETAIL_DIRECT_TIMEOUT_MS = 8_000L
        private const val DETAIL_PROBE_TIMEOUT_MS = 7_000L
        private const val DETAIL_FALLBACK_SEARCH_TIMEOUT_MS = 18_000L
        private const val DETAIL_FALLBACK_PROBE_TIMEOUT_MS = 17_000L
        private const val MAX_DETAIL_FALLBACK_SOURCES = 1_000
        private const val MAX_DETAIL_FALLBACK_CANDIDATES = 32
        private const val MAX_DETAIL_FALLBACK_RESULTS_PER_SOURCE = 16
        private const val MAX_DETAIL_FALLBACK_CONCURRENT_SEARCHES = 24
        private const val MAX_DETAIL_FALLBACK_CONCURRENT_PROBES = 16
        private const val MAX_CONTENT_FALLBACK_CANDIDATES = 32
        private const val MAX_CONTENT_FALLBACK_CONCURRENT_PROBES = 8
        private const val CONTENT_FALLBACK_DETAIL_TIMEOUT_MS = 6_000L
        private const val CONTENT_FALLBACK_CONTENT_TIMEOUT_MS = 6_000L
        private const val CONTENT_FALLBACK_TOTAL_TIMEOUT_MS = 24_000L
        private const val MAX_CATALOG_TAIL_BACKTRACK_CHAPTERS = 2048
        private const val CATALOG_TAIL_CONTENT_TIMEOUT_MS = 2_500L
        private const val CATALOG_TAIL_TOTAL_TIMEOUT_MS = 90_000L
        private const val MIN_READABLE_CATALOG_CHAPTERS = 5
        private const val PREFERRED_CATALOG_CHAPTERS = 500
        private const val MAX_VALIDATION_TITLE_GROUPS = 12
        private const val MAX_VALIDATION_PER_TITLE = 5
        private const val MAX_VALIDATION_COVER_FALLBACK_PER_TITLE = 3
        private const val MAX_CONCURRENT_VALIDATIONS = 12
        private const val SEARCH_VALIDATION_TIMEOUT_MS = 6_000L
        private const val UNVALIDATED_RESULT_PENALTY = 300
        private const val DETAIL_FAILURE_PENALTY = 600
        private const val COVER_PRESENT_BONUS = 350
        private const val MISSING_COVER_PENALTY = -450
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
        private const val COVER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"
        private val coverHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
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
        private val readingCandidateComparator = compareByDescending<ValidatedSearchCandidate> { it.chapterCount }
            .thenByDescending { it.score }
            .thenBy { it.ranked.sourceIndex }
            .thenBy { it.ranked.resultIndex }
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
        private val SOURCE_NAME_PRIORITY = listOf(
            "55读书",
            "笔趣",
            "新笔趣",
            "顶点",
            "69书",
            "八一",
            "书海",
            "零点",
            "起点"
        )
        private val SOURCE_NEGATIVE_MARKERS = listOf(
            "同人",
            "成人",
            "情色",
            "福利",
            "黄",
            "H"
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
        private val KNOWN_BOOK_AUTHORS = mapOf(
            "斗破苍穹" to "天蚕土豆",
            "诡秘之主" to "爱潜水的乌贼",
            "凡人修仙传" to "忘语",
            "遮天" to "辰东",
            "完美世界" to "辰东",
            "牧神记" to "宅猪",
            "大奉打更人" to "卖报小郎君",
            "剑来" to "烽火戏诸侯",
            "雪中悍刀行" to "烽火戏诸侯",
            "庆余年" to "猫腻",
            "将夜" to "猫腻",
            "择天记" to "猫腻",
            "全职高手" to "蝴蝶蓝",
            "盗墓笔记" to "南派三叔",
            "鬼吹灯" to "天下霸唱",
            "斗罗大陆" to "唐家三少",
            "神印王座" to "唐家三少",
            "星辰变" to "我吃西红柿",
            "吞噬星空" to "我吃西红柿",
            "盘龙" to "我吃西红柿",
            "武动乾坤" to "天蚕土豆",
            "元尊" to "天蚕土豆",
            "大主宰" to "天蚕土豆",
            "一念永恒" to "耳根",
            "仙逆" to "耳根"
        )
        private val KNOWN_TITLE_ALIAS_SEARCHES = mapOf(
            "灵源仙路" to listOf(
                "灵源仙途",
                "灵源仙途：我养的灵兽太懂感恩了"
            )
        )
    }
}
