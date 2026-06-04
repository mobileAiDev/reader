package com.ldp.reader.media

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.ldp.reader.App
import com.ldp.reader.media.legado.MediaLegadoSourceImporter
import java.io.File
import java.util.Locale

object ImportedMediaSourceStore {
    private const val STORAGE_DIR = "media-source-engine"
    private const val USER_STORAGE_FILE_NAME = "user-media-sources.json"

    private val gson = Gson()
    private val importer = MediaLegadoSourceImporter()

    @Synchronized
    fun appendFromJson(json: String): MediaImportSummary {
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

        val imported = incoming.size - duplicateCount
        return MediaImportSummary(
            acceptedCount = incoming.size,
            importedCount = imported.coerceAtLeast(0),
            duplicateCount = duplicateCount,
            rejectedCount = rejectedCount(json),
            audioCount = incoming.count { it.source.sourceType == MediaSourceType.AUDIO },
            comicCount = incoming.count { it.source.sourceType == MediaSourceType.COMIC }
        )
    }

    @Synchronized
    fun loadReport(): MediaSourceImportReport? {
        val json = readUserSourceJson() ?: return null
        return when (val result = importer.importJson(json)) {
            is MediaEngineResult.Success -> result.value
            is MediaEngineResult.Failure -> null
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
                is MediaEngineResult.Success -> {
                    val source = result.value.sources.singleOrNull() ?: return@mapNotNull null
                    if (source.sourceType != MediaSourceType.AUDIO &&
                        source.sourceType != MediaSourceType.COMIC
                    ) {
                        return@mapNotNull null
                    }
                    AcceptedSourceEntry(source, sourceJson.deepCopy())
                }
                is MediaEngineResult.Failure -> null
            }
        }
    }

    private fun rejectedCount(json: String): Int {
        if (json.isBlank()) return 0
        return when (val result = importer.importJson(json)) {
            is MediaEngineResult.Success -> result.value.rejectedSources.size
            is MediaEngineResult.Failure -> 1
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

    private fun sourceKey(source: MediaSourceDefinition): String {
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
        val source: MediaSourceDefinition,
        val raw: JsonObject
    )
}
