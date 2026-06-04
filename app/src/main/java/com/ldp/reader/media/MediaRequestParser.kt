package com.ldp.reader.media

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

object MediaRequestParser {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"

    fun parse(
        rawValue: String,
        baseUrl: String = "",
        defaultHeaders: Map<String, String> = emptyMap()
    ): MediaRequest? {
        val value = rawValue.trim().trim('"', '\'')
        if (value.isBlank()) return null
        val split = splitUrlAndOptions(value)
        val url = resolveUrl(baseUrl, split.first).ifBlank { return null }
        val headers = linkedMapOf<String, String>()
        headers.putAll(defaultHeaders.filterKeys { it.isNotBlank() })
        if (!headers.containsHeader("User-Agent")) {
            headers["User-Agent"] = DEFAULT_USER_AGENT
        }
        if (!headers.containsHeader("Referer") && baseUrl.startsWith("http", ignoreCase = true)) {
            headers["Referer"] = baseUrl
        }
        headers.putAll(parseHeaders(split.second))
        return MediaRequest(url, headers.filterValues { it.isNotBlank() })
    }

    fun resolveUrl(baseUrl: String, rawUrl: String): String {
        val value = rawUrl.trim().trim('"', '\'')
        if (value.isBlank()) return ""
        if (value.startsWith("data:", ignoreCase = true)) return value
        if (value.startsWith("//")) {
            val scheme = runCatching { URI(baseUrl).scheme }.getOrNull() ?: "https"
            return "$scheme:$value"
        }
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return canonicalizeHttpUrl(value)
        }
        return runCatching { URI(baseUrl).resolve(value).toString() }
            .getOrElse { resolveLenientHttpUrl(baseUrl, value) ?: value }
            .let(::canonicalizeHttpUrl)
    }

    private fun resolveLenientHttpUrl(baseUrl: String, rawUrl: String): String? {
        val origin = HTTP_ORIGIN.find(baseUrl)?.value ?: return null
        if (rawUrl.startsWith("/")) return origin + rawUrl
        val cleanBase = baseUrl.substringBefore("#").substringBefore("?")
        val basePath = cleanBase
            .removePrefix(origin)
            .substringBeforeLast("/", missingDelimiterValue = "")
        return if (basePath.isBlank()) {
            "$origin/$rawUrl"
        } else {
            "$origin$basePath/$rawUrl"
        }
    }

    private fun canonicalizeHttpUrl(value: String): String {
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            return value
        }
        return value.toHttpUrlOrNull()?.toString() ?: value
    }

    private fun splitUrlAndOptions(value: String): Pair<String, String?> {
        val optionStart = value.indexOfOptionJson()
        if (optionStart < 0) return value to null
        return value.substring(0, optionStart).trim() to value.substring(optionStart + 1).trim()
    }

    private fun String.indexOfOptionJson(): Int {
        for (index in indices) {
            if (this[index] != ',') continue
            val next = substring(index + 1).trimStart()
            if (next.startsWith("{")) return index
        }
        return -1
    }

    private fun parseHeaders(optionJson: String?): Map<String, String> {
        if (optionJson.isNullOrBlank()) return emptyMap()
        val root = parseJsonLenient(optionJson)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return parseHeaderRegex(optionJson)
        val headers = root.get("headers") ?: root.get("header") ?: return emptyMap()
        return headersFromElement(headers)
    }

    private fun headersFromElement(element: JsonElement): Map<String, String> {
        return when {
            element.isJsonObject -> element.asJsonObject.toStringMap()
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                parseJsonLenient(element.asString)?.takeIf { it.isJsonObject }?.asJsonObject?.toStringMap()
                    ?: emptyMap()
            }
            else -> emptyMap()
        }
    }

    private fun parseJsonLenient(value: String): JsonElement? {
        return runCatching { JsonParser.parseString(value) }.getOrNull()
            ?: runCatching { JsonParser.parseString(normalizeJsonLike(value)) }.getOrNull()
    }

    private fun normalizeJsonLike(value: String): String {
        return value
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '"')
            .replace('’', '"')
            .replace('\'', '"')
            .replace(Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:""")) { match ->
                """${match.groupValues[1]}"${match.groupValues[2]}":"""
            }
    }

    private fun parseHeaderRegex(value: String): Map<String, String> {
        val output = linkedMapOf<String, String>()
        HEADER_ENTRY.findAll(value).forEach { match ->
            output[match.groupValues[1]] = match.groupValues[2]
        }
        return output
    }

    private fun JsonObject.toStringMap(): Map<String, String> {
        return entrySet()
            .filter { it.value.isJsonPrimitive }
            .associate { it.key to it.value.asString }
    }

    private fun Map<String, String>.containsHeader(name: String): Boolean {
        return keys.any { it.equals(name, ignoreCase = true) }
    }

    private val HEADER_ENTRY = Regex("""['"]([^'"]+)['"]\s*:\s*['"]([^'"]*)['"]""")
    private val HTTP_ORIGIN = Regex("""https?://[^/?#]+""", RegexOption.IGNORE_CASE)
}
