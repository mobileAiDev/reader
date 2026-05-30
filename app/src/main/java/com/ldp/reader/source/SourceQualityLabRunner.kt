package com.ldp.reader.source

import com.ldp.reader.sourceengine.EngineFailure
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.legado.JdkHttpFetcher
import com.ldp.reader.sourceengine.legado.LegadoSourceEngine
import com.ldp.reader.sourceengine.legado.LegadoSourceImporter
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.CanonicalChapterList
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceBookDetail
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.sourceengine.model.SourceImportFailure
import com.ldp.reader.sourceengine.model.SourceSearchReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class SourceQualityLabRunner(
    private val importer: LegadoSourceImporter = LegadoSourceImporter(),
    private val engine: SourceQualityProbeEngine = LegadoSourceQualityProbeEngine(),
    private val router: SourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
) {
    fun run(json: String, config: SourceQualityLabConfig = SourceQualityLabConfig()): SourceQualityLabReport {
        val imported = when (val result = importer.importJson(json)) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> {
                return SourceQualityLabReport(
                    config = config,
                    importedCount = 0,
                    rejectedCount = 0,
                    entries = emptyList(),
                    importFailure = result.failure.readableMessage()
                )
            }
        }

        val entries = ArrayList<SourceQualityLabEntry>()
        imported.rejectedSources.forEach { rejected ->
            entries.add(rejectedEntry(rejected))
        }
        var networkProbeCount = 0
        imported.sources.forEachIndexed { index, source ->
            val needsNetworkProbe = source.enabled && SourceEngineCompatibility.isCompatible(source)
            val allowNetworkProbe = !needsNetworkProbe || networkProbeCount < config.maxSources
            if (needsNetworkProbe && allowNetworkProbe) {
                networkProbeCount += 1
            }
            entries.add(probeSource(index, source, config, allowNetworkProbe))
        }
        return SourceQualityLabReport(
            config = config,
            importedCount = imported.sources.size,
            rejectedCount = imported.rejectedSources.size,
            entries = entries,
            importFailure = null
        )
    }

    fun writeReport(report: SourceQualityLabReport, outputDir: File): SourceQualityLabArtifacts {
        outputDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(report.generatedAtMs))
        val summaryFile = File(outputDir, "source-quality-lab-$stamp.txt")
        val tsvFile = File(outputDir, "source-quality-lab-$stamp.tsv")
        val latestSummaryFile = File(outputDir, "source-quality-lab-latest.txt")
        val latestTsvFile = File(outputDir, "source-quality-lab-latest.tsv")
        val summary = report.toSummaryText()
        val tsv = report.toTsv()
        summaryFile.writeText(summary)
        tsvFile.writeText(tsv)
        latestSummaryFile.writeText(summary)
        latestTsvFile.writeText(tsv)
        return SourceQualityLabArtifacts(summaryFile, tsvFile, latestSummaryFile, latestTsvFile)
    }

    private fun probeSource(
        index: Int,
        source: BookSource,
        config: SourceQualityLabConfig,
        allowNetworkProbe: Boolean
    ): SourceQualityLabEntry {
        val baseline = router.sourceDebugSnapshot(source)
        if (!source.enabled) {
            return sourceEntry(index, source, SourceQualityLabStatus.DISABLED, baseline)
        }
        if (!SourceEngineCompatibility.isCompatible(source)) {
            return sourceEntry(index, source, SourceQualityLabStatus.INCOMPATIBLE, baseline)
        }
        if (!allowNetworkProbe) {
            return sourceEntry(
                index = index,
                source = source,
                status = SourceQualityLabStatus.SKIPPED_BY_LIMIT,
                baseline = baseline,
                message = "skipped by maxSources=${config.maxSources}"
            )
        }

        val search = when (val result = engine.search(source, config.keyword)) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> {
                return sourceEntry(
                    index,
                    source,
                    SourceQualityLabStatus.SEARCH_FAILED,
                    baseline,
                    message = result.failure.readableMessage()
                )
            }
        }
        if (search.books.isEmpty()) {
            return sourceEntry(
                index,
                source,
                SourceQualityLabStatus.SEARCH_EMPTY,
                baseline,
                searchCount = 0,
                message = search.attempts.firstOrNull()?.message.orEmpty()
            )
        }

        var bestPartial: SourceQualityLabEntry? = null
        search.books.take(config.maxBooksPerSource).forEach { book ->
            val detail = when (val result = engine.getBookDetail(book)) {
                is EngineResult.Success -> result.value
                is EngineResult.Failure -> {
                    bestPartial = betterPartial(
                        bestPartial,
                        sourceEntry(
                            index,
                            source,
                            SourceQualityLabStatus.DETAIL_FAILED,
                            baseline,
                            searchCount = search.books.size,
                            bookName = book.name,
                            author = book.author,
                            bookUrl = book.bookUrl,
                            message = result.failure.readableMessage()
                        )
                    )
                    return@forEach
                }
            }
            val catalog = when (val result = engine.getCanonicalChapterList(detail)) {
                is EngineResult.Success -> result.value
                is EngineResult.Failure -> {
                    bestPartial = betterPartial(
                        bestPartial,
                        sourceEntry(
                            index,
                            source,
                            SourceQualityLabStatus.CATALOG_FAILED,
                            baseline,
                            searchCount = search.books.size,
                            bookName = detail.name,
                            author = detail.author,
                            bookUrl = detail.book.bookUrl,
                            message = result.failure.readableMessage()
                        )
                    )
                    return@forEach
                }
            }
            if (catalog.chapters.isEmpty()) {
                bestPartial = betterPartial(
                    bestPartial,
                    sourceEntry(
                        index,
                        source,
                        SourceQualityLabStatus.CATALOG_EMPTY,
                        baseline,
                        searchCount = search.books.size,
                        bookName = detail.name,
                        author = detail.author,
                        bookUrl = detail.book.bookUrl,
                        message = "catalog empty"
                    )
                )
                return@forEach
            }

            val contentResult = validateContentSamples(catalog, config)
            if (contentResult.bestContent != null && contentResult.lowQualityMessage == null) {
                val best = contentResult.bestContent
                return sourceEntry(
                    index = index,
                    source = source,
                    status = SourceQualityLabStatus.AVAILABLE,
                    baseline = baseline,
                    searchCount = search.books.size,
                    bookName = detail.name,
                    author = detail.author,
                    bookUrl = detail.book.bookUrl,
                    chapterCount = catalog.chapters.size,
                    duplicateCount = catalog.duplicateCount,
                    missingRangeCount = catalog.missingOrdinalRanges.size,
                    contentQuality = best.report.qualityScore,
                    contentCoherence = best.report.coherenceScore,
                    contentLength = best.report.cleanedLength,
                    message = "sampled=${contentResult.sampledCount}"
                )
            }
            bestPartial = betterPartial(
                bestPartial,
                sourceEntry(
                    index = index,
                    source = source,
                    status = if (contentResult.lowQualityMessage == null) {
                        SourceQualityLabStatus.CONTENT_FAILED
                    } else {
                        SourceQualityLabStatus.CONTENT_LOW_QUALITY
                    },
                    baseline = baseline,
                    searchCount = search.books.size,
                    bookName = detail.name,
                    author = detail.author,
                    bookUrl = detail.book.bookUrl,
                    chapterCount = catalog.chapters.size,
                    duplicateCount = catalog.duplicateCount,
                    missingRangeCount = catalog.missingOrdinalRanges.size,
                    contentQuality = contentResult.bestContent?.report?.qualityScore ?: 0,
                    contentCoherence = contentResult.bestContent?.report?.coherenceScore ?: 0,
                    contentLength = contentResult.bestContent?.report?.cleanedLength ?: 0,
                    message = contentResult.lowQualityMessage ?: contentResult.failureMessage.orEmpty()
                )
            )
        }

        return bestPartial ?: sourceEntry(
            index,
            source,
            SourceQualityLabStatus.DETAIL_FAILED,
            baseline,
            searchCount = search.books.size,
            message = "no book detail could be validated"
        )
    }

    private fun validateContentSamples(
        catalog: CanonicalChapterList,
        config: SourceQualityLabConfig
    ): ContentSampleResult {
        var sampled = 0
        var bestContent: CleanContent? = null
        var lastFailure: String? = null
        sampleChapterIndices(catalog.chapters.size, config.maxContentSamples).forEach { index ->
            val chapter = catalog.chapters[index].sourceChapters.firstOrNull()
            if (chapter == null) {
                lastFailure = "sample ${index + 1} has no source chapter"
                return@forEach
            }
            val content = when (val result = engine.getCleanContent(chapter)) {
                is EngineResult.Success -> result.value
                is EngineResult.Failure -> {
                    lastFailure = result.failure.readableMessage()
                    return@forEach
                }
            }
            sampled += 1
            bestContent = listOfNotNull(bestContent, content)
                .maxWithOrNull(compareBy<CleanContent> { it.report.qualityScore }
                    .thenBy { it.report.coherenceScore }
                    .thenBy { it.report.cleanedLength })
        }
        val best = bestContent
        if (best == null) {
            return ContentSampleResult(null, sampled, failureMessage = lastFailure ?: "no content sample")
        }
        val lowQualityMessage = when {
            best.report.cleanedLength < config.minContentChars ->
                "content too short: ${best.report.cleanedLength}"
            best.report.qualityScore < config.minContentQualityScore ->
                "quality too low: ${best.report.qualityScore}"
            best.report.coherenceScore < config.minContentCoherenceScore ->
                "coherence too low: ${best.report.coherenceScore}"
            else -> null
        }
        return ContentSampleResult(best, sampled, lowQualityMessage = lowQualityMessage)
    }

    private fun sourceEntry(
        index: Int,
        source: BookSource,
        status: SourceQualityLabStatus,
        baseline: SourceQualityRouter.SourceDebugSnapshot,
        searchCount: Int = 0,
        bookName: String = "",
        author: String = "",
        bookUrl: String = "",
        chapterCount: Int = 0,
        duplicateCount: Int = 0,
        missingRangeCount: Int = 0,
        contentQuality: Int = 0,
        contentCoherence: Int = 0,
        contentLength: Int = 0,
        message: String = ""
    ): SourceQualityLabEntry {
        val score = scoreEntry(
            status = status,
            baselineScore = baseline.score,
            chapterCount = chapterCount,
            duplicateCount = duplicateCount,
            missingRangeCount = missingRangeCount,
            contentQuality = contentQuality,
            contentCoherence = contentCoherence,
            contentLength = contentLength
        )
        return SourceQualityLabEntry(
            index = index,
            sourceName = source.sourceName,
            sourceUrl = source.sourceUrl,
            enabled = source.enabled,
            status = status,
            usable = status == SourceQualityLabStatus.AVAILABLE,
            score = score,
            tier = tierForScore(score),
            seedTier = baseline.tier,
            seedScore = baseline.score,
            bucket = baseline.bucket,
            searchCount = searchCount,
            bookName = bookName,
            author = author,
            bookUrl = bookUrl,
            chapterCount = chapterCount,
            duplicateCount = duplicateCount,
            missingRangeCount = missingRangeCount,
            contentQuality = contentQuality,
            contentCoherence = contentCoherence,
            contentLength = contentLength,
            message = message
        )
    }

    private fun rejectedEntry(rejected: SourceImportFailure): SourceQualityLabEntry {
        return SourceQualityLabEntry(
            index = rejected.index,
            sourceName = "",
            sourceUrl = "",
            enabled = false,
            status = SourceQualityLabStatus.REJECTED,
            usable = false,
            score = 0,
            tier = 0,
            seedTier = 0,
            seedScore = 0,
            bucket = "",
            searchCount = 0,
            bookName = "",
            author = "",
            bookUrl = "",
            chapterCount = 0,
            duplicateCount = 0,
            missingRangeCount = 0,
            contentQuality = 0,
            contentCoherence = 0,
            contentLength = 0,
            message = rejected.failure.readableMessage()
        )
    }

    private fun betterPartial(
        current: SourceQualityLabEntry?,
        candidate: SourceQualityLabEntry
    ): SourceQualityLabEntry {
        return if (current == null || candidate.score > current.score) candidate else current
    }

    private fun scoreEntry(
        status: SourceQualityLabStatus,
        baselineScore: Int,
        chapterCount: Int,
        duplicateCount: Int,
        missingRangeCount: Int,
        contentQuality: Int,
        contentCoherence: Int,
        contentLength: Int
    ): Int {
        return when (status) {
            SourceQualityLabStatus.REJECTED,
            SourceQualityLabStatus.DISABLED -> 0
            SourceQualityLabStatus.INCOMPATIBLE -> 2_500
            SourceQualityLabStatus.SKIPPED_BY_LIMIT -> 1_000
            SourceQualityLabStatus.SEARCH_FAILED -> 3_000
            SourceQualityLabStatus.SEARCH_EMPTY -> 4_000
            SourceQualityLabStatus.DETAIL_FAILED -> 4_500
            SourceQualityLabStatus.CATALOG_FAILED,
            SourceQualityLabStatus.CATALOG_EMPTY -> 4_800
            SourceQualityLabStatus.CONTENT_FAILED,
            SourceQualityLabStatus.CONTENT_LOW_QUALITY -> 5_500
            SourceQualityLabStatus.AVAILABLE -> {
                val catalogScore = chapterCount.coerceAtMost(1_000)
                val duplicatePenalty = (duplicateCount * 20).coerceAtMost(600)
                val missingPenalty = (missingRangeCount * 25).coerceAtMost(600)
                val lengthScore = (contentLength / 20).coerceAtMost(500)
                (6_000 + baselineScore / 10 + contentQuality * 15 + contentCoherence * 10 +
                    catalogScore + lengthScore - duplicatePenalty - missingPenalty)
                    .coerceIn(6_500, 10_000)
            }
        }
    }

    private fun tierForScore(score: Int): Int {
        return when {
            score >= 8_000 -> 1
            score >= 6_500 -> 2
            score > 0 -> 3
            else -> 0
        }
    }

    private fun sampleChapterIndices(chapterCount: Int, maxSamples: Int): List<Int> {
        if (chapterCount <= 0 || maxSamples <= 0) return emptyList()
        val indices = LinkedHashSet<Int>()
        indices.add(0)
        if (maxSamples >= 2 && chapterCount > 2) indices.add(chapterCount / 2)
        if (maxSamples >= 3 && chapterCount > 1) indices.add(chapterCount - 1)
        return indices.take(maxSamples).filter { it in 0 until chapterCount }
    }

    private fun EngineFailure.readableMessage(): String {
        return when (this) {
            is EngineFailure.ContractViolation -> "contract: $message"
            is EngineFailure.NetworkError -> "network: $message"
            is EngineFailure.ParseError -> "parse: $message"
            is EngineFailure.RuleError -> "rule: $message"
        }
    }

    private data class ContentSampleResult(
        val bestContent: CleanContent?,
        val sampledCount: Int,
        val failureMessage: String? = null,
        val lowQualityMessage: String? = null
    )
}

