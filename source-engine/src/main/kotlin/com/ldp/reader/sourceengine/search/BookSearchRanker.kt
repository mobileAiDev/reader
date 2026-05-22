package com.ldp.reader.sourceengine.search

import com.ldp.reader.sourceengine.model.SourceBook
import java.util.Locale

class BookSearchRanker {
    fun rank(
        keyword: String,
        candidates: List<SearchCandidate>,
        limit: Int
    ): List<RankedSearchBook> {
        val bestByBook = LinkedHashMap<String, RankedSearchBook>()
        scoreCandidates(keyword, candidates).forEach { scored ->
            val key = dedupeKey(scored.book)
            val existing = bestByBook[key]
            if (existing == null || rankedComparator.compare(scored, existing) < 0) {
                bestByBook[key] = scored
            }
        }

        return bestByBook.values
            .sortedWith(rankedComparator)
            .take(limit)
    }

    fun scoreCandidates(
        keyword: String,
        candidates: List<SearchCandidate>
    ): List<RankedSearchBook> {
        val query = normalizeQuery(keyword)
        if (query.isBlank()) return emptyList()

        val consensusByTitle = titleConsensusBySource(candidates)
        val hasTitleFieldMatches = candidates.any {
            !isHardRejected(it.book.name) && isTitleFieldMatch(query, it.book)
        }
        val hasCompetingAuthorFieldMatches = candidates.count {
            !isHardRejected(it.book.name) && isAuthorFieldMatch(query, it.book.author)
        } >= MIN_AUTHOR_QUERY_MATCHES
        val hasExactTitleMatches = candidates.any {
            !isHardRejected(it.book.name) && normalizeTitle(it.book) == query
        }
        return candidates.map { candidate ->
            score(keyword, candidate)
                .promoteByConsensus(consensusByTitle[normalizeTitle(candidate.book)] ?: 0)
                .demoteNonExactTitleWhenExactTitleExists(query, candidate, hasExactTitleMatches)
                .demoteTitleOnlyAuthorQueryHit(query, candidate, hasCompetingAuthorFieldMatches)
                .demoteAuthorOnlyTitleQueryHit(query, candidate, hasTitleFieldMatches && !hasCompetingAuthorFieldMatches)
        }.filter { scored -> scored.score >= minAcceptedScore(query) }
    }

    fun score(keyword: String, candidate: SearchCandidate): RankedSearchBook {
        if (isHardRejected(candidate.book.name)) {
            return RankedSearchBook(
                book = candidate.book,
                score = 0,
                evidence = "title:hard-rejected",
                sourceIndex = candidate.sourceIndex,
                resultIndex = candidate.resultIndex
            )
        }
        val query = normalizeQuery(keyword)
        val title = normalizeTitle(candidate.book)
        val author = normalizeAuthor(candidate.book.author)

        val titleScore = fieldScore(query, title, TITLE_WEIGHT, "title")
            .demote(
                derivativeTitlePenalty(query, title) +
                    prefixSeparatorDerivativePenalty(keyword, candidate.book.name)
            )
        val authorScore = fieldScore(query, author, AUTHOR_WEIGHT, "author")
        val best = listOf(titleScore, authorScore).maxByOrNull { it.score } ?: ScoreEvidence(0, "none")
        val sourcePenalty = (candidate.sourceIndex * SOURCE_ORDER_PENALTY)
            .plus(candidate.resultIndex * RESULT_ORDER_PENALTY)
            .coerceAtMost(MAX_ORDER_PENALTY)
        val completionBonus = completionTitleBonus(query, title, author, candidate.searchQuery)
        val metadataBonus = if (
            best.evidence.startsWith("title:") &&
            candidate.book.author.isNotBlank()
        ) {
            AUTHOR_PRESENT_BONUS
        } else {
            0
        }
        val score = (best.score + completionBonus + metadataBonus - sourcePenalty).coerceAtLeast(0)

        return RankedSearchBook(
            book = candidate.book,
            score = score,
            evidence = if (completionBonus > 0) "${best.evidence}:completion" else best.evidence,
            sourceIndex = candidate.sourceIndex,
            resultIndex = candidate.resultIndex
        )
    }

    fun displayTitle(book: SourceBook): String {
        val rawTitle = book.name.trim()
        val rawAuthor = book.author
            .replace(Regex("""^作者[:：]?\s*"""), "")
            .trim()
        if (rawTitle.isBlank() || rawAuthor.isBlank()) return rawTitle
        val cleaned = rawTitle
            .removeSuffix(rawAuthor)
            .trim()
            .trimEnd('-', '_', ':', '：', '·', ' ')
        return cleaned.takeIf { it.length >= MIN_MEANINGFUL_CHARS && it != rawTitle } ?: rawTitle
    }

