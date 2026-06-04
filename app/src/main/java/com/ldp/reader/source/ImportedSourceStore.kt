package com.ldp.reader.source

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.ldp.reader.App
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.legado.LegadoSourceImporter
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.BookSourceType
import com.ldp.reader.sourceengine.model.SourceImportReport
import java.io.File
import java.util.Locale

object ImportedSourceStore {
    private const val STORAGE_DIR = "source-engine"
    private const val USER_STORAGE_FILE_NAME = "user-novel-sources.json"

    private val gson = Gson()
    private val importer = LegadoSourceImporter()

    @Synchronized
    fun appendFromJson(json: String): ImportedSourceSummary {
        val incoming = acceptedEntries(json)
        val existing = acceptedEntries(readUserSourceJson().orEmpty())
        val existingKeys = existing.mapTo(LinkedHashSet()) { sourceKey(it.source) }
        val mergedObjects = JsonArray()
        existing.forEach { mergedObjects.add(it.raw) }

        var duplicateCount = 0
        incoming.forEach { entry ->
            if (existingKeys.add(sourceKey(entry.source))) {
                mergedObjects.add(entry.raw)
            } else {
                duplicateCount += 1
            }
        }

        sourceFile.parentFile?.mkdirs()
        sourceFile.writeText(gson.toJson(mergedObjects), Charsets.UTF_8)
        SourceEngineRuntime.invalidate()

        val imported = incoming.size - duplicateCount
        return ImportedSourceSummary(
            acceptedCount = incoming.size,
            importedCount = imported.coerceAtLeast(0),
            duplicateCount = duplicateCount,
            rejectedCount = rejectedCount(json),
            textCount = incoming.count { it.source.sourceType == BookSourceType.TEXT },
            audioCount = 0,
            comicCount = 0,
            fileCount = 0
        )
    }

    @Synchronized
    fun loadReport(): SourceImportReport? {
        val json = readUserSourceJson() ?: return null
        return when (val result = importer.importJson(json)) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> null
        }
    }

    private fun readUserSourceJson(): String? {
        return sourceFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    private fun acceptedEntries(json: String): List<AcceptedSourceEntry> {
        if (json.isBlank()) return emptyList()
        return sourceObjects(json).mapNotNull { sourceJson ->
            val singleJson = gson.toJson(sourceJson)
            when (val result = importer.importJson(singleJson)) {
                is EngineResult.Success -> {
                    val source = result.value.sources.singleOrNull() ?: return@mapNotNull null
                    if (source.sourceType != BookSourceType.TEXT) return@mapNotNull null
                    AcceptedSourceEntry(source, sourceJson.deepCopy())
                }
                is EngineResult.Failure -> null
            }
        }
    }

    private fun rejectedCount(json: String): Int {
        if (json.isBlank()) return 0
        return when (val result = importer.importJson(json)) {
            is EngineResult.Success -> result.value.rejectedSources.size
            is EngineResult.Failure -> 1
        }
    }

    private fun sourceObjects(json: String): List<JsonObject> {
        return try {
            val root = unwrapProviderPayload(JsonParser.parseString(json))
            when {
                root.isJsonArray -> root.asJsonArray.mapNotNull { it.asObjectOrNull() }
                root.isJsonObject -> listOf(root.asJsonObject)
                else -> emptyList()
            }
        } catch (_: JsonParseException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }

    private fun unwrapProviderPayload(root: JsonElement): JsonElement {
        if (!root.isJsonObject) return root
        val data = root.asJsonObject.get("data") ?: return root
        return if (data.isJsonArray || data.isJsonObject) data else root
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? {
        return if (isJsonObject) asJsonObject else null
    }

    private fun sourceKey(source: BookSource): String {
        return normalize(source.sourceName) + "|" + normalize(source.sourceUrl)
    }

    private fun normalize(value: String): String {
        return value.trim()
            .trimEnd('/')
            .lowercase(Locale.ROOT)
    }

    private val sourceFile: File
        get() = File(File(App.getContext().filesDir, STORAGE_DIR), USER_STORAGE_FILE_NAME)

    private data class AcceptedSourceEntry(
        val source: BookSource,
        val raw: JsonObject
    )
}

data class ImportedSourceSummary(
    val acceptedCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val textCount: Int,
    val audioCount: Int,
    val comicCount: Int,
    val fileCount: Int
)
