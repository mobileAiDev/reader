package com.ldp.reader.media.legado

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ldp.reader.media.MediaRequestParser
import com.ldp.reader.media.legado.MediaHttpRequest

internal data class LegadoMediaUrlRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val charset: String? = null,
    val webView: Boolean = false,
    val webViewDelayMillis: Long = 1_200L
) {
    fun toMediaHttpRequest(): MediaHttpRequest {
        return MediaHttpRequest(
            url = url,
            method = method,
            headers = headers,
            body = body,
            charset = charset
        )
    }

    companion object {
        fun parse(rawValue: String, baseUrl: String, defaultHeaders: Map<String, String>): LegadoMediaUrlRequest {
            val (rawUrl, optionJson) = splitUrlAndOptions(rawValue.trim())
            val option = parseJsonLenient(optionJson)?.takeIf { it.isJsonObject }?.asJsonObject
            val headers = linkedMapOf<String, String>()
            headers.putAll(defaultHeaders.filterKeys { it.isNotBlank() })
            headers.putAll(option.headers())
            val body = option.string("body")
            return LegadoMediaUrlRequest(
                url = MediaRequestParser.resolveUrl(baseUrl, rawUrl),
                method = option.string("method")?.uppercase() ?: if (body != null) "POST" else "GET",
                headers = headers.filterValues { it.isNotBlank() },
                body = body,
                charset = option.string("charset"),
                webView = option.boolean("webView") ||
                    option.boolean("useWebView") ||
                    optionJson.booleanFlag("webView") ||
                    optionJson.booleanFlag("useWebView"),
                webViewDelayMillis = option.long("webViewDelayTime")
                    ?: option.long("webViewDelay")
                    ?: 1_200L
            )
        }

        fun resolveRuleUrl(baseUrl: String, rawValue: String): String {
            val (rawUrl, optionJson) = splitUrlAndOptions(rawValue.trim())
            val resolved = MediaRequestParser.resolveUrl(baseUrl, rawUrl)
            return if (optionJson.isNullOrBlank()) resolved else "$resolved,$optionJson"
        }

        private fun splitUrlAndOptions(value: String): Pair<String, String?> {
            val index = value.indexOfOptionJson()
            if (index < 0) return value to null
            return value.substring(0, index).trim() to value.substring(index + 1).trim()
        }

        private fun String.indexOfOptionJson(): Int {
            for (index in indices) {
                if (this[index] != ',') continue
                if (substring(index + 1).trimStart().startsWith("{")) return index
            }
            return -1
        }

        private fun parseJsonLenient(value: String?): JsonElement? {
            if (value.isNullOrBlank()) return null
            val normalized = normalizeJsonLike(value)
            return runCatching { JsonParser.parseString(value) }.getOrNull()
                ?: runCatching { JsonParser.parseString(normalized) }.getOrNull()
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

        private fun JsonObject?.headers(): Map<String, String> {
            val headers = this?.get("headers") ?: this?.get("header") ?: return emptyMap()
            return when {
                headers.isJsonObject -> headers.asJsonObject.toStringMap()
                headers.isJsonPrimitive -> parseJsonLenient(headers.asString)
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.toStringMap()
                    .orEmpty()
                else -> emptyMap()
            }
        }

        private fun JsonObject.toStringMap(): Map<String, String> {
            return entrySet()
                .filter { (_, value) -> value.isJsonPrimitive }
                .associate { (key, value) -> key to value.asString }
        }

        private fun JsonObject?.string(name: String): String? {
            val value = this?.get(name) ?: return null
            return value.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }

        private fun JsonObject?.boolean(name: String): Boolean {
            val value = this?.get(name) ?: return false
            if (!value.isJsonPrimitive) return false
            val primitive = value.asJsonPrimitive
            return when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isString -> primitive.asString.equals("true", ignoreCase = true)
                else -> false
            }
        }

        private fun JsonObject?.long(name: String): Long? {
            val value = this?.get(name) ?: return null
            return runCatching { value.asLong }.getOrNull()
        }

        private fun String?.orDefault(defaultValue: String): String {
            return this?.takeIf { it.isNotBlank() } ?: defaultValue
        }

        private fun String?.booleanFlag(name: String): Boolean {
            if (this.isNullOrBlank()) return false
            val normalized = normalizeJsonLike(this)
            return Regex(""""$name"\s*:\s*"?true"?""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        }
    }
}
