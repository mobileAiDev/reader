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
        val rules = buildString {
            appendLine(source.searchUrl)
            source.ruleSearch.rules.values.forEach { appendLine(it) }
            source.ruleBookInfo.rules.values.forEach { appendLine(it) }
            source.ruleToc.rules.values.forEach { appendLine(it) }
            source.ruleContent.rules.values.forEach { appendLine(it) }
        }
        return UNSUPPORTED_RULE_MARKERS.none { rules.contains(it, ignoreCase = true) }
    }

    private val GENERAL_SOURCE_BLOCKLIST = listOf(
        "PO18",
        "po18",
        "海棠",
        "耽美",
        "BL",
        "成人"
    )

    private val UNSUPPORTED_RULE_MARKERS = listOf(
        "<js>",
        "@js:",
        "java.ajax",
        "java.put",
        "eval(",
        "source.",
        "cookie.",
        "{{eval",
        "{{java.put"
    )
}
