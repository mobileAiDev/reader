package com.ldp.reader.source

import com.ldp.reader.App
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.legado.LegadoSourceImporter
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.BookSourceType
import com.ldp.reader.sourceengine.model.SourceImportFailure
import com.ldp.reader.sourceengine.model.SourceImportReport
import java.io.File
import java.util.Locale

object SourceEngineRuntime {
    private const val STORAGE_DIR = "source-engine"
    private const val STORAGE_FILE_NAME = "book-sources.json"
    private const val ASSET_FILE_NAME = "source-engine/book-sources.json"

    private val importer = LegadoSourceImporter()
    private var cachedReport: SourceImportReport? = null

    @Synchronized
    fun loadReport(): SourceImportReport {
        cachedReport?.let { return it }
        val reports = listOfNotNull(
            readAssetReport(),
            ImportedSourceStore.loadReport()?.withUserImportedMarker(),
            readLegacyStorageReport()?.withUserImportedMarker()
        )
        return mergeReports(reports).also { cachedReport = it }
    }

    @Synchronized
    fun invalidate() {
        cachedReport = null
    }

    private fun readAssetReport(): SourceImportReport {
        val json = App.getContext().assets.open(ASSET_FILE_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return when (val result = importer.importJson(json)) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> error("Source-engine import failed: ${result.failure}")
        }
    }

    private fun readLegacyStorageReport(): SourceImportReport? {
        val file = File(File(App.getContext().filesDir, STORAGE_DIR), STORAGE_FILE_NAME)
        if (!file.isFile) return null
        return when (val result = importer.importJson(file.readText(Charsets.UTF_8))) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> null
        }
    }

    private fun mergeReports(reports: List<SourceImportReport>): SourceImportReport {
        val sources = ArrayList<BookSource>()
        val failures = ArrayList<SourceImportFailure>()
        val seen = LinkedHashSet<String>()
        reports.forEach { report ->
            report.sources.forEach { source ->
                if (seen.add(sourceKey(source))) {
                    sources.add(source)
                }
            }
            failures.addAll(report.rejectedSources)
        }
        return SourceImportReport(sources, failures)
    }

    private fun SourceImportReport.withUserImportedMarker(): SourceImportReport {
        return SourceImportReport(
            sources = sources.map { source ->
                source.copy(sourceComment = source.sourceComment.withMarker(USER_IMPORTED_SOURCE_MARKER))
            },
            rejectedSources = rejectedSources
        )
    }

    private fun String?.withMarker(marker: String): String {
        if (this?.contains(marker) == true) return this
        return listOfNotNull(this?.takeIf { it.isNotBlank() }, marker).joinToString("\n")
    }

    fun compatibleSources(): List<BookSource> {
        return compatibleSourcesForType(BookSourceType.TEXT)
    }

    fun compatibleSourcesForType(sourceType: Int): List<BookSource> {
        if (sourceType != BookSourceType.TEXT) return emptyList()
        return loadReport().sources
            .filter { it.sourceType == sourceType }
            .filter(SourceEngineCompatibility::isCompatible)
    }

    fun compatibleBuiltInSourcesForType(sourceType: Int): List<BookSource> {
        if (sourceType != BookSourceType.TEXT) return emptyList()
        return readAssetReport().sources
            .filter { it.sourceType == sourceType }
            .filter(SourceEngineCompatibility::isCompatible)
    }

    fun findSource(sourceUrl: String): BookSource {
        return findSource(sourceUrl, loadReport().sources)
            ?: error("Source-engine source not found: $sourceUrl")
    }

    internal fun findSource(sourceUrl: String, sources: List<BookSource>): BookSource? {
        return sources.firstOrNull { it.sourceUrl == sourceUrl }
            ?: findSourceByNormalizedUrl(sourceUrl, sources)
    }

    private fun findSourceByNormalizedUrl(sourceUrl: String, sources: List<BookSource>): BookSource? {
        val targetKeys = sourceLookupKeys(sourceUrl)
        if (targetKeys.isEmpty()) return null
        return sources.firstOrNull { source ->
            sourceLookupKeys(source.sourceUrl).any { key -> key in targetKeys }
        }
    }

    private fun sourceLookupKeys(sourceUrl: String): Set<String> {
        val trimmed = sourceUrl.trim()
        if (trimmed.isBlank()) return emptySet()
        val withoutScheme = trimmed
            .removePrefix("http://")
            .removePrefix("https://")
        return linkedSetOf(
            normalizeSourceLookupKey(trimmed),
            normalizeSourceLookupKey(withoutScheme),
            normalizeSourceLookupKey(withoutScheme.substringBefore("#"))
        ).filterTo(LinkedHashSet()) { it.isNotBlank() }
    }

    private fun normalizeSourceLookupKey(value: String): String {
        return value.trim()
            .trimEnd('/')
            .lowercase(Locale.ROOT)
    }

    private fun sourceKey(source: BookSource): String {
        return normalizeSourceLookupKey(source.sourceName) + "|" + normalizeSourceLookupKey(source.sourceUrl)
    }

    private const val USER_IMPORTED_SOURCE_MARKER = "reader:user-imported-source"
}
