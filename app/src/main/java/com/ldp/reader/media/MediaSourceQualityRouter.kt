package com.ldp.reader.media

import com.ldp.reader.App
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceBookDetail
import com.ldp.reader.media.MediaSourceChapter
import com.tencent.mmkv.MMKV
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class MediaSourceQualityRouter(
    private val storage: MediaSourceQualityStorage = defaultStorage(),
    private val seed: MediaSourceQualitySeed = defaultSeed()
) {
    private val statsCache = ConcurrentHashMap<String, MediaSourceStats>()

    fun waterfallSources(kind: ReaderMediaKind, sources: List<MediaSourceDefinition>): List<MediaSourceDefinition> {
        return waterfallFromScored(sources.map { source -> scoredSource(kind, source) })
    }

    fun waterfallSourcesForQuery(
        kind: ReaderMediaKind,
        sources: List<MediaSourceDefinition>,
        query: String
    ): List<MediaSourceDefinition> {
        val normalizedQuery = normalizeTitle(query)
        if (normalizedQuery.isBlank()) return waterfallSources(kind, sources)
        val personal = personalSources(kind, sources, normalizedQuery)
        val personalKeys = personal.mapTo(LinkedHashSet()) { sourceKey(it) }
        return personal + waterfallSources(kind, sources.filterNot { sourceKey(it) in personalKeys })
    }

    fun rankSearchResults(
        kind: ReaderMediaKind,
        keyword: String,
        books: List<MediaSourceBook>,
        limit: Int
    ): List<RankedMediaSearchBook> {
        val query = normalizeTitle(keyword)
        if (query.isBlank()) return emptyList()
        val indexedBooks = books.mapIndexed { index, book -> IndexedMediaBook(index, book) }
        return indexedBooks
            .groupBy { canonicalBookKey(kind, keyword, it.book) }
            .values
            .mapNotNull { group ->
                val consensus = consensusForGroup(group.map { it.book })
                if (consensus.sourceCount < minDisplayConsensusSources(kind)) return@mapNotNull null
                if (!group.any { titleMatchesQuery(kind, query, it.book) }) return@mapNotNull null
                group.map { item ->
                    scoreSearchBook(
                        kind = kind,
                        query = query,
                        keyword = keyword,
                        book = item.book,
                        resultIndex = item.index,
                        consensus = consensus
                    )
                }
                    .filter { it.titleScore > 0 }
                    .maxWithOrNull(searchGroupRepresentativeComparator)
                    ?.withoutInternalScores()
            }
            .sortedWith(rankedComparator)
            .take(limit)
    }

    fun recordSearchResult(kind: ReaderMediaKind, book: MediaSourceBook, keyword: String) {
        val query = normalizeTitle(keyword)
        val title = MediaTitleKey.normalizedForQuery(book.name, keyword)
        if (query.isBlank() || title.isBlank()) return
        val delta = when {
            title == query -> 8
            MediaTitleKey.normalizedTitleMatchesQuery(kind, query, title) -> 4
            else -> 1
        }
        adjustGlobal(kind, book.source, delta)
    }

    fun recordDetailResolved(kind: ReaderMediaKind, detail: MediaSourceBookDetail) {
        adjustGlobal(kind, detail.book.source, 12)
        adjustBook(kind, detail.book, 12)
    }

    fun recordDetailFailed(kind: ReaderMediaKind, book: MediaSourceBook) {
        adjustGlobal(kind, book.source, -20)
        adjustBook(kind, book, -20)
    }

    fun recordChapterListResolved(kind: ReaderMediaKind, detail: MediaSourceBookDetail, chapterCount: Int) {
        val delta = when {
            chapterCount >= 20 -> 30
            chapterCount > 0 -> 15
            else -> -25
        }
        adjustGlobal(kind, detail.book.source, delta)
        adjustBook(kind, detail.book, delta)
    }

    fun recordContentResolved(kind: ReaderMediaKind, chapter: MediaSourceChapter, resolvedItemCount: Int) {
        val delta = when {
            resolvedItemCount >= 3 -> 45
            resolvedItemCount > 0 -> 25
            else -> -35
        }
        adjustGlobal(kind, chapter.source, delta)
        adjustBook(kind, chapter.book, delta)
    }

    private fun personalSources(
        kind: ReaderMediaKind,
        sources: List<MediaSourceDefinition>,
        normalizedBookName: String
    ): List<MediaSourceDefinition> {
        return sources.mapNotNull { source ->
            val stats = stats(personalStatsKey(kind, source, normalizedBookName))
            if (stats.events < PERSONAL_TIER_MIN_EVENTS) return@mapNotNull null
            if (stats.successes <= stats.failures) return@mapNotNull null
            val score = (sourceScore(kind, source) + stats.delta * PERSONAL_DELTA_MULTIPLIER)
                .coerceIn(MIN_SCORE, MAX_SCORE)
            if (score < PERSONAL_TIER_MIN_SCORE) return@mapNotNull null
            ScoredMediaSource(source, score, 0)
        }.sortedWith(scoredComparator)
            .map { it.source }
    }

    private fun scoreSearchBook(
        kind: ReaderMediaKind,
        query: String,
        keyword: String,
        book: MediaSourceBook,
        resultIndex: Int,
        consensus: MediaConsensusInfo
    ): RankedMediaSearchBook {
        val title = MediaTitleKey.normalizedForQuery(book.name, keyword)
        val titleScore = titleFieldScore(kind, query, title)
        val author = normalizeAuthor(book.author)
        val authorScore = fieldScore(query, author, AUTHOR_WEIGHT)
        val baseScore = when (kind) {
            ReaderMediaKind.COMIC,
            ReaderMediaKind.AUDIO -> titleScore
            ReaderMediaKind.NOVEL -> maxOf(titleScore, authorScore)
        }
        val hasCover = hasUsableCover(book)
        val effectiveSourceCount = consensus.sourceCount
        val consensusBonus = when {
            effectiveSourceCount >= 2 -> {
                CONSENSUS_BONUS + ((effectiveSourceCount - 2) * CONSENSUS_STEP_BONUS)
                    .coerceAtMost(MAX_CONSENSUS_STEP_BONUS)
            }
            else -> 0
        }
        val coverBonus = when {
            kind == ReaderMediaKind.COMIC && hasCover -> COMIC_COVER_BONUS
            kind == ReaderMediaKind.AUDIO && hasCover -> AUDIO_COVER_BONUS
            else -> 0
        }
        val sourceQualityBonus = (sourceScore(kind, book.source) - BASE_SCORE) / 8
        val personalBonus = personalSearchBonus(kind, book)
        val orderPenalty = resultIndex.coerceAtMost(MAX_RESULT_ORDER_PENALTY)
        val score = (baseScore + consensusBonus + coverBonus + sourceQualityBonus + personalBonus - orderPenalty)
            .coerceAtLeast(0)
        return RankedMediaSearchBook(
            book = book,
            score = score,
            sourceCount = effectiveSourceCount.coerceAtLeast(1),
            hasCover = hasCover,
            sourceScore = sourceScore(kind, book.source),
            titleScore = titleScore
        )
    }

    private fun personalSearchBonus(kind: ReaderMediaKind, book: MediaSourceBook): Int {
        val stats = stats(personalStatsKey(kind, book.source, normalizeTitle(book.name)))
        if (stats.events < PERSONAL_TIER_MIN_EVENTS || stats.successes <= stats.failures) return 0
        return (stats.delta * PERSONAL_SEARCH_BONUS_MULTIPLIER)
            .coerceIn(0, MAX_PERSONAL_SEARCH_BONUS)
    }

    private fun consensusForGroup(group: List<MediaSourceBook>): MediaConsensusInfo {
        return MediaConsensusInfo(
            sourceCount = group.map { sourceKey(it.source) }.toSet().size,
            hasAnyCover = group.any { hasUsableCover(it) }
        )
    }

    private fun hasUsableCover(book: MediaSourceBook): Boolean {
        val cover = book.coverUrl.trim()
        return cover.startsWith("http://", ignoreCase = true) ||
            cover.startsWith("https://", ignoreCase = true)
    }

    private fun scoredSource(kind: ReaderMediaKind, source: MediaSourceDefinition): ScoredMediaSource {
        val score = sourceScore(kind, source)
        return ScoredMediaSource(source, score, tierForScore(kind, source, score))
    }

    private fun sourceScore(kind: ReaderMediaKind, source: MediaSourceDefinition): Int {
        val seedScore = seed.recordFor(kind, source)?.score ?: heuristicSourceScore(source)
        val stats = stats(globalStatsKey(kind, source))
        return (seedScore + stats.delta).coerceIn(MIN_SCORE, MAX_SCORE)
    }

    private fun tierForScore(kind: ReaderMediaKind, source: MediaSourceDefinition, score: Int): Int {
        val seedTier = seed.recordFor(kind, source)?.tier?.takeIf { it in 1..3 }
        val scoreTier = when (score) {
            in 7_200..MAX_SCORE -> 1
            in 4_800 until 7_200 -> 2
            else -> 3
        }
        return seedTier?.let { minOf(it, scoreTier) } ?: scoreTier
    }

    private fun waterfallFromScored(scored: List<ScoredMediaSource>): List<MediaSourceDefinition> {
        val tierQueues = linkedMapOf(
            0 to ArrayDeque(scored.filter { it.tier == 0 }.sortedWith(scoredComparator)),
            1 to ArrayDeque(scored.filter { it.tier == 1 }.sortedWith(scoredComparator)),
            2 to ArrayDeque(scored.filter { it.tier == 2 }.sortedWith(scoredComparator)),
            3 to ArrayDeque(scored.filter { it.tier == 3 }.sortedWith(scoredComparator))
        )
        val output = ArrayList<MediaSourceDefinition>(scored.size)
        while (tierQueues[0]?.isNotEmpty() == true) {
            output.add(tierQueues.getValue(0).removeFirst().source)
        }
        while ((tierQueues[1]?.isNotEmpty() == true) ||
            (tierQueues[2]?.isNotEmpty() == true) ||
            (tierQueues[3]?.isNotEmpty() == true)
        ) {
            TIER_WATERFALL_WEIGHTS.forEach { (tier, count) ->
                val queue = tierQueues[tier] ?: return@forEach
                repeat(count) {
                    if (queue.isNotEmpty()) output.add(queue.removeFirst().source)
                }
            }
        }
        return output
    }

    private fun adjustGlobal(kind: ReaderMediaKind, source: MediaSourceDefinition, delta: Int) {
        adjust(globalStatsKey(kind, source), delta)
    }

    private fun adjustBook(kind: ReaderMediaKind, book: MediaSourceBook, delta: Int) {
        adjust(personalStatsKey(kind, book.source, normalizeTitle(book.name)), delta)
    }

    private fun adjust(key: String, delta: Int) {
        val current = stats(key)
        current.delta = (current.delta + delta).coerceIn(-MAX_DYNAMIC_DELTA, MAX_DYNAMIC_DELTA)
        current.events += 1
        if (delta > 0) current.successes += 1 else if (delta < 0) current.failures += 1
        storage.write(key, current)
    }

    private fun stats(key: String): MediaSourceStats {
        return statsCache.getOrPut(key) { storage.read(key) ?: MediaSourceStats() }
    }

    private fun heuristicSourceScore(source: MediaSourceDefinition): Int {
        val label = "${source.sourceName}\n${source.sourceUrl}\n${source.sourceGroup.orEmpty()}\n${source.sourceComment.orEmpty()}"
            .lowercase(Locale.ROOT)
        var score = BASE_SCORE
        val userImported = label.contains(USER_IMPORTED_SOURCE_MARKER)
        if (userImported) score += USER_IMPORTED_TIER2_BONUS
        if (label.contains("优+")) score += 1_500
        if (label.contains("优")) score += 700
        if (HIGH_PRIORITY_MARKERS.any { label.contains(it) }) score += 500
        if (LOW_PRIORITY_MARKERS.any { label.contains(it) }) score -= 900
        if (userImported) return score.coerceIn(MIN_SCORE, USER_IMPORTED_TIER2_MAX_SCORE)
        return score.coerceIn(MIN_SCORE, MAX_SCORE)
    }

    private fun fieldScore(query: String, field: String, weight: Int): Int {
        if (query.isBlank() || field.isBlank()) return 0
        if (field == query) return weight + 2_000
        if (field.startsWith(query)) return weight + 1_300 - surplusPenalty(query, field)
        if (field.contains(query)) return weight + 900 - surplusPenalty(query, field)
        val coverage = query.toSet().count { field.contains(it) }.toDouble() / query.length.coerceAtLeast(1)
        return if (query.length >= 3 && coverage >= 0.75) weight + (coverage * 300).toInt() else 0
    }

    private fun titleFieldScore(kind: ReaderMediaKind, query: String, title: String): Int {
        if (query.isBlank() || title.isBlank()) return 0
        if (title == query) return TITLE_WEIGHT + 2_000
        if (title.startsWith(query)) return TITLE_WEIGHT + 1_300 - surplusPenalty(query, title)
        if (MediaTitleKey.queryContainsTitleAsSameWorkVariant(query, title)) return TITLE_WEIGHT + 600
        if (kind == ReaderMediaKind.AUDIO) return 0
        if (title.contains(query)) return TITLE_WEIGHT + 900 - surplusPenalty(query, title)
        return 0
    }

    private fun surplusPenalty(query: String, field: String): Int {
        return ((field.length - query.length).coerceAtLeast(0) * 18).coerceAtMost(420)
    }

    private fun titleMatchesQuery(kind: ReaderMediaKind, query: String, book: MediaSourceBook): Boolean {
        return MediaTitleKey.normalizedTitleMatchesQuery(
            kind = kind,
            query = query,
            title = MediaTitleKey.normalizedForQuery(book.name, query)
        )
    }

    private fun canonicalBookKey(kind: ReaderMediaKind, keyword: String, book: MediaSourceBook): String {
        return MediaTitleKey.consensusKey(kind, keyword, book.name)
    }

    private fun minDisplayConsensusSources(kind: ReaderMediaKind): Int {
        return when (kind) {
            ReaderMediaKind.NOVEL -> MIN_DISPLAY_CONSENSUS_SOURCES
            ReaderMediaKind.COMIC,
            ReaderMediaKind.AUDIO -> 1
        }
    }

    private fun normalizeTitle(value: String): String {
        return MediaTitleKey.normalized(value)
    }

    private fun normalizeAuthor(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("""作者[:：]?\s*"""), "")
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+"""), "")
            .trim()
    }

    private fun sourceKey(source: MediaSourceDefinition): String {
        return MediaSourceIdentity.sourceKey(source)
    }

    private fun globalStatsKey(kind: ReaderMediaKind, source: MediaSourceDefinition): String {
        return recordKey("global", kind.seedKey + "\n" + sourceKey(source))
    }

    private fun personalStatsKey(
        kind: ReaderMediaKind,
        source: MediaSourceDefinition,
        normalizedBookName: String
    ): String {
        return recordKey("book", kind.seedKey + "\n" + sourceKey(source) + "\n" + normalizedBookName)
    }

    private data class ScoredMediaSource(
        val source: MediaSourceDefinition,
        val score: Int,
        val tier: Int
    )

    companion object {
        private const val MEDIA_SOURCE_SEED_ASSET = "media-source-quality-seed-v1.tsv"
        private const val MIN_SCORE = 0
        private const val MAX_SCORE = 10_000
        private const val BASE_SCORE = 5_000
        private const val MAX_DYNAMIC_DELTA = 2_500
        private const val USER_IMPORTED_TIER2_BONUS = 1_400
        private const val USER_IMPORTED_TIER2_MAX_SCORE = 6_900
        private const val PERSONAL_TIER_MIN_EVENTS = 2
        private const val PERSONAL_TIER_MIN_SCORE = 4_800
        private const val PERSONAL_DELTA_MULTIPLIER = 8
        private const val PERSONAL_SEARCH_BONUS_MULTIPLIER = 5
        private const val MAX_PERSONAL_SEARCH_BONUS = 1_200
        private const val TITLE_WEIGHT = 8_000
        private const val AUTHOR_WEIGHT = 7_200
        private const val CONSENSUS_BONUS = 1_400
        private const val CONSENSUS_STEP_BONUS = 700
        private const val MAX_CONSENSUS_STEP_BONUS = 4_200
        private const val COMIC_COVER_BONUS = 1_200
        private const val AUDIO_COVER_BONUS = 400
        private const val MAX_RESULT_ORDER_PENALTY = 120
        private const val MIN_DISPLAY_CONSENSUS_SOURCES = 2
        private const val USER_IMPORTED_SOURCE_MARKER = "reader:user-imported-source"
        private val TIER_WATERFALL_WEIGHTS = listOf(1 to 6, 2 to 3, 3 to 1)
        private val HIGH_PRIORITY_MARKERS = listOf("看漫画", "包子", "动漫之家", "漫画台", "爱优漫", "拷贝")
        private val LOW_PRIORITY_MARKERS = listOf("英文", "日文", "raw", "搬运", "发现")

        private val scoredComparator = compareByDescending<ScoredMediaSource> { it.score }
            .thenBy { it.source.sourceName.length }
            .thenBy { it.source.sourceName }

        private val rankedComparator = compareByDescending<RankedMediaSearchBook> { it.score }
            .thenByDescending { it.hasCover }
            .thenByDescending { it.sourceCount }
            .thenByDescending { it.sourceScore }
            .thenBy { it.book.name.length }
            .thenBy { it.book.name }

        private val searchGroupRepresentativeComparator =
            compareBy<RankedMediaSearchBook> { it.score }
                .thenBy { if (it.hasCover) 1 else 0 }
                .thenBy { it.sourceScore }

        private fun defaultStorage(): MediaSourceQualityStorage {
            return runCatching {
                MmkvMediaSourceQualityStorage()
            }.getOrElse {
                InMemoryMediaSourceQualityStorage()
            }
        }

        private fun defaultSeed(): MediaSourceQualitySeed {
            return runCatching {
                App.getContext().assets.open(MEDIA_SOURCE_SEED_ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .use { MediaSourceQualitySeed.fromTsv(it.readText()) }
            }.getOrElse {
                MediaSourceQualitySeed.empty()
            }
        }

        private fun recordKey(scope: String, value: String): String {
            return "media_source_quality_v1:$scope:${sha256(value)}"
        }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

internal data class RankedMediaSearchBook(
    val book: MediaSourceBook,
    val score: Int,
    val sourceCount: Int,
    val hasCover: Boolean,
    val sourceScore: Int,
    val titleScore: Int = 0
) {
    fun withoutInternalScores(): RankedMediaSearchBook {
        return copy(titleScore = 0)
    }
}

private data class IndexedMediaBook(
    val index: Int,
    val book: MediaSourceBook
)

private data class MediaConsensusInfo(
    val sourceCount: Int = 1,
    val hasAnyCover: Boolean = false
)

internal data class MediaSourceStats(
    var delta: Int = 0,
    var events: Int = 0,
    var successes: Int = 0,
    var failures: Int = 0
) {
    fun encode(): String = listOf(delta, events, successes, failures).joinToString("|")

    companion object {
        fun decode(value: String): MediaSourceStats? {
            val parts = value.split("|")
            if (parts.size < 4) return null
            return runCatching {
                MediaSourceStats(
                    delta = parts[0].toInt(),
                    events = parts[1].toInt(),
                    successes = parts[2].toInt(),
                    failures = parts[3].toInt()
                )
            }.getOrNull()
        }
    }
}

internal interface MediaSourceQualityStorage {
    fun read(key: String): MediaSourceStats?
    fun write(key: String, value: MediaSourceStats)
}

internal class InMemoryMediaSourceQualityStorage : MediaSourceQualityStorage {
    private val values = ConcurrentHashMap<String, MediaSourceStats>()

    override fun read(key: String): MediaSourceStats? = values[key]?.copy()

    override fun write(key: String, value: MediaSourceStats) {
        values[key] = value.copy()
    }
}

private class MmkvMediaSourceQualityStorage : MediaSourceQualityStorage {
    private val mmkv = MMKV.mmkvWithID("media_source_quality_v1")

    override fun read(key: String): MediaSourceStats? {
        return mmkv.decodeString(key, null)?.let { MediaSourceStats.decode(it) }
    }

    override fun write(key: String, value: MediaSourceStats) {
        mmkv.encode(key, value.encode())
    }
}
