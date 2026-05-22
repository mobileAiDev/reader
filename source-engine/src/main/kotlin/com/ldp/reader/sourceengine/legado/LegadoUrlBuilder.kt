package com.ldp.reader.sourceengine.legado

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ldp.reader.sourceengine.model.BookSource
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset

class LegadoUrlBuilder {
    fun buildRequest(source: BookSource, rawSearchUrl: String, keyword: String, page: Int): HttpRequest {
        val parsed = parseUrlConfig(rawSearchUrl)
        val method = parsed.config?.getString("method")?.uppercase() ?: "GET"
        val charset = parsed.config?.getString("charset")
        val body = parsed.config?.getString("body")?.let { renderCommonTemplate(it, keyword, page, charset) }
        val url = resolveUrl(source.baseUrl(), renderCommonTemplate(parsed.url, keyword, page, charset))
        return HttpRequest(
            url = url,
            method = method,
            headers = source.headers + parsed.config.headers(),
            body = body,
            charset = charset
        )
    }

    fun buildConfiguredRequest(source: BookSource, rawUrl: String): HttpRequest {
        val parsed = parseUrlConfig(rawUrl)
        val method = parsed.config?.getString("method")?.uppercase() ?: "GET"
        val charset = parsed.config?.getString("charset")
        val url = resolveUrl(source.baseUrl(), parsed.url)
        return HttpRequest(
            url = url,
            method = method,
            headers = source.headers + parsed.config.headers(),
            body = parsed.config?.getString("body"),
            charset = charset
        )
    }

    fun resolveUrl(baseUrl: String, url: String): String {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return ""
        if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            return cleanUrl
        }
        return URI(baseUrl).resolve(cleanUrl).toString()
    }

    private fun renderCommonTemplate(raw: String, keyword: String, page: Int, charset: String?): String {
        val encodedKey = urlEncode(keyword, charset)
        val traditionalKey = simplifiedToTraditional(keyword)
        val encodedTraditionalKey = urlEncode(traditionalKey, charset)
        val simplifiedKey = traditionalToSimplified(keyword)
        val encodedSimplifiedKey = urlEncode(simplifiedKey, charset)
        return raw
            .replace("{{key}}", encodedKey)
            .replace("{{page}}", page.toString())
            .replace("{{java.encodeURI(key)}}", encodedKey)
            .replace("{{java.encodeURIComponent(key)}}", encodedKey)
            .replace("{{java.s2t(key)}}", encodedTraditionalKey)
            .replace("{{java.t2s(key)}}", encodedSimplifiedKey)
            .replace("{{java.encodeURI(java.s2t(key))}}", encodedTraditionalKey)
            .replace("{{java.encodeURIComponent(java.s2t(key))}}", encodedTraditionalKey)
            .replace("{{java.encodeURI(java.t2s(key))}}", encodedSimplifiedKey)
            .replace("{{java.encodeURIComponent(java.t2s(key))}}", encodedSimplifiedKey)
    }

    private fun urlEncode(value: String, charset: String?): String {
        val encoding = charset?.takeIf { it.isNotBlank() } ?: "UTF-8"
        return runCatching {
            Charset.forName(encoding)
            URLEncoder.encode(value, encoding)
        }.getOrElse {
            URLEncoder.encode(value, "UTF-8")
        }
    }

    private fun parseUrlConfig(raw: String): ParsedUrl {
        val value = raw.trim()
        val configStart = URL_CONFIG_START.find(value)?.range?.first ?: -1
        if (configStart < 0) {
            return ParsedUrl(value, null)
        }
        val url = value.substring(0, configStart)
        val configText = value.substring(configStart + 1).trim()
        val config = runCatching {
            JsonParser.parseString(configText).asJsonObject
        }.getOrNull()
        return ParsedUrl(url, config)
    }

    private fun JsonObject.getString(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive) value.asString else null
    }

    private fun JsonObject?.headers(): Map<String, String> {
        val headers = this?.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return headers.entrySet()
            .filter { (_, value) -> value.isJsonPrimitive }
            .associate { (name, value) -> name to value.asString }
    }

    private fun BookSource.baseUrl(): String {
        return sourceUrl.substringBefore("##").substringBefore("#").let { base ->
            if (base.endsWith("/")) base else "$base/"
        }
    }

    private fun simplifiedToTraditional(value: String): String {
        return value.map { char -> SIMPLIFIED_TO_TRADITIONAL[char] ?: char }.joinToString("")
    }

    private fun traditionalToSimplified(value: String): String {
        return value.map { char -> TRADITIONAL_TO_SIMPLIFIED[char] ?: char }.joinToString("")
    }

    private data class ParsedUrl(
        val url: String,
        val config: JsonObject?
    )

    companion object {
        private val SIMPLIFIED_TO_TRADITIONAL = mapOf(
            '万' to '萬',
            '长' to '長',
            '青' to '青',
            '剑' to '劍',
            '来' to '來',
            '书' to '書',
            '仙' to '仙',
            '界' to '界',
            '修' to '修',
            '我' to '我',
            '在' to '在',
            '诚' to '誠',
            '为' to '為',
            '云' to '雲',
            '医' to '醫',
            '龙' to '龍',
            '阶' to '階',
            '参' to '參',
            '虫' to '蟲',
            '现' to '現',
            '无' to '無',
            '情' to '情',
            '后' to '後'
        )
        private val TRADITIONAL_TO_SIMPLIFIED = SIMPLIFIED_TO_TRADITIONAL.entries.associate { (simple, traditional) ->
            traditional to simple
        }
        private val URL_CONFIG_START = Regex(""",\s*\{""")
    }
}
