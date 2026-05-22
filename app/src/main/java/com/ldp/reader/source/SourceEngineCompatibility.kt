package com.ldp.reader.source

import com.ldp.reader.sourceengine.model.BookSource

object SourceEngineCompatibility {
    fun isCompatible(source: BookSource): Boolean {
        val label = "${source.sourceName}\n${source.sourceUrl}"
        if (GENERAL_SOURCE_BLOCKLIST.any { label.contains(it, ignoreCase = true) }) {
            return false
        }
        if (source.searchUrl.isNullOrBlank()) return false
        if (!source.ruleSearch.rules.keys.containsAll(listOf("bookList", "name", "bookUrl"))) {
            return false
        }
        if (!source.ruleToc.rules.keys.containsAll(listOf("chapterList", "chapterName", "chapterUrl"))) {
            return false
        }
        if (!source.ruleContent.rules.containsKey("content")) {
            return false
        }
        val criticalRules = listOfNotNull(
            source.searchUrl,
            source.ruleSearch.rules["bookList"],
            source.ruleSearch.rules["name"],
            source.ruleSearch.rules["bookUrl"],
            source.ruleBookInfo.rules["tocUrl"],
            source.ruleToc.rules["chapterList"],
            source.ruleToc.rules["chapterName"],
            source.ruleToc.rules["chapterUrl"],
            source.ruleContent.rules["content"]
        )
        return criticalRules.none { hasUnsupportedCriticalRule(it) }
    }

    private fun hasUnsupportedCriticalRule(rule: String): Boolean {
        if (rule.contains("<js>", ignoreCase = true)) {
            return true
        }
        val ruleBeforeExecutableSuffix = rule.substringBefore("@js:").trim()
        if (rule.contains("@js:", ignoreCase = true) && ruleBeforeExecutableSuffix.isBlank()) {
            return true
        }
        val supportedSubset = ruleBeforeExecutableSuffix
            .replace(Regex("""java\.put\("[^"]+"\s*,\s*baseUrl\)"""), "")
        return UNSUPPORTED_RULE_MARKERS.none { marker ->
            supportedSubset.contains(marker, ignoreCase = true)
        }.not()
    }

    private val GENERAL_SOURCE_BLOCKLIST = listOf(
        "同人"
    )

    private val UNSUPPORTED_RULE_MARKERS = listOf(
        "java.ajax",
        "java.put",
        "eval(",
        "source.",
        "cookie.",
        "{{eval",
        "{{java.put"
    )
}