    fun canonicalTitleKey(book: SourceBook): String {
        return normalizeTitle(book)
    }

    private fun fieldScore(
        query: String,
        field: String,
        weight: Int,
        label: String
    ): ScoreEvidence {
        if (query.isBlank() || field.isBlank()) return ScoreEvidence(0, "$label:blank")
        if (field == query) return ScoreEvidence(weight + 1000, "$label:exact")
        if (field.startsWith(query)) {
            return ScoreEvidence(weight + 800 - surplusPenalty(query, field), "$label:prefix")
        }
        if (field.contains(query)) {
            return ScoreEvidence(weight + 600 - surplusPenalty(query, field), "$label:contains")
        }
        if (query.contains(field) && field.length >= MIN_MEANINGFUL_CHARS) {
            return ScoreEvidence(weight + 500, "$label:query-contains-field")
        }
        if (query.length >= MIN_SUBSEQUENCE_QUERY_CHARS && isSubsequence(query, field)) {
            return ScoreEvidence(weight + 250, "$label:subsequence")
        }

        val coverage = characterCoverage(query, field)
        val orderedCoverage = orderedCharacterCoverage(query, field)
        if (
            query.length >= MIN_COVERAGE_QUERY_CHARS &&
            coverage >= HIGH_COVERAGE &&
            orderedCoverage >= HIGH_ORDERED_COVERAGE
        ) {
            return ScoreEvidence(weight + (coverage * 80).toInt(), "$label:high-coverage")
        }
        return ScoreEvidence(0, "$label:unrelated")
    }

    private fun dedupeKey(book: SourceBook): String {
        return listOf(normalizeTitle(book), normalizeAuthor(book.author)).joinToString("\n")
    }

    private fun titleConsensusBySource(candidates: List<SearchCandidate>): Map<String, Int> {
        return candidates
            .groupBy { normalizeTitle(it.book) }
            .mapValues { (_, group) ->
                group.map { it.book.source.sourceUrl.ifBlank { it.sourceIndex.toString() } }
                    .toSet()
                    .size
            }
    }

    private fun normalizeQuery(value: String): String {
        return normalizeTitleSynonyms(normalizeToken(value))
    }

    private fun normalizeTitle(value: String): String {
        return normalizeTitleSynonyms(normalizeToken(value))
            .replace("最新章节", "")
            .replace("全文阅读", "")
            .replace("无弹窗", "")
            .replace("小说", "")
    }

    private fun normalizeTitle(book: SourceBook): String {
        val title = normalizeTitle(book.name)
        val author = normalizeAuthor(book.author)
        return removeAuthorSuffix(title, author)
    }

    private fun removeAuthorSuffix(title: String, author: String): String {
        if (title.isBlank() || author.isBlank()) return title
        if (!title.endsWith(author)) return title
        val cleaned = title.removeSuffix(author)
        return cleaned.takeIf { it.length >= MIN_MEANINGFUL_CHARS } ?: title
    }

    private fun normalizeAuthor(value: String): String {
        return normalizeToken(value)
            .removePrefix("作者")
            .removePrefix("作家")
    }

