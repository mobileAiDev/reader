package com.ldp.reader.algorithmtest.source

import com.ldp.reader.algorithmtest.AlgorithmTrace
import com.ldp.reader.algorithmtest.TraceRecorder
import com.ldp.reader.algorithmtest.catalog.CatalogFusionProbe
import com.ldp.reader.algorithmtest.catalog.NamedCatalog
import com.ldp.reader.algorithmtest.core.ChapterInput
import com.ldp.reader.algorithmtest.core.ChapterQualityGate
import com.ldp.reader.algorithmtest.core.CleanReport
import com.ldp.reader.algorithmtest.core.NovelPollutionAnalyzer
import com.ldp.reader.sourceengine.EngineFailure
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.legado.JdkHttpFetcher
import com.ldp.reader.sourceengine.legado.LegadoSourceEngine
import com.ldp.reader.sourceengine.legado.LegadoSourceImporter
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SourceExperimentConfig(
    val title: String,
    val author: String,
    val sourceJson: String,
    val qualitySeedTsv: String = "",
    val maxSearchSources: Int = 160,
    val maxCatalogSources: Int = 24,
    val maxFingerprintChapters: Int = 50,
    val maxTailChapters: Int = 50,
    val minSampledChapters: Int = 8,
    val minCleanMemoryChapters: Int = 8,
    val maxMemoryBackfillAttempts: Int = 256,
    val minCatalogChapters: Int = 80,
    val downloadWholeBook: Boolean = false
)

data class SourceExperimentReport(
    val importedSources: Int,
    val searchMatches: Int,
    val sourceFailures: List<String>,
    val sampledChapters: List<ChapterInput>,
    val catalogSummary: String,
    val sampleValidationSummary: String,
    val cleanReport: CleanReport,
    val sourceTrace: List<String>
) {
    fun humanSummary(): String {
        return buildString {
            appendLine("Imported sources: $importedSources")
            appendLine("Search matches: $searchMatches")
            appendLine("Source failures: ${sourceFailures.size}")
            sourceFailures.take(12).forEach { failure -> appendLine("- $failure") }
            appendLine()
            appendLine(catalogSummary)
            appendLine()
            appendLine(sampleValidationSummary)
            appendLine()
            appendLine(cleanReport.humanSummary())
        }
    }
}
data class SourceFetchReport(
    val importedSources: Int,
    val searchMatches: Int,
    val sourceFailures: List<String>,
    val sampledChapters: List<ChapterInput>,
    val catalogSummary: String,
    val sampleValidationSummary: String,
    val sourceTrace: List<String>
) {
    fun humanSummary(): String {
        return buildString {
            appendLine("Imported sources: $importedSources")
            appendLine("Search matches: $searchMatches")
            appendLine("Source failures: ${sourceFailures.size}")
            sourceFailures.take(12).forEach { failure -> appendLine("- $failure") }
            appendLine()
            appendLine(catalogSummary)
            appendLine()
            appendLine(sampleValidationSummary)
            appendLine()
            appendLine("Fetched chapters: ${sampledChapters.size}")
        }
    }
}

data class ImportedSourceSet(
    val sources: List<BookSource>,
    val rejectedCount: Int
)

private data class FetchedSourceData(
    val importedSources: Int,
    val searchMatches: Int,
    val sourceFailures: List<String>,
    val sampledChapters: List<ChapterInput>,
    val seedChapterIndexes: Set<Int>,
    val catalogSummary: String,
    val sampleValidationSummary: String
)

private data class FetchedBookCandidate(
    val chapters: List<ChapterInput>,
    val seedChapterIndexes: Set<Int>,
    val cleanMemoryChapters: Int
)

private data class RealNovelSelectionPlan(
    val allPositions: List<Int>,
    val seedPositions: Set<Int>,
    val targetPositions: Set<Int>,
    val seedEndExclusive: Int
)

