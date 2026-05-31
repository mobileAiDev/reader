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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val MAX_SAMPLE_DETAIL_CHARS = 700
private const val RARE_READABLE_MAX_SOURCE_COUNT = 2

internal class SourceQualityLabRunner(
    private val importer: LegadoSourceImporter = LegadoSourceImporter(),
    private val engine: SourceQualityProbeEngine = LegadoSourceQualityProbeEngine(),
    private val router: SourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage()),
    private val concurrentEngineFactory: () -> SourceQualityProbeEngine = { LegadoSourceQualityProbeEngine() }
) {
    fun run(
        json: String,
        config: SourceQualityLabConfig = SourceQualityLabConfig(),
        progress: ((SourceQualityLabProgress) -> Unit)? = null
    ): SourceQualityLabReport {
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
        val sourceOffset = config.normalizedSourceOffset()
        val maxSources = config.maxSources.coerceAtLeast(0)
        val totalCompatibleSources = imported.sources
            .count { source -> source.enabled && SourceEngineCompatibility.isCompatible(source) }
        val probeTotal = (totalCompatibleSources - sourceOffset)
            .coerceAtLeast(0)
            .coerceAtMost(maxSources)
        var networkProbeCount = 0
        var compatiblePosition = 0
        val plans = imported.sources.mapIndexed { index, source ->
            val needsNetworkProbe = source.enabled && SourceEngineCompatibility.isCompatible(source)
            val compatibleIndex = if (needsNetworkProbe) compatiblePosition++ else -1
            val allowNetworkProbe = needsNetworkProbe &&
                compatibleIndex >= sourceOffset &&
                networkProbeCount < maxSources
            val probePosition = if (allowNetworkProbe) {
                networkProbeCount += 1
                networkProbeCount
            } else {
                0
            }
            val skipMessage = if (needsNetworkProbe && !allowNetworkProbe) {
                "skipped by batch sourceOffset=${config.sourceOffset} " +
                    "maxSources=${config.maxSources} " +
                    "compatiblePosition=${compatibleIndex + 1}/$totalCompatibleSources"
            } else {
                ""
            }
            SourceProbePlan(
                index = index,
                source = source,
                allowNetworkProbe = allowNetworkProbe,
                probePosition = probePosition,
                probeTotal = probeTotal,
                skipMessage = skipMessage
            )
        }
        entries.addAll(probeSources(plans, config, progress))
        return SourceQualityLabReport(
            config = config,
            importedCount = imported.sources.size,
            rejectedCount = imported.rejectedSources.size,
            entries = annotateRareReadableSamples(entries),
            importFailure = null
        )
    }

    private fun probeSources(
        plans: List<SourceProbePlan>,
        config: SourceQualityLabConfig,
        progress: ((SourceQualityLabProgress) -> Unit)?
    ): List<SourceQualityLabEntry> {
        val concurrency = config.maxConcurrentSources.coerceAtLeast(1)
        if (concurrency == 1) {
            return plans.map { plan ->
                probeSource(plan, config, engine, progress)
            }
        }
        val executor = Executors.newFixedThreadPool(concurrency)
        return try {
            val futures: List<Future<SourceQualityLabEntry>> = plans.map { plan ->
                executor.submit(Callable {
                    val probeEngine = if (plan.allowNetworkProbe) {
                        concurrentEngineFactory()
                    } else {
                        engine
                    }
                    probeSource(plan, config, probeEngine, progress)
                })
            }
            futures.map { it.get() }
        } finally {
            executor.shutdownNow()
        }
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
        plan: SourceProbePlan,
        config: SourceQualityLabConfig,
        probeEngine: SourceQualityProbeEngine,
        progress: ((SourceQualityLabProgress) -> Unit)?
    ): SourceQualityLabEntry {
        val index = plan.index
        val source = plan.source
        val baseline = router.sourceDebugSnapshot(source)
        if (!source.enabled) {
            return sourceEntry(index, source, SourceQualityLabStatus.DISABLED, baseline)
        }
        if (!SourceEngineCompatibility.isCompatible(source)) {
            return sourceEntry(index, source, SourceQualityLabStatus.INCOMPATIBLE, baseline)
        }
        if (!plan.allowNetworkProbe) {
            return sourceEntry(
                index = index,
                source = source,
                status = SourceQualityLabStatus.SKIPPED_BY_LIMIT,
                baseline = baseline,
                message = plan.skipMessage.ifBlank { "skipped by maxSources=${config.maxSources}" }
            )
        }

        val sampleEntries = ArrayList<SourceQualityLabEntry>()
        val sampleKeywords = config.effectiveSampleKeywords()
        for (sampleIndex in sampleKeywords.indices) {
            val keyword = sampleKeywords[sampleIndex]
            progress?.invoke(
                SourceQualityLabProgress(
                    sourcePosition = plan.probePosition,
                    sourceTotal = plan.probeTotal,
                    sourceName = source.sourceName,
                    samplePosition = sampleIndex + 1,
                    sampleTotal = sampleKeywords.size,
                    sampleKeyword = keyword
                )
            )
            val entry = probeSourceSample(probeEngine, index, source, baseline, config, keyword)
            sampleEntries.add(entry)
            if (config.stopSourceAfterSearchFailure && entry.status == SourceQualityLabStatus.SEARCH_FAILED) {
                break
            }
        }
        val availableEntries = sampleEntries.filter { it.usable }
        val totalSearchCount = sampleEntries.sumOf { it.searchCount }
        val statusSummary = formatSampleStatusSummary(sampleEntries)
        if (availableEntries.isNotEmpty()) {
            val best = availableEntries.maxWithOrNull(compareBy<SourceQualityLabEntry> { it.score }
                .thenBy { it.contentQuality }
                .thenBy { it.contentLength })!!
            val hitEntries = sampleEntries.filter { it.status != SourceQualityLabStatus.SEARCH_EMPTY }
            val failedHitCount = hitEntries.count { it.status.isFailure }
            val score = scoreEntry(
                status = SourceQualityLabStatus.AVAILABLE,
                baselineScore = baseline.score,
                chapterCount = best.chapterCount,
                duplicateCount = best.duplicateCount,
                missingRangeCount = best.missingRangeCount,
                contentQuality = best.contentQuality,
                contentCoherence = best.contentCoherence,
                contentLength = best.contentLength,
                failedHitCount = failedHitCount
            )
            return best.copy(
                score = score,
                tier = tierForScore(score),
                searchCount = totalSearchCount,
                sampleCount = sampleEntries.size,
                availableSampleCount = availableEntries.size,
                failedSampleCount = sampleEntries.size - availableEntries.size,
                readableSamples = availableEntries.map { it.sampleKeyword }.distinct(),
                durationMs = sampleEntries.sumOf { it.durationMs },
                searchMs = sampleEntries.sumOf { it.searchMs },
                detailMs = sampleEntries.sumOf { it.detailMs },
                catalogMs = sampleEntries.sumOf { it.catalogMs },
                contentMs = sampleEntries.sumOf { it.contentMs },
                message = "readable=${availableEntries.size}/${sampleEntries.size}; " +
                    "searchEmpty=${sampleEntries.count { it.status == SourceQualityLabStatus.SEARCH_EMPTY }}; " +
                    "failedHits=$failedHitCount/${hitEntries.size}; " +
                    "best=${best.sampleKeyword}:${best.bookName}; $statusSummary"
            )
        }

        val bestPartial = sampleEntries.maxWithOrNull(compareBy<SourceQualityLabEntry> { it.score }
            .thenBy { it.contentQuality }
            .thenBy { it.contentLength })
        return bestPartial?.copy(
            searchCount = totalSearchCount,
            sampleCount = sampleEntries.size,
            availableSampleCount = 0,
            failedSampleCount = sampleEntries.count { it.status.isFailure },
            readableSamples = emptyList(),
            durationMs = sampleEntries.sumOf { it.durationMs },
            searchMs = sampleEntries.sumOf { it.searchMs },
            detailMs = sampleEntries.sumOf { it.detailMs },
            catalogMs = sampleEntries.sumOf { it.catalogMs },
            contentMs = sampleEntries.sumOf { it.contentMs },
            message = "samples=0/${sampleEntries.size}; $statusSummary"
        ) ?: sourceEntry(
            index,
            source,
            SourceQualityLabStatus.DETAIL_FAILED,
            baseline,
            message = "no sample keywords configured"
        )
    }

    private fun probeSourceSample(
        probeEngine: SourceQualityProbeEngine,
        index: Int,
        source: BookSource,
        baseline: SourceQualityRouter.SourceDebugSnapshot,
        config: SourceQualityLabConfig,
        keyword: String
    ): SourceQualityLabEntry {
        val sampleStartNs = System.nanoTime()
        var searchMs = 0L
        var detailMs = 0L
        var catalogMs = 0L
        var contentMs = 0L
        val searchStartNs = System.nanoTime()
        val search = when (val result = probeEngine.search(source, keyword)) {
            is EngineResult.Success -> {
                searchMs = elapsedMs(searchStartNs)
                result.value
            }
            is EngineResult.Failure -> {
                searchMs = elapsedMs(searchStartNs)
                return sourceSampleEntry(
                    index,
                    source,
                    SourceQualityLabStatus.SEARCH_FAILED,
                    baseline,
                    keyword,
                    durationMs = elapsedMs(sampleStartNs),
                    searchMs = searchMs,
                    message = result.failure.readableMessage()
                )
            }
        }
        val matchedBooks = if (config.requireExactSearchMatch) {
            search.books.filter { book -> isExactTitleMatch(book.name, keyword) }
        } else {
            search.books
        }
        if (matchedBooks.isEmpty()) {
            if (search.books.isNotEmpty()) {
                return sourceSampleEntry(
                    index,
                    source,
                    SourceQualityLabStatus.SEARCH_MISMATCH,
                    baseline,
                    keyword,
                    searchCount = search.books.size,
                    durationMs = elapsedMs(sampleStartNs),
                    searchMs = searchMs,
                    message = "no exact title match; first=${search.books.firstOrNull()?.name.orEmpty()}"
                )
            }
            return sourceSampleEntry(
                index,
                source,
                SourceQualityLabStatus.SEARCH_EMPTY,
                baseline,
                keyword,
                searchCount = 0,
                durationMs = elapsedMs(sampleStartNs),
                searchMs = searchMs,
                message = search.attempts.firstOrNull()?.message.orEmpty()
            )
        }

        var bestPartial: SourceQualityLabEntry? = null
        matchedBooks.take(config.maxBooksPerSource).forEach { book ->
            val detailStartNs = System.nanoTime()
            val detail = when (val result = probeEngine.getBookDetail(book)) {
                is EngineResult.Success -> {
                    detailMs += elapsedMs(detailStartNs)
                    result.value
                }
                is EngineResult.Failure -> {
                    detailMs += elapsedMs(detailStartNs)
                    bestPartial = betterPartial(
                        bestPartial,
                        sourceSampleEntry(
                            index,
                            source,
                            SourceQualityLabStatus.DETAIL_FAILED,
                            baseline,
                            keyword,
                            searchCount = search.books.size,
                            bookName = book.name,
                            author = book.author,
                            bookUrl = book.bookUrl,
                            durationMs = elapsedMs(sampleStartNs),
                            searchMs = searchMs,
                            detailMs = detailMs,
                            message = result.failure.readableMessage()
                        )
                    )
                    return@forEach
                }
            }
            val catalogStartNs = System.nanoTime()
            val catalog = when (val result = probeEngine.getCanonicalChapterList(detail)) {
                is EngineResult.Success -> {
                    catalogMs += elapsedMs(catalogStartNs)
                    result.value
                }
                is EngineResult.Failure -> {
                    catalogMs += elapsedMs(catalogStartNs)
                    bestPartial = betterPartial(
                        bestPartial,
                        sourceSampleEntry(
                            index,
                            source,
                            SourceQualityLabStatus.CATALOG_FAILED,
                            baseline,
                            keyword,
                            searchCount = search.books.size,
                            bookName = detail.name,
                            author = detail.author,
                            bookUrl = detail.book.bookUrl,
                            durationMs = elapsedMs(sampleStartNs),
                            searchMs = searchMs,
                            detailMs = detailMs,
                            catalogMs = catalogMs,
                            message = result.failure.readableMessage()
                        )
                    )
                    return@forEach
                }
            }
            if (catalog.chapters.isEmpty()) {
                bestPartial = betterPartial(
                    bestPartial,
                    sourceSampleEntry(
                        index,
                        source,
                        SourceQualityLabStatus.CATALOG_EMPTY,
                        baseline,
                        keyword,
                        searchCount = search.books.size,
                        bookName = detail.name,
                        author = detail.author,
                        bookUrl = detail.book.bookUrl,
                        durationMs = elapsedMs(sampleStartNs),
                        searchMs = searchMs,
                        detailMs = detailMs,
                        catalogMs = catalogMs,
                        message = "catalog empty"
                    )
                )
                return@forEach
            }

            val contentStartNs = System.nanoTime()
            val contentResult = validateContentSamples(probeEngine, catalog, config)
            contentMs += elapsedMs(contentStartNs)
            if (contentResult.bestContent != null && contentResult.lowQualityMessage == null) {
                val best = contentResult.bestContent
                return sourceSampleEntry(
                    index = index,
                    source = source,
                    status = SourceQualityLabStatus.AVAILABLE,
                    baseline = baseline,
                    sampleKeyword = keyword,
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
                    durationMs = elapsedMs(sampleStartNs),
                    searchMs = searchMs,
                    detailMs = detailMs,
                    catalogMs = catalogMs,
                    contentMs = contentMs,
                    message = "sampled=${contentResult.sampledCount}"
                )
            }
            bestPartial = betterPartial(
                bestPartial,
                sourceSampleEntry(
                    index = index,
                    source = source,
                    status = if (contentResult.lowQualityMessage == null) {
                        SourceQualityLabStatus.CONTENT_FAILED
                    } else {
                        SourceQualityLabStatus.CONTENT_LOW_QUALITY
                    },
                    baseline = baseline,
                    sampleKeyword = keyword,
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
                    durationMs = elapsedMs(sampleStartNs),
                    searchMs = searchMs,
                    detailMs = detailMs,
                    catalogMs = catalogMs,
                    contentMs = contentMs,
                    message = contentResult.lowQualityMessage ?: contentResult.failureMessage.orEmpty()
                )
            )
        }

        return bestPartial ?: sourceSampleEntry(
            index,
            source,
            SourceQualityLabStatus.DETAIL_FAILED,
            baseline,
            keyword,
            searchCount = search.books.size,
            durationMs = elapsedMs(sampleStartNs),
            searchMs = searchMs,
            detailMs = detailMs,
            catalogMs = catalogMs,
            contentMs = contentMs,
            message = "no book detail could be validated"
        )
    }

    private fun validateContentSamples(
        probeEngine: SourceQualityProbeEngine,
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
            val content = when (val result = probeEngine.getCleanContent(chapter)) {
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
        sampleKeyword: String = "",
        sampleCount: Int = 0,
        availableSampleCount: Int = 0,
        failedSampleCount: Int = 0,
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
        durationMs: Long = 0,
        searchMs: Long = 0,
        detailMs: Long = 0,
        catalogMs: Long = 0,
        contentMs: Long = 0,
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
            sampleKeyword = sampleKeyword,
            sampleCount = sampleCount,
            availableSampleCount = availableSampleCount,
            failedSampleCount = failedSampleCount,
            readableSamples = if (status == SourceQualityLabStatus.AVAILABLE && sampleKeyword.isNotBlank()) {
                listOf(sampleKeyword)
            } else {
                emptyList()
            },
            readableSourceCountForSample = 0,
            rareReadable = false,
            rareReadableKeywords = emptyList(),
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
            durationMs = durationMs,
            searchMs = searchMs,
            detailMs = detailMs,
            catalogMs = catalogMs,
            contentMs = contentMs,
            message = message
        )
    }

    private fun sourceSampleEntry(
        index: Int,
        source: BookSource,
        status: SourceQualityLabStatus,
        baseline: SourceQualityRouter.SourceDebugSnapshot,
        sampleKeyword: String,
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
        durationMs: Long = 0,
        searchMs: Long = 0,
        detailMs: Long = 0,
        catalogMs: Long = 0,
        contentMs: Long = 0,
        message: String = ""
    ): SourceQualityLabEntry {
        return sourceEntry(
            index = index,
            source = source,
            status = status,
            baseline = baseline,
            sampleKeyword = sampleKeyword,
            sampleCount = 1,
            availableSampleCount = if (status == SourceQualityLabStatus.AVAILABLE) 1 else 0,
            failedSampleCount = if (status.isFailure) 1 else 0,
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
            durationMs = durationMs,
            searchMs = searchMs,
            detailMs = detailMs,
            catalogMs = catalogMs,
            contentMs = contentMs,
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
            sampleKeyword = "",
            sampleCount = 0,
            availableSampleCount = 0,
            failedSampleCount = 0,
            readableSamples = emptyList(),
            readableSourceCountForSample = 0,
            rareReadable = false,
            rareReadableKeywords = emptyList(),
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
            durationMs = 0,
            searchMs = 0,
            detailMs = 0,
            catalogMs = 0,
            contentMs = 0,
            message = rejected.failure.readableMessage()
        )
    }

    private fun betterPartial(
        current: SourceQualityLabEntry?,
        candidate: SourceQualityLabEntry
    ): SourceQualityLabEntry {
        return if (current == null || candidate.score > current.score) candidate else current
    }

    private fun formatSampleStatusSummary(entries: List<SourceQualityLabEntry>): String {
        val statusSummary = entries.groupingBy { it.status }
            .eachCount()
            .entries
            .sortedBy { it.key.name }
            .joinToString(",") { "${it.key.name}=${it.value}" }
        val sampleDetails = entries.joinToString(" | ") { entry ->
            buildString {
                append(entry.sampleKeyword)
                append(":")
                append(entry.status.name)
                if (entry.bookName.isNotBlank()) {
                    append("/")
                    append(entry.bookName)
                }
            }
        }.take(MAX_SAMPLE_DETAIL_CHARS)
        return "statuses=$statusSummary; samples=$sampleDetails"
    }

    private fun annotateRareReadableSamples(entries: List<SourceQualityLabEntry>): List<SourceQualityLabEntry> {
        val readableByKeyword = entries
            .asSequence()
            .flatMap { it.readableSamples.asSequence() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        return entries.map { entry ->
            val rareKeywords = entry.readableSamples.filter { keyword ->
                (readableByKeyword[keyword] ?: 0) in 1..RARE_READABLE_MAX_SOURCE_COUNT
            }
            val readableCount = if (rareKeywords.isNotEmpty()) {
                readableByKeyword[rareKeywords.first()] ?: 0
            } else if (entry.usable && entry.sampleKeyword.isNotBlank()) {
                readableByKeyword[entry.sampleKeyword] ?: 0
            } else {
                0
            }
            entry.copy(
                readableSourceCountForSample = readableCount,
                rareReadable = rareKeywords.isNotEmpty(),
                rareReadableKeywords = rareKeywords
            )
        }
    }

    private fun scoreEntry(
        status: SourceQualityLabStatus,
        baselineScore: Int,
        chapterCount: Int,
        duplicateCount: Int,
        missingRangeCount: Int,
        contentQuality: Int,
        contentCoherence: Int,
        contentLength: Int,
        failedHitCount: Int = 0
    ): Int {
        return when (status) {
            SourceQualityLabStatus.REJECTED,
            SourceQualityLabStatus.DISABLED -> 0
            SourceQualityLabStatus.INCOMPATIBLE -> 2_500
            SourceQualityLabStatus.SKIPPED_BY_LIMIT -> 1_000
            SourceQualityLabStatus.SEARCH_FAILED -> 3_000
            SourceQualityLabStatus.SEARCH_MISMATCH -> 3_200
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
                val failedHitPenalty = (failedHitCount * 250).coerceAtMost(1_500)
                (6_000 + baselineScore / 10 + contentQuality * 15 + contentCoherence * 10 +
                    catalogScore + lengthScore - duplicatePenalty - missingPenalty)
                    .minus(failedHitPenalty)
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

    private fun isExactTitleMatch(candidate: String, keyword: String): Boolean {
        return normalizeSearchTitle(candidate) == normalizeSearchTitle(keyword)
    }

    private fun normalizeSearchTitle(value: String): String {
        return value
            .trim()
            .replace(Regex("^[《「『【\\[]+"), "")
            .replace(Regex("[》」』】\\]]+$"), "")
            .replace(Regex("[\\s　]+"), "")
    }

    private fun elapsedMs(startNs: Long): Long {
        return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0)
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

    private data class SourceProbePlan(
        val index: Int,
        val source: BookSource,
        val allowNetworkProbe: Boolean,
        val probePosition: Int,
        val probeTotal: Int,
        val skipMessage: String
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

internal val DEFAULT_SOURCE_QUALITY_SAMPLE_KEYWORDS = listOf(
    "斗破苍穹",
    "诡秘之主",
    "大奉打更人",
    "凡人修仙传",
    "我在精神病院学斩神",
    "十日终焉",
    "我不是戏神",
    "异兽迷城",
    "剑来",
    "雪中悍刀行"
)

internal data class SourceQualityLabConfig(
    val keyword: String = DEFAULT_SOURCE_QUALITY_SAMPLE_KEYWORDS.first(),
    val sampleKeywords: List<String> = DEFAULT_SOURCE_QUALITY_SAMPLE_KEYWORDS,
    val sourceOffset: Int = 0,
    val maxSources: Int = 20,
    val maxBooksPerSource: Int = 1,
    val maxContentSamples: Int = 3,
    val minContentChars: Int = 200,
    val minContentQualityScore: Int = 70,
    val minContentCoherenceScore: Int = 70,
    val requireExactSearchMatch: Boolean = true,
    val stopSourceAfterSearchFailure: Boolean = true,
    val maxConcurrentSources: Int = 1
) {
    fun effectiveSampleKeywords(): List<String> {
        val candidates = sampleKeywords.ifEmpty { listOf(keyword) }
        return candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf(DEFAULT_SOURCE_QUALITY_SAMPLE_KEYWORDS.first()) }
    }

    fun normalizedSourceOffset(): Int = sourceOffset.coerceAtLeast(0)
}

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
        val sampleKeywords = config.effectiveSampleKeywords()
        return buildString {
            appendLine("Source quality lab")
            appendLine("sampleBookCount=${sampleKeywords.size}")
            appendLine("sampleKeywords=${sampleKeywords.joinToString()}")
            appendLine(
                    "sourceOffset=${config.sourceOffset.coerceAtLeast(0)} " +
                "maxSources=${config.maxSources} " +
                    "maxConcurrentSources=${config.maxConcurrentSources.coerceAtLeast(1)} " +
                    "requireExactSearchMatch=${config.requireExactSearchMatch} " +
                    "stopSourceAfterSearchFailure=${config.stopSourceAfterSearchFailure}"
            )
            appendLine("imported=$importedCount rejected=$rejectedCount disabled=$disabledCount incompatible=$incompatibleCount")
            appendLine("probed=$probedCount available=$availableCount failed=$failedCount")
            appendLine("bookSamples=${entries.sumOf { it.availableSampleCount }}/${entries.sumOf { it.sampleCount }}")
            appendLine("rareReadable=${entries.count { it.rareReadable }}")
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
            appendLine("Rare readable")
            entries.filter { it.rareReadable }
                .sortedBy { it.readableSourceCountForSample }
                .take(30)
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
            "sampleKeyword",
            "sampleCount",
            "availableSampleCount",
            "failedSampleCount",
            "readableSamples",
            "readableSourceCountForSample",
            "rareReadable",
            "rareReadableKeywords",
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
            "durationMs",
            "searchMs",
            "detailMs",
            "catalogMs",
            "contentMs",
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
                            entry.sampleKeyword,
                            entry.sampleCount,
                            entry.availableSampleCount,
                            entry.failedSampleCount,
                            entry.readableSamples.joinToString("|"),
                            entry.readableSourceCountForSample,
                            entry.rareReadable,
                            entry.rareReadableKeywords.joinToString("|"),
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
                            entry.durationMs,
                            entry.searchMs,
                            entry.detailMs,
                            entry.catalogMs,
                            entry.contentMs,
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

internal data class SourceQualityLabProgress(
    val sourcePosition: Int,
    val sourceTotal: Int,
    val sourceName: String,
    val samplePosition: Int,
    val sampleTotal: Int,
    val sampleKeyword: String
) {
    fun toDisplayText(): String {
        return "Running isolated source quality lab " +
            "source=$sourcePosition/$sourceTotal " +
            "sample=$samplePosition/$sampleTotal " +
            "book=$sampleKeyword\nsourceName=$sourceName"
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
    val sampleKeyword: String,
    val sampleCount: Int,
    val availableSampleCount: Int,
    val failedSampleCount: Int,
    val readableSamples: List<String>,
    val readableSourceCountForSample: Int,
    val rareReadable: Boolean,
    val rareReadableKeywords: List<String>,
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
    val durationMs: Long,
    val searchMs: Long,
    val detailMs: Long,
    val catalogMs: Long,
    val contentMs: Long,
    val message: String
) {
    fun summaryLine(): String {
        val rareBooks = rareReadableKeywords.joinToString("|")
        return "tier=$tier score=$score status=${status.name} source=$sourceName " +
            "samples=$availableSampleCount/$sampleCount book=$bookName chapters=$chapterCount " +
            "quality=$contentQuality coherence=$contentCoherence " +
            "rareReadable=$rareReadable rareBooks=$rareBooks " +
            "readableSources=$readableSourceCountForSample msg=$message"
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
    SEARCH_MISMATCH(isNetworkProbe = true, isFailure = true),
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
