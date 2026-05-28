package com.ldp.reader.source

import android.util.Log
import com.ldp.reader.App
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkState
import com.ldp.reader.sourceengine.content.v5.V5SourceRunResult
import com.tencent.mmkv.MMKV
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal class SourceQualityRouter(
    private val storage: SourceQualityStorage = defaultStorage(),
    private val seed: SourceQualitySeed = defaultSeed()
) {
    private val bookSourceStats = ConcurrentHashMap<String, ScoreStats>()

    fun waterfallSources(sources: List<BookSource>): List<BookSource> {
        val scored = sources.map { source ->
            ScoredSource(
                source = source,
                score = sourceScore(source),
                tier = sourceTier(source),
                bucket = sourceBucket(source)
            )
        }
        return waterfallFromScored(scored)
    }

    fun waterfallSourcesForBook(sources: List<BookSource>, bookName: String): List<BookSource> {
        val normalizedBookName = normalizeBookName(bookName)
        if (normalizedBookName.isBlank()) return waterfallSources(sources)
        val personalTier = personalWaterfallSourcesForNormalizedBook(sources, normalizedBookName)
        val personalKeys = personalTier.mapTo(mutableSetOf()) { source -> sourceKey(source) }
        val globalRemainder = waterfallSources(sources.filterNot { source -> sourceKey(source) in personalKeys })
        val ordered = personalTier + globalRemainder
        AiBridgeTrace.event(
            "source_quality_book_waterfall",
            bookName,
            AiBridgeTrace.fields(
                "sources" to sources.size,
                "personal" to personalTier.size,
                "global" to globalRemainder.size,
                "first" to ordered.take(8).joinToString("|") { source -> source.sourceName }
            )
        )
        Log.i(
            TAG,
            "operation=bookWaterfall book=$bookName sources=${sources.size} " +
                "personal=${personalTier.size} first=${ordered.take(8).joinToString("|") { it.sourceName }}"
        )
        return ordered
    }

    fun personalWaterfallSourcesForBook(sources: List<BookSource>, bookName: String): List<BookSource> {
        val normalizedBookName = normalizeBookName(bookName)
        if (normalizedBookName.isBlank()) return emptyList()
        val personalTier = personalWaterfallSourcesForNormalizedBook(sources, normalizedBookName)
        AiBridgeTrace.event(
            "source_quality_book_personal_waterfall",
            bookName,
            AiBridgeTrace.fields(
                "sources" to sources.size,
                "personal" to personalTier.size,
                "first" to personalTier.take(8).joinToString("|") { source -> source.sourceName }
            )
        )
        return personalTier
    }

    fun globalWaterfallSourcesForBook(sources: List<BookSource>, bookName: String): List<BookSource> {
        val normalizedBookName = normalizeBookName(bookName)
        if (normalizedBookName.isBlank()) return waterfallSources(sources)
        val personalKeys = personalWaterfallSourcesForNormalizedBook(sources, normalizedBookName)
            .mapTo(mutableSetOf()) { source -> sourceKey(source) }
        val globalRemainder = waterfallSources(sources.filterNot { source -> sourceKey(source) in personalKeys })
        AiBridgeTrace.event(
            "source_quality_book_global_waterfall",
            bookName,
            AiBridgeTrace.fields(
                "sources" to sources.size,
                "personal" to personalKeys.size,
                "global" to globalRemainder.size,
                "first" to globalRemainder.take(8).joinToString("|") { source -> source.sourceName }
            )
        )
        return globalRemainder
    }

    private fun personalWaterfallSourcesForNormalizedBook(
        sources: List<BookSource>,
        normalizedBookName: String
    ): List<BookSource> {
        return sources
            .mapNotNull { source -> personalTierSource(source, normalizedBookName) }
            .sortedWith(scoredSourceComparator)
            .map { it.source }
    }

    private fun waterfallFromScored(scored: List<ScoredSource>): List<BookSource> {
        val tierQueues = linkedMapOf(
            1 to ArrayDeque(interleaveByBucket(scored.filter { it.tier == 1 }.sortedWith(scoredSourceComparator))),
            2 to ArrayDeque(interleaveByBucket(scored.filter { it.tier == 2 }.sortedWith(scoredSourceComparator))),
            3 to ArrayDeque(interleaveByBucket(scored.filter { it.tier == 3 }.sortedWith(scoredSourceComparator)))
        )
        val output = ArrayList<BookSource>(scored.size)
        while (tierQueues.values.any { it.isNotEmpty() }) {
            TIER_WATERFALL_WEIGHTS.forEach { (tier, count) ->
                val queue = tierQueues[tier] ?: return@forEach
                repeat(count) {
                    if (queue.isNotEmpty()) output.add(queue.removeFirst())
                }
            }
        }
        return output
    }

    fun sourceScore(source: BookSource): Int {
        val staticScore = sourceSeedFor(source)?.score ?: heuristicSourceScore(source)
        return (staticScore - specialSourcePenalty(source)).coerceIn(MIN_SCORE, MAX_SCORE)
    }

    fun sourceDebugSnapshot(source: BookSource): SourceDebugSnapshot {
        return SourceDebugSnapshot(
            score = sourceScore(source),
            tier = sourceTier(source),
            bucket = sourceBucket(source)
        )
    }

    fun bookSourceScore(source: BookSource, bookName: String): Int {
        val inherited = sourceScore(source)
        val stats = bookSourceStatsFor(source, bookName)
        if (stats.events == 0) return inherited
        return (inherited + stats.delta * BOOK_LOCAL_SCORE_MULTIPLIER)
            .coerceIn(MIN_SCORE, MAX_SCORE)
    }

    fun bookSourceScore(book: SourceBook): Int {
        return bookSourceScore(book.source, book.name)
    }

    fun bookSourceSnapshot(book: SourceBook): SourceQualitySnapshot {
        val stats = bookSourceStatsFor(book)
        return SourceQualitySnapshot(
            score = bookSourceScore(book),
            latestObservedOrdinal = stats.latestObservedOrdinal,
            latestVerifiedGoodOrdinal = stats.latestVerifiedGoodOrdinal,
            badTailStartOrdinal = stats.badTailStartOrdinal,
            events = stats.events
        )
    }

    fun routeScoreBoost(book: SourceBook): Int {
        return ((bookSourceScore(book) - BASE_SOURCE_SCORE) / 10.0).roundToInt()
    }

    fun recordSearchValidation(
        book: SourceBook,
        chapterCount: Int,
        freshnessHint: Int,
        coverUsable: Boolean,
        validation: String
    ) {
        val catalogSignal = maxOf(chapterCount, freshnessHint)
        val bookDelta = when {
            catalogSignal >= 1_000 -> 140
            catalogSignal >= 500 -> 110
            catalogSignal >= 100 -> 60
            catalogSignal >= 30 -> 20
            catalogSignal > 0 -> -30
            else -> -60
        } + if (coverUsable) 8 else -5
        val validationDelta = when (validation) {
            "detail-catalog-tail-content" -> 45
            "detail-catalog-content" -> 45
            "detail-catalog" -> 30
            "detail-catalog-unreadable" -> -90
            "detail-only" -> -15
            "detail-failed" -> -60
            "unvalidated" -> -10
            else -> 0
        }
        val totalDelta = bookDelta + validationDelta
        traceScoreEvidence(
            eventName = "source_quality_search_validation",
            book = book,
            delta = totalDelta,
            fields = arrayOf(
                "chapterCount" to chapterCount,
                "freshness" to freshnessHint,
                "cover" to coverUsable,
                "validation" to validation,
                "catalogDelta" to bookDelta,
                "validationDelta" to validationDelta
            )
        )
        adjust(book, totalDelta)
        if (catalogSignal > 0) {
            updateBookRange(book, observed = catalogSignal, verifiedGood = null, badTailStart = null)
        }
    }

    fun recordCatalogResolved(book: SourceBook, chapterCount: Int, rawChapterCount: Int) {
        val completeness = if (rawChapterCount <= 0) 0 else chapterCount * 100 / rawChapterCount
        val bookDelta = when {
            chapterCount >= 1_000 && completeness >= 95 -> 180
            chapterCount >= 500 && completeness >= 95 -> 140
            chapterCount >= 100 -> 70
            chapterCount >= 30 -> 25
            chapterCount > 0 -> -60
            else -> -120
        }
        traceScoreEvidence(
            eventName = "source_quality_catalog_resolved",
            book = book,
            delta = bookDelta,
            fields = arrayOf(
                "chapters" to chapterCount,
                "raw" to rawChapterCount,
                "completeness" to completeness
            )
        )
        adjust(book, bookDelta)
        updateBookRange(
            book = book,
            observed = rawChapterCount.coerceAtLeast(chapterCount),
            verifiedGood = chapterCount,
            badTailStart = null
        )
    }

    fun recordCatalogTailTrimmed(book: SourceBook, kept: Int, rawChapterCount: Int) {
        if (rawChapterCount <= 0) return
        val stats = bookSourceStatsFor(book)
        val previousVerified = stats.latestVerifiedGoodOrdinal
        val verifiedGain = (kept - previousVerified).coerceAtLeast(0)
        val badTailCount = (rawChapterCount - kept).coerceAtLeast(0)
        val pollutionPenalty = when {
            kept <= 0 -> 120
            badTailCount <= 0 -> 0
            else -> (badTailCount * 4).coerceAtMost(80)
        }
        val continuityBonus = verifiedGain * VERIFIED_NEW_CHAPTER_REWARD
        val bookDelta = (continuityBonus - pollutionPenalty).coerceIn(-180, 260)
        traceScoreEvidence(
            eventName = "source_quality_tail_trimmed",
            book = book,
            delta = bookDelta,
            fields = arrayOf(
                "kept" to kept,
                "raw" to rawChapterCount,
                "verifiedGain" to verifiedGain,
                "badTail" to badTailCount,
                "continuityBonus" to continuityBonus,
                "pollutionPenalty" to pollutionPenalty
            )
        )
        adjust(book, bookDelta)
        updateBookRange(
            book = book,
            observed = rawChapterCount,
            verifiedGood = kept,
            badTailStart = if (kept < rawChapterCount) kept + 1 else null
        )
    }

    fun recordContentResolved(chapter: SourceChapter, content: CleanContent) {
        val quality = content.report.qualityScore
        val coherence = content.report.coherenceScore
        val length = content.report.cleanedLength
        val bookDelta = when {
            quality >= 90 && coherence >= 90 && length >= 1_000 -> 120
            quality >= 75 && coherence >= 75 && length >= 300 -> 55
            else -> -90
        }
        traceScoreEvidence(
            eventName = "source_quality_content_resolved",
            book = chapter.book,
            delta = bookDelta,
            fields = arrayOf(
                "chapter" to chapter.name,
                "quality" to quality,
                "coherence" to coherence,
                "length" to length
            )
        )
        adjust(chapter.book, bookDelta)
        chapterOrdinal(chapter.name)?.let { ordinal ->
            updateBookRange(
                book = chapter.book,
                observed = ordinal,
                verifiedGood = ordinal,
                badTailStart = null
            )
        }
    }

    fun recordContentRejected(chapter: SourceChapter) {
        traceScoreEvidence(
            eventName = "source_quality_content_rejected",
            book = chapter.book,
            delta = -120,
            fields = arrayOf("chapter" to chapter.name)
        )
        adjust(chapter.book, bookDelta = -120)
    }

    fun recordV5SourceRun(book: SourceBook, run: V5SourceRunResult) {
        val counts = run.marks.groupingBy { mark -> mark.state }.eachCount()
        recordV5ChapterMarks(
            book = book,
            latestObservedOrdinal = run.latestObservedOrdinal,
            latestNormalOrdinal = run.latestNormalOrdinal,
            firstBadTailOrdinal = run.firstBadTailOrdinal,
            normalCount = counts[V5ChapterMarkState.NORMAL] ?: 0,
            wrongCount = counts[V5ChapterMarkState.WRONG] ?: 0,
            nonStoryCount = counts[V5ChapterMarkState.NON_STORY] ?: 0,
            badExtractionCount = counts[V5ChapterMarkState.BAD_EXTRACTION] ?: 0,
            inconclusiveCount = counts[V5ChapterMarkState.INCONCLUSIVE] ?: 0
        )
    }

    fun recordV5ChapterMarks(
        book: SourceBook,
        latestObservedOrdinal: Int,
        latestNormalOrdinal: Int,
        firstBadTailOrdinal: Int?,
        normalCount: Int,
        wrongCount: Int,
        nonStoryCount: Int,
        badExtractionCount: Int,
        inconclusiveCount: Int
    ) {
        val key = bookSourceKey(book)
        val stats = bookSourceStatsFor(book)
        val previousVerified = stats.latestVerifiedGoodOrdinal
        val verifiedGain = (latestNormalOrdinal - previousVerified).coerceAtLeast(0)
        val normalChapterBonus = normalCount.coerceAtMost(V5_NORMAL_CHAPTER_REWARD_LIMIT) * V5_NORMAL_CHAPTER_REWARD
        val verifiedGainBonus = verifiedGain * V5_VERIFIED_NEW_CHAPTER_REWARD
        val noNormalPenalty = if (normalCount <= 0 && latestObservedOrdinal > 0) V5_NO_NORMAL_CHAPTER_PENALTY else 0
        val badPenalty =
            wrongCount * V5_WRONG_CHAPTER_PENALTY +
                badExtractionCount * V5_BAD_EXTRACTION_PENALTY +
                nonStoryCount * V5_NON_STORY_PENALTY +
                inconclusiveCount * V5_INCONCLUSIVE_PENALTY +
                noNormalPenalty
        val bookDelta = (verifiedGainBonus + normalChapterBonus - badPenalty)
            .coerceIn(-V5_BOOK_SOURCE_MAX_PENALTY, V5_BOOK_SOURCE_MAX_REWARD)
        traceScoreEvidence(
            eventName = "source_quality_v5_marks",
            book = book,
            delta = bookDelta,
            fields = arrayOf(
                "observed" to latestObservedOrdinal,
                "normalOrdinal" to latestNormalOrdinal,
                "badTail" to firstBadTailOrdinal,
                "normal" to normalCount,
                "wrong" to wrongCount,
                "nonStory" to nonStoryCount,
                "badExtraction" to badExtractionCount,
                "inconclusive" to inconclusiveCount,
                "verifiedGain" to verifiedGain,
                "badPenalty" to badPenalty
            )
        )

        stats.delta = (stats.delta + bookDelta).coerceIn(-BOOK_SOURCE_DYNAMIC_RANGE, BOOK_SOURCE_DYNAMIC_RANGE)
        stats.events += 1
        stats.successCount += normalCount
        stats.failureCount += wrongCount + badExtractionCount
        stats.wrongContentCount += wrongCount
        stats.latestObservedOrdinal = maxOf(stats.latestObservedOrdinal, latestObservedOrdinal)
        if (latestNormalOrdinal > 0) {
            stats.latestVerifiedGoodOrdinal = maxOf(stats.latestVerifiedGoodOrdinal, latestNormalOrdinal)
        }
        if (firstBadTailOrdinal != null && firstBadTailOrdinal > 0) {
            stats.badTailStartOrdinal = when (val current = stats.badTailStartOrdinal) {
                0 -> firstBadTailOrdinal
                else -> minOf(current, firstBadTailOrdinal)
            }
        }
        stats.updatedAtMs = System.currentTimeMillis()
        persistBookSourceStats(key, stats)
    }

    fun flush() {
        storage.flush()
    }

    private fun adjust(book: SourceBook, bookDelta: Int) {
        val key = bookSourceKey(book)
        val stats = bookSourceStatsFor(book)
        val before = stats.delta
        stats.delta = (stats.delta + bookDelta).coerceIn(-BOOK_SOURCE_DYNAMIC_RANGE, BOOK_SOURCE_DYNAMIC_RANGE)
        stats.events += 1
        stats.updatedAtMs = System.currentTimeMillis()
        persistBookSourceStats(key, stats)
        traceScoreApplied(book, before, bookDelta, stats)
    }

    private fun updateBookRange(
        book: SourceBook,
        observed: Int,
        verifiedGood: Int?,
        badTailStart: Int?
    ) {
        val key = bookSourceKey(book)
        val stats = bookSourceStatsFor(book)
        val beforeVerified = stats.latestVerifiedGoodOrdinal
        val beforeObserved = stats.latestObservedOrdinal
        val beforeBadTail = stats.badTailStartOrdinal
        stats.latestObservedOrdinal = maxOf(stats.latestObservedOrdinal, observed)
        if (verifiedGood != null) {
            stats.latestVerifiedGoodOrdinal = maxOf(stats.latestVerifiedGoodOrdinal, verifiedGood)
        }
        if (badTailStart != null && badTailStart > 0) {
            stats.badTailStartOrdinal = when (val current = stats.badTailStartOrdinal) {
                0 -> badTailStart
                else -> minOf(current, badTailStart)
            }
        }
        stats.updatedAtMs = System.currentTimeMillis()
        persistBookSourceStats(key, stats)
        AiBridgeTrace.state(
            "source_quality_range_updated",
            book.name,
            AiBridgeTrace.fields(
                "source" to sourceLabel(book.source),
                "observed" to stats.latestObservedOrdinal,
                "verified" to stats.latestVerifiedGoodOrdinal,
                "badTail" to stats.badTailStartOrdinal,
                "beforeObserved" to beforeObserved,
                "beforeVerified" to beforeVerified,
                "beforeBadTail" to beforeBadTail
            )
        )
    }

    private fun traceScoreEvidence(
        eventName: String,
        book: SourceBook,
        delta: Int,
        fields: Array<Pair<String, Any?>>
    ) {
        AiBridgeTrace.event(
            eventName,
            book.name,
            AiBridgeTrace.fields(
                "source" to sourceLabel(book.source),
                "delta" to delta,
                *fields
            )
        )
    }

    private fun traceScoreApplied(book: SourceBook, beforeDelta: Int, appliedDelta: Int, stats: ScoreStats) {
        AiBridgeTrace.state(
            "source_quality_score_applied",
            book.name,
            AiBridgeTrace.fields(
                "source" to sourceLabel(book.source),
                "applied" to appliedDelta,
                "beforeDelta" to beforeDelta,
                "afterDelta" to stats.delta,
                "events" to stats.events,
                "bookScore" to bookSourceScore(book)
            )
        )
        Log.i(
            TAG,
            "operation=sourceQualityApplied book=${book.name} source=${sourceLabel(book.source)} " +
                "applied=$appliedDelta beforeDelta=$beforeDelta afterDelta=${stats.delta} events=${stats.events} " +
                "bookScore=${bookSourceScore(book)}"
        )
    }

    private fun sourceTier(source: BookSource): Int {
        val scoreTier = when (sourceScore(source)) {
            in 7_000..MAX_SCORE -> 1
            in 5_000 until 7_000 -> 2
            else -> 3
        }
        val seedTier = sourceSeedFor(source)?.tier?.takeIf { it in 1..3 }
        return seedTier?.let { minOf(it, scoreTier) } ?: scoreTier
    }

    private fun personalTierSource(source: BookSource, normalizedBookName: String): ScoredSource? {
        val stats = bookSourceStatsFor(source, normalizedBookName)
        if (stats.events < BOOK_PERSONAL_TIER_MIN_EVENTS) return null
        if (stats.delta <= 0) return null
        if (stats.latestVerifiedGoodOrdinal <= 0) return null
        if (!hasReadableTail(stats)) return null
        val score = bookSourceScore(source, normalizedBookName)
        if (score < BOOK_PERSONAL_TIER_MIN_SCORE) return null
        return ScoredSource(
            source = source,
            score = score,
            tier = 0,
            bucket = sourceBucket(source)
        )
    }

    private fun hasReadableTail(stats: ScoreStats): Boolean {
        val badTailStart = stats.badTailStartOrdinal
        return badTailStart == 0 || stats.latestVerifiedGoodOrdinal >= badTailStart - 1
    }

    private fun heuristicSourceScore(source: BookSource): Int {
        return BASE_SOURCE_SCORE +
            priorityBonus(source) -
            negativePenalty(source)
    }

    private fun priorityBonus(source: BookSource): Int {
        val label = sourceLabel(source)
        val index = SOURCE_PRIORITY_MARKERS.indexOfFirst { marker -> label.contains(marker) }
        return if (index >= 0) 1_500 - index * 120 else 0
    }

    private fun negativePenalty(source: BookSource): Int {
        val label = sourceLabel(source)
        return SOURCE_NEGATIVE_MARKERS.count { marker -> label.contains(marker) } * 1_600
    }

    private fun specialSourcePenalty(source: BookSource): Int {
        val label = sourceLabel(source)
        return SPECIAL_SOURCE_MARKERS.count { marker -> label.contains(marker) } * 1_800
    }

    private fun interleaveByBucket(scored: List<ScoredSource>): List<BookSource> {
        val buckets = linkedMapOf<String, ArrayDeque<ScoredSource>>()
        scored.sortedWith(scoredSourceComparator).forEach { item ->
            buckets.getOrPut(item.bucket) { ArrayDeque() }.add(item)
        }
        val output = ArrayList<BookSource>(scored.size)
        var lastBucket: String? = null
        var bucketStreak = 0
        while (buckets.isNotEmpty()) {
            val selectedBucket = nextBucket(buckets, lastBucket, bucketStreak)
            val queue = buckets.getValue(selectedBucket)
            output.add(queue.removeFirst().source)
            if (queue.isEmpty()) buckets.remove(selectedBucket)
            if (selectedBucket == lastBucket) {
                bucketStreak += 1
            } else {
                lastBucket = selectedBucket
                bucketStreak = 1
            }
        }
        return output
    }

    private fun nextBucket(
        buckets: Map<String, ArrayDeque<ScoredSource>>,
        lastBucket: String?,
        bucketStreak: Int
    ): String {
        val ranked = buckets.entries
            .sortedWith { left, right ->
                scoredSourceComparator.compare(left.value.first(), right.value.first())
            }
        if (lastBucket == null || bucketStreak < MAX_BUCKET_STREAK) {
            return ranked.first().key
        }
        return ranked.firstOrNull { it.key != lastBucket }?.key ?: ranked.first().key
    }

    private fun sourceBucket(source: BookSource): String {
        sourceSeedFor(source)?.bucket?.takeIf { it.isNotBlank() }?.let { return it }
        val label = sourceLabel(source)
        return when {
            label.contains("出版") || label.contains("文学") || label.contains("实体") -> "published"
            label.contains("女频") || label.contains("言情") || label.contains("晋江") -> "romance"
            label.contains("po18") || label.contains("海棠") || label.contains("成人") ||
                label.contains("御书") || label.contains("腐小书") -> "adult"
            label.contains("笔趣") || label.contains("顶点") || label.contains("55读书") ||
                label.contains("69书") || label.contains("八一") -> "general"
            else -> source.sourceGroup?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT) ?: "misc"
        }
    }

    private fun sourceSeedFor(source: BookSource): SourceQualitySeed.SourceSeed? {
        return seed.recordFor(source)
    }

    private fun bookSourceStatsFor(book: SourceBook): ScoreStats {
        val key = bookSourceKey(book)
        return bookSourceStats.getOrPut(key) {
            storage.read(recordKey(BOOK_SOURCE_SCOPE, key)) ?: ScoreStats()
        }
    }

    private fun bookSourceStatsFor(source: BookSource, bookName: String): ScoreStats {
        val key = bookSourceKey(source, bookName)
        return bookSourceStats.getOrPut(key) {
            storage.read(recordKey(BOOK_SOURCE_SCOPE, key)) ?: ScoreStats()
        }
    }

    private fun persistBookSourceStats(key: String, stats: ScoreStats) {
        storage.write(recordKey(BOOK_SOURCE_SCOPE, key), stats)
    }

    private fun sourceKey(source: BookSource): String {
        return source.sourceUrl.ifBlank { source.sourceName }
    }

    private fun bookSourceKey(book: SourceBook): String {
        return bookSourceKey(book.source, book.name)
    }

    private fun bookSourceKey(source: BookSource, bookName: String): String {
        return sourceKey(source) + "\n" + normalizeBookName(bookName)
    }

    private fun normalizeBookName(name: String): String {
        return name
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+"""), "")
            .trim()
    }

    private fun sourceLabel(source: BookSource): String {
        return (source.sourceName + "\n" + source.sourceUrl + "\n" + source.sourceGroup.orEmpty())
            .lowercase(Locale.ROOT)
    }

    private fun chapterOrdinal(title: String): Int? {
        return CHAPTER_HINT_PATTERNS
            .asSequence()
            .flatMap { pattern -> pattern.findAll(title) }
            .mapNotNull { match ->
                match.groupValues
                    .asSequence()
                    .drop(1)
                    .firstOrNull { group -> group.isNotBlank() }
                    ?.toIntOrNull()
            }
            .filter { it in 1..10_000 }
            .maxOrNull()
    }

    data class SourceQualitySnapshot(
        val score: Int,
        val latestObservedOrdinal: Int,
        val latestVerifiedGoodOrdinal: Int,
        val badTailStartOrdinal: Int,
        val events: Int
    )

    private data class ScoredSource(
        val source: BookSource,
        val score: Int,
        val tier: Int,
        val bucket: String
    )

    data class SourceDebugSnapshot(
        val score: Int,
        val tier: Int,
        val bucket: String
    )

    companion object {
        private const val BOOK_SOURCE_SCOPE = "book_source"
        private const val TAG = "SourceQualityRouter"
        private const val MIN_SCORE = 0
        private const val MAX_SCORE = 10_000
        private const val BASE_SOURCE_SCORE = 5_000
        private const val BOOK_SOURCE_DYNAMIC_RANGE = 5_000
        private const val VERIFIED_NEW_CHAPTER_REWARD = 20
        private const val V5_VERIFIED_NEW_CHAPTER_REWARD = 120
        private const val V5_NORMAL_CHAPTER_REWARD = 8
        private const val V5_NORMAL_CHAPTER_REWARD_LIMIT = 6
        private const val V5_WRONG_CHAPTER_PENALTY = 70
        private const val V5_BAD_EXTRACTION_PENALTY = 45
        private const val V5_NON_STORY_PENALTY = 25
        private const val V5_INCONCLUSIVE_PENALTY = 8
        private const val V5_NO_NORMAL_CHAPTER_PENALTY = 120
        private const val V5_BOOK_SOURCE_MAX_REWARD = 360
        private const val V5_BOOK_SOURCE_MAX_PENALTY = 260
        private const val BOOK_LOCAL_SCORE_MULTIPLIER = 2
        private const val BOOK_PERSONAL_TIER_MIN_EVENTS = 2
        private const val BOOK_PERSONAL_TIER_MIN_SCORE = 4_800
        private const val MAX_BUCKET_STREAK = 1
        private const val SOURCE_QUALITY_SEED_ASSET = "source-quality-seed-v1.tsv"
        private val TIER_WATERFALL_WEIGHTS = listOf(1 to 6, 2 to 3, 3 to 1)
        private val SOURCE_PRIORITY_MARKERS = listOf(
            "笔趣阁22",
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
        private val SOURCE_NEGATIVE_MARKERS = listOf("同人")
        private val SPECIAL_SOURCE_MARKERS = listOf(
            "po18",
            "海棠",
            "haitang",
            "御书",
            "yushuwu",
            "no1xs",
            "腐小书",
            "fuxs",
            "sfacg",
            "轻小说吧",
            "yunqi.qq.com"
        )
        private val CHAPTER_HINT_PATTERNS = listOf(
            Regex("""第\s*(\d{1,5})\s*[章节回]""", RegexOption.IGNORE_CASE),
            Regex("""(?:chapter|chap\.?|ch\.?)\s*(\d{1,5})""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,5})\s*(?:chapter|chap\.?|ch\.?)""", RegexOption.IGNORE_CASE)
        )
        private val scoredSourceComparator = compareByDescending<ScoredSource> { it.score }
            .thenBy { it.source.sourceName.length }
            .thenBy { it.source.sourceName }

        private fun defaultStorage(): SourceQualityStorage {
            return runCatching {
                BufferedSourceQualityStorage(MmkvSourceQualityStorage())
            }.getOrElse {
                InMemorySourceQualityStorage()
            }
        }

        private fun defaultSeed(): SourceQualitySeed {
            return runCatching {
                App.getContext().assets.open(SOURCE_QUALITY_SEED_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                    SourceQualitySeed.fromTsv(reader.readText())
                }
            }.getOrElse {
                SourceQualitySeed.empty()
            }
        }

        private fun recordKey(scope: String, key: String): String {
            return "source_quality_v1:$scope:${sha256(key)}"
        }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

internal data class SourceQualitySeed(
    private val records: Map<String, SourceSeed>
) {
    fun recordFor(source: BookSource): SourceSeed? {
        return records[seedKey(source.sourceUrl)] ?: records[seedKey(source.sourceName)]
    }

    data class SourceSeed(
        val sourceUrl: String,
        val sourceName: String,
        val tier: Int?,
        val bucket: String?,
        val score: Int,
        val speedScore: Int,
        val coverageScore: Int,
        val freshnessScore: Int,
        val qualityScore: Int,
        val stabilityScore: Int,
        val note: String
    )

    companion object {
        fun empty(): SourceQualitySeed = SourceQualitySeed(emptyMap())

        fun fromTsv(text: String): SourceQualitySeed {
            val records = LinkedHashMap<String, SourceSeed>()
            text.lineSequence()
                .map { line -> line.trimEnd() }
                .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("#") }
                .filterNot { line -> line.startsWith("kind\t", ignoreCase = true) }
                .forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size < MIN_SOURCE_SEED_COLUMNS ||
                        parts[0].trim().lowercase(Locale.ROOT) != "source"
                    ) {
                        return@forEach
                    }
                    val sourceUrl = parts[1].trim()
                    val sourceName = parts[2].trim()
                    if (sourceUrl.isBlank() && sourceName.isBlank()) return@forEach
                    val seed = SourceSeed(
                        sourceUrl = sourceUrl,
                        sourceName = sourceName,
                        tier = parts[3].toIntOrNull()?.takeIf { it in 1..3 },
                        bucket = parts[4].trim().ifBlank { null },
                        score = parts[5].toIntOrNull()?.coerceIn(0, 10_000) ?: DEFAULT_SCORE,
                        speedScore = parts[6].toIntOrNull() ?: 0,
                        coverageScore = parts[7].toIntOrNull() ?: 0,
                        freshnessScore = parts[8].toIntOrNull() ?: 0,
                        qualityScore = parts[9].toIntOrNull() ?: 0,
                        stabilityScore = parts[10].toIntOrNull() ?: 0,
                        note = parts.getOrNull(11).orEmpty()
                    )
                    listOf(sourceUrl, sourceName)
                        .filter { it.isNotBlank() }
                        .forEach { key -> records[seedKey(key)] = seed }
                }
            return SourceQualitySeed(records)
        }

        private const val MIN_SOURCE_SEED_COLUMNS = 11
        private const val DEFAULT_SCORE = 5_000

        private fun seedKey(value: String): String {
            return value
                .trim()
                .trimEnd('/')
                .lowercase(Locale.ROOT)
        }
    }
}

internal data class ScoreStats(
    var delta: Int = 0,
    var events: Int = 0,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var timeoutCount: Int = 0,
    var wrongContentCount: Int = 0,
    var latestObservedOrdinal: Int = 0,
    var latestVerifiedGoodOrdinal: Int = 0,
    var badTailStartOrdinal: Int = 0,
    var updatedAtMs: Long = 0
) {
    fun encode(): String {
        return listOf(
            delta,
            events,
            successCount,
            failureCount,
            timeoutCount,
            wrongContentCount,
            latestObservedOrdinal,
            latestVerifiedGoodOrdinal,
            badTailStartOrdinal,
            updatedAtMs
        ).joinToString("|")
    }

    companion object {
        fun decode(value: String): ScoreStats? {
            val parts = value.split("|")
            if (parts.size < 10) return null
            return runCatching {
                ScoreStats(
                    delta = parts[0].toInt(),
                    events = parts[1].toInt(),
                    successCount = parts[2].toInt(),
                    failureCount = parts[3].toInt(),
                    timeoutCount = parts[4].toInt(),
                    wrongContentCount = parts[5].toInt(),
                    latestObservedOrdinal = parts[6].toInt(),
                    latestVerifiedGoodOrdinal = parts[7].toInt(),
                    badTailStartOrdinal = parts[8].toInt(),
                    updatedAtMs = parts[9].toLong()
                )
            }.getOrNull()
        }
    }
}

internal interface SourceQualityStorage {
    fun read(key: String): ScoreStats?
    fun write(key: String, value: ScoreStats)
    fun flush()
}

internal class InMemorySourceQualityStorage : SourceQualityStorage {
    private val values = ConcurrentHashMap<String, ScoreStats>()

    override fun read(key: String): ScoreStats? {
        return values[key]?.copy()
    }

    override fun write(key: String, value: ScoreStats) {
        values[key] = value.copy()
    }

    override fun flush() = Unit
}

private class MmkvSourceQualityStorage : SourceQualityStorage {
    private val mmkv = MMKV.mmkvWithID("source_quality_score_v1")

    override fun read(key: String): ScoreStats? {
        return mmkv.decodeString(key, null)?.let { ScoreStats.decode(it) }
    }

    override fun write(key: String, value: ScoreStats) {
        mmkv.encode(key, value.encode())
    }

    override fun flush() = Unit
}

private class BufferedSourceQualityStorage(
    private val delegate: SourceQualityStorage,
    private val flushDelayMs: Long = 750L
) : SourceQualityStorage {
    private val lock = Any()
    private val pending = LinkedHashMap<String, ScoreStats>()
    private val scheduled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "source-quality-score-flush").apply { isDaemon = true }
    }

    override fun read(key: String): ScoreStats? {
        synchronized(lock) {
            pending[key]?.let { return it.copy() }
        }
        return delegate.read(key)
    }

    override fun write(key: String, value: ScoreStats) {
        synchronized(lock) {
            pending[key] = value.copy()
        }
        scheduleFlush()
    }

    override fun flush() {
        flushPending()
        delegate.flush()
    }

    private fun scheduleFlush() {
        if (scheduled.compareAndSet(false, true)) {
            executor.schedule({ flushPending() }, flushDelayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun flushPending() {
        val snapshot = synchronized(lock) {
            if (pending.isEmpty()) {
                scheduled.set(false)
                return
            }
            LinkedHashMap(pending).also { pending.clear() }
        }
        snapshot.forEach { (key, value) -> delegate.write(key, value) }
        scheduled.set(false)
        synchronized(lock) {
            if (pending.isNotEmpty()) scheduleFlush()
        }
    }
}
