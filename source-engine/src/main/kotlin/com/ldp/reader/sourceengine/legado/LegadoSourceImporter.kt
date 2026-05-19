package com.ldp.reader.sourceengine.legado

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import com.ldp.reader.sourceengine.EngineFailure
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.DiagnosticSeverity
import com.ldp.reader.sourceengine.model.SourceDiagnostic
import com.ldp.reader.sourceengine.model.SourceImportFailure
import com.ldp.reader.sourceengine.model.SourceImportReport

class LegadoSourceImporter {
    fun importJson(json: String): EngineResult<SourceImportReport> {
        if (json.isBlank()) {
            return EngineResult.Failure(EngineFailure.ParseError("Source JSON is blank."))
        }

        return try {
            val root = unwrapProviderPayload(JsonParser.parseString(json))
            val sourceElements = when {
                root.isJsonArray -> root.asJsonArray.toList()
                root.isJsonObject -> listOf(root)
                else -> {
                    return EngineResult.Failure(
                        EngineFailure.ParseError("Source JSON root must be an object or array.")
                    )
                }
            }

            val sources = ArrayList<BookSource>()
            val failures = ArrayList<SourceImportFailure>()
            sourceElements.forEachIndexed { index, element ->
                if (!element.isJsonObject) {
                    failures.add(
                        SourceImportFailure(
                            index,
                            EngineFailure.ContractViolation("Source entry must be a JSON object.")
                        )
                    )
                    return@forEachIndexed
                }

                when (val parsed = parseSource(index, element.asJsonObject)) {
                    is ParsedSource.Accepted -> sources.add(parsed.source)
                    is ParsedSource.Rejected -> failures.add(parsed.failure)
                }
            }

            EngineResult.Success(SourceImportReport(sources, failures))
        } catch (e: JsonParseException) {
            EngineResult.Failure(EngineFailure.ParseError(e.message ?: "Invalid source JSON."))
        } catch (e: IllegalStateException) {
            EngineResult.Failure(EngineFailure.ParseError(e.message ?: "Invalid source JSON."))
        }
    }

    private fun parseSource(index: Int, sourceJson: JsonObject): ParsedSource {
        val diagnostics = ArrayList<SourceDiagnostic>()
        val sourceName = sourceJson.stringOrNull("bookSourceName")
        val sourceUrl = sourceJson.stringOrNull("bookSourceUrl")

        if (sourceName.isNullOrBlank()) {
            return ParsedSource.Rejected(
                SourceImportFailure(
                    index,
                    EngineFailure.ContractViolation("bookSourceName is required.")
                )
            )
        }
        if (sourceUrl.isNullOrBlank()) {
            return ParsedSource.Rejected(
                SourceImportFailure(
                    index,
                    EngineFailure.ContractViolation("bookSourceUrl is required.")
                )
            )
        }

        diagnostics.addAll(diagnoseUnsupportedTopLevelFields(sourceJson))

        val source = BookSource(
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            sourceGroup = sourceJson.stringOrNull("bookSourceGroup"),
            sourceComment = sourceJson.stringOrNull("bookSourceComment"),
            enabled = sourceJson.booleanOrDefault("enabled", true),
            headers = parseHeaders(sourceJson.get("header"), diagnostics),
            searchUrl = sourceJson.stringOrNull("searchUrl"),
            ruleSearch = parseRuleSet("ruleSearch", sourceJson.get("ruleSearch"), diagnostics),
            ruleBookInfo = parseRuleSet("ruleBookInfo", sourceJson.get("ruleBookInfo"), diagnostics),
            ruleToc = parseRuleSet("ruleToc", sourceJson.get("ruleToc"), diagnostics),
            ruleContent = parseRuleSet("ruleContent", sourceJson.get("ruleContent"), diagnostics),
            diagnostics = diagnostics
        )

        return ParsedSource.Accepted(source)
    }

    fun extractSourceArrayJson(json: String): EngineResult<String> {
        return try {
            val root = unwrapProviderPayload(JsonParser.parseString(json))
            if (!root.isJsonArray) {
                EngineResult.Failure(EngineFailure.ParseError("Source JSON root must be an array."))
            } else {
                EngineResult.Success(gson.toJson(root))
            }
        } catch (e: JsonParseException) {
            EngineResult.Failure(EngineFailure.ParseError(e.message ?: "Invalid source JSON."))
        } catch (e: IllegalStateException) {
            EngineResult.Failure(EngineFailure.ParseError(e.message ?: "Invalid source JSON."))
        }
    }

