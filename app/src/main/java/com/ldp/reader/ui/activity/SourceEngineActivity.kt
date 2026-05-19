package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ldp.reader.databinding.ActivitySourceEngineBinding
import com.ldp.reader.source.SourceEngineCompatibility
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.legado.JdkHttpFetcher
import com.ldp.reader.sourceengine.legado.LegadoSourceEngine
import com.ldp.reader.sourceengine.legado.LegadoSourceImporter
import com.ldp.reader.sourceengine.model.CanonicalChapter
import com.ldp.reader.sourceengine.model.ChapterOrdinalRange
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.SourceImportReport
import java.io.File
import java.net.URL

class SourceEngineActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySourceEngineBinding
    private val importer = LegadoSourceImporter()
    private val engine = LegadoSourceEngine(fetcher = JdkHttpFetcher(3000, 5000))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceEngineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.sourceEngineToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.sourceEngineToolbar.setNavigationOnClickListener { finish() }
        binding.sourceEngineImportButton.setOnClickListener { renderSampleImport() }
        binding.sourceEngineStorageImportButton.setOnClickListener { renderStorageImport() }
        binding.sourceEngineLegadoImportButton.setOnClickListener { importLegadoSources() }
        binding.sourceEngineSearchButton.setOnClickListener { runSearchChain() }
        binding.sourceEngineStoragePath.text = storageSourceFile.absolutePath
        renderReaderMode()
        renderStorageImport()
    }

    private fun renderReaderMode() {
        binding.sourceEngineReaderMode.text = "当前阅读链路：书源引擎"
    }

    private fun renderStorageImport() {
        val sourceFile = storageSourceFile
        if (!sourceFile.isFile) {
            renderStorageMissing(sourceFile)
            return
        }
        renderImportResult("Storage import", sourceFile.readText())
    }

    private fun importLegadoSources() {
        runTask("Importing Legado sources...") {
            val attempts = ArrayList<String>()
            val payload = readLegadoProviderPayload(attempts)
                ?: readLegadoWebPayload(attempts)
                ?: run {
                    renderFailure(buildString {
                        appendLine("Legado import failed")
                        attempts.forEach { appendLine(it) }
                    })
                    return@runTask
                }

            when (val extracted = importer.extractSourceArrayJson(payload)) {
                is EngineResult.Success -> {
                    sourceEngineDir.mkdirs()
                    storageSourceFile.writeText(extracted.value)
                    renderImportResult(
                        buildString {
                            appendLine("Legado import")
                            attempts.forEach { appendLine(it) }
                        }.trimEnd(),
                        extracted.value
                    )
                }
                is EngineResult.Failure -> renderFailure(buildString {
                    appendLine("Legado import failed: ${extracted.failure}")
                    attempts.forEach { appendLine(it) }
                })
            }
        }
    }

    private fun readLegadoProviderPayload(attempts: MutableList<String>): String? {
        for (authority in LEGADO_PROVIDER_AUTHORITIES) {
            val uri = Uri.parse("content://$authority/bookSources/query")
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        attempts.add("provider $authority: empty cursor")
                        return@runCatching null
                    }
                    val payload = cursor.getString(0)
                    if (payload.isNullOrBlank()) {
                        attempts.add("provider $authority: blank result")
                        null
                    } else {
                        attempts.add("provider $authority: ${payload.length} chars")
                        payload
                    }
                }
            }.onFailure { error ->
                attempts.add("provider $authority: ${error.message ?: error.javaClass.simpleName}")
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun readLegadoWebPayload(attempts: MutableList<String>): String? {
        for (url in LEGADO_WEB_SOURCE_URLS) {
            runCatching {
                val payload = URL(url).readText()
                attempts.add("web $url: ${payload.length} chars")
                payload
            }.onFailure { error ->
                attempts.add("web $url: ${error.message ?: error.javaClass.simpleName}")
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun renderSampleImport() {
        renderImportResult("Sample import", sampleSourceJson)
    }

    private fun renderImportResult(title: String, json: String) {
        when (val result = importer.importJson(json)) {
            is EngineResult.Success -> {
                val report = result.value
                runOnUiThread {
                    binding.sourceEngineSourceCount.text = report.sources.size.toString()
                    binding.sourceEngineRejectedCount.text = report.rejectedSources.size.toString()
                    binding.sourceEngineDiagnosticCount.text = report.diagnosticCount.toString()
                    binding.sourceEngineReport.text = buildString {
                        appendLine(title)
                        appendLine("sources=${report.sources.size}")
                        appendLine("rejected=${report.rejectedSources.size}")
                        report.sources.take(40).forEach { source ->
                            appendLine()
                            appendLine("source=${source.sourceName}")
                            appendLine("url=${source.sourceUrl}")
                            appendLine("enabled=${source.enabled}")
                            appendLine("searchRules=${source.ruleSearch.rules.keys.joinToString()}")
                            appendLine("tocRules=${source.ruleToc.rules.keys.joinToString()}")
                            appendLine("contentRules=${source.ruleContent.rules.keys.joinToString()}")
                            source.diagnostics.forEach { diagnostic ->
                                appendLine("${diagnostic.severity} ${diagnostic.code} ${diagnostic.path}")
                            }
                        }
                        if (report.sources.size > 40) {
                            appendLine()
                            appendLine("... ${report.sources.size - 40} more sources")
                        }
                        report.rejectedSources.take(20).forEach { rejected ->
                            appendLine("rejected[${rejected.index}]=${rejected.failure}")
                        }
                    }
                }
            }
            is EngineResult.Failure -> {
                runOnUiThread {
                    binding.sourceEngineSourceCount.text = "0"
                    binding.sourceEngineRejectedCount.text = "0"
                    binding.sourceEngineDiagnosticCount.text = "1"
                    binding.sourceEngineReport.text = "Import failed: ${result.failure}"
                }
            }
        }
    }

    private fun runSearchChain() {
        runTask("Running search/detail/toc/catalog/content cleaning chain...") {
            val report = loadSourceReport() ?: return@runTask
            val keyword = binding.sourceEngineSearchKeyword.text?.toString()?.trim()
                .orEmpty()
                .ifBlank { DEFAULT_KEYWORD }
            val searchableSources = report.sources.filter { source ->
                source.enabled && !source.searchUrl.isNullOrBlank()
            }.filter { source -> isEngineCompatibleForLab(source) }
            val attemptLog = ArrayList<String>()
            searchableSources.take(MAX_CHAIN_SOURCES).forEachIndexed { sourceIndex, source ->
                val search = when (val value = engine.search(listOf(source), keyword, maxSources = 1)) {
                    is EngineResult.Success -> value.value
                    is EngineResult.Failure -> {
                        attemptLog.add("${source.sourceName}: search failed ${value.failure}")
                        return@forEachIndexed
                    }
                }
                val searchAttempt = search.attempts.firstOrNull()
                attemptLog.add(
                    "${sourceIndex + 1}. ${source.sourceName}: search=${search.books.size}, ${searchAttempt?.message.orEmpty()}"
                )
                search.books.take(MAX_BOOKS_PER_SOURCE).forEach { book ->
                    val detail = when (val value = engine.getBookDetail(book)) {
                        is EngineResult.Success -> value.value
                        is EngineResult.Failure -> {
                            attemptLog.add("${source.sourceName}: detail failed ${value.failure}")
                            return@forEach
                        }
                    }
                    val canonicalChapters = when (val value = engine.getCanonicalChapterList(detail)) {
                        is EngineResult.Success -> value.value
                        is EngineResult.Failure -> {
                            attemptLog.add("${source.sourceName}: toc failed ${value.failure}")
                            return@forEach
                        }
                    }
                    if (canonicalChapters.chapters.isEmpty()) {
                        attemptLog.add("${source.sourceName}: toc empty for ${detail.name}")
                        return@forEach
                    }
                    val contentSamples = validateContentSamples(
                        source.sourceName,
                        canonicalChapters.chapters,
                        attemptLog
                    ) ?: return@forEach
                    val firstSample = contentSamples.first()
                    val minQualityScore = contentSamples.minOf { it.content.report.qualityScore }
                    val minCoherenceScore = contentSamples.minOf { it.content.report.coherenceScore }

                    renderImportSummary("Full chain verified", report, buildString {
                        appendLine("keyword=$keyword")
                        appendLine("testedSources=${sourceIndex + 1}")
                        appendLine("book=${detail.name}")
                        appendLine("author=${detail.author}")
                        appendLine("source=${book.source.sourceName}")
                        appendLine("bookUrl=${book.bookUrl}")
                        appendLine("tocUrl=${detail.tocUrl}")
                        appendLine("canonicalChapters=${canonicalChapters.chapters.size}")
                        appendLine("duplicateChapters=${canonicalChapters.duplicateCount}")
                        appendLine("firstChapter=${canonicalChapters.chapters.first().displayTitle}")
                        appendLine("lastChapter=${canonicalChapters.chapters.last().displayTitle}")
                        appendLine("missingOrdinalRanges=${formatMissingRanges(canonicalChapters.missingOrdinalRanges)}")
                        appendLine("sampledChapters=${contentSamples.size}")
                        appendLine("minContentQualityScore=$minQualityScore")
                        appendLine("minContentCoherenceScore=$minCoherenceScore")
                        appendLine("contentSamples=${formatContentSamples(contentSamples)}")
                        appendLine()
                        appendLine("firstSamplePreview=")
                        appendLine(firstSample.content.cleanedContent.take(1200))
                        appendLine()
                        appendLine("attempts=")
                        attemptLog.forEach { appendLine(it) }
                    })
                    return@runTask
                }
            }

            renderImportSummary("Full chain failed", report, buildString {
                appendLine("keyword=$keyword")
                appendLine("testedSources=${minOf(searchableSources.size, MAX_CHAIN_SOURCES)}")
                appendLine("attempts=")
                attemptLog.forEach { appendLine(it) }
            })
        }
    }

    private fun validateContentSamples(
        sourceName: String,
        chapters: List<CanonicalChapter>,
        attemptLog: MutableList<String>
    ): List<ChapterContentSample>? {
        val samples = ArrayList<ChapterContentSample>()
        sampleChapterIndices(chapters.size).forEach { chapterIndex ->
            val canonicalChapter = chapters[chapterIndex]
            val sourceChapter = canonicalChapter.sourceChapters.firstOrNull()
            if (sourceChapter == null) {
                attemptLog.add("$sourceName: sample ${chapterIndex + 1} has no source chapter")
                return null
            }
            val content = when (val value = engine.getCleanContent(sourceChapter)) {
                is EngineResult.Success -> value.value
                is EngineResult.Failure -> {
                    attemptLog.add("$sourceName: content failed at ${canonicalChapter.displayTitle} ${value.failure}")
                    return null
                }
            }
            if (content.cleanedContent.isBlank()) {
                attemptLog.add("$sourceName: content blank at ${canonicalChapter.displayTitle}")
                return null
            }
            if (
                content.report.cleanedLength < MIN_CLEAN_CONTENT_CHARS ||
                content.report.qualityScore < MIN_CONTENT_QUALITY_SCORE ||
                content.report.coherenceScore < MIN_CONTENT_COHERENCE_SCORE
            ) {
                attemptLog.add(
                    "$sourceName: content low quality at ${canonicalChapter.displayTitle} " +
                        "score=${content.report.qualityScore}, " +
                        "coherence=${content.report.coherenceScore}, " +
                        "cleaned=${content.report.cleanedLength}, " +
                        "warnings=${content.report.warnings.joinToString()}, " +
                        "coherenceMarkers=${content.report.coherenceMarkers.joinToString()}"
                )
                return null
            }
            samples.add(
                ChapterContentSample(
                    position = chapterIndex + 1,
                    title = canonicalChapter.displayTitle,
                    content = content
                )
            )
        }
        return samples
    }

    private fun sampleChapterIndices(chapterCount: Int): List<Int> {
        if (chapterCount <= 0) return emptyList()
        val indices = LinkedHashSet<Int>()
        indices.add(0)
        if (chapterCount > 2) {
            indices.add(chapterCount / 2)
        }
        val tailSampleCount = minOf(LAST_CHAPTER_SAMPLE_COUNT, chapterCount)
        for (offset in tailSampleCount downTo 1) {
            indices.add(chapterCount - offset)
        }
        return indices.filter { it in 0 until chapterCount }
    }

    private fun formatContentSamples(samples: List<ChapterContentSample>): String {
        return samples.joinToString(" | ") { sample ->
            val report = sample.content.report
            "${sample.position}:${sample.title}" +
                "/score=${report.qualityScore}" +
                "/coherence=${report.coherenceScore}" +
                "/chars=${report.cleanedLength}" +
                "/markers=${report.coherenceMarkers.joinToString().ifBlank { "none" }}"
        }
    }

    private fun isEngineCompatibleForLab(source: com.ldp.reader.sourceengine.model.BookSource): Boolean {
        return SourceEngineCompatibility.isCompatible(source)
    }

    private fun loadSourceReport(): SourceImportReport? {
        val sourceFile = storageSourceFile
        if (!sourceFile.isFile) {
            renderStorageMissing(sourceFile)
            return null
        }
        return when (val result = importer.importJson(sourceFile.readText())) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> {
                renderFailure("Storage import failed: ${result.failure}")
                null
            }
        }
    }

    private fun renderStorageMissing(sourceFile: File) {
        runOnUiThread {
            binding.sourceEngineSourceCount.text = "0"
            binding.sourceEngineRejectedCount.text = "0"
            binding.sourceEngineDiagnosticCount.text = "0"
            binding.sourceEngineReport.text = buildString {
                appendLine("Storage import")
                appendLine("missing=${sourceFile.absolutePath}")
                appendLine("Write a Legado source JSON file to this exact app-private path, then tap Import storage.")
            }
        }
    }

    private fun renderImportSummary(
        title: String,
        report: SourceImportReport,
        detail: String
    ) {
        runOnUiThread {
            binding.sourceEngineSourceCount.text = report.sources.size.toString()
            binding.sourceEngineRejectedCount.text = report.rejectedSources.size.toString()
            binding.sourceEngineDiagnosticCount.text = report.diagnosticCount.toString()
            binding.sourceEngineReport.text = buildString {
                appendLine(title)
                appendLine("sources=${report.sources.size}")
                appendLine("searchable=${report.sources.count { it.enabled && !it.searchUrl.isNullOrBlank() }}")
                appendLine()
                append(detail)
            }
        }
    }

    private fun formatMissingRanges(ranges: List<ChapterOrdinalRange>): String {
        if (ranges.isEmpty()) return "none"
        val visible = ranges.take(MAX_VISIBLE_MISSING_RANGES).joinToString()
        return if (ranges.size > MAX_VISIBLE_MISSING_RANGES) {
            "$visible ... +${ranges.size - MAX_VISIBLE_MISSING_RANGES}"
        } else {
            visible
        }
    }

    private fun renderFailure(message: String) {
        runOnUiThread {
            binding.sourceEngineReport.text = message
        }
    }

    private fun runTask(progressText: String, block: () -> Unit) {
        binding.sourceEngineReport.text = progressText
        Thread {
            try {
                block()
            } catch (e: Exception) {
                renderFailure(e.stackTraceToString())
            }
        }.start()
    }

    private val sourceEngineDir: File
        get() = File(filesDir, STORAGE_DIR)

    private val storageSourceFile: File
        get() = File(sourceEngineDir, STORAGE_FILE_NAME)

    private data class ChapterContentSample(
        val position: Int,
        val title: String,
        val content: CleanContent
    )

    companion object {
        private const val STORAGE_DIR = "source-engine"
        private const val STORAGE_FILE_NAME = "book-sources.json"
        private const val DEFAULT_KEYWORD = "斗破苍穹"
        private const val MAX_CHAIN_SOURCES = 220
        private const val MAX_BOOKS_PER_SOURCE = 2
        private const val LAST_CHAPTER_SAMPLE_COUNT = 3
        private const val MAX_VISIBLE_MISSING_RANGES = 12
        private const val MIN_CLEAN_CONTENT_CHARS = 200
        private const val MIN_CONTENT_QUALITY_SCORE = 70
        private const val MIN_CONTENT_COHERENCE_SCORE = 70

        fun start(context: Context) {
            context.startActivity(Intent(context, SourceEngineActivity::class.java))
        }

        private val LEGADO_PROVIDER_AUTHORITIES = listOf(
            "io.legado.app.release.readerProvider",
            "io.legado.app.debug.readerProvider"
        )

        private val LEGADO_WEB_SOURCE_URLS = listOf(
            "http://127.0.0.1:1122/getBookSources",
            "http://127.0.0.1:1234/getBookSources"
        )

        private val sampleSourceJson = """
            [
              {
                "bookSourceName": "Codex Sample Source",
                "bookSourceUrl": "https://example.org",
                "bookSourceGroup": "lab",
                "bookSourceComment": "source engine fixture",
                "enabled": true,
                "header": {"User-Agent": "ReaderSourceEngine"},
                "searchUrl": "https://example.org/search?q={{key}}",
                "ruleSearch": {
                  "bookList": ".book",
                  "name": ".title",
                  "author": ".author",
                  "bookUrl": "a@href"
                },
                "ruleBookInfo": {
                  "name": "h1",
                  "author": ".author",
                  "tocUrl": ".toc@href"
                },
                "ruleToc": {
                  "chapterList": ".chapter",
                  "chapterName": "a@text",
                  "chapterUrl": "a@href"
                },
                "ruleContent": {
                  "content": ".content@html"
                },
                "loginUrl": "https://example.org/login",
                "exploreUrl": "https://example.org/explore"
              }
            ]
        """.trimIndent()
    }
}
