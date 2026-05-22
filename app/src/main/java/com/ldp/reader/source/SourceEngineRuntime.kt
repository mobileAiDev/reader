package com.ldp.reader.source

import com.ldp.reader.App
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.legado.LegadoSourceImporter
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceImportReport
import java.io.File

object SourceEngineRuntime {
    private const val STORAGE_DIR = "source-engine"
    private const val STORAGE_FILE_NAME = "book-sources.json"
    private const val ASSET_FILE_NAME = "source-engine/book-sources.json"

    private val importer = LegadoSourceImporter()
    private var cachedReport: SourceImportReport? = null

    @Synchronized
    fun loadReport(): SourceImportReport {
        cachedReport?.let { return it }
        val file = File(File(App.getContext().filesDir, STORAGE_DIR), STORAGE_FILE_NAME)
        val json = if (file.isFile) {
            file.readText()
        } else {
            App.getContext().assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return when (val result = importer.importJson(json)) {
            is EngineResult.Success -> result.value.also { cachedReport = it }
            is EngineResult.Failure -> error("Source-engine import failed: ${result.failure}")
        }
    }

    fun compatibleSources(): List<BookSource> {
        return loadReport().sources.filter { SourceEngineCompatibility.isCompatible(it) }
    }

    fun findSource(sourceUrl: String): BookSource {
        return loadReport().sources.firstOrNull { it.sourceUrl == sourceUrl }
            ?: error("Source-engine source not found: $sourceUrl")
    }
}
