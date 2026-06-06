package com.ldp.reader.media

import com.ldp.reader.App
import com.ldp.reader.media.legado.MediaLegadoSourceImporter
import com.ldp.reader.media.MediaEngineResult
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceImportFailure
import com.ldp.reader.media.MediaSourceImportReport
import java.util.Locale

object MediaSourceRuntime {
    private const val ASSET_FILE_NAME = "media-source-engine/media-sources.json"

    private val importer = MediaLegadoSourceImporter()
    private var cachedReport: MediaSourceImportReport? = null

    @Synchronized
    fun loadReport(): MediaSourceImportReport {
        cachedReport?.let { return it }
        val reports = listOfNotNull(
            readAssetReport(),
            ImportedMediaSourceStore.loadReport()?.withUserImportedMarker()
        )
        return mergeReports(reports).also { cachedReport = it }
    }

    @Synchronized
    fun invalidate() {
        cachedReport = null
    }

    fun compatibleSourcesForType(sourceType: Int): List<MediaSourceDefinition> {
        return loadReport().sources
            .filter { it.sourceType == sourceType }
            .filter(MediaSourceCompatibility::isCompatible)
    }

    fun compatibleBuiltInSourcesForType(sourceType: Int): List<MediaSourceDefinition> {
        return readAssetReport().sources
            .filter { it.sourceType == sourceType }
            .filter(MediaSourceCompatibility::isCompatible)
    }

    fun compatibleSourceForUrl(sourceType: Int, sourceUrl: String): MediaSourceDefinition? {
        val target = normalize(sourceUrl)
        if (target.isBlank()) return null
        return compatibleSourcesForType(sourceType)
            .firstOrNull { normalize(it.sourceUrl) == target }
    }

    private fun readAssetReport(): MediaSourceImportReport {
        val json = App.getContext().assets.open(ASSET_FILE_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return when (val result = importer.importJson(json)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> error("Media source import failed: ${result.failure}")
        }
    }

    private fun mergeReports(reports: List<MediaSourceImportReport>): MediaSourceImportReport {
        val sources = ArrayList<MediaSourceDefinition>()
        val failures = ArrayList<MediaSourceImportFailure>()
        val seen = LinkedHashSet<String>()
        reports.forEach { report ->
            report.sources.forEach { source ->
                if (seen.add(sourceKey(source))) {
                    sources.add(source)
                }
            }
            failures.addAll(report.rejectedSources)
        }
        return MediaSourceImportReport(sources, failures)
    }

    private fun MediaSourceImportReport.withUserImportedMarker(): MediaSourceImportReport {
        return MediaSourceImportReport(
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

    private fun sourceKey(source: MediaSourceDefinition): String {
        return normalize(source.sourceName) + "|" + normalize(source.sourceUrl)
    }

    private fun normalize(value: String): String {
        return value.trim()
            .trimEnd('/')
            .lowercase(Locale.ROOT)
    }

    private const val USER_IMPORTED_SOURCE_MARKER = "reader:user-imported-source"
}
