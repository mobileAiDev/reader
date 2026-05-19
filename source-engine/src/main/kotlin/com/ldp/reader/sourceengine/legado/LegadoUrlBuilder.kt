package com.ldp.reader.sourceengine.legado

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ldp.reader.sourceengine.model.BookSource
import java.net.URI
import java.net.URLEncoder

class LegadoUrlBuilder {
    fun buildRequest(source: BookSource, rawSearchUrl: String, keyword: String, page: Int): HttpRequest {
        val parsed = parseUrlConfig(rawSearchUrl)
        val method = parsed.config?.getString("method")?.uppercase() ?: "GET"
        val charset = parsed.config?.getString("charset")
        val body = parsed.config?.getString("body")?.let { renderCommonTemplate(it, keyword, page) }
        val url = resolveUrl(source.baseUrl(), renderCommonTemplate(parsed.url, keyword, page))
        return HttpRequest(
            url = url,
            method = method,
            headers = source.headers,
            body = body,
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

    private fun renderCommonTemplate(raw: String, keyword: String, page: Int): String {
        val encodedKey = URLEncoder.encode(keyword, "UTF-8")
        return raw
            .replace("{{key}}", encodedKey)
            .replace("{{page}}", page.toString())
            .replace("{{java.encodeURI(key)}}", encodedKey)
            .replace("{{java.encodeURIComponent(key)}}", encodedKey)
    }

    private fun parseUrlConfig(raw: String): ParsedUrl {
        val value = raw.trim()
        val configStart = value.indexOf(",{")
        if (configStart < 0) {
            return ParsedUrl(value, null)
        }
        val url = value.substring(0, configStart)
        val configText = value.substring(configStart + 1)
        val config = runCatching {
            JsonParser.parseString(configText).asJsonObject
        }.getOrNull()
        return ParsedUrl(url, config)
    }

    private fun JsonObject.getString(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive) value.asString else null
    }

    private fun BookSource.baseUrl(): String {
        return sourceUrl.substringBefore("##").substringBefore("#").let { base ->
            if (base.endsWith("/")) base else "$base/"
        }
    }

    private data class ParsedUrl(
        val url: String,
        val config: JsonObject?
    )
}
