package com.ldp.reader.algorithmtest

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ldp.reader.algorithmtest.core.ChapterInput
import com.ldp.reader.algorithmtest.core.ChapterQualityGate
import com.ldp.reader.algorithmtest.core.CleanReport
import com.ldp.reader.algorithmtest.core.CleanSuggestion
import com.ldp.reader.algorithmtest.core.NovelPollutionAnalyzer
import com.ldp.reader.algorithmtest.source.BatchNovelTarget
import com.ldp.reader.algorithmtest.source.ImportedSourceSet
import com.ldp.reader.algorithmtest.source.SourceExperimentConfig
import com.ldp.reader.algorithmtest.source.SourceExperimentReport
import com.ldp.reader.algorithmtest.source.SourceFetchReport
import com.ldp.reader.algorithmtest.source.SourceExperimentRunner
import com.ldp.reader.algorithmtest.source.BatchNovelTargets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private companion object {
        private const val BUNDLED_SOURCE_ASSET = "source-engine/book-sources.json"
        private const val QUALITY_SEED_ASSET = "source-quality-seed-v1.tsv"
        private const val BATCH_PARALLELISM = 5
        private const val FETCH_BATCH_PARALLELISM = 8
        private const val FETCH_TARGET_LIMIT = 100
        private const val FETCH_TOP_UP_START_INDEX = FETCH_TARGET_LIMIT
        private const val FETCH_TOP_UP_LIMIT = 12
        private const val BATCH_ITEM_TIMEOUT_MS = 15 * 60 * 1_000L
        private const val FETCH_ITEM_TIMEOUT_MS = 60 * 60 * 1_000L
        private const val RAW_REPLAY_TAIL_RISK_WINDOW_CHAPTERS = 100
        private const val RAW_REPLAY_TARGET_RECENT_CHAPTERS = 2
        private const val RAW_REPLAY_TARGET_NEIGHBOR_RADIUS = 0
        private const val RAW_REPLAY_TARGET_EXTENDED_MIN_OFFSET = 256
        private const val RAW_REPLAY_NEAR_CONTEXT_SPAN = 300
        private const val RAW_REPLAY_NEAR_CONTEXT_SAMPLES = 8
        private const val RAW_REPLAY_MID_CONTEXT_SAMPLES = 2
        private const val RAW_REPLAY_LONG_ANCHOR_SAMPLES = 1
        private const val RAW_REPLAY_MIN_USABLE_CONTEXT_CHAPTERS = 8
        private const val RAW_REPLAY_MAX_CONTEXT_BACKFILL_ATTEMPTS = 256
        private const val RAW_REPLAY_PARALLELISM = 5
        private val RAW_CORPUS_BATCH_DIRS = listOf(
            "fetch-batch-1779484863140",
            "fetch-topup-1779493474318"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val analyzer = NovelPollutionAnalyzer()
    private val sourceRunner = SourceExperimentRunner(analyzer = analyzer)
    private val chapterQualityGate = ChapterQualityGate()

    private lateinit var titleInput: EditText
    private lateinit var authorInput: EditText
    private lateinit var sourceJsonInput: EditText
    private lateinit var chapterInput: EditText
    private lateinit var outputView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        AlgorithmTrace.state("activity_started", "algorithm-test", "ready")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(title("Novel Algorithm Test"))
        titleInput = input("Title", "仙逆", singleLine = true)
        authorInput = input("Author", "耳根", singleLine = true)
        sourceJsonInput = input("Source JSON, device file path, or use bundled source-engine asset", "", minLines = 3)
        chapterInput = input(
            "Manual chapters. Use ### title as chapter header.",
            "",
            minLines = 8
        )
        outputView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(0, dp(12), 0, 0)
        }

        root.addView(titleInput)
        root.addView(authorInput)
        root.addView(row(
            button("Analyze Paste") { runManualChapters() },
            button("Clear Log") { outputView.text = "" }
        ))
        root.addView(sourceJsonInput)
        root.addView(row(
            button("Run Bundled Sources") { runBundledSourceExperiment() },
            button("Run Source Input") { runSourceExperiment(loadSourceJsonInput()) }
        ))
        root.addView(button("Run Batch Sources") { runBundledBatchSourceExperiment() })
        root.addView(button("Run Batch Fetch Only") { runBundledBatchFetchOnlyExperiment() })
        root.addView(button("Run Fetch Top Up 101-112") { runBundledFetchTopUpExperiment() })
        root.addView(button("Run Local Raw Corpus Replay") { runLocalRawCorpusReplay() })
        root.addView(chapterInput)
        root.addView(outputView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return ScrollView(this).apply { addView(root) }
    }

    private fun runManualChapters() {
        val chapters = parseManualChapters(chapterInput.text.toString())
        if (chapters.isEmpty()) {
            output("No manual chapters. Paste chapters or run source-engine.")
            return
        }
        AlgorithmTrace.event("manual_started", titleInput.text.toString(), "chapters_${chapters.size}")
        output("Analyzing ${chapters.size} pasted chapters...")
        scope.launch {
            val report = withContext(Dispatchers.Default) {
                analyzer.analyze(
                    title = titleInput.text.toString(),
                    author = authorInput.text.toString(),
                    chapters = chapters
                )
            }
            AlgorithmTrace.state(
                "manual_finished",
                report.title,
                AlgorithmTrace.fields("suggestions" to report.suggestions.size, "chunks" to report.chunkCount)
            )
            output(report.humanSummary() + "\n\nTrace:\n" + report.logs.joinToString("\n"))
        }
    }

    private fun runBundledSourceExperiment() {
        val title = titleInput.text.toString().trim()
        if (title.isBlank()) {
            output("Title is required.")
            return
        }
        output("Loading bundled source-engine asset for $title...")
        scope.launch {
            val sourceJson = withContext(Dispatchers.IO) { loadBundledSourceJson() }
            val qualitySeedTsv = withContext(Dispatchers.IO) { loadQualitySeedTsv() }
            startSourceExperiment(title, sourceJson, qualitySeedTsv)
        }
    }

    private fun runBundledBatchSourceExperiment() {
        output("Loading bundled source-engine asset for batch...")
        scope.launch {
            val sourceJson = withContext(Dispatchers.IO) { loadBundledSourceJson() }
            val qualitySeedTsv = withContext(Dispatchers.IO) { loadQualitySeedTsv() }
            if (sourceJson.isBlank()) {
                output("No source configuration loaded.")
                return@launch
            }
            runBatchSourceExperiment(sourceJson, qualitySeedTsv)
        }
    }

    private fun runBundledBatchFetchOnlyExperiment() {
        output("Loading bundled source-engine asset for fetch-only batch...")
        scope.launch {
            val sourceJson = withContext(Dispatchers.IO) { loadBundledSourceJson() }
            val qualitySeedTsv = withContext(Dispatchers.IO) { loadQualitySeedTsv() }
            if (sourceJson.isBlank()) {
                output("No source configuration loaded.")
                return@launch
            }
            runBatchFetchOnlyExperiment(sourceJson, qualitySeedTsv)
        }
    }

    private fun runBundledFetchTopUpExperiment() {
        output("Loading bundled source-engine asset for fetch top-up...")
        scope.launch {
            val sourceJson = withContext(Dispatchers.IO) { loadBundledSourceJson() }
            val qualitySeedTsv = withContext(Dispatchers.IO) { loadQualitySeedTsv() }
            if (sourceJson.isBlank()) {
                output("No source configuration loaded.")
                return@launch
            }
            runBatchFetchOnlyExperiment(
                sourceJson = sourceJson,
                qualitySeedTsv = qualitySeedTsv,
                targetStartIndex = FETCH_TOP_UP_START_INDEX,
                targetLimit = FETCH_TOP_UP_LIMIT,
                batchPrefix = "fetch-topup"
            )
        }
    }

    private fun runLocalRawCorpusReplay() {
        output("Starting local raw corpus replay from fetched device files...")
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val outputDir = createBatchReportDir(startedAt, prefix = "raw-corpus-replay")
            val baseDir = getExternalFilesDir("algorithm-test") ?: File(filesDir, "algorithm-test")
            val corpusRoots = RAW_CORPUS_BATCH_DIRS.map { name -> File(baseDir, name) }
            val result = withContext(Dispatchers.Default) {
                replayRawCorpus(corpusRoots, outputDir)
            }
            output(
                "Raw corpus replay finished.\n" +
                    "Items=${result.itemCount}, ok=${result.okCount}, fail=${result.failCount}, " +
                    "suggestionBooks=${result.suggestionBookCount}, suggestions=${result.suggestionCount}\n" +
                    "Output: ${outputDir.absolutePath}\n" +
                    "Summary: ${File(outputDir, "corpus-summary.tsv").absolutePath}\n" +
                    "Audit plan: ${File(outputDir, "audit-plan.tsv").absolutePath}"
            )
        }
    }

    private fun runSourceExperiment(sourceJson: String) {
        val title = titleInput.text.toString().trim()
        startSourceExperiment(title, sourceJson, qualitySeedTsv = "")
    }

    private fun startSourceExperiment(title: String, sourceJson: String, qualitySeedTsv: String) {
        if (sourceJson.isBlank()) {
            output("No source configuration loaded.")
            return
        }
        if (title.isBlank()) {
            output("Title is required.")
            return
        }
        AlgorithmTrace.event("source_experiment_started", title, "jsonChars_${sourceJson.length}")
        output("Fetching source data for $title...")
        scope.launch {
            try {
                val report = sourceRunner.run(
                    SourceExperimentConfig(
                        title = title,
                        author = authorInput.text.toString().trim(),
                        sourceJson = sourceJson,
                        qualitySeedTsv = qualitySeedTsv
                    )
                )
                val reportDir = withContext(Dispatchers.IO) { writeDeviceReport(title, report) }
                AlgorithmTrace.state(
                    "source_experiment_finished",
                    title,
                    AlgorithmTrace.fields(
                        "sources" to report.importedSources,
                        "matches" to report.searchMatches,
                        "suggestions" to report.cleanReport.suggestions.size
                    )
                )
                output(report.humanSummary() + "\n\nDevice report: ${reportDir.absolutePath}")
            } catch (error: Throwable) {
                val reportDir = withContext(Dispatchers.IO) { writeFailureReport(title, error) }
                AlgorithmTrace.state("source_experiment_failed", title, error.message.orEmpty())
                output("Source experiment failed: ${error.message}\n\nDevice failure report: ${reportDir.absolutePath}")
            }
        }
    }

    private suspend fun runBatchSourceExperiment(sourceJson: String, qualitySeedTsv: String) {
        val startedAt = System.currentTimeMillis()
        val batchDir = createBatchReportDir(startedAt)
        val batchTargets = BatchNovelTargets.all
        val importedSources = withContext(Dispatchers.IO) { sourceRunner.importSources(sourceJson) }
        AlgorithmTrace.event("source_batch_started", "batch", "targets_${batchTargets.size}")
        output(
            "Batch started: ${batchDir.absolutePath}\n" +
                "Targets=${batchTargets.size}, parallelism=$BATCH_PARALLELISM, sources=${importedSources.sources.size}"
        )

        val semaphore = Semaphore(BATCH_PARALLELISM)
        val finishedCount = AtomicInteger(0)
        val results = coroutineScope {
            batchTargets.mapIndexed { index, target ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        runBatchItem(
                            index = index,
                            target = target,
                            total = batchTargets.size,
                            sourceJson = sourceJson,
                            qualitySeedTsv = qualitySeedTsv,
                            importedSources = importedSources,
                            batchDir = batchDir
                        ).also {
                            val finished = finishedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                output(
                                    "Batch progress $finished/${batchTargets.size}\n" +
                                        "Latest: ${target.title} / ${target.author}\n\n" +
                                        "Report dir: ${batchDir.absolutePath}"
                                )
                            }
                        }
                    }
                }
            }.awaitAll().sortedBy { result -> result.index }
        }

        writeBatchSummary(batchDir, results)
        val summary = results.joinToString(separator = "\n") { result -> result.line }
        val finished = "Batch finished in ${System.currentTimeMillis() - startedAt}ms\n\n$summary"
        AlgorithmTrace.state("source_batch_finished", "batch", "targets_${batchTargets.size}")
        output(finished + "\nBatch summary: ${File(batchDir, "batch-summary.txt").absolutePath}")
    }

    private suspend fun runBatchFetchOnlyExperiment(
        sourceJson: String,
        qualitySeedTsv: String,
        targetStartIndex: Int = 0,
        targetLimit: Int = FETCH_TARGET_LIMIT,
        batchPrefix: String = "fetch-batch"
    ) {
        val startedAt = System.currentTimeMillis()
        val batchDir = createBatchReportDir(startedAt, prefix = batchPrefix)
        val allTargets = BatchNovelTargets.all
        val batchTargets = allTargets.drop(targetStartIndex).take(targetLimit)
        if (batchTargets.isEmpty()) {
            output("No fetch-only targets for start=$targetStartIndex limit=$targetLimit.")
            return
        }
        val importedSources = withContext(Dispatchers.IO) { sourceRunner.importSources(sourceJson) }
        AlgorithmTrace.event(
            "source_fetch_batch_started",
            batchPrefix,
            AlgorithmTrace.fields(
                "targets" to batchTargets.size,
                "start" to targetStartIndex,
                "limit" to targetLimit
            )
        )
        output(
            "Fetch-only batch started: ${batchDir.absolutePath}\n" +
                "Targets=${targetStartIndex + 1}-${targetStartIndex + batchTargets.size}/${allTargets.size}, " +
                "parallelism=$FETCH_BATCH_PARALLELISM, sources=${importedSources.sources.size}, chapters=whole-book"
        )

        val semaphore = Semaphore(FETCH_BATCH_PARALLELISM)
        val finishedCount = AtomicInteger(0)
        val results = coroutineScope {
            batchTargets.mapIndexed { index, target ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        runFetchOnlyItem(
                            index = targetStartIndex + index,
                            target = target,
                            total = allTargets.size,
                            qualitySeedTsv = qualitySeedTsv,
                            importedSources = importedSources,
                            batchDir = batchDir
                        ).also {
                            val finished = finishedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                output(
                                    "Fetch-only progress $finished/${batchTargets.size}\n" +
                                        "Latest: ${target.title} / ${target.author}\n\n" +
                                        "Report dir: ${batchDir.absolutePath}"
                                )
                            }
                        }
                    }
                }
            }.awaitAll().sortedBy { result -> result.index }
        }

        writeBatchSummary(batchDir, results)
        val summary = results.joinToString(separator = "\n") { result -> result.line }
        val finished = "Fetch-only batch finished in ${System.currentTimeMillis() - startedAt}ms\n\n$summary"
        AlgorithmTrace.state("source_fetch_batch_finished", batchPrefix, "targets_${batchTargets.size}")
        output(finished + "\nBatch summary: ${File(batchDir, "batch-summary.txt").absolutePath}")
    }

    private suspend fun runFetchOnlyItem(
        index: Int,
        target: BatchNovelTarget,
        total: Int,
        qualitySeedTsv: String,
        importedSources: ImportedSourceSet,
        batchDir: File
    ): BatchItemResult {
        val label = "${index + 1}/$total ${target.title} / ${target.author}"
        val reportDir = createReportDir(
            title = "${(index + 1).toString().padStart(3, '0')}-${target.title}",
            parentDir = batchDir
        )
        val statusFile = File(reportDir, "status.txt")
        writeBatchLive(
            batchDir,
            "${System.currentTimeMillis()}\tFETCH_START\t${index + 1}\t$total\t${target.title}\t${target.author}\t${target.kind}\t${reportDir.absolutePath}"
        )
        writeStatus(
            statusFile,
            "FETCH_START\t${System.currentTimeMillis()}\t$label\tparallelism=$FETCH_BATCH_PARALLELISM\tchapters=whole-book"
        )
        AlgorithmTrace.state("source_fetch_item_started", target.title, AlgorithmTrace.fields("index" to index))
        withContext(Dispatchers.Main) {
            output("Fetch-only running $label\n\nReport dir: ${batchDir.absolutePath}")
        }
        return try {
            val itemTrace = FileAlgorithmTrace(
                tagKey = "fetch_${index + 1}_${target.title}",
                file = File(reportDir, "source-trace-live.txt")
            )
            val report = withTimeout(FETCH_ITEM_TIMEOUT_MS) {
                writeStatus(statusFile, "FETCH_RUNNER_START\t${System.currentTimeMillis()}")
                SourceExperimentRunner(
                    analyzer = NovelPollutionAnalyzer(),
                    trace = itemTrace
                ).fetchOnlyWithSources(
                    SourceExperimentConfig(
                        title = target.title,
                        author = target.author,
                        sourceJson = "",
                        qualitySeedTsv = qualitySeedTsv,
                        minSampledChapters = 1,
                        downloadWholeBook = true
                    ),
                    importedSources
                )
            }
            writeFetchReport(reportDir, report)
            writeStatus(
                statusFile,
                "FETCH_OK\t${System.currentTimeMillis()}\tchapters=${report.sampledChapters.size}"
            )
            writeBatchLive(
                batchDir,
                "${System.currentTimeMillis()}\tFETCH_OK\t${index + 1}\t$total\t${target.title}\t${target.author}\t${target.kind}\tchapters=${report.sampledChapters.size}\t${reportDir.absolutePath}"
            )
            AlgorithmTrace.state(
                "source_fetch_item_finished",
                target.title,
                AlgorithmTrace.fields("index" to index, "chapters" to report.sampledChapters.size)
            )
            BatchItemResult(
                index = index,
                line = "OK_FETCH\t${target.title}\t${target.author}\t${target.kind}\t${reportDir.absolutePath}\t" +
                    "chapters=${report.sampledChapters.size}"
            )
        } catch (error: Throwable) {
            writeFailureReport(reportDir, error)
            writeStatus(statusFile, "FETCH_FAIL\t${System.currentTimeMillis()}\t${error.message.orEmpty()}")
            writeBatchLive(
                batchDir,
                "${System.currentTimeMillis()}\tFETCH_FAIL\t${index + 1}\t$total\t${target.title}\t${target.author}\t${target.kind}\t${error.message.orEmpty()}\t${reportDir.absolutePath}"
            )
            AlgorithmTrace.state("source_fetch_item_failed", target.title, error.message.orEmpty())
            BatchItemResult(
                index = index,
                line = "FAIL_FETCH\t${target.title}\t${target.author}\t${target.kind}\t${reportDir.absolutePath}\t${error.message.orEmpty()}"
            )
        }
    }

    private suspend fun runBatchItem(
        index: Int,
        target: BatchNovelTarget,
        total: Int,
        sourceJson: String,
        qualitySeedTsv: String,
        importedSources: ImportedSourceSet,
        batchDir: File
    ): BatchItemResult {
        val label = "${index + 1}/$total ${target.title} / ${target.author}"
        val reportDir = createReportDir(
            title = "${(index + 1).toString().padStart(3, '0')}-${target.title}",
            parentDir = batchDir
        )
        val statusFile = File(reportDir, "status.txt")
        writeBatchLive(
            batchDir,
            "${System.currentTimeMillis()}\tSTART\t${index + 1}\t$total\t${target.title}\t${target.author}\t${target.kind}\t${reportDir.absolutePath}"
        )
        writeStatus(
            statusFile,
            "START\t${System.currentTimeMillis()}\t$label\tparallelism=$BATCH_PARALLELISM"
        )
        AlgorithmTrace.state("source_batch_item_started", target.title, AlgorithmTrace.fields("index" to index))
        withContext(Dispatchers.Main) {
            output("Batch running $label\n\nReport dir: ${batchDir.absolutePath}")
        }
        return try {
            val itemTrace = FileAlgorithmTrace(
                tagKey = "batch_${index + 1}_${target.title}",
                file = File(reportDir, "source-trace-live.txt")
            )
            val report = withTimeout(BATCH_ITEM_TIMEOUT_MS) {
                writeStatus(statusFile, "RUNNER_START\t${System.currentTimeMillis()}")
                SourceExperimentRunner(
                    analyzer = NovelPollutionAnalyzer(),
                    trace = itemTrace
                ).runWithSources(
                SourceExperimentConfig(
                    title = target.title,
                    author = target.author,
                    sourceJson = sourceJson,
                    qualitySeedTsv = qualitySeedTsv
                    ),
                    importedSources
                )
            }
            writeDeviceReport(reportDir, report)
            writeStatus(
                statusFile,
                "OK\t${System.currentTimeMillis()}\tsuggestions=${report.cleanReport.suggestions.size}\tchapters=${report.sampledChapters.size}"
            )
            writeBatchLive(
                batchDir,
                "${System.currentTimeMillis()}\tOK\t${index + 1}\t$total\t${target.title}\t${target.author}\t${target.kind}\tsuggestions=${report.cleanReport.suggestions.size}\tchapters=${report.sampledChapters.size}\t${reportDir.absolutePath}"
            )
            AlgorithmTrace.state(
                "source_batch_item_finished",
                target.title,
                AlgorithmTrace.fields("index" to index, "suggestions" to report.cleanReport.suggestions.size)
            )
            BatchItemResult(
                index = index,
                line = "OK\t${target.title}\t${target.author}\t${target.kind}\t${reportDir.absolutePath}\t" +
                    "suggestions=${report.cleanReport.suggestions.size}\tchapters=${report.sampledChapters.size}"
            )
        } catch (error: Throwable) {
            writeFailureReport(reportDir, error)
            writeStatus(statusFile, "FAIL\t${System.currentTimeMillis()}\t${error.message.orEmpty()}")
            writeBatchLive(
                batchDir,
                "${System.currentTimeMillis()}\tFAIL\t${index + 1}\t$total\t${target.title}\t${target.author}\t${target.kind}\t${error.message.orEmpty()}\t${reportDir.absolutePath}"
            )
            AlgorithmTrace.state("source_batch_item_failed", target.title, error.message.orEmpty())
            BatchItemResult(
                index = index,
                line = "FAIL\t${target.title}\t${target.author}\t${target.kind}\t${reportDir.absolutePath}\t${error.message.orEmpty()}"
            )
        }
    }

    private fun writeBatchSummary(
        batchDir: File,
        results: List<BatchItemResult>
    ) {
        val body = buildString {
            results.sortedBy { result -> result.index }.forEach { result -> appendLine(result.line) }
        }
        File(batchDir, "batch-summary.txt").writeText(body)
    }

    @Synchronized
    private fun writeBatchLive(batchDir: File, line: String) {
        File(batchDir, "batch-live.tsv").appendText(line + "\n")
    }

    private fun writeStatus(file: File, line: String) {
        file.appendText(line + "\n")
    }

    private suspend fun replayRawCorpus(
        corpusRoots: List<File>,
        outputDir: File
    ): RawCorpusReplayResult {
        val corpusItems = findSuccessfulRawCorpusItems(corpusRoots)
        val summaryFile = File(outputDir, "corpus-summary.tsv")
        val auditPlanFile = File(outputDir, "audit-plan.tsv")
        val liveFile = File(outputDir, "replay-live.tsv")
        val extractDir = File(outputDir, "audit-extracts").apply { mkdirs() }

        summaryFile.writeText(
            "bookNo\ttitle\tauthor\tfullChapters\tanalysisChapters\ttargetChapters\tcontextChapters\t" +
                "chunks\tsuggestions\tsuggestionIndexes\tlistMs\tsampleMs\treadMs\tanalyzeMs\tauditMs\telapsedMs\treportDir\n"
        )
        auditPlanFile.writeText(
            "bookNo\ttitle\tauthor\tkind\tchapterIndex\tchapterTitle\talgorithmState\tconfidence\tstatus\tnote\textractFile\n"
        )
        liveFile.writeText(
            "startedAt=${System.currentTimeMillis()}\titems=${corpusItems.size}\t" +
                "parallelism=$RAW_REPLAY_PARALLELISM\troots=${corpusRoots.joinToString { it.name }}\n"
        )
        AlgorithmTrace.event("raw_corpus_replay_started", "raw-corpus", "items_${corpusItems.size}")

        val okCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val suggestionBookCount = AtomicInteger(0)
        val suggestionCount = AtomicInteger(0)
        val finishedCount = AtomicInteger(0)
        val writeLock = Any()
        fun appendLive(line: String) {
            synchronized(writeLock) { liveFile.appendText(line) }
        }
        fun appendSummary(line: String) {
            synchronized(writeLock) { summaryFile.appendText(line) }
        }
        fun appendAuditRows(rows: List<String>) {
            synchronized(writeLock) {
                rows.forEach { row -> auditPlanFile.appendText(row + "\n") }
            }
        }

        val semaphore = Semaphore(RAW_REPLAY_PARALLELISM)
        coroutineScope {
            corpusItems.mapIndexed { ordinal, item ->
                async(Dispatchers.Default) {
                    semaphore.withPermit {
                        val itemStartedAt = System.currentTimeMillis()
                        val reportDir = File(outputDir, item.reportName).apply { mkdirs() }
                        appendLive(
                            "${System.currentTimeMillis()}\tSTART\t${ordinal + 1}\t${corpusItems.size}\t" +
                                "${item.bookNo}\t${item.title}\n"
                        )
                        try {
                            val listStartedAt = System.currentTimeMillis()
                            val chapterFiles = item.listChapterFiles()
                            val listMs = System.currentTimeMillis() - listStartedAt
                            appendLive(
                                "${System.currentTimeMillis()}\tLIST_DONE\t${ordinal + 1}\t${corpusItems.size}\t" +
                                    "${item.bookNo}\tfullChapters=${chapterFiles.size}\tlistMs=$listMs\n"
                            )
                            val sampleStartedAt = System.currentTimeMillis()
                            val sample = selectRawReplayChapterFiles(chapterFiles)
                            val sampleMs = System.currentTimeMillis() - sampleStartedAt
                            appendLive(
                                "${System.currentTimeMillis()}\tSAMPLE_DONE\t${ordinal + 1}\t${corpusItems.size}\t" +
                                    "${item.bookNo}\tanalysisChapters=${sample.analysis.size}\t" +
                                    "targetChapters=${sample.targetIndexes.size}\tcontextChapters=${sample.contextIndexes.size}\t" +
                                    "roles=${sample.roleCountsText()}\tsampleMs=$sampleMs\n"
                            )
                            val readStartedAt = System.currentTimeMillis()
                            val analysisChapters = sample.analysis.map { chapterFile -> chapterFile.readChapter() }
                            val readMs = System.currentTimeMillis() - readStartedAt
                            appendLive(
                                "${System.currentTimeMillis()}\tREAD_DONE\t${ordinal + 1}\t${corpusItems.size}\t" +
                                    "${item.bookNo}\tchapters=${analysisChapters.size}\treadMs=$readMs\n"
                            )
                            val analyzeStartedAt = System.currentTimeMillis()
                            appendLive(
                                "${System.currentTimeMillis()}\tANALYZE_START\t${ordinal + 1}\t${corpusItems.size}\t" +
                                    "${item.bookNo}\tseedChapters=${sample.contextIndexes.size}\t" +
                                    "targetChapters=${sample.targetIndexes.size}\n"
                            )
                            val report = NovelPollutionAnalyzer().analyze(
                                title = item.title,
                                author = item.author,
                                chapters = analysisChapters,
                                seedChapterIndexes = sample.contextIndexes,
                                progress = { stage ->
                                    appendLive(
                                        "${System.currentTimeMillis()}\tANALYZE_STAGE\t${ordinal + 1}\t${corpusItems.size}\t" +
                                            "${item.bookNo}\t$stage\n"
                                    )
                                }
                            )
                            val analyzeMs = System.currentTimeMillis() - analyzeStartedAt
                            appendLive(
                                "${System.currentTimeMillis()}\tANALYZE_DONE\t${ordinal + 1}\t${corpusItems.size}\t" +
                                    "${item.bookNo}\tchunks=${report.chunkCount}\tsuggestions=${report.suggestions.size}\t" +
                                    "analyzeMs=$analyzeMs\n"
                            )
                            File(reportDir, "algorithm-report.txt").writeText(report.humanSummary(maxFeatures = 20))
                            File(reportDir, "algorithm-log.txt").writeText(report.logs.joinToString("\n"))
                            File(reportDir, "source-dir.txt").writeText(item.sourceDir.absolutePath)
                            File(reportDir, "analysis-chapters.tsv").writeText(
                                analysisChapters.joinToString(separator = "\n") { chapter ->
                                    val role = sample.rolesByChapterIndex[chapter.index].orEmpty()
                                    "${chapter.index}\t$role\t${chapter.title}"
                                }
                            )
                            File(reportDir, "sampling-plan.txt").writeText(sample.describe())

                            val suggestionIndexes = report.suggestions
                                .filter { suggestion -> suggestion.chapterIndex in sample.targetIndexes }
                                .map { suggestion -> suggestion.chapterIndex }
                                .distinct()
                                .sorted()
                            val targetSuggestions = report.suggestions
                                .filter { suggestion -> suggestion.chapterIndex in sample.targetIndexes }
                            val targetReport = report.copy(suggestions = targetSuggestions)
                            okCount.incrementAndGet()
                            suggestionCount.addAndGet(suggestionIndexes.size)
                            if (suggestionIndexes.isNotEmpty()) suggestionBookCount.incrementAndGet()
                            val auditStartedAt = System.currentTimeMillis()
                            val auditRows = buildRawAuditPlanRows(item, chapterFiles, targetReport, suggestionIndexes, extractDir)
                            val auditMs = System.currentTimeMillis() - auditStartedAt
                            val elapsedMs = System.currentTimeMillis() - itemStartedAt
                            appendSummary(
                                listOf(
                                    item.bookNo.toString(),
                                    item.title,
                                    item.author,
                                    chapterFiles.size.toString(),
                                    analysisChapters.size.toString(),
                                    sample.targetIndexes.size.toString(),
                                    sample.contextIndexes.size.toString(),
                                    report.chunkCount.toString(),
                                    suggestionIndexes.size.toString(),
                                    suggestionIndexes.joinToString(","),
                                    listMs.toString(),
                                    sampleMs.toString(),
                                    readMs.toString(),
                                    analyzeMs.toString(),
                                    auditMs.toString(),
                                    elapsedMs.toString(),
                                    reportDir.absolutePath
                                ).joinToString("\t") + "\n"
                            )
                            appendAuditRows(auditRows)
                            val finished = finishedCount.incrementAndGet()
                            appendLive(
                                "${System.currentTimeMillis()}\tOK\t${ordinal + 1}\t${corpusItems.size}\t${item.bookNo}\t" +
                                    "${item.title}\tfinished=$finished\tfullChapters=${chapterFiles.size}\t" +
                                    "analysisChapters=${analysisChapters.size}\ttargetChapters=${sample.targetIndexes.size}\t" +
                                    "contextChapters=${sample.contextIndexes.size}\tsuggestions=${suggestionIndexes.size}\t" +
                                    "listMs=$listMs\tsampleMs=$sampleMs\treadMs=$readMs\tanalyzeMs=$analyzeMs\t" +
                                    "auditMs=$auditMs\tms=$elapsedMs\n"
                            )
                            AlgorithmTrace.state(
                                "raw_corpus_replay_item_finished",
                                item.title,
                                AlgorithmTrace.fields(
                                    "bookNo" to item.bookNo,
                                    "finished" to finished,
                                    "fullChapters" to chapterFiles.size,
                                    "analysisChapters" to analysisChapters.size,
                                    "targetChapters" to sample.targetIndexes.size,
                                    "contextChapters" to sample.contextIndexes.size,
                                    "suggestions" to suggestionIndexes.size,
                                    "listMs" to listMs,
                                    "sampleMs" to sampleMs,
                                    "readMs" to readMs,
                                    "analyzeMs" to analyzeMs,
                                    "auditMs" to auditMs,
                                    "ms" to elapsedMs
                                )
                            )
                        } catch (error: Throwable) {
                            failCount.incrementAndGet()
                            val finished = finishedCount.incrementAndGet()
                            File(reportDir, "failure.txt").writeText(error.stackTraceToString())
                            appendLive(
                                "${System.currentTimeMillis()}\tFAIL\t${ordinal + 1}\t${corpusItems.size}\t${item.bookNo}\t" +
                                    "${item.title}\tfinished=$finished\t${error.message.orEmpty()}\n"
                            )
                            AlgorithmTrace.state("raw_corpus_replay_item_failed", item.title, error.message.orEmpty())
                        } finally {
                            System.gc()
                        }
                    }
                }
            }.awaitAll()
        }

        liveFile.appendText(
            "${System.currentTimeMillis()}\tFINISH\titems=${corpusItems.size}\tok=${okCount.get()}\t" +
                "fail=${failCount.get()}\tsuggestionBooks=${suggestionBookCount.get()}\t" +
                "suggestions=${suggestionCount.get()}\n"
        )
        AlgorithmTrace.state(
            "raw_corpus_replay_finished",
            "raw-corpus",
            AlgorithmTrace.fields(
                "items" to corpusItems.size,
                "ok" to okCount.get(),
                "fail" to failCount.get(),
                "suggestionBooks" to suggestionBookCount.get(),
                "suggestions" to suggestionCount.get()
            )
        )
        return RawCorpusReplayResult(
            itemCount = corpusItems.size,
            okCount = okCount.get(),
            failCount = failCount.get(),
            suggestionBookCount = suggestionBookCount.get(),
            suggestionCount = suggestionCount.get()
        )
    }

    private fun findSuccessfulRawCorpusItems(corpusRoots: List<File>): List<RawCorpusItem> {
        return corpusRoots
            .filter { root -> root.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { file -> file.isFile && file.name == "fetch-report.txt" }
                    .mapNotNull { reportFile ->
                        val sourceDir = reportFile.parentFile ?: return@mapNotNull null
                        val bookNo = parseBookNo(sourceDir.name) ?: return@mapNotNull null
                        val target = BatchNovelTargets.all.getOrNull(bookNo - 1)
                        RawCorpusItem(
                            bookNo = bookNo,
                            title = target?.title ?: parseRawTitle(sourceDir.name),
                            author = target?.author.orEmpty(),
                            sourceDir = sourceDir
                        )
                    }
                    .toList()
            }
            .sortedBy { item -> item.bookNo }
    }

    private fun buildRawAuditPlanRows(
        item: RawCorpusItem,
        chapterFiles: List<RawChapterFile>,
        report: CleanReport,
        suggestionIndexes: List<Int>,
        extractDir: File
    ): List<String> {
        val rows = ArrayList<String>()
        val byIndex = chapterFiles.associateBy { chapter -> chapter.index }
        val suggestionByIndex = report.suggestions.associateBy { suggestion -> suggestion.chapterIndex }
        if (suggestionIndexes.isNotEmpty()) {
            selectProblemAuditIndexes(suggestionIndexes).forEach { chapterIndex ->
                val suggestion = suggestionByIndex[chapterIndex]
                val chapterFile = byIndex[chapterIndex]
                if (chapterFile != null) {
                    rows.add(writeAuditRow(item, "SUGGESTION_CHECK", chapterFile, suggestion, extractDir))
                }
                listOf(chapterIndex - 1, chapterIndex + 1).forEach { neighborIndex ->
                    if (neighborIndex >= 0 && neighborIndex !in suggestionIndexes) {
                        byIndex[neighborIndex]?.let { neighborFile ->
                            rows.add(writeAuditRow(item, "BOUNDARY_NEIGHBOR_CHECK", neighborFile, null, extractDir))
                        }
                    }
                }
            }
        } else {
            selectCleanTailAuditChapterFiles(chapterFiles).forEach { chapterFile ->
                rows.add(writeAuditRow(item, "NO_SUGGEST_TAIL_CHECK", chapterFile, null, extractDir))
            }
        }
        return rows.distinct()
    }

    private fun writeAuditRow(
        item: RawCorpusItem,
        kind: String,
        chapterFile: RawChapterFile,
        suggestion: CleanSuggestion?,
        extractDir: File
    ): String {
        val chapter = chapterFile.readChapter()
        val extractFile = File(
            extractDir,
            "book-${item.bookNo.toString().padStart(3, '0')}-chapter-" +
                "${chapter.index.toString().padStart(5, '0')}-$kind.txt"
        )
        extractFile.writeText(
            buildString {
                appendLine("bookNo=${item.bookNo}")
                appendLine("title=${item.title}")
                appendLine("author=${item.author}")
                appendLine("kind=$kind")
                appendLine("chapterIndex=${chapter.index}")
                appendLine("chapterTitle=${chapter.title}")
                appendLine("sourceFile=${chapterFile.file.absolutePath}")
                if (suggestion != null) {
                    appendLine("algorithmState=${suggestion.stateType}")
                    appendLine("pollutionType=${suggestion.pollutionType}")
                    appendLine("startOffset=${suggestion.startOffset}")
                    appendLine("endOffset=${suggestion.endOffset}")
                    appendLine("confidence=${"%.3f".format(suggestion.confidence)}")
                    appendLine("action=${suggestion.action}")
                    suggestion.reasons.forEach { reason -> appendLine("reason=$reason") }
                }
                appendLine()
                appendLine(chapter.content)
            }
        )
        return listOf(
            item.bookNo.toString(),
            item.title,
            item.author,
            kind,
            chapter.index.toString(),
            chapter.title,
            suggestion?.stateType?.name.orEmpty(),
            suggestion?.confidence?.let { "%.3f".format(it) }.orEmpty(),
            "PENDING",
            "",
            extractFile.absolutePath
        ).joinToString("\t") { value -> value.replace('\t', ' ').replace('\n', ' ') }
    }

    private fun selectProblemAuditIndexes(suggestionIndexes: List<Int>): List<Int> {
        val selected = LinkedHashSet<Int>()
        suggestionIndexes.take(3).forEach { selected.add(it) }
        var offset = 4
        while (offset <= suggestionIndexes.size) {
            selected.add(suggestionIndexes[offset - 1])
            offset *= 2
        }
        suggestionIndexes.lastOrNull()?.let { selected.add(it) }
        return selected.toList()
    }

    private fun selectCleanTailAuditChapterFiles(chapterFiles: List<RawChapterFile>): List<RawChapterFile> {
        val chapterCount = chapterFiles.size
        if (chapterCount <= 0) return emptyList()
        val selected = LinkedHashSet<Int>()
        var offset = 1
        while (offset <= chapterCount && selected.size < 8) {
            selected.add(chapterCount - offset)
            offset *= 2
        }
        return selected.map { position -> chapterFiles[position] }
    }

    private fun selectRawReplayChapterFiles(chapterFiles: List<RawChapterFile>): RawReplaySample {
        val chapterCount = chapterFiles.size
        if (chapterCount <= 0) return RawReplaySample(emptyList(), emptySet(), emptySet(), emptyMap())
        val tailStart = (chapterCount - RAW_REPLAY_TAIL_RISK_WINDOW_CHAPTERS).coerceAtLeast(0)
        val targetRolesByPosition = selectTargetProbePositions(
            chapterCount = chapterCount,
            tailStart = tailStart
        )
        val targetPositions = targetRolesByPosition.keys.sorted()
        val nearContextStart = (tailStart - RAW_REPLAY_NEAR_CONTEXT_SPAN).coerceAtLeast(0)
        val nearContextPositions = evenlySpacedPositions(
            startInclusive = nearContextStart,
            endExclusive = tailStart,
            count = RAW_REPLAY_NEAR_CONTEXT_SAMPLES
        )
        val midContextStart = (chapterCount * 35 / 100).coerceAtMost(nearContextStart)
        val midContextPositions = evenlySpacedPositions(
            startInclusive = midContextStart,
            endExclusive = nearContextStart,
            count = RAW_REPLAY_MID_CONTEXT_SAMPLES
        )
        val longAnchorEnd = midContextStart
        val longAnchorPositions = evenlySpacedPositions(
            startInclusive = 0,
            endExclusive = longAnchorEnd,
            count = RAW_REPLAY_LONG_ANCHOR_SAMPLES
        )
        val fallbackContextEnd = targetPositions.minOrNull()?.coerceAtLeast(0) ?: 0
        val fallbackContextPositions = if (
            longAnchorPositions.isEmpty() &&
            midContextPositions.isEmpty() &&
            nearContextPositions.isEmpty() &&
            fallbackContextEnd > 0
        ) {
            evenlySpacedPositions(
                startInclusive = 0,
                endExclusive = fallbackContextEnd,
                count = RAW_REPLAY_NEAR_CONTEXT_SAMPLES
            )
        } else {
            emptyList()
        }
        val targetPositionSet = targetPositions.toSet()
        val rolesByPosition = LinkedHashMap<Int, String>()
        fallbackContextPositions.forEach { position -> rolesByPosition[position] = "FALLBACK_CONTEXT" }
        longAnchorPositions.forEach { position -> rolesByPosition[position] = "LONG_ANCHOR" }
        midContextPositions.forEach { position -> rolesByPosition[position] = "MID_CONTEXT" }
        nearContextPositions.forEach { position -> rolesByPosition[position] = "NEAR_CONTEXT" }
        targetRolesByPosition.forEach { (position, role) -> rolesByPosition[position] = role }
        val selectedPositions = rolesByPosition.keys.sorted()
        val analysis = selectedPositions.map { position -> chapterFiles[position] }
        val targetIndexes = targetPositionSet.map { position -> chapterFiles[position].index }.toSet()
        val contextIndexes = analysis.map { file -> file.index }.filterNot { index -> index in targetIndexes }.toSet()
        val rolesByChapterIndex = selectedPositions.associate { position ->
            chapterFiles[position].index to rolesByPosition.getValue(position)
        }
        return qualityAwareRawReplaySample(
            chapterFiles = chapterFiles,
            initial = RawReplaySample(
                analysis = analysis,
                targetIndexes = targetIndexes,
                contextIndexes = contextIndexes,
                rolesByChapterIndex = rolesByChapterIndex
            )
        )
    }

    private fun qualityAwareRawReplaySample(
        chapterFiles: List<RawChapterFile>,
        initial: RawReplaySample
    ): RawReplaySample {
        if (initial.contextIndexes.size >= RAW_REPLAY_MIN_USABLE_CONTEXT_CHAPTERS &&
            usableRawContextCount(chapterFiles, initial.contextIndexes) >= RAW_REPLAY_MIN_USABLE_CONTEXT_CHAPTERS
        ) {
            return initial
        }

        val positionsByChapterIndex = chapterFiles
            .mapIndexed { position, file -> file.index to position }
            .toMap()
        val rolesByChapterIndex = LinkedHashMap(initial.rolesByChapterIndex)
        val selectedPositions = rolesByChapterIndex.keys
            .mapNotNull { index -> positionsByChapterIndex[index] }
            .toMutableSet()
        val targetPositions = initial.targetIndexes
            .mapNotNull { index -> positionsByChapterIndex[index] }
            .toSet()
        val contextIndexes = initial.contextIndexes.toMutableSet()
        val diagnostics = ArrayList(initial.diagnostics)

        var usableContext = usableRawContextCount(chapterFiles, contextIndexes)
        val backfillPositions = rawContextBackfillPositions(
            chapterCount = chapterFiles.size,
            tailStart = (chapterFiles.size - RAW_REPLAY_TAIL_RISK_WINDOW_CHAPTERS).coerceAtLeast(0),
            excludedPositions = selectedPositions,
            targetPositions = targetPositions,
            maxAttempts = RAW_REPLAY_MAX_CONTEXT_BACKFILL_ATTEMPTS
        )
        diagnostics.add(
            "qualityBackfillStart usableContext=$usableContext " +
                "min=$RAW_REPLAY_MIN_USABLE_CONTEXT_CHAPTERS candidates=${backfillPositions.size}"
        )
        for (position in backfillPositions) {
            val file = chapterFiles[position]
            val quality = chapterQualityGate.inspect(file.readChapter())
            diagnostics.add(
                "qualityBackfillProbe position=$position chapter=${file.index} " +
                    "quality=${quality.type} cleanChars=${quality.metrics.cleanedChars}"
            )
            selectedPositions.add(position)
            if (quality.usableForStory) {
                rolesByChapterIndex[file.index] = "MEMORY_BACKFILL"
                contextIndexes.add(file.index)
                usableContext += 1
                diagnostics.add("qualityBackfillAccept chapter=${file.index} usableContext=$usableContext")
                if (usableContext >= RAW_REPLAY_MIN_USABLE_CONTEXT_CHAPTERS) break
            }
        }
        diagnostics.add(
            "qualityBackfillFinish usableContext=$usableContext " +
                "analysis=${rolesByChapterIndex.size}"
        )
        val analysis = rolesByChapterIndex.keys
            .mapNotNull { index -> positionsByChapterIndex[index]?.let { position -> chapterFiles[position] } }
            .sortedBy { file -> file.index }
        return RawReplaySample(
            analysis = analysis,
            targetIndexes = initial.targetIndexes,
            contextIndexes = contextIndexes,
            rolesByChapterIndex = rolesByChapterIndex.toSortedMap(),
            diagnostics = diagnostics
        )
    }

    private fun usableRawContextCount(
        chapterFiles: List<RawChapterFile>,
        contextIndexes: Set<Int>
    ): Int {
        if (contextIndexes.isEmpty()) return 0
        return chapterFiles
            .asSequence()
            .filter { file -> file.index in contextIndexes }
            .count { file -> chapterQualityGate.inspect(file.readChapter()).usableForStory }
    }

    private fun rawContextBackfillPositions(
        chapterCount: Int,
        tailStart: Int,
        excludedPositions: Set<Int>,
        targetPositions: Set<Int>,
        maxAttempts: Int
    ): List<Int> {
        if (chapterCount <= 0 || maxAttempts <= 0) return emptyList()
        val endExclusive = when {
            tailStart > 0 -> tailStart
            targetPositions.isNotEmpty() -> targetPositions.minOrNull()?.coerceAtLeast(1) ?: chapterCount
            else -> (chapterCount * 7 / 10).coerceAtLeast(1)
        }.coerceIn(1, chapterCount)
        val selected = LinkedHashSet<Int>()
        fun add(position: Int) {
            if (position in 0 until endExclusive &&
                position !in excludedPositions &&
                position !in targetPositions
            ) {
                selected.add(position)
            }
        }

        var offset = 1
        while (offset <= endExclusive && selected.size < maxAttempts / 2) {
            add(endExclusive - offset)
            offset *= 2
        }
        val nearCount = minOf(24, maxAttempts)
        val nearStart = (endExclusive - nearCount * 3).coerceAtLeast(0)
        evenlySpacedPositions(nearStart, endExclusive, nearCount).asReversed().forEach(::add)
        evenlySpacedPositions(0, endExclusive, maxAttempts).forEach(::add)
        return selected.take(maxAttempts)
    }

    private fun selectTargetProbePositions(
        chapterCount: Int,
        tailStart: Int
    ): Map<Int, String> {
        val selected = LinkedHashMap<Int, String>()
        fun addWindow(center: Int, role: String) {
            for (delta in -RAW_REPLAY_TARGET_NEIGHBOR_RADIUS..RAW_REPLAY_TARGET_NEIGHBOR_RADIUS) {
                val position = center + delta
                if (position in 0 until chapterCount) selected.putIfAbsent(position, role)
            }
        }

        val recentStart = (chapterCount - RAW_REPLAY_TARGET_RECENT_CHAPTERS).coerceAtLeast(tailStart)
        (recentStart until chapterCount).forEach { position -> selected[position] = "TARGET_RECENT" }

        val riskWindowSize = chapterCount - tailStart
        val offsets = ArrayList<Int>()
        var offset = 1
        while (offset <= riskWindowSize) {
            offsets.add(offset)
            offset *= 2
        }
        if (chapterCount > RAW_REPLAY_TAIL_RISK_WINDOW_CHAPTERS) {
            offsets.add(RAW_REPLAY_TAIL_RISK_WINDOW_CHAPTERS)
        }
        offset = RAW_REPLAY_TARGET_EXTENDED_MIN_OFFSET
        while (offset <= chapterCount) {
            offsets.add(offset)
            offset *= 2
        }

        val distinctOffsets = offsets
            .filter { oneBasedOffset -> oneBasedOffset in 1..chapterCount }
            .distinct()
            .sorted()
        distinctOffsets.forEach { oneBasedOffset ->
            val center = chapterCount - oneBasedOffset
            val role = if (oneBasedOffset <= RAW_REPLAY_TAIL_RISK_WINDOW_CHAPTERS) {
                "TARGET_TAIL"
            } else {
                "TARGET_EXTENDED"
            }
            addWindow(center, role)
        }
        return selected.toSortedMap()
    }

    private fun evenlySpacedPositions(
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

    private fun RawCorpusItem.listChapterFiles(): List<RawChapterFile> {
        val chapterDir = File(sourceDir, "chapters")
        return chapterDir.listFiles { file -> file.isFile && file.extension == "txt" }
            .orEmpty()
            .sortedWith(compareBy({ parseChapterIndex(it.name) }, { it.name }))
            .map { file ->
                val index = parseChapterIndex(file.name)
                RawChapterFile(
                    index = index,
                    title = parseChapterTitle(file.name),
                    file = file
                )
            }
    }

    private fun parseBookNo(name: String): Int? {
        return Regex("""^(\d{3})-""").find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseRawTitle(name: String): String {
        return name.replace(Regex("""^\d{3}-"""), "").replace(Regex("""-\d+$"""), "")
    }

    private fun parseChapterIndex(name: String): Int {
        return Regex("""^(\d{5})-""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun parseChapterTitle(name: String): String {
        return name.removeSuffix(".txt").replace(Regex("""^\d{5}-"""), "")
    }

    private fun parseManualChapters(value: String): List<ChapterInput> {
        val chapters = ArrayList<ChapterInput>()
        var currentTitle = "Chapter 1"
        var builder = StringBuilder()
        value.lineSequence().forEach { line ->
            if (line.startsWith("###")) {
                if (builder.isNotBlank()) {
                    chapters.add(ChapterInput(chapters.size, currentTitle, builder.toString()))
                }
                currentTitle = line.removePrefix("###").trim().ifBlank { "Chapter ${chapters.size + 1}" }
                builder = StringBuilder()
            } else {
                builder.appendLine(line)
            }
        }
        if (builder.isNotBlank()) {
            chapters.add(ChapterInput(chapters.size, currentTitle, builder.toString()))
        }
        return chapters
    }

    private fun loadSourceJsonInput(): String {
        val value = sourceJsonInput.text.toString().trim()
        if (value.startsWith("/") || value.matches(Regex("""[A-Za-z]:[\\/].*"""))) {
            return runCatching { File(value).readText() }.getOrElse { "" }
        }
        return value
    }

    private fun loadBundledSourceJson(): String {
        return runCatching {
            assets.open(BUNDLED_SOURCE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onSuccess { json ->
            AlgorithmTrace.state(
                "bundled_source_loaded",
                BUNDLED_SOURCE_ASSET,
                AlgorithmTrace.fields("chars" to json.length)
            )
        }.getOrElse { error ->
            AlgorithmTrace.state("bundled_source_load_failed", BUNDLED_SOURCE_ASSET, error.message.orEmpty())
            ""
        }
    }

    private fun loadQualitySeedTsv(): String {
        return runCatching {
            assets.open(QUALITY_SEED_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onSuccess { tsv ->
            AlgorithmTrace.state(
                "quality_seed_loaded",
                QUALITY_SEED_ASSET,
                AlgorithmTrace.fields("chars" to tsv.length)
            )
        }.getOrElse { error ->
            AlgorithmTrace.state("quality_seed_load_failed", QUALITY_SEED_ASSET, error.message.orEmpty())
            ""
        }
    }

    private fun writeDeviceReport(title: String, report: SourceExperimentReport): File {
        val reportDir = createReportDir(title)
        writeDeviceReport(reportDir, report)
        return reportDir
    }

    private fun writeDeviceReport(reportDir: File, report: SourceExperimentReport) {
        File(reportDir, "report.txt").writeText(report.humanSummary())
        File(reportDir, "source-trace.txt").writeText(report.sourceTrace.joinToString("\n"))
        File(reportDir, "algorithm-log.txt").writeText(report.cleanReport.logs.joinToString("\n"))
        val chapterDir = File(reportDir, "chapters").apply { mkdirs() }
        report.sampledChapters.forEach { chapter ->
            File(chapterDir, "${chapter.index.toString().padStart(5, '0')}-${safeName(chapter.title)}.txt")
                .writeText(chapter.content)
        }
        AlgorithmTrace.state("device_report_written", reportDir.name, reportDir.absolutePath)
    }

    private fun writeFetchReport(reportDir: File, report: SourceFetchReport) {
        File(reportDir, "fetch-report.txt").writeText(report.humanSummary())
        File(reportDir, "source-trace.txt").writeText(report.sourceTrace.joinToString("\n"))
        val chapterDir = File(reportDir, "chapters").apply { mkdirs() }
        report.sampledChapters.forEach { chapter ->
            File(chapterDir, "${chapter.index.toString().padStart(5, '0')}-${safeName(chapter.title)}.txt")
                .writeText(chapter.content)
        }
        AlgorithmTrace.state("device_fetch_report_written", reportDir.name, reportDir.absolutePath)
    }

    private fun writeFailureReport(title: String, error: Throwable): File {
        val reportDir = createReportDir(title)
        writeFailureReport(reportDir, error)
        return reportDir
    }

    private fun writeFailureReport(reportDir: File, error: Throwable) {
        File(reportDir, "failure.txt").writeText(error.stackTraceToString())
        File(reportDir, "source-trace.txt").writeText(AlgorithmTrace.snapshot().joinToString("\n"))
        AlgorithmTrace.state("device_failure_report_written", reportDir.name, reportDir.absolutePath)
    }

    private fun createReportDir(title: String, parentDir: File? = null): File {
        val baseDir = getExternalFilesDir("algorithm-test") ?: File(filesDir, "algorithm-test")
        val parent = parentDir ?: baseDir
        return File(parent, "${safeName(title)}-${System.currentTimeMillis()}").apply { mkdirs() }
    }

    private fun createBatchReportDir(startedAt: Long, prefix: String = "batch"): File {
        val baseDir = getExternalFilesDir("algorithm-test") ?: File(filesDir, "algorithm-test")
        return File(baseDir, "$prefix-$startedAt").apply { mkdirs() }
    }

    private fun safeName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(80)
    }

    private fun output(value: String) {
        outputView.text = value
    }

    private fun title(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 22f
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun input(
        hint: String,
        defaultValue: String,
        singleLine: Boolean = false,
        minLines: Int = 1
    ): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(defaultValue)
            isSingleLine = singleLine
            this.minLines = minLines
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
    }

    private fun row(left: View, right: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun StringBuilder.isNotBlank(): Boolean = toString().isNotBlank()

    private data class BatchItemResult(
        val index: Int,
        val line: String
    )

    private data class RawCorpusItem(
        val bookNo: Int,
        val title: String,
        val author: String,
        val sourceDir: File
    ) {
        val reportName: String =
            "book-${bookNo.toString().padStart(3, '0')}"
    }

    private data class RawChapterFile(
        val index: Int,
        val title: String,
        val file: File
    ) {
        fun readChapter(): ChapterInput {
            return ChapterInput(
                index = index,
                title = title,
                content = file.readText(Charsets.UTF_8)
            )
        }
    }

    private data class RawReplaySample(
        val analysis: List<RawChapterFile>,
        val targetIndexes: Set<Int>,
        val contextIndexes: Set<Int>,
        val rolesByChapterIndex: Map<Int, String>,
        val diagnostics: List<String> = emptyList()
    ) {
        fun roleCountsText(): String {
            return rolesByChapterIndex.values
                .groupingBy { role -> role }
                .eachCount()
                .toSortedMap()
                .entries
                .joinToString(",") { "${it.key}:${it.value}" }
        }

        fun describe(): String {
            return buildString {
                appendLine("analysisChapters=${analysis.size}")
                appendLine("targetChapters=${targetIndexes.size}")
                appendLine("contextChapters=${contextIndexes.size}")
                appendLine("roleCounts=${roleCountsText()}")
                diagnostics.forEach { diagnostic -> appendLine(diagnostic) }
                appendLine(
                    "targetRange=${targetIndexes.minOrNull()?.toString().orEmpty()}.." +
                        targetIndexes.maxOrNull()?.toString().orEmpty()
                )
                appendLine("chapters:")
                analysis.forEach { chapter ->
                    appendLine("${chapter.index}\t${rolesByChapterIndex[chapter.index].orEmpty()}\t${chapter.title}")
                }
            }
        }
    }

    private data class RawCorpusReplayResult(
        val itemCount: Int,
        val okCount: Int,
        val failCount: Int,
        val suggestionBookCount: Int,
        val suggestionCount: Int
    )
}