class SourceExperimentRunner(
    private val importer: LegadoSourceImporter = LegadoSourceImporter(),
    private val engine: LegadoSourceEngine = LegadoSourceEngine(
        fetcher = JdkHttpFetcher(
            connectTimeoutMillis = 3_000,
            readTimeoutMillis = 4_000
        )
    ),
    private val analyzer: NovelPollutionAnalyzer = NovelPollutionAnalyzer(),
    private val catalogFusionProbe: CatalogFusionProbe = CatalogFusionProbe(),
    private val trace: TraceRecorder = AlgorithmTrace
) {
    private val qualityGate = ChapterQualityGate()

    suspend fun run(config: SourceExperimentConfig): SourceExperimentReport = withContext(Dispatchers.IO) {
        require(config.title.isNotBlank()) { "title is required" }
        require(config.sourceJson.isNotBlank()) { "sourceJson is required" }
        runWithSources(config, importSources(config.sourceJson))
    }

    fun importSources(sourceJson: String): ImportedSourceSet {
        require(sourceJson.isNotBlank()) { "sourceJson is required" }
        val importReport = importer.importJson(sourceJson).valueOrThrow("source import")
        return ImportedSourceSet(
            sources = importReport.sources.filter { source -> source.enabled },
            rejectedCount = importReport.rejectedSources.size
        )
    }

    suspend fun runWithSources(
        config: SourceExperimentConfig,
        importedSources: ImportedSourceSet
    ): SourceExperimentReport = withContext(Dispatchers.IO) {
        val fetched = fetchSourceData(config, importedSources)
        trace.event(
            "analysis_started",
            config.title,
            trace.fields("chapters" to fetched.sampledChapters.size)
        )
        val cleanReport = analyzer.analyze(
            title = config.title,
            author = config.author,
            chapters = fetched.sampledChapters,
            seedChapterIndexes = fetched.seedChapterIndexes
        )
        trace.state(
            "analysis_finished",
            config.title,
            trace.fields(
                "chunks" to cleanReport.chunkCount,
                "suggestions" to cleanReport.suggestions.size
            )
        )
        SourceExperimentReport(
            importedSources = fetched.importedSources,
            searchMatches = fetched.searchMatches,
            sourceFailures = fetched.sourceFailures,
            sampledChapters = fetched.sampledChapters,
            catalogSummary = fetched.catalogSummary,
            sampleValidationSummary = fetched.sampleValidationSummary,
            cleanReport = cleanReport,
            sourceTrace = trace.snapshot()
        )
    }

    suspend fun fetchOnlyWithSources(
        config: SourceExperimentConfig,
        importedSources: ImportedSourceSet
    ): SourceFetchReport = withContext(Dispatchers.IO) {
        val fetched = fetchSourceData(config, importedSources)
        SourceFetchReport(
            importedSources = fetched.importedSources,
            searchMatches = fetched.searchMatches,
            sourceFailures = fetched.sourceFailures,
            sampledChapters = fetched.sampledChapters,
            catalogSummary = fetched.catalogSummary,
            sampleValidationSummary = fetched.sampleValidationSummary,
            sourceTrace = trace.snapshot()
        )
    }

    private suspend fun fetchSourceData(
        config: SourceExperimentConfig,
        importedSources: ImportedSourceSet
    ): FetchedSourceData {
        require(config.title.isNotBlank()) { "title is required" }
        require(importedSources.sources.isNotEmpty()) { "no imported sources" }
        trace.clear()
        val sources = importedSources.sources
        trace.state(
            "source_import_reused",
            config.title,
            trace.fields("accepted" to sources.size, "rejected" to importedSources.rejectedCount)
        )

        val catalogs = ArrayList<NamedCatalog>()
        val fetchedChapters = ArrayList<ChapterInput>()
        val fetchedSeedChapterIndexes = LinkedHashSet<Int>()
        val sourceFailures = ArrayList<String>()
        val titleKey = normalizeTitle(config.title)
        val authorKey = normalizeAuthor(config.author)
        val sourceQuality = SourceQualityIndex.parse(config.qualitySeedTsv)
        var exactMatchCount = 0
        var catalogAttempts = 0

        val searchSources = searchableSources(sources, config.maxSearchSources, sourceQuality)
        trace.event(
            "search_started",
            config.title,
            trace.fields(
                "sources" to sources.size,
                "searchable" to sources.count { source -> source.enabled && !source.searchUrl.isNullOrBlank() },
                "planned" to searchSources.size,
                "qualityRecords" to sourceQuality.size,
                "tier1Planned" to searchSources.count { ranked -> ranked.quality?.tier == 1 }
            )
        )
        sourceLoop@ for ((index, rankedSource) in searchSources.withIndex()) {
            val source = rankedSource.source
            val books = searchSource(config, rankedSource, index)
            val matches = books
                .filter { book -> normalizeTitle(book.name) == titleKey }
                .filter { book -> authorKey.isBlank() || normalizeAuthor(book.author) == authorKey }
                .distinctBy { book -> book.source.sourceUrl + "\n" + book.bookUrl }
            exactMatchCount += matches.size
            if (matches.isNotEmpty()) {
                trace.state(
                    "search_source_matches",
                    config.title,
                    trace.fields("index" to index, "source" to source.sourceName, "matches" to matches.size)
                )
            }
            for (book in matches) {
                if (catalogAttempts >= config.maxCatalogSources.coerceAtLeast(1)) break@sourceLoop
                catalogAttempts += 1
                val sourceFetched = fetchBookCandidate(config, book, catalogs, sourceFailures)
                if (sourceFetched.chapters.size >= config.minSampledChapters &&
                    sourceFetched.cleanMemoryChapters >= config.minCleanMemoryChapters
                ) {
                    fetchedChapters.addAll(sourceFetched.chapters)
                    fetchedSeedChapterIndexes.addAll(sourceFetched.seedChapterIndexes)
                    break@sourceLoop
                }
            }
        }
        trace.state(
            "search_finished",
            config.title,
            trace.fields(
                "attempts" to searchSources.size,
                "matches" to exactMatchCount,
                "catalogAttempts" to catalogAttempts,
                "acceptedContent" to fetchedChapters.size
            )
        )

        require(exactMatchCount > 0) { "no exact title/author search matches" }
        require(catalogs.isNotEmpty()) { "no usable catalogs. failures=${sourceFailures.take(4)}" }
        require(fetchedChapters.isNotEmpty()) { "no usable sampled content. failures=${sourceFailures.take(4)}" }
        val catalogReport = catalogFusionProbe.analyze(catalogs)
        val sampleValidationSummary = SampleValidationProbe.analyze(fetchedChapters)
        return FetchedSourceData(
            importedSources = sources.size,
            searchMatches = exactMatchCount,
            sourceFailures = sourceFailures.toList(),
            sampledChapters = fetchedChapters.toList(),
            seedChapterIndexes = fetchedSeedChapterIndexes.toSet(),
            catalogSummary = catalogReport.humanSummary(),
            sampleValidationSummary = sampleValidationSummary
        )
    }

    private suspend fun fetchBookCandidate(
        config: SourceExperimentConfig,
        book: SourceBook,
        catalogs: MutableList<NamedCatalog>,
        sourceFailures: MutableList<String>
    ): FetchedBookCandidate {
        trace.event("catalog_fetch_started", config.title, "source_${book.source.sourceName}")
        val detail = when (val result = engine.getBookDetail(book)) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> {
                val failure = "detail ${book.source.sourceName}: ${result.failure.message()}"
                sourceFailures.add(failure)
                trace.state("detail_fetch_failed", config.title, failure)
                return FetchedBookCandidate(emptyList(), emptySet(), cleanMemoryChapters = 0)
            }
        }
        val chapters = when (val result = engine.getChapterList(detail)) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> {
                val failure = "catalog ${book.source.sourceName}: ${result.failure.message()}"
                sourceFailures.add(failure)
                trace.state("catalog_fetch_failed", config.title, failure)
                return FetchedBookCandidate(emptyList(), emptySet(), cleanMemoryChapters = 0)
            }
        }
        if (chapters.size < config.minCatalogChapters) {
            val failure = "short catalog ${book.source.sourceName}: ${chapters.size} < ${config.minCatalogChapters}"
            sourceFailures.add(failure)
            trace.state(
                "catalog_source_rejected",
                config.title,
                trace.fields(
                    "source" to book.source.sourceName,
                    "chapters" to chapters.size,
                    "minCatalogChapters" to config.minCatalogChapters
                )
            )
            return FetchedBookCandidate(emptyList(), emptySet(), cleanMemoryChapters = 0)
        }
        catalogs.add(NamedCatalog(book.source.sourceName, chapters.map { chapter -> chapter.name }))
        val sourceFetched = fetchSelectedChapters(config, book, chapters, sourceFailures)
        if (sourceFetched.chapters.size >= config.minSampledChapters &&
            sourceFetched.cleanMemoryChapters >= config.minCleanMemoryChapters
        ) {
            trace.state(
                "content_source_accepted",
                config.title,
                trace.fields(
                    "source" to book.source.sourceName,
                    "chapters" to sourceFetched.chapters.size,
                    "cleanMemory" to sourceFetched.cleanMemoryChapters
                )
            )
        } else {
            val failure = "not enough clean story memory ${book.source.sourceName}: " +
                "${sourceFetched.cleanMemoryChapters} < ${config.minCleanMemoryChapters}, " +
                "sampled=${sourceFetched.chapters.size}"
            sourceFailures.add(failure)
            trace.state(
                "content_source_rejected",
                config.title,
                trace.fields(
                    "source" to book.source.sourceName,
                    "chapters" to sourceFetched.chapters.size,
                    "cleanMemory" to sourceFetched.cleanMemoryChapters,
                    "minCleanMemory" to config.minCleanMemoryChapters
                )
            )
        }
        trace.state(
            "catalog_fetch_finished",
            config.title,
            trace.fields("source" to book.source.sourceName, "chapters" to chapters.size)
        )
        return sourceFetched
    }

    private suspend fun fetchSelectedChapters(
        config: SourceExperimentConfig,
        book: SourceBook,
        chapters: List<SourceChapter>,
        sourceFailures: MutableList<String>
    ): FetchedBookCandidate {
        val sourceFetchedChapters = LinkedHashMap<Int, ChapterInput>()
        val seedChapterIndexes = LinkedHashSet<Int>()
        val selectedPlan = selectedRealNovelPlan(
            chapterCount = chapters.size,
            maxFingerprintChapters = config.maxFingerprintChapters,
            maxTailChapters = config.maxTailChapters,
            downloadWholeBook = config.downloadWholeBook
        )
        val selectedIndexes = selectedPlan.allPositions
        trace.event(
            "content_fetch_plan",
            config.title,
            trace.fields(
                "catalog" to chapters.size,
                "selected" to selectedIndexes.size,
                "seedPlanned" to selectedPlan.seedPositions.size,
                "targetPlanned" to selectedPlan.targetPositions.size,
                "first" to (selectedIndexes.firstOrNull() ?: -1),
                "last" to (selectedIndexes.lastOrNull() ?: -1)
            )
        )
        var continuousBlankContent = 0
        var abandonContentSource = false
        var cleanMemoryChapters = 0

        suspend fun fetchOne(position: Int, role: String): Boolean {
            if (abandonContentSource || position !in chapters.indices || sourceFetchedChapters.containsKey(position)) return false
            val chapter = chapters[position]
            when (val content = engine.getCleanContent(chapter)) {
                is EngineResult.Success -> {
                    val cleanedContent = content.value.cleanedContent
                    if (cleanedContent.isBlank()) {
                        continuousBlankContent += 1
                        val failure = "blank content ${book.source.sourceName} #${chapter.index} ${chapter.name}"
                        sourceFailures.add(failure)
                        trace.state(
                            "content_fetch_blank",
                            config.title,
                            trace.fields(
                                "source" to book.source.sourceName,
                                "index" to chapter.index,
                                "position" to position,
                                "role" to role,
                                "blankRun" to continuousBlankContent
                            )
                        )
                        if (continuousBlankContent >= 3) {
                            trace.state(
                                "content_source_abandoned",
                                config.title,
                                trace.fields(
                                    "source" to book.source.sourceName,
                                    "reason" to "continuous_blank_content"
                                )
                            )
                            abandonContentSource = true
                        }
                        return false
                    }
                    continuousBlankContent = 0
                    val chapterInput = ChapterInput(chapter.index, chapter.name, cleanedContent)
                    sourceFetchedChapters[position] = chapterInput
                    val quality = qualityGate.inspect(chapterInput)
                    if (position in selectedPlan.seedPositions) {
                        seedChapterIndexes.add(chapter.index)
                        if (quality.usableForStory) cleanMemoryChapters += 1
                    } else if (role == "MEMORY_BACKFILL") {
                        seedChapterIndexes.add(chapter.index)
                        if (quality.usableForStory) cleanMemoryChapters += 1
                    }
                    trace.event(
                        "content_fetch_finished",
                        config.title,
                        trace.fields(
                            "index" to chapter.index,
                            "position" to position,
                            "role" to role,
                            "title" to chapter.name,
                            "chars" to cleanedContent.length,
                            "quality" to quality.type,
                            "cleanChars" to quality.metrics.cleanedChars
                        )
                    )
                    return quality.usableForStory
                }
                is EngineResult.Failure -> {
                    val failure = "content ${book.source.sourceName} #${chapter.index} ${chapter.name}: ${content.failure.message()}"
                    sourceFailures.add(failure)
                    trace.state(
                        "content_fetch_failed",
                        config.title,
                        trace.fields(
                            "source" to book.source.sourceName,
                            "index" to chapter.index,
                            "position" to position,
                            "role" to role,
                            "reason" to content.failure.message()
                        )
                    )
                }
            }
            return false
        }

        for (chapterIndex in selectedIndexes) {
            fetchOne(
                position = chapterIndex,
                role = when (chapterIndex) {
                    in selectedPlan.seedPositions -> "MEMORY_SEED"
                    in selectedPlan.targetPositions -> "TARGET"
                    else -> "SAMPLE"
                }
            )
            if (abandonContentSource) break
        }

        if (!config.downloadWholeBook && cleanMemoryChapters < config.minCleanMemoryChapters) {
            val backfillPositions = memoryBackfillPositions(
                chapterCount = chapters.size,
                seedEndExclusive = selectedPlan.seedEndExclusive,
                excludedPositions = sourceFetchedChapters.keys,
                maxAttempts = config.maxMemoryBackfillAttempts
            )
            trace.state(
                "content_memory_backfill_started",
                config.title,
                trace.fields(
                    "source" to book.source.sourceName,
                    "cleanMemory" to cleanMemoryChapters,
                    "minCleanMemory" to config.minCleanMemoryChapters,
                    "candidates" to backfillPositions.size
                )
            )
            for (position in backfillPositions) {
                fetchOne(position, role = "MEMORY_BACKFILL")
                if (cleanMemoryChapters >= config.minCleanMemoryChapters) break
                if (abandonContentSource) break
            }
            trace.state(
                "content_memory_backfill_finished",
                config.title,
                trace.fields(
                    "source" to book.source.sourceName,
                    "chapters" to sourceFetchedChapters.size,
                    "cleanMemory" to cleanMemoryChapters,
                    "minCleanMemory" to config.minCleanMemoryChapters
                )
            )
        }

        return FetchedBookCandidate(
            chapters = sourceFetchedChapters
                .toSortedMap()
                .values
                .toList(),
            seedChapterIndexes = seedChapterIndexes.toSet(),
            cleanMemoryChapters = cleanMemoryChapters
        )
    }

    private fun selectedRealNovelPlan(
        chapterCount: Int,
        maxFingerprintChapters: Int,
        maxTailChapters: Int,
        downloadWholeBook: Boolean
    ): RealNovelSelectionPlan {
        if (chapterCount <= 0) return RealNovelSelectionPlan(emptyList(), emptySet(), emptySet(), 0)
        if (downloadWholeBook) {
            val seedEndExclusive = (chapterCount - maxTailChapters.coerceAtLeast(1)).coerceAtLeast(1)
            return RealNovelSelectionPlan(
                allPositions = (0 until chapterCount).toList(),
                seedPositions = (0 until seedEndExclusive).toSet(),
                targetPositions = (seedEndExclusive until chapterCount).toSet(),
                seedEndExclusive = seedEndExclusive
            )
        }
        val fingerprintEndExclusive = (chapterCount * 7 / 10).coerceAtLeast(1)
        val fingerprintCount = maxFingerprintChapters.coerceAtLeast(1)
        val fingerprintIndexes = evenlySpacedIndexes(0, fingerprintEndExclusive, fingerprintCount)
        val tailStart = (chapterCount - maxTailChapters.coerceAtLeast(1)).coerceAtLeast(0)
        val tailIndexes = (tailStart until chapterCount).toList()
        return RealNovelSelectionPlan(
            allPositions = (fingerprintIndexes + tailIndexes).distinct().sorted(),
            seedPositions = fingerprintIndexes.toSet(),
            targetPositions = tailIndexes.toSet(),
            seedEndExclusive = fingerprintEndExclusive
        )
    }

    private fun memoryBackfillPositions(
        chapterCount: Int,
        seedEndExclusive: Int,
        excludedPositions: Set<Int>,
        maxAttempts: Int
    ): List<Int> {
        if (chapterCount <= 0 || seedEndExclusive <= 0 || maxAttempts <= 0) return emptyList()
        val endExclusive = seedEndExclusive.coerceIn(1, chapterCount)
        val selected = LinkedHashSet<Int>()
        fun add(position: Int) {
            if (position in 0 until endExclusive && position !in excludedPositions) selected.add(position)
        }

        var offset = 1
        while (offset <= endExclusive && selected.size < maxAttempts / 2) {
            add(endExclusive - offset)
            offset *= 2
        }
        val nearCount = minOf(24, maxAttempts)
        val nearStart = (endExclusive - nearCount * 3).coerceAtLeast(0)
        evenlySpacedIndexes(nearStart, endExclusive, nearCount).asReversed().forEach(::add)
        evenlySpacedIndexes(0, endExclusive, maxAttempts).forEach(::add)
        return selected.take(maxAttempts)
    }

    private fun evenlySpacedIndexes(
        startInclusive: Int,
        endExclusive: Int,
        count: Int
    ): List<Int> {
        val size = endExclusive - startInclusive
        if (size <= 0) return emptyList()
        if (size <= count) return (startInclusive until endExclusive).toList()
        return (0 until count).map { index ->
            startInclusive + ((size - 1).toLong() * index / (count - 1).coerceAtLeast(1)).toInt()
        }.distinct()
    }

    private fun searchableSources(
        sources: List<BookSource>,
        maxSearchSources: Int,
        qualityIndex: SourceQualityIndex
    ): List<RankedSource> {
        return sources
            .asSequence()
            .filter { source -> source.enabled }
            .filter { source -> !source.searchUrl.isNullOrBlank() }
            .filter { source -> source.ruleSearch.rules.containsKey("bookList") }
            .mapIndexed { index, source ->
                RankedSource(
                    source = source,
                    originalIndex = index,
                    quality = qualityIndex.find(source)
                )
            }
            .sortedWith(
                compareBy<RankedSource> { ranked -> ranked.quality?.tier ?: Int.MAX_VALUE }
                    .thenByDescending { ranked -> ranked.quality?.score ?: 0 }
                    .thenBy { ranked -> ranked.originalIndex }
            )
            .take(maxSearchSources.coerceAtLeast(1))
            .toList()
    }

    private fun searchSource(
        config: SourceExperimentConfig,
        rankedSource: RankedSource,
        index: Int
    ): List<SourceBook> {
        val source = rankedSource.source
        val startMs = System.currentTimeMillis()
        trace.event(
            "search_source_started",
            config.title,
            trace.fields(
                "index" to index,
                "source" to source.sourceName,
                "tier" to (rankedSource.quality?.tier ?: 0),
                "score" to (rankedSource.quality?.score ?: 0),
                "url" to source.sourceUrl
            )
        )
        return when (val result = engine.search(listOf(source), config.title, maxSources = 1)) {
            is EngineResult.Success -> {
                val attempt = result.value.attempts.firstOrNull()
                trace.state(
                    "search_source_finished",
                    config.title,
                    trace.fields(
                        "index" to index,
                        "source" to source.sourceName,
                        "tier" to (rankedSource.quality?.tier ?: 0),
                        "score" to (rankedSource.quality?.score ?: 0),
                        "success" to (attempt?.success ?: false),
                        "books" to result.value.books.size,
                        "ms" to (System.currentTimeMillis() - startMs),
                        "message" to attempt?.message.orEmpty()
                    )
                )
                result.value.books
            }
            is EngineResult.Failure -> {
                trace.state(
                    "search_source_failed",
                    config.title,
                    trace.fields(
                        "index" to index,
                        "source" to source.sourceName,
                        "tier" to (rankedSource.quality?.tier ?: 0),
                        "score" to (rankedSource.quality?.score ?: 0),
                        "ms" to (System.currentTimeMillis() - startMs),
                        "reason" to result.failure.message()
                    )
                )
                emptyList()
            }
        }
    }

    private fun normalizeTitle(value: String): String {
        return value
            .lowercase()
            .replace(Regex("""[《》「」『』【】\[\]（）()，,。.!！?？\s]+"""), "")
    }

    private fun normalizeAuthor(value: String): String {
        return value
            .replace(Regex("""^作者[:：]\s*"""), "")
            .replace(Regex("""\s+"""), "")
            .trim()
    }

    private fun <T> EngineResult<T>.valueOrThrow(operation: String): T {
        return when (this) {
            is EngineResult.Success -> value
            is EngineResult.Failure -> error("$operation failed: ${failure.message()}")
        }
    }

    private fun EngineFailure.message(): String {
        return when (this) {
            is EngineFailure.ContractViolation -> message
            is EngineFailure.NetworkError -> message
            is EngineFailure.ParseError -> message
            is EngineFailure.RuleError -> message
        }
    }

    private data class RankedSource(
        val source: BookSource,
        val originalIndex: Int,
        val quality: SourceQualityRecord?
    )

    private data class SourceQualityRecord(
        val tier: Int,
        val score: Int
    )

    private class SourceQualityIndex private constructor(
        private val byUrl: Map<String, SourceQualityRecord>,
        private val byName: Map<String, SourceQualityRecord>
    ) {
        val size: Int = byUrl.size

        fun find(source: BookSource): SourceQualityRecord? {
            return byUrl[urlKey(source.sourceUrl)]
                ?: byUrl[baseUrlKey(source.sourceUrl)]
                ?: byName[nameKey(source.sourceName)]
        }

        companion object {
            fun parse(tsv: String): SourceQualityIndex {
                if (tsv.isBlank()) return SourceQualityIndex(emptyMap(), emptyMap())
                val byUrl = LinkedHashMap<String, SourceQualityRecord>()
                val byName = LinkedHashMap<String, SourceQualityRecord>()
                var header: List<String>? = null
                tsv.lineSequence().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val columns = rawLine.split('\t')
                    if (header == null) {
                        header = columns
                        return@forEach
                    }
                    val currentHeader = header ?: return@forEach
                    fun value(name: String): String {
                        val columnIndex = currentHeader.indexOf(name)
                        return if (columnIndex >= 0) columns.getOrNull(columnIndex).orEmpty().trim() else ""
                    }
                    if (value("kind") != "source") return@forEach
                    val tier = value("tier").toIntOrNull() ?: return@forEach
                    val score = value("score").toIntOrNull() ?: 0
                    val record = SourceQualityRecord(tier = tier, score = score)
                    val sourceUrl = value("sourceUrl")
                    val sourceName = value("sourceName")
                    putBest(byUrl, urlKey(sourceUrl), record)
                    putBest(byUrl, baseUrlKey(sourceUrl), record)
                    putBest(byName, nameKey(sourceName), record)
                }
                return SourceQualityIndex(byUrl, byName)
            }

            private fun putBest(
                target: MutableMap<String, SourceQualityRecord>,
                key: String,
                record: SourceQualityRecord
            ) {
                if (key.isBlank()) return
                val existing = target[key]
                if (existing == null || compare(record, existing) < 0) {
                    target[key] = record
                }
            }

            private fun compare(left: SourceQualityRecord, right: SourceQualityRecord): Int {
                return compareValuesBy(left, right, { it.tier }, { -it.score })
            }
        }
    }

    private companion object {
        fun urlKey(value: String): String {
            return value.trim().lowercase().trimEnd('/')
        }

        fun baseUrlKey(value: String): String {
            return urlKey(value).substringBefore("#")
        }

        fun nameKey(value: String): String {
            return value.lowercase().replace(Regex("""\s+"""), "")
        }
    }
}

