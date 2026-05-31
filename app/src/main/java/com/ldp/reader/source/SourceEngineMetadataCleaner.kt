package com.ldp.reader.source

import java.util.Locale

object SourceEngineMetadataCleaner {
    private val brTagRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val paragraphTagRegex = Regex("""</?p[^>]*>""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")
    private val nbspEntityRegex = Regex("""(?i)&(?:amp;)?nbsp;?""")
    private val bareNbspRegex = Regex("""(?i)\bnbsp;?\b""")
    private val brokenLgtRegex = Regex("""(?i)&lgt;?""")
    private val entityRegex = Regex("""&(#x[0-9a-fA-F]+|#\d+|[A-Za-z][A-Za-z0-9]+);?""")

    fun cleanIntro(value: String?): String {
        val cleaned = decodeEntities(value.orEmpty())
            .replace(brTagRegex, " ")
            .replace(paragraphTagRegex, " ")
            .replace(tagRegex, " ")
            .replace(Regex("""各位书友.*$"""), "")
            .normalizeMetadataSpaces()
        if (isInvalidIntroFragment(cleaned)) return ""
        return cleaned
    }

    fun cleanText(value: String?): String {
        return decodeEntities(value.orEmpty())
            .replace(brTagRegex, " ")
            .replace(paragraphTagRegex, " ")
            .replace(tagRegex, " ")
            .normalizeMetadataSpaces()
    }

    fun cleanContent(value: String?): String {
        return decodeEntities(value.orEmpty())
            .replace(brTagRegex, "\n")
            .replace(Regex("""</p\s*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<p[^>]*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(tagRegex, "")
            .replace(Regex("""[ \t\x0B\f\r]+"""), " ")
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .joinToString("\n")
    }

    private fun decodeEntities(value: String): String {
        var result = normalizeBrokenEntities(value)
        repeat(2) {
            result = normalizeBrokenEntities(
                entityRegex.replace(result) { match ->
                    decodeEntity(match.groupValues[1]) ?: match.value
                }
            )
        }
        return result
    }

    private fun normalizeBrokenEntities(value: String): String {
        return value
            .replace('\u00A0', ' ')
            .replace(nbspEntityRegex, " ")
            .replace(brokenLgtRegex, " ")
            .replace(bareNbspRegex, " ")
    }

    private fun decodeEntity(entity: String): String? {
        val normalized = entity.lowercase(Locale.ROOT)
        return when {
            normalized == "nbsp" -> " "
            normalized == "amp" -> "&"
            normalized == "lt" -> "<"
            normalized == "gt" -> ">"
            normalized == "quot" -> "\""
            normalized == "apos" -> "'"
            normalized.startsWith("#x") -> decodeCodePoint(normalized.drop(2), 16)
            normalized.startsWith("#") -> decodeCodePoint(normalized.drop(1), 10)
            else -> null
        }
    }

    private fun decodeCodePoint(value: String, radix: Int): String? {
        val codePoint = value.toIntOrNull(radix) ?: return null
        return runCatching { String(Character.toChars(codePoint)) }.getOrNull()
    }

    private fun String.normalizeMetadataSpaces(): String {
        return replace(whitespaceRegex, " ").trim()
    }

    private fun isInvalidIntroFragment(value: String): Boolean {
        if (value.isBlank()) return false
        val normalized = value.lowercase(Locale.ROOT)
        return normalized.contains("meta property") ||
            normalized.contains("og:image") ||
            normalized.contains("content=\"http") ||
            normalized.contains("content='http") ||
            Regex("""^["']?\s*/?\s*>""").containsMatchIn(value)
    }
}