internal interface SourceQualityProbeEngine {
    fun search(source: BookSource, keyword: String): EngineResult<SourceSearchReport>
    fun getBookDetail(book: SourceBook): EngineResult<SourceBookDetail>
    fun getCanonicalChapterList(detail: SourceBookDetail): EngineResult<CanonicalChapterList>
    fun getCleanContent(chapter: SourceChapter): EngineResult<CleanContent>
}

internal class LegadoSourceQualityProbeEngine(
    private val engine: LegadoSourceEngine = LegadoSourceEngine(fetcher = JdkHttpFetcher(3000, 5000))
) : SourceQualityProbeEngine {
    override fun search(source: BookSource, keyword: String): EngineResult<SourceSearchReport> {
        return engine.search(listOf(source), keyword, maxSources = 1)
    }

    override fun getBookDetail(book: SourceBook): EngineResult<SourceBookDetail> {
        return engine.getBookDetail(book)
    }

    override fun getCanonicalChapterList(detail: SourceBookDetail): EngineResult<CanonicalChapterList> {
        return engine.getCanonicalChapterList(detail)
    }

    override fun getCleanContent(chapter: SourceChapter): EngineResult<CleanContent> {
        return engine.getCleanContent(chapter)
    }
}

internal data class SourceQualityLabConfig(
    val keyword: String = "斗破苍穹",
    val maxSources: Int = 80,
    val maxBooksPerSource: Int = 1,
    val maxContentSamples: Int = 3,
    val minContentChars: Int = 200,
    val minContentQualityScore: Int = 70,
    val minContentCoherenceScore: Int = 70
)

