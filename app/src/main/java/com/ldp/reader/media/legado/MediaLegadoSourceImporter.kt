package com.ldp.reader.media.legado

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import com.ldp.reader.media.MediaEngineFailure
import com.ldp.reader.media.MediaEngineResult
import com.ldp.reader.media.MediaLegadoRuleSet
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaDiagnosticSeverity
import com.ldp.reader.media.MediaSourceDiagnostic
import com.ldp.reader.media.MediaSourceImportFailure
import com.ldp.reader.media.MediaSourceImportReport

class MediaLegadoSourceImporter {
    fun importJson(json: String): MediaEngineResult<MediaSourceImportReport> {
        if (json.isBlank()) {
            return MediaEngineResult.Failure(MediaEngineFailure.ParseError("Source JSON is blank."))
        }

        return try {
            val root = unwrapProviderPayload(JsonParser.parseString(json))
            val sourceElements = when {
                root.isJsonArray -> root.asJsonArray.toList()
                root.isJsonObject -> listOf(root)
                else -> {
                    return MediaEngineResult.Failure(
                        MediaEngineFailure.ParseError("Source JSON root must be an object or array.")
                    )
                }
            }

            val sources = ArrayList<MediaSourceDefinition>()
            val failures = ArrayList<MediaSourceImportFailure>()
            sourceElements.forEachIndexed { index, element ->
                if (!element.isJsonObject) {
                    failures.add(
                        MediaSourceImportFailure(
                            index,
                            MediaEngineFailure.ContractViolation("Source entry must be a JSON object.")
                        )
                    )
                    return@forEachIndexed
                }

                when (val parsed = parseSource(index, element.asJsonObject)) {
                    is ParsedSource.Accepted -> sources.add(parsed.source)
                    is ParsedSource.Rejected -> failures.add(parsed.failure)
                }
            }

            MediaEngineResult.Success(MediaSourceImportReport(sources, failures))
        } catch (e: JsonParseException) {
            MediaEngineResult.Failure(MediaEngineFailure.ParseError(e.message ?: "Invalid source JSON."))
        } catch (e: IllegalStateException) {
            MediaEngineResult.Failure(MediaEngineFailure.ParseError(e.message ?: "Invalid source JSON."))
        }
    }

    private fun parseSource(index: Int, sourceJson: JsonObject): ParsedSource {
        val diagnostics = ArrayList<MediaSourceDiagnostic>()
        val sourceName = sourceJson.stringOrNull("bookSourceName")
        val sourceUrl = sourceJson.stringOrNull("bookSourceUrl")

        if (sourceName.isNullOrBlank()) {
            return ParsedSource.Rejected(
                MediaSourceImportFailure(
                    index,
                    MediaEngineFailure.ContractViolation("bookSourceName is required.")
                )
            )
        }
        if (sourceUrl.isNullOrBlank()) {
            return ParsedSource.Rejected(
                MediaSourceImportFailure(
                    index,
                    MediaEngineFailure.ContractViolation("bookSourceUrl is required.")
                )
            )
        }

        diagnostics.addAll(diagnoseUnsupportedTopLevelFields(sourceJson))

        val source = MediaSourceDefinition(
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            sourceType = sourceJson.intOrDefault("bookSourceType", 0),
            sourceGroup = sourceJson.stringOrNull("bookSourceGroup"),
            sourceComment = sourceJson.stringOrNull("bookSourceComment"),
            enabled = sourceJson.booleanOrDefault("enabled", true),
            headers = parseHeaders(sourceJson.get("header"), diagnostics),
            searchUrl = sourceJson.stringOrNull("searchUrl"),
            ruleSearch = parseRuleSet("ruleSearch", sourceJson.get("ruleSearch"), diagnostics),
            ruleBookInfo = parseRuleSet("ruleBookInfo", sourceJson.get("ruleBookInfo"), diagnostics),
            ruleToc = parseRuleSet("ruleToc", sourceJson.get("ruleToc"), diagnostics),
            ruleContent = parseRuleSet("ruleContent", sourceJson.get("ruleContent"), diagnostics),
            diagnostics = diagnostics,
            jsLib = sourceJson.stringOrNull("jsLib").orEmpty(),
            loginUrl = sourceJson.stringOrNull("loginUrl").orEmpty()
        )

        return ParsedSource.Accepted(source)
    }

    fun extractSourceArrayJson(json: String): MediaEngineResult<String> {
        return try {
            val root = unwrapProviderPayload(JsonParser.parseString(json))
            if (!root.isJsonArray) {
                MediaEngineResult.Failure(MediaEngineFailure.ParseError("Source JSON root must be an array."))
            } else {
                MediaEngineResult.Success(gson.toJson(root))
            }
        } catch (e: JsonParseException) {
            MediaEngineResult.Failure(MediaEngineFailure.ParseError(e.message ?: "Invalid source JSON."))
        } catch (e: IllegalStateException) {
            MediaEngineResult.Failure(MediaEngineFailure.ParseError(e.message ?: "Invalid source JSON."))
        }
    }

    private fun unwrapProviderPayload(root: JsonElement): JsonElement {
        if (!root.isJsonObject) return root
        val obj = root.asJsonObject
        val data = obj.get("data") ?: return root
        return if (data.isJsonArray || data.isJsonObject) data else root
    }

