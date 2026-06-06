package com.ldp.reader.media

object MediaDisplayTextCleaner {
    private val brTagRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val paragraphTagRegex = Regex("""</?p[^>]*>""", RegexOption.IGNORE_CASE)
    private val jsBlockRegex = Regex("""(?is)<js>[\s\S]*?</js>""")
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")
    private val nbspEntityRegex = Regex("""(?i)&(?:amp;)?nbsp;?""")
    private val brokenLgtRegex = Regex("""(?i)&lgt;?""")
    private val unicodeEscapeRegex = Regex("""\\+u([0-9a-fA-F]{4})""")
    private val escapedAmpersandRegex = Regex("""\\+&""")
    private val escapedSemicolonSeparatorRegex = Regex("""\\+\s*;""")
    private val escapedQuoteRegex = Regex("""\\+(["'])""")
    private val numericEntityRegex = Regex("""(?i)&#(x[0-9a-f]+|\d+);?""")

    fun clean(value: String?): String {
        val original = decodeVisibleEscapes(value.orEmpty()).trim()
        if (original.isBlank() || looksLikeRuleScript(original)) return ""
        val withoutScripts = jsBlockRegex.replace(original, " ")
        if (withoutScripts.isBlank() || looksLikeRuleScript(withoutScripts)) return ""
        val cleaned = withoutScripts
            .replace('\u00A0', ' ')
            .replace(nbspEntityRegex, " ")
            .replace(brokenLgtRegex, " ")
            .replace(brTagRegex, " ")
            .replace(paragraphTagRegex, " ")
            .replace(tagRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()
        return cleaned.takeUnless { looksLikeRuleScript(it) }.orEmpty()
    }

    private fun decodeVisibleEscapes(value: String): String {
        val unicodeDecoded = unicodeEscapeRegex.replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        val slashDecoded = unicodeDecoded
            .replace(escapedAmpersandRegex, "&")
            .replace(escapedSemicolonSeparatorRegex, "；")
            .replace(escapedQuoteRegex) { it.groupValues[1] }
        val namedDecoded = slashDecoded
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
        return numericEntityRegex.replace(namedDecoded) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                token.drop(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            codePoint?.takeIf { it in 0..Char.MAX_VALUE.code }?.toChar()?.toString() ?: match.value
        }
    }

    private fun looksLikeRuleScript(value: String): Boolean {
        val normalized = value.trimStart()
        if (normalized.startsWith("<js>", ignoreCase = true)) return true
        if (normalized.startsWith("@js:", ignoreCase = true)) return true
        if (normalized.startsWith("function", ignoreCase = true)) return true
        if (normalized.startsWith("(function", ignoreCase = true)) return true
        if (normalized.startsWith("var ", ignoreCase = true)) return true
        if (normalized.startsWith("let ", ignoreCase = true)) return true
        if (normalized.startsWith("const ", ignoreCase = true)) return true
        return SCRIPT_RULE_MARKERS.any { marker ->
            normalized.contains(marker, ignoreCase = true)
        }
    }

    private val SCRIPT_RULE_MARKERS = listOf(
        "java.ajax",
        "source.",
        "cookie.",
        "{{",
        "@js:",
        "</js>"
    )
}