internal data class SourceQualityLabReport(
    val config: SourceQualityLabConfig,
    val importedCount: Int,
    val rejectedCount: Int,
    val entries: List<SourceQualityLabEntry>,
    val importFailure: String?,
    val generatedAtMs: Long = System.currentTimeMillis()
) {
    val disabledCount: Int
        get() = entries.count { it.status == SourceQualityLabStatus.DISABLED }
    val incompatibleCount: Int
        get() = entries.count { it.status == SourceQualityLabStatus.INCOMPATIBLE }
    val availableCount: Int
        get() = entries.count { it.status == SourceQualityLabStatus.AVAILABLE }
    val failedCount: Int
        get() = entries.count { it.status.isFailure }
    val probedCount: Int
        get() = entries.count { it.status.isNetworkProbe }

    fun toSummaryText(): String {
        return buildString {
            appendLine("Source quality lab")
            appendLine("keyword=${config.keyword}")
            appendLine("imported=$importedCount rejected=$rejectedCount disabled=$disabledCount incompatible=$incompatibleCount")
            appendLine("probed=$probedCount available=$availableCount failed=$failedCount")
            appendLine("tier1=${entries.count { it.tier == 1 }} tier2=${entries.count { it.tier == 2 }} tier3=${entries.count { it.tier == 3 }} tier0=${entries.count { it.tier == 0 }}")
            importFailure?.let {
                appendLine()
                appendLine("importFailure=$it")
            }
            appendLine()
            appendLine("Top available")
            entries.filter { it.usable }
                .sortedByDescending { it.score }
                .take(20)
                .forEach { appendLine(it.summaryLine()) }
            appendLine()
            appendLine("Failures and disabled")
            entries.filter { !it.usable }
                .sortedWith(compareBy<SourceQualityLabEntry> { it.status.name }.thenBy { it.index })
                .take(60)
                .forEach { appendLine(it.summaryLine()) }
        }
    }

    fun toTsv(): String {
        val header = listOf(
            "index",
            "status",
            "usable",
            "tier",
            "score",
            "seedTier",
            "seedScore",
            "bucket",
            "sourceName",
            "sourceUrl",
            "enabled",
            "searchCount",
            "bookName",
            "author",
            "bookUrl",
            "chapterCount",
            "duplicateCount",
            "missingRangeCount",
            "contentQuality",
            "contentCoherence",
            "contentLength",
            "message"
        )
        return buildString {
            appendLine(header.joinToString("\t"))
            entries.sortedWith(compareBy<SourceQualityLabEntry> { it.tier == 0 }.thenByDescending { it.score })
                .forEach { entry ->
                    appendLine(
                        listOf(
                            entry.index,
                            entry.status.name,
                            entry.usable,
                            entry.tier,
                            entry.score,
                            entry.seedTier,
                            entry.seedScore,
                            entry.bucket,
                            entry.sourceName,
                            entry.sourceUrl,
                            entry.enabled,
                            entry.searchCount,
                            entry.bookName,
                            entry.author,
                            entry.bookUrl,
                            entry.chapterCount,
                            entry.duplicateCount,
                            entry.missingRangeCount,
                            entry.contentQuality,
                            entry.contentCoherence,
                            entry.contentLength,
                            entry.message
                        ).joinToString("\t") { value -> value.toString().tsvCell() }
                    )
                }
        }
    }

    private fun String.tsvCell(): String {
        return replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }
}