    private fun diagnoseUnsupportedTopLevelFields(sourceJson: JsonObject): List<MediaSourceDiagnostic> {
        val diagnostics = ArrayList<MediaSourceDiagnostic>()
        sourceJson.entrySet().forEach { (key, value) ->
            if (!value.isMeaningful()) {
                return@forEach
            }

            when {
                key in supportedTopLevelFields -> Unit
                key in knownUnsupportedTopLevelFields -> diagnostics.add(
                    MediaSourceDiagnostic(
                        severity = MediaDiagnosticSeverity.WARNING,
                        code = "unsupported_top_level_field",
                        path = key,
                        message = "$key is present but is not supported by the media source contract."
                    )
                )
                else -> diagnostics.add(
                    MediaSourceDiagnostic(
                        severity = MediaDiagnosticSeverity.WARNING,
                        code = "unknown_top_level_field",
                        path = key,
                        message = "$key is not part of the media source import contract."
                    )
                )
            }
        }
        return diagnostics
    }

    private fun parseHeaders(
        headerElement: JsonElement?,
        diagnostics: MutableList<MediaSourceDiagnostic>
    ): Map<String, String> {
        if (headerElement == null || headerElement.isJsonNull || !headerElement.isMeaningful()) {
            return emptyMap()
        }

        if (headerElement.isJsonObject) {
            return headerElement.asJsonObject.entrySet()
                .filter { it.value.isJsonPrimitive }
                .associate { it.key to it.value.asString }
        }

        if (headerElement.isJsonPrimitive && headerElement.asJsonPrimitive.isString) {
            return parseHeaderString(headerElement.asString, diagnostics)
        }

        diagnostics.add(
            MediaSourceDiagnostic(
                severity = MediaDiagnosticSeverity.ERROR,
                code = "unsupported_header_shape",
                path = "header",
                message = "header must be a JSON object or a JSON-object string."
            )
        )
        return emptyMap()
    }

    private fun parseHeaderString(
        header: String,
        diagnostics: MutableList<MediaSourceDiagnostic>
    ): Map<String, String> {
        if (header.isBlank()) {
            return emptyMap()
        }
        return try {
            gson.fromJson(header, headerMapType)
        } catch (e: JsonParseException) {
            diagnostics.add(
                MediaSourceDiagnostic(
                    severity = MediaDiagnosticSeverity.ERROR,
                    code = "malformed_header",
                    path = "header",
                    message = "header string must be a valid JSON object."
                )
            )
            emptyMap()
        }
    }

    private fun parseRuleSet(
        groupName: String,
        groupElement: JsonElement?,
        diagnostics: MutableList<MediaSourceDiagnostic>
    ): MediaLegadoRuleSet {
        if (groupElement == null || groupElement.isJsonNull || !groupElement.isMeaningful()) {
            return MediaLegadoRuleSet(groupName, emptyMap())
        }
        if (!groupElement.isJsonObject) {
            diagnostics.add(
                MediaSourceDiagnostic(
                    severity = MediaDiagnosticSeverity.WARNING,
                    code = "unsupported_rule_group_shape",
                    path = groupName,
                    message = "$groupName must be a JSON object in the media source contract."
                )
            )
            return MediaLegadoRuleSet(groupName, emptyMap())
        }

        val rules = LinkedHashMap<String, String>()
        groupElement.asJsonObject.entrySet().forEach { (ruleName, ruleValue) ->
            if (ruleValue.isJsonPrimitive) {
                rules[ruleName] = ruleValue.asString
            } else {
                diagnostics.add(
                    MediaSourceDiagnostic(
                        severity = MediaDiagnosticSeverity.WARNING,
                        code = "unsupported_rule_value_shape",
                        path = "$groupName.$ruleName",
                        message = "$groupName.$ruleName must be a primitive string-compatible rule."
                    )
                )
            }
        }
        return MediaLegadoRuleSet(groupName, rules)
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asString
    }

    private fun JsonObject.booleanOrDefault(name: String, defaultValue: Boolean): Boolean {
        val element = get(name) ?: return defaultValue
        if (!element.isJsonPrimitive) return defaultValue
        val primitive = element.asJsonPrimitive
        return if (primitive.isBoolean) primitive.asBoolean else defaultValue
    }

    private fun JsonObject.intOrDefault(name: String, defaultValue: Int): Int {
        val element = get(name) ?: return defaultValue
        if (!element.isJsonPrimitive) return defaultValue
        return runCatching { element.asInt }.getOrDefault(defaultValue)
    }

    private fun JsonElement.isMeaningful(): Boolean {
        if (isJsonNull) return false
        if (isJsonPrimitive) {
            val primitive = asJsonPrimitive
            return !primitive.isString || primitive.asString.isNotBlank()
        }
        if (isJsonArray) return asJsonArray.size() > 0
        if (isJsonObject) return asJsonObject.size() > 0
        return true
    }

    private sealed class ParsedSource {
        data class Accepted(val source: MediaSourceDefinition) : ParsedSource()
        data class Rejected(val failure: MediaSourceImportFailure) : ParsedSource()
    }

    companion object {
        private val gson = Gson()
        private val headerMapType = object : TypeToken<Map<String, String>>() {}.type

        private val supportedTopLevelFields = setOf(
            "bookSourceName",
            "bookSourceUrl",
            "bookSourceType",
            "bookSourceGroup",
            "bookSourceComment",
            "enabled",
            "header",
            "searchUrl",
            "ruleSearch",
            "ruleBookInfo",
            "ruleToc",
            "ruleContent",
            "jsLib",
            "loginUrl"
        )

        private val knownUnsupportedTopLevelFields = setOf(
            "customOrder",
            "enabledExplore",
            "exploreUrl",
            "lastUpdateTime",
            "loadWithBaseUrl",
            "loginUi",
            "respondTime",
            "variableComment",
            "weight"
        )
    }
}