    private fun normalizeToken(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace('靈', '灵')
            .replace('書', '书')
            .replace('霧', '雾')
            .replace(Regex("""作者[:：]\s*"""), "")
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+"""), "")
            .trim()
    }

    private fun normalizeTitleSynonyms(value: String): String {
        return value
            .replace("仙途", "仙路")
    }

    private fun isHardRejected(title: String): Boolean {
        val normalizedTitle = normalizeToken(title)
        return HARD_REJECT_TITLE_MARKERS.any { normalizedTitle.contains(it) }
    }

    private fun isSubsequence(query: String, field: String): Boolean {
        var queryIndex = 0
        field.forEach { char ->
            if (queryIndex < query.length && query[queryIndex] == char) {
                queryIndex++
            }
        }
        return queryIndex == query.length
    }

    private fun characterCoverage(query: String, field: String): Double {
        val queryChars = query.toSet()
        if (queryChars.isEmpty()) return 0.0
        val matched = queryChars.count { field.contains(it) }
        return matched.toDouble() / queryChars.size.toDouble()
    }

    private fun orderedCharacterCoverage(query: String, field: String): Double {
        if (query.isBlank()) return 0.0
        var fieldIndex = 0
        var matched = 0
        query.forEach { queryChar ->
            while (fieldIndex < field.length && field[fieldIndex] != queryChar) {
                fieldIndex++
            }
            if (fieldIndex < field.length) {
                matched++
                fieldIndex++
            }
        }
        return matched.toDouble() / query.length.toDouble()
    }

    private fun isAuthorFieldMatch(query: String, author: String): Boolean {
        val normalizedAuthor = normalizeAuthor(author)
        return normalizedAuthor == query ||
            normalizedAuthor.startsWith(query) ||
            query.startsWith(normalizedAuthor) && normalizedAuthor.length >= MIN_MEANINGFUL_CHARS
    }

    private fun isTitleFieldMatch(query: String, book: SourceBook): Boolean {
        val title = normalizeTitle(book)
        return title == query || title.startsWith(query) || title.contains(query)
    }

    private fun surplusPenalty(query: String, field: String): Int {
        val surplus = (field.length - query.length).coerceAtLeast(0)
        return (surplus * TITLE_SURPLUS_PENALTY).coerceAtMost(MAX_TITLE_SURPLUS_PENALTY)
    }

    private fun derivativeTitlePenalty(query: String, title: String): Int {
        if (query.length < MIN_MEANINGFUL_CHARS) return 0
        if (!title.contains(query)) return 0
        return DERIVATIVE_TITLE_MARKERS
            .filter { marker -> title.contains(marker) && !query.contains(marker) }
            .sumOf { DERIVATIVE_MARKER_PENALTY }
            .coerceAtMost(MAX_DERIVATIVE_MARKER_PENALTY)
    }

    private fun prefixSeparatorDerivativePenalty(rawQuery: String, rawTitle: String): Int {
        val query = rawQuery.trim()
        val title = rawTitle.trim()
        if (query.length < MIN_MEANINGFUL_CHARS || !title.startsWith(query, ignoreCase = true)) return 0
        val nextChar = title.getOrNull(query.length) ?: return 0
        return if (nextChar in DERIVATIVE_PREFIX_SEPARATORS) PREFIX_SEPARATOR_DERIVATIVE_PENALTY else 0
    }

    private fun completionTitleBonus(
        query: String,
        title: String,
        author: String,
        rawSearchQuery: String?
    ): Int {
        val searchQuery = normalizeQuery(rawSearchQuery.orEmpty())
        if (query.isBlank() || searchQuery.isBlank() || searchQuery == query) return 0
        if (title != searchQuery) return 0
        if (searchQuery.startsWith(query)) return COMPLETION_TITLE_BONUS
        if (query.length >= MIN_MEANINGFUL_CHARS && searchQuery.contains(query)) {
            return CONTAINS_COMPLETION_TITLE_BONUS
        }
        return if (isAuthorFieldMatch(query, author)) AUTHOR_COMPLETION_TITLE_BONUS else 0
    }

    private fun minAcceptedScore(query: String): Int {
        return if (query.length <= 1) TITLE_WEIGHT + 600 else AUTHOR_WEIGHT + 150
    }

    private data class ScoreEvidence(
        val score: Int,
        val evidence: String
    ) {
        fun demote(penalty: Int): ScoreEvidence {
            if (penalty <= 0 || score <= 0) return this
            return copy(score = (score - penalty).coerceAtLeast(0), evidence = "$evidence:demoted")
        }
    }

    private fun RankedSearchBook.promoteByConsensus(sourceCount: Int): RankedSearchBook {
        if (score <= 0) return this.copy(sourceCount = sourceCount.coerceAtLeast(1))
        if (sourceCount <= 1) return this.copy(sourceCount = 1)
        val bonus = ((sourceCount - 1) * CONSENSUS_SOURCE_BONUS).coerceAtMost(MAX_CONSENSUS_BONUS)
        return copy(score = score + bonus, evidence = "$evidence:consensus", sourceCount = sourceCount)
    }

    private fun RankedSearchBook.demoteAuthorOnlyTitleQueryHit(
        query: String,
        candidate: SearchCandidate,
        hasTitleFieldMatches: Boolean
    ): RankedSearchBook {
        if (!hasTitleFieldMatches) return this
        if (!evidence.startsWith("author:")) return this
        if (isTitleFieldMatch(query, candidate.book)) return this
        if (isAuthorCompletionTitleHit(query, candidate)) return this
        return copy(
            score = 0,
            evidence = "$evidence:author-only-title-query-rejected"
        )
    }

    private fun RankedSearchBook.demoteTitleOnlyAuthorQueryHit(
        query: String,
        candidate: SearchCandidate,
        hasAuthorFieldMatches: Boolean
    ): RankedSearchBook {
        if (!hasAuthorFieldMatches) return this
        if (!evidence.startsWith("title:")) return this
        if (isAuthorFieldMatch(query, candidate.book.author)) return this
        if (normalizeTitle(candidate.book) == query) return this
        if (isAuthorCompletionTitleHit(query, candidate)) return this
        return copy(
            score = 0,
            evidence = "$evidence:title-only-author-query-rejected"
        )
    }

    private fun RankedSearchBook.demoteNonExactTitleWhenExactTitleExists(
        query: String,
        candidate: SearchCandidate,
        hasExactTitleMatches: Boolean
    ): RankedSearchBook {
        if (!hasExactTitleMatches) return this
        val title = normalizeTitle(candidate.book)
        if (title == query) return this
        if (!isTitleFieldMatch(query, candidate.book)) return this
        if (sourceCount >= MULTI_SOURCE_EXACT_COMPANION_COUNT) {
            return copy(
                score = score.coerceAtMost(EXACT_TITLE_AVAILABLE_COMPANION_SCORE_CAP),
                evidence = "$evidence:exact-title-available-capped"
            )
        }
        return copy(
            score = (score - EXACT_TITLE_AVAILABLE_PENALTY).coerceAtLeast(0),
            evidence = "$evidence:exact-title-available-demoted"
        )
    }

    private fun isAuthorCompletionTitleHit(query: String, candidate: SearchCandidate): Boolean {
        val searchQuery = normalizeQuery(candidate.searchQuery.orEmpty())
        if (searchQuery.isBlank() || searchQuery == query) return false
        return normalizeTitle(candidate.book) == searchQuery
    }

    companion object {
        private const val TITLE_WEIGHT = 9000
        private const val AUTHOR_WEIGHT = 8200
        private const val MIN_MEANINGFUL_CHARS = 2
        private const val MIN_SUBSEQUENCE_QUERY_CHARS = 3
        private const val MIN_COVERAGE_QUERY_CHARS = 3
        private const val MIN_AUTHOR_QUERY_MATCHES = 2
        private const val HIGH_COVERAGE = 0.80
        private const val HIGH_ORDERED_COVERAGE = 0.75
        private const val SOURCE_ORDER_PENALTY = 1
        private const val RESULT_ORDER_PENALTY = 1
        private const val MAX_ORDER_PENALTY = 80
        private const val AUTHOR_PRESENT_BONUS = 120
        private const val TITLE_SURPLUS_PENALTY = 12
        private const val MAX_TITLE_SURPLUS_PENALTY = 240
        private const val DERIVATIVE_MARKER_PENALTY = 260
        private const val MAX_DERIVATIVE_MARKER_PENALTY = 900
        private const val PREFIX_SEPARATOR_DERIVATIVE_PENALTY = 900
        private const val CONSENSUS_SOURCE_BONUS = 320
        private const val MAX_CONSENSUS_BONUS = 6_400
        private const val COMPLETION_TITLE_BONUS = 7_000
        private const val CONTAINS_COMPLETION_TITLE_BONUS = 6_000
        private const val AUTHOR_COMPLETION_TITLE_BONUS = 7_000
        private const val EXACT_TITLE_AVAILABLE_PENALTY = 8_000
        private const val MULTI_SOURCE_EXACT_COMPANION_COUNT = 2
        private const val EXACT_TITLE_AVAILABLE_COMPANION_SCORE_CAP = TITLE_WEIGHT + 900
        private val DERIVATIVE_PREFIX_SEPARATORS = setOf(':', '：', '-', '_', '·')
        private val HARD_REJECT_TITLE_MARKERS = listOf(
            "同人"
        )
        private val DERIVATIVE_TITLE_MARKERS = listOf(
            "同人",
            "改写",
            "外传",
            "番外",
            "后传",
            "续集",
            "之",
            "篇",
            "传",
            "黑暗",
            "复仇"
        )

        private val rankedComparator = compareByDescending<RankedSearchBook> { it.score }
            .thenByDescending { it.sourceCount }
            .thenBy { it.book.name.length }
            .thenBy { if (it.book.author.isBlank()) 1 else 0 }
            .thenBy { it.sourceIndex }
            .thenBy { it.resultIndex }
            .thenBy { it.book.name }
    }
}

data class SearchCandidate(
    val book: SourceBook,
    val sourceIndex: Int = 0,
    val resultIndex: Int = 0,
    val searchQuery: String? = null
)

data class RankedSearchBook(
    val book: SourceBook,
    val score: Int,
    val evidence: String,
    val sourceIndex: Int,
    val resultIndex: Int,
    val sourceCount: Int = 1
)
