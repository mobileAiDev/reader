package com.ldp.reader.media

import org.jsoup.Jsoup

object ComicPageExtractor {
    private val imageSuffix = safeRegex("""(?i)\.(jpg|jpeg|png|webp|gif)(\?.*)?$""")
    private val httpImageUrl = safeRegex("""(?i)https?://[^\s"'<>\\]+?\.(?:jpg|jpeg|png|webp|gif)(?:\?[^"'<>\s\\]*)?(?:,\{[^<>\s]*})?""")
    private val imageTag = safeRegex("""(?is)<img\b[^>]*>""")
    private val placeholderNames = listOf(
        "load.gif",
        "loading.gif",
        "spinner.gif",
        "placeholder.gif",
        "blank.gif",
        "transparent.gif",
        "spacer.gif"
    )

    fun extract(rawContent: String): List<String> {
        return extractRequests(rawContent).map { it.url }
    }

    fun extractRequests(
        rawContent: String,
        baseUrl: String = "",
        defaultHeaders: Map<String, String> = emptyMap()
    ): List<MediaRequest> {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) return emptyList()
        val legadoImages = extractLegadoImageRequests(trimmed, baseUrl, defaultHeaders)
        if (legadoImages.isNotEmpty()) return legadoImages
        val htmlImages = Jsoup.parseBodyFragment(trimmed, baseUrl)
            .select("img")
            .mapNotNull { element ->
                listOf(
                    "data-src",
                    "data-original",
                    "data-page-image-url",
                    "data-echo",
                    "src"
                ).firstNotNullOfOrNull { attr ->
                    element.attr("abs:$attr").ifBlank { element.attr(attr) }
                        .trim()
                        .takeIf { it.isNotBlank() }
                }
            }
            .mapNotNull { MediaRequestParser.parse(it, baseUrl, defaultHeaders) }
        val usableHtmlImages = htmlImages.filterNot { isPlaceholderImage(it.url) }
        if (usableHtmlImages.isNotEmpty()) return usableHtmlImages.distinct()

        val embeddedUrls = (httpImageUrl?.findAll(trimmed) ?: emptySequence())
            .map { it.value.trim() }
            .mapNotNull { MediaRequestParser.parse(it, baseUrl, defaultHeaders) }
            .filterNot { isPlaceholderImage(it.url) }
            .distinctBy { it.url }
            .toList()
        if (embeddedUrls.isNotEmpty()) return embeddedUrls

        return trimmed.lineSequence()
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                    (it.startsWith("http") || it.startsWith("//") || imageSuffix?.containsMatchIn(it) == true)
            }
            .mapNotNull { MediaRequestParser.parse(it, baseUrl, defaultHeaders) }
            .filterNot { isPlaceholderImage(it.url) }
            .distinctBy { it.url }
            .toList()
    }

    private fun extractLegadoImageRequests(
        content: String,
        baseUrl: String,
        defaultHeaders: Map<String, String>
    ): List<MediaRequest> {
        if (!content.contains(",{") && !content.contains("headers", ignoreCase = true)) return emptyList()
        return (imageTag?.findAll(content) ?: emptySequence())
            .mapNotNull { match -> extractImageAttribute(match.value) }
            .mapNotNull { value -> MediaRequestParser.parse(value, baseUrl, defaultHeaders) }
            .filterNot { isPlaceholderImage(it.url) }
            .distinctBy { it.url }
            .toList()
    }

    private fun extractImageAttribute(tag: String): String? {
        IMAGE_ATTR_NAMES.forEach { name ->
            val value = extractAttributeValue(tag, name)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun extractAttributeValue(tag: String, name: String): String? {
        val match = Regex("""(?i)\b${Regex.escape(name)}\s*=\s*(['"])""").find(tag) ?: return null
        val quote = match.groupValues[1].singleOrNull() ?: return null
        val valueStart = match.range.last + 1
        val optionStart = tag.indexOf(",{", valueStart)
        if (optionStart >= 0) {
            balancedJsonEnd(tag, optionStart + 1)?.let { jsonEnd ->
                return tag.substring(valueStart, jsonEnd + 1).trim()
            }
        }
        val valueEnd = tag.indexOf(quote, valueStart).takeIf { it >= 0 } ?: return null
        return tag.substring(valueStart, valueEnd).trim()
    }

    private fun balancedJsonEnd(value: String, start: Int): Int? {
        if (start !in value.indices || value[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val char = value[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun isPlaceholderImage(url: String): Boolean {
        val normalized = url.trim().substringBefore('?').lowercase()
        if (normalized.isBlank()) return true
        if (normalized == "about:blank" || normalized.startsWith("data:image/")) return true
        if (placeholderNames.any { normalized.endsWith(it) }) return true
        return normalized.contains("/load/") ||
            normalized.contains("/loading/") ||
            normalized.contains("/placeholder/") ||
            normalized.contains("/blank/") ||
            normalized.contains("/spacer/") ||
            normalized.contains("reader-pic-pending")
    }

    private fun safeRegex(pattern: String): Regex? {
        return runCatching { Regex(pattern) }.getOrNull()
    }

    private val IMAGE_ATTR_NAMES = listOf("src", "data-src", "data-original")
}
