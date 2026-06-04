package com.ldp.reader.media

import org.jsoup.Jsoup

object ComicPageExtractor {
    private val imageSuffix = safeRegex("""(?i)\.(jpg|jpeg|png|webp|gif)(\?.*)?$""")
    private val httpImageUrl = safeRegex("""(?i)https?://[^\s"'<>\\]+?\.(?:jpg|jpeg|png|webp|gif)(?:\?[^"'<>\s\\]*)?(?:,\{[^<>\s]*})?""")
    private val legadoImageAttr = safeRegex("""(?is)<img[^>]+(?:src|data-src|data-original)=["'](https?://[\s\S]*?)["']\s*/?>""")
    private val placeholderNames = listOf(
        "load.gif",
        "loading.gif",
        "spinner.gif",
        "placeholder.gif",
        "blank.gif"
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
        return (legadoImageAttr?.findAll(content) ?: emptySequence())
            .mapNotNull { match ->
                MediaRequestParser.parse(match.groupValues[1], baseUrl, defaultHeaders)
            }
            .filterNot { isPlaceholderImage(it.url) }
            .distinctBy { it.url }
            .toList()
    }

    private fun isPlaceholderImage(url: String): Boolean {
        val normalized = url.substringBefore('?').lowercase()
        if (placeholderNames.any { normalized.endsWith(it) }) return true
        return normalized.contains("/load/") ||
            normalized.contains("/loading/") ||
            normalized.contains("reader-pic-pending")
    }

    private fun safeRegex(pattern: String): Regex? {
        return runCatching { Regex(pattern) }.getOrNull()
    }
}
