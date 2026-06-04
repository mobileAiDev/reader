package com.ldp.reader.media

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ldp.reader.media.MediaSourceChapter

internal object MediaContentJsAdapter {
    private val imageSuffix = Regex("""(?i)\.(jpg|jpeg|png|webp|gif)(?:[?#].*)?$""")
    private val constUrlPrefix = Regex("""const\s+\w+\s*=\s*["']([^"']+)["']""")
    private val jsonUrlField = Regex("""["']url["']\s*:\s*["']([^"']+)["']""")

    fun adaptComicRawContent(rawContent: String, chapter: MediaSourceChapter): String {
        if (rawContent.isBlank()) return rawContent
        if (ComicPageExtractor.extractRequests(rawContent, chapter.chapterUrl, chapter.source.headers).isNotEmpty()) {
            return rawContent
        }
        val rule = chapter.source.ruleContent.rules["content"].orEmpty()
        if (!rule.contains("@js:", ignoreCase = true)) return rawContent
        adaptSplitLineImages(rawContent, rule, chapter)?.let { return it }
        adaptJsonUrlImages(rawContent, rule, chapter)?.let { return it }
        return rawContent
    }

    private fun adaptSplitLineImages(
        rawContent: String,
        rule: String,
        chapter: MediaSourceChapter
    ): String? {
        if (!rule.contains("result.split", ignoreCase = true)) return null
        if (!rule.contains("<img", ignoreCase = true)) return null
        val defaultHeaders = if (rule.contains("Referer", ignoreCase = true)) {
            chapter.source.headers + ("Referer" to chapter.chapterUrl)
        } else {
            chapter.source.headers
        }
        val requests = rawContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { MediaRequestParser.parse(it, chapter.chapterUrl, defaultHeaders) }
            .filter { isImageUrl(it.url) }
            .distinctBy { it.url }
            .toList()
        if (requests.isEmpty()) return null
        return requests.joinToString("\n") { request ->
            """<img src="${escapeAttribute(request.url)},${headersOption(request.headers)}">"""
        }
    }

    private fun adaptJsonUrlImages(
        rawContent: String,
        rule: String,
        chapter: MediaSourceChapter
    ): String? {
        if (!rule.contains("JSON.parse", ignoreCase = true)) return null
        if (!rule.contains(".url", ignoreCase = true)) return null
        val prefix = constUrlPrefix.find(rule)?.groupValues?.getOrNull(1).orEmpty()
        val imageUrls = extractUrlValues(rawContent)
            .map { resolveRuleImageUrl(it, prefix, chapter.chapterUrl) }
            .filter { isImageUrl(it) }
            .distinct()
        if (imageUrls.isEmpty()) return null
        return imageUrls.joinToString("\n") { url -> """<img src="${escapeAttribute(url)}">""" }
    }

    private fun extractUrlValues(rawContent: String): List<String> {
        val output = LinkedHashSet<String>()
        runCatching { JsonParser.parseString(rawContent) }
            .getOrNull()
            ?.let { collectJsonUrlValues(it, output) }
        if (output.isEmpty()) {
            jsonUrlField.findAll(rawContent).forEach { match ->
                match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(output::add)
            }
        }
        return output.toList()
    }

    private fun collectJsonUrlValues(element: JsonElement, output: MutableSet<String>) {
        when {
            element.isJsonObject -> {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    if (key.equals("url", ignoreCase = true) &&
                        value.isJsonPrimitive &&
                        value.asJsonPrimitive.isString
                    ) {
                        value.asString.takeIf { it.isNotBlank() }?.let(output::add)
                    }
                    collectJsonUrlValues(value, output)
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { collectJsonUrlValues(it, output) }
        }
    }

    private fun resolveRuleImageUrl(rawUrl: String, prefix: String, baseUrl: String): String {
        val value = rawUrl.trim()
        if (value.isBlank()) return ""
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("//") ||
            prefix.isBlank()
        ) {
            return MediaRequestParser.resolveUrl(baseUrl, value)
        }
        return if (value.startsWith("/")) {
            prefix.trimEnd('/') + value
        } else {
            prefix.trimEnd('/') + "/" + value
        }
    }

    private fun headersOption(headers: Map<String, String>): String {
        val root = JsonObject()
        val headerObject = JsonObject()
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) headerObject.addProperty(key, value)
        }
        root.add("headers", headerObject)
        return root.toString()
    }

    private fun isImageUrl(value: String): Boolean {
        val normalized = value.substringBefore(',').trim()
        return normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true) ||
            normalized.startsWith("data:image/", ignoreCase = true) ||
            imageSuffix.containsMatchIn(normalized)
    }

    private fun escapeAttribute(value: String): String {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
    }
}
