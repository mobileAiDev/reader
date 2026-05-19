package com.ldp.reader.sourceengine.legado

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class LegadoRuleEvaluator(
    private val urlBuilder: LegadoUrlBuilder = LegadoUrlBuilder(),
    private val jsonPath: JsonPathLite = JsonPathLite()
) {
    fun parseBody(body: String, baseUrl: String): BodyContext {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            BodyContext(json = JsonParser.parseString(body), document = null, baseUrl = baseUrl)
        } else {
            BodyContext(json = null, document = Jsoup.parse(body, baseUrl), baseUrl = baseUrl)
        }
    }

    fun list(rule: String?, context: BodyContext): List<RuleNode> {
        val cleanRule = normalizeRulePrefix(cleanExecutableSuffix(rule))
        if (cleanRule.isBlank()) return emptyList()
        if (context.json != null && cleanRule.startsWith("$")) {
            return jsonPath.select(context.json, cleanRule)
                .map { RuleNode(json = it, element = null, baseUrl = context.baseUrl) }
        }
        val document = context.document ?: return emptyList()
        return selectElementChain(listOf(document), cleanRule)
            .map { RuleNode(json = null, element = it, baseUrl = context.baseUrl) }
    }

    fun string(rule: String?, node: RuleNode): String {
        var cleanRule = normalizeRulePrefix(cleanExecutableSuffix(rule))
        if (cleanRule.isBlank()) return ""
        cleanRule.split("||").forEach { candidate ->
            val value = evaluateSingle(candidate, node)
            if (value.isNotBlank()) return value
        }
        return evaluateSingle(cleanRule, node)
    }

    fun resolveUrl(baseUrl: String, url: String): String {
        return urlBuilder.resolveUrl(baseUrl, url)
    }

    private fun evaluateSingle(rule: String, node: RuleNode): String {
        val templateRendered = renderTemplate(rule.trim(), node)
        val parts = templateRendered.split("##")
        val baseRule = parts.first()
        val baseValue = when {
            templateRendered != rule.trim() -> baseRule
            baseRule.contains("&&") -> evaluateCombinedRule(baseRule, node)
            normalizeRulePrefix(baseRule).startsWith("$") && node.json != null -> {
                jsonPath.readString(node.json, normalizeRulePrefix(baseRule))
            }
            node.element != null -> evaluateElementRule(baseRule, node.element, node.baseUrl)
            else -> ""
        }
        return applyFilters(baseValue, parts.drop(1))
    }

    private fun evaluateCombinedRule(rule: String, node: RuleNode): String {
        return rule.split("&&")
            .map { evaluateSingle(it, node) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun renderTemplate(rule: String, node: RuleNode): String {
        return TEMPLATE.replace(rule) { match ->
            val inner = match.groupValues[1].trim()
            when {
                inner.startsWith("@@") -> string(inner.removePrefix("@@"), node)
                inner.startsWith("$") && node.json != null -> jsonPath.readString(node.json, inner)
                else -> ""
            }
        }
    }

    private fun evaluateElementRule(rule: String, element: Element, baseUrl: String): String {
        val cleanRule = normalizeRulePrefix(rule.trim())
        if (cleanRule.isBlank()) return ""
        if (cleanRule == "text") return element.text()
        if (cleanRule == "html") return element.html()
        if (cleanRule == "href") return element.absUrl("href").ifBlank { element.attr("href") }

        val tokens = cleanRule.split("@").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return element.text()

        var current = listOf(element)
        tokens.forEachIndexed { index, token ->
            val extractor = ElementExtractor.fromToken(token)
            if (extractor != null) {
                return current.joinToString("\n") { extractor.extract(it, baseUrl) }.trim()
            }
            current = selectElements(current, token)
            if (current.isEmpty()) return ""
            if (index == tokens.lastIndex) {
                return current.joinToString("\n") { it.text() }.trim()
            }
        }
        return current.joinToString("\n") { it.text() }.trim()
    }

    private fun selectElements(parents: List<Element>, rawSelector: String): List<Element> {
        val selector = parseSelector(rawSelector)
        if (selector.css.isBlank()) return parents
        val selected = Elements()
        parents.forEach { parent ->
            if (selector.textQuery != null) {
                selected.addAll(parent.getAllElements().filter { element ->
                    element.text().contains(selector.textQuery)
                })
            } else {
                selected.addAll(parent.select(selector.css))
            }
        }
        var result = selected.toList()
        selector.includeIndex?.let { index ->
            result = result.getBySignedIndex(index)?.let { listOf(it) } ?: emptyList()
        }
        if (selector.excludeIndexes.isNotEmpty()) {
            val excluded = selector.excludeIndexes.mapNotNull { result.getBySignedIndex(it) }.toSet()
            if (excluded.isNotEmpty()) result = result.filter { it !in excluded }
        }
        return result
    }

    private fun selectElementChain(parents: List<Element>, rawRule: String): List<Element> {
        var current = parents
        rawRule.split("@")
            .filter { it.isNotBlank() }
            .forEach { token ->
                if (ElementExtractor.fromToken(token) != null) return current
                current = selectElements(current, token)
                if (current.isEmpty()) return emptyList()
            }
        return current
    }

    private fun parseSelector(raw: String): ParsedSelector {
        var value = raw.trim()
        if (value.startsWith("+@css:")) value = value.removePrefix("+@css:")

        val excludeMatch = Regex("""^(.+)!(-?\d+(?::-?\d+)*)$""").matchEntire(value)
        if (excludeMatch != null) {
            return ParsedSelector(
                css = normalizeCss(excludeMatch.groupValues[1]),
                includeIndex = null,
                excludeIndexes = excludeMatch.groupValues[2].split(":").map { it.toInt() },
                textQuery = textQuery(excludeMatch.groupValues[1])
            )
        }

        val includeMatch = Regex("""^(.+)\.(-?\d+)$""").matchEntire(value)
        if (includeMatch != null && !value.startsWith("[property")) {
            return ParsedSelector(
                css = normalizeCss(includeMatch.groupValues[1]),
                includeIndex = includeMatch.groupValues[2].toInt(),
                excludeIndexes = emptyList(),
                textQuery = textQuery(includeMatch.groupValues[1])
            )
        }

        return ParsedSelector(
            css = normalizeCss(value),
            includeIndex = null,
            excludeIndexes = emptyList(),
            textQuery = textQuery(value)
        )
    }

    private fun normalizeCss(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("class.") -> "." + value.removePrefix("class.")
            value.startsWith("id.") -> "#" + value.removePrefix("id.")
            value.startsWith("tag.") -> value.removePrefix("tag.")
            value.startsWith("text.") -> "*"
            else -> value
        }
    }

    private fun textQuery(raw: String): String? {
        return raw.trim().takeIf { it.startsWith("text.") }?.removePrefix("text.")
    }

    private fun applyFilters(base: String, filters: List<String>): String {
        if (filters.isEmpty()) return base.trim()
        var value = base
        var index = 0
        while (index < filters.size) {
            val pattern = filters[index]
            val replacement = filters.getOrNull(index + 1) ?: ""
            if (pattern.isNotBlank()) {
                value = runCatching {
                    Regex(pattern).replace(value, normalizeReplacement(replacement))
                }.getOrElse { value }
            }
            index += 2
        }
        return value.trim()
    }

    private fun normalizeReplacement(raw: String): String {
        if (raw.startsWith("$") && raw.getOrNull(1)?.isDigit() != true) {
            return raw.drop(1)
        }
        return raw
    }

    private fun cleanExecutableSuffix(rule: String?): String {
        if (rule.isNullOrBlank()) return ""
        return rule
            .lineSequence()
            .takeWhile { !it.trimStart().startsWith("@js:") }
            .joinToString("\n")
            .trim()
    }

    private fun normalizeRulePrefix(rule: String): String {
        val value = rule.trim()
        return when {
            value.startsWith("@JSon:", ignoreCase = true) -> value.substringAfter(":").trim()
            value.startsWith("@JSON:", ignoreCase = true) -> value.substringAfter(":").trim()
            else -> value
        }
    }

    private fun <T> List<T>.getBySignedIndex(index: Int): T? {
        val actual = if (index < 0) size + index else index
        return getOrNull(actual)
    }

    data class BodyContext(
        val json: JsonElement?,
        val document: Document?,
        val baseUrl: String
    )

    data class RuleNode(
        val json: JsonElement?,
        val element: Element?,
        val baseUrl: String
    )

    private data class ParsedSelector(
        val css: String,
        val includeIndex: Int?,
        val excludeIndexes: List<Int>,
        val textQuery: String?
    )

    private enum class ElementExtractor {
        TEXT,
        HTML,
        TEXT_NODES,
        HREF,
        SRC,
        DATA_ORIGINAL,
        CONTENT;

        fun extract(element: Element, baseUrl: String): String {
            return when (this) {
                TEXT -> element.text()
                HTML -> element.html()
                TEXT_NODES -> element.textNodes().joinToString("\n") { it.text() }
                HREF -> element.attr("href")
                SRC -> element.attr("src")
                DATA_ORIGINAL -> element.attr("data-original")
                CONTENT -> element.attr("content")
            }
        }

        companion object {
            fun fromToken(token: String): ElementExtractor? {
                return when (token.trim()) {
                    "text" -> TEXT
                    "html" -> HTML
                    "textNodes" -> TEXT_NODES
                    "href" -> HREF
                    "src" -> SRC
                    "data-original" -> DATA_ORIGINAL
                    "content" -> CONTENT
                    else -> null
                }
            }
        }
    }

    companion object {
        private val TEMPLATE = Regex("""\{\{([\s\S]+?)\}\}""")
    }
}