    private fun unwrapProviderPayload(root: JsonElement): JsonElement {
        if (!root.isJsonObject) return root
        val obj = root.asJsonObject
        val data = obj.get("data") ?: return root
        return if (data.isJsonArray || data.isJsonObject) data else root
    }

    private fun diagnoseUnsupportedTopLevelFields(sourceJson: JsonObject): List<SourceDiagnostic> {
        val diagnostics = ArrayList<SourceDiagnostic>()
        sourceJson.entrySet().forEach { (key, value) ->
            if (!value.isMeaningful()) {
                return@forEach
            }

            when {
                key in supportedTopLevelFields -> Unit
                key in knownUnsupportedTopLevelFields -> diagnostics.add(
                    SourceDiagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        code = "unsupported_top_level_field",
                        path = key,
                        message = "$key is present but is not supported by the first source-engine contract."
                    )
                )
                else -> diagnostics.add(
                    SourceDiagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        code = "unknown_top_level_field",
                        path = key,
                        message = "$key is not part of the first source-engine import contract."
                    )
                )
            }
        }
        return diagnostics
    }

    private fun parseHeaders(
        headerElement: JsonElement?,
        diagnostics: MutableList<SourceDiagnostic>
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
            SourceDiagnostic(
                severity = DiagnosticSeverity.ERROR,
                code = "unsupported_header_shape",
                path = "header",
                message = "header must be a JSON object or a JSON-object string."
            )
        )
        return emptyMap()
    }

    private fun parseHeaderString(
        header: String,
        diagnostics: MutableList<SourceDiagnostic>
    ): Map<String, String> {
        if (header.isBlank()) {
            return emptyMap()
        }
        return try {
            gson.fromJson(header, headerMapType)
        } catch (e: JsonParseException) {
            diagnostics.add(
                SourceDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
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
        diagnostics: MutableList<SourceDiagnostic>
    ): LegadoRuleSet {
        if (groupElement == null || groupElement.isJsonNull || !groupElement.isMeaningful()) {
            return LegadoRuleSet(groupName, emptyMap())
        }
        if (!groupElement.isJsonObject) {
            diagnostics.add(
                SourceDiagnostic(
                    severity = DiagnosticSeverity.WARNING,
                    code = "unsupported_rule_group_shape",
                    path = groupName,
                    message = "$groupName must be a JSON object in the first source-engine contract."
                )
            )
            return LegadoRuleSet(groupName, emptyMap())
        }

        val rules = LinkedHashMap<String, String>()
        groupElement.asJsonObject.entrySet().forEach { (ruleName, ruleValue) ->
            if (ruleValue.isJsonPrimitive) {
                rules[ruleName] = ruleValue.asString
            } else {
                diagnostics.add(
                    SourceDiagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        code = "unsupported_rule_value_shape",
                        path = "$groupName.$ruleName",
                        message = "$groupName.$ruleName must be a primitive string-compatible rule."
                    )
                )
            }
        }
        return LegadoRuleSet(groupName, rules)
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
        data class Accepted(val source: BookSource) : ParsedSource()
        data class Rejected(val failure: SourceImportFailure) : ParsedSource()
    }

    companion object {
        private val gson = Gson()
        private val headerMapType = object : TypeToken<Map<String, String>>() {}.type

        private val supportedTopLevelFields = setOf(
            "bookSourceName",
            "bookSourceUrl",
            "bookSourceGroup",
            "bookSourceComment",
            "enabled",
            "header",
            "searchUrl",
            "ruleSearch",
            "ruleBookInfo",
            "ruleToc",
            "ruleContent"
        )

        private val knownUnsupportedTopLevelFields = setOf(
            "bookSourceType",
            "customOrder",
            "enabledExplore",
            "exploreUrl",
            "lastUpdateTime",
            "loadWithBaseUrl",
            "loginUi",
            "loginUrl",
            "respondTime",
            "variableComment",
            "weight",
            "jsLib"
        )
    }
}