internal data class SourceQualityLabEntry(
    val index: Int,
    val sourceName: String,
    val sourceUrl: String,
    val enabled: Boolean,
    val status: SourceQualityLabStatus,
    val usable: Boolean,
    val score: Int,
    val tier: Int,
    val seedTier: Int,
    val seedScore: Int,
    val bucket: String,
    val searchCount: Int,
    val bookName: String,
    val author: String,
    val bookUrl: String,
    val chapterCount: Int,
    val duplicateCount: Int,
    val missingRangeCount: Int,
    val contentQuality: Int,
    val contentCoherence: Int,
    val contentLength: Int,
    val message: String
) {
    fun summaryLine(): String {
        return "tier=$tier score=$score status=${status.name} source=$sourceName " +
            "book=$bookName chapters=$chapterCount quality=$contentQuality coherence=$contentCoherence msg=$message"
    }
}

internal enum class SourceQualityLabStatus(
    val isNetworkProbe: Boolean,
    val isFailure: Boolean
) {
    REJECTED(isNetworkProbe = false, isFailure = false),
    DISABLED(isNetworkProbe = false, isFailure = false),
    INCOMPATIBLE(isNetworkProbe = false, isFailure = false),
    SKIPPED_BY_LIMIT(isNetworkProbe = false, isFailure = false),
    SEARCH_FAILED(isNetworkProbe = true, isFailure = true),
    SEARCH_EMPTY(isNetworkProbe = true, isFailure = true),
    DETAIL_FAILED(isNetworkProbe = true, isFailure = true),
    CATALOG_FAILED(isNetworkProbe = true, isFailure = true),
    CATALOG_EMPTY(isNetworkProbe = true, isFailure = true),
    CONTENT_FAILED(isNetworkProbe = true, isFailure = true),
    CONTENT_LOW_QUALITY(isNetworkProbe = true, isFailure = true),
    AVAILABLE(isNetworkProbe = true, isFailure = false)
}

internal data class SourceQualityLabArtifacts(
    val summaryFile: File,
    val tsvFile: File,
    val latestSummaryFile: File,
    val latestTsvFile: File
)
