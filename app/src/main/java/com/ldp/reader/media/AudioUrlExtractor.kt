package com.ldp.reader.media

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.jsoup.Jsoup

object AudioUrlExtractor {
    private val audioSuffix = Regex("""(?i)\.(mp3|m4a|aac|ogg|wav|flac|m3u8|mp4)([?#].*)?$""")
    private val unsupportedAudioSuffix = Regex("""(?i)\.(wma)([?#].*)?$""")
    private val preferredJsonKeys = setOf(
        "url",
        "src",
        "playurl",
        "play_url",
        "audiourl",
        "audio_url",
        "mediaurl",
        "media_url",
        "mp3",
        "m4a"
    )

    fun extract(rawContent: String): String? {
        return extractRequest(rawContent)?.url
    }

    fun extractRequest(
        rawContent: String,
        baseUrl: String = "",
        defaultHeaders: Map<String, String> = emptyMap()
    ): MediaRequest? {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) return null
        if (looksLikeAccessError(trimmed)) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            MediaRequestParser.parse(trimmed, baseUrl, defaultHeaders)
                ?.takeIf { isPotentialPlayableUrl(it.url) }
                ?.let { return it }
        }
        extractFromJson(trimmed, baseUrl, defaultHeaders)?.let { return it }
        val audioTagUrl = Jsoup.parseBodyFragment(trimmed, baseUrl)
            .select("audio,source")
            .firstNotNullOfOrNull { element ->
                element.attr("abs:src")
                    .ifBlank { element.attr("src") }
                    .trim()
                    .let { MediaRequestParser.parse(it, baseUrl, defaultHeaders) }
                    ?.takeIf { isPotentialPlayableUrl(it.url) }
            }
        if (audioTagUrl != null) return audioTagUrl

        return extractHttpCandidates(trimmed)
            .mapNotNull { MediaRequestParser.parse(it, baseUrl, defaultHeaders) }
            .firstOrNull { isPotentialPlayableUrl(it.url) && audioSuffix.containsMatchIn(it.url) }
    }

    private fun extractHttpCandidates(content: String): Sequence<String> = sequence {
        var index = 0
        while (index < content.length) {
            val start = content.indexOfHttpUrl(index)
            if (start < 0) break
            val mainEnd = content.findHttpUrlEnd(start)
            val mainUrl = content.substring(start, mainEnd).trimPlaybackUrlTail()
            if (mainUrl.isNotBlank()) {
                val optionEnd = content.findLegadoOptionEnd(mainEnd)
                yield(
                    if (optionEnd > mainEnd) {
                        mainUrl + content.substring(mainEnd, optionEnd)
                    } else {
                        mainUrl
                    }
                )
                index = maxOf(optionEnd, mainEnd + 1)
                continue
            }
            index = maxOf(mainEnd + 1, start + 1)
        }
    }

    private fun String.indexOfHttpUrl(fromIndex: Int): Int {
        val http = indexOf("http://", fromIndex, ignoreCase = true)
        val https = indexOf("https://", fromIndex, ignoreCase = true)
        return when {
            http < 0 -> https
            https < 0 -> http
            else -> minOf(http, https)
        }
    }

    private fun String.findHttpUrlEnd(start: Int): Int {
        var index = start
        while (index < length) {
            val char = this[index]
            if (char.isWhitespace() || char == '"' || char == '\'' || char == '<' || char == '>' || char == '\\') {
                break
            }
            if (char == ',' && substring(index + 1).trimStart().startsWith("{")) {
                break
            }
            index++
        }
        return index
    }

    private fun String.findLegadoOptionEnd(urlEnd: Int): Int {
        if (urlEnd >= length || this[urlEnd] != ',') return urlEnd
        var index = urlEnd + 1
        while (index < length && this[index].isWhitespace()) index++
        if (index >= length || this[index] != '{') return urlEnd
        var depth = 0
        var inString = false
        var escaped = false
        while (index < length) {
            val char = this[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        return urlEnd
    }

    private fun String.trimPlaybackUrlTail(): String {
        return trimEnd { char ->
            char == ',' || char == '.' || char == ';' || char == ')' ||
                char == ']' || char == '}' || char == '。' || char == '，'
        }
    }

    private fun extractFromJson(
        content: String,
        baseUrl: String,
        defaultHeaders: Map<String, String>
    ): MediaRequest? {
        val root = runCatching { JsonParser.parseString(content) }.getOrNull() ?: return null
        val candidates = ArrayList<String>()
        collectJsonUrls(root, candidates)
        return candidates.asSequence()
            .mapNotNull { MediaRequestParser.parse(it, baseUrl, defaultHeaders) }
            .firstOrNull { audioSuffix.containsMatchIn(it.url) }
            ?: candidates.asSequence()
                .mapNotNull { MediaRequestParser.parse(it, baseUrl, defaultHeaders) }
                .firstOrNull { isPotentialPlayableUrl(it.url) }
    }

    private fun collectJsonUrls(element: JsonElement, output: MutableList<String>) {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                obj.entrySet().forEach { (key, value) ->
                    if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        val normalized = value.asString.trim()
                        if (preferredJsonKeys.contains(key.lowercase()) && isPotentialPlayableUrl(normalized)) {
                            output.add(normalized)
                        }
                    }
                    collectJsonUrls(value, output)
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { collectJsonUrls(it, output) }
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                val value = element.asString.trim()
                if (isHttpUrl(value) && audioSuffix.containsMatchIn(value)) {
                    output.add(value)
                }
            }
        }
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("http://") || value.startsWith("https://")
    }

    private fun isPotentialPlayableUrl(value: String): Boolean {
        if (!isHttpUrl(value)) return false
        if (unsupportedAudioSuffix.containsMatchIn(value)) return false
        if (value.any { isCjkCharacter(it) }) return false
        val lower = value.lowercase()
        if (lower.contains("music.163.com/song/media/outer/url?id=1817544979")) return false
        return !lower.contains("convert_url_with_sign") && !lower.contains("stype=albuminfo")
    }

    private fun looksLikeAccessError(value: String): Boolean {
        val lower = value.lowercase()
        return listOf(
            "访问次数",
            "登录后",
            "请登录",
            "免登录",
            "not available",
            "copyright protection",
            "forbidden"
        ).any { marker -> lower.contains(marker.lowercase()) }
    }

    private fun isCjkCharacter(char: Char): Boolean {
        val block = Character.UnicodeBlock.of(char)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }
}
