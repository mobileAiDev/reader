package com.ldp.reader.media.legado

import com.ldp.reader.media.MediaEngineFailure
import com.ldp.reader.media.MediaEngineResult
import com.ldp.reader.media.legado.MediaHttpFetcher
import com.ldp.reader.media.legado.MediaHttpResponse
import com.ldp.reader.media.legado.MediaLegadoRuleEvaluator
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceType
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceBookDetail
import com.ldp.reader.media.MediaSourceChapter
import com.ldp.reader.media.MediaSourceSearchAttempt
import com.ldp.reader.media.MediaSourceSearchReport
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.regex.Pattern

internal class LegadoMediaRuleRuntime(
    private val fetcher: MediaHttpFetcher,
    private val evaluator: MediaLegadoRuleEvaluator = MediaLegadoRuleEvaluator(),
    private val scriptRuntime: LegadoMediaScriptRuntime = LegadoMediaScriptRuntime(fetcher, evaluator)
) {
    fun search(source: MediaSourceDefinition, keyword: String, page: Int = 1): MediaEngineResult<MediaSourceSearchReport> {
        return runCatching {
            val request = buildSearchRequest(source, keyword, page)
            val response = fetchPage(request)
            val context = evaluator.parseBody(response.body, response.finalUrl)
            context.variables["key"] = keyword
            context.variables["page"] = page.toString()
            val nodes = evaluateList(
                rule = source.ruleSearch.rules["bookList"],
                context = context,
                source = source,
                chapter = null,
                initialResult = response.body
            )
            val books = nodes.mapNotNull { node -> parseSearchBook(source, node) }
            MediaSourceSearchReport(
                books = books,
                attempts = listOf(MediaSourceSearchAttempt(source.sourceName, true, books.size, request.url))
            )
        }.fold(
            onSuccess = { MediaEngineResult.Success(it) },
            onFailure = {
                MediaEngineResult.Success(
                    MediaSourceSearchReport(
                        books = emptyList(),
                        attempts = listOf(
                            MediaSourceSearchAttempt(
                                source.sourceName,
                                false,
                                0,
                                it.message ?: it.javaClass.simpleName
                            )
                        )
                    )
                )
            }
        )
    }

    fun detail(book: MediaSourceBook): MediaEngineResult<MediaSourceBookDetail> {
        return runCatching {
            val request = LegadoMediaUrlRequest.parse(
                rawValue = book.bookUrl,
                baseUrl = book.source.baseUrl(),
                defaultHeaders = book.source.headers
            )
            val response = fetchPage(request)
            val context = evaluator.parseBody(response.body, response.finalUrl)
            context.variables.putIfAbsent("url", book.bookUrl)
            context.variables.putIfAbsent("bookUrl", book.bookUrl)
            val root = rootNode(context)
            val detailNode = resolveDetailNode(book.source.ruleBookInfo.rules["init"], context, root, book.source)
            detailNode.variables.putIfAbsent("url", book.bookUrl)
            detailNode.variables.putIfAbsent("bookUrl", book.bookUrl)
            applyBookInfoPutRules(book.source, detailNode)
            val tocUrl = evaluateString(
                rule = book.source.ruleBookInfo.rules["tocUrl"],
                node = detailNode,
                source = book.source,
                chapter = null,
                initialResult = response.body
            )
                .ifBlank { book.bookUrl }
                .let { LegadoMediaUrlRequest.resolveRuleUrl(response.finalUrl, it) }
            MediaSourceBookDetail(
                book = book,
                name = evaluateString(book.source.ruleBookInfo.rules["name"], detailNode, book.source, null, null)
                    .ifBlank { book.name },
                author = evaluateString(book.source.ruleBookInfo.rules["author"], detailNode, book.source, null, null)
                    .ifBlank { book.author },
                coverUrl = evaluateString(book.source.ruleBookInfo.rules["coverUrl"], detailNode, book.source, null, null)
                    .ifBlank { book.coverUrl }
                    .let { resolvePlainUrl(response.finalUrl, it) },
                intro = evaluateString(book.source.ruleBookInfo.rules["intro"], detailNode, book.source, null, null)
                    .ifBlank { book.intro },
                kind = evaluateString(book.source.ruleBookInfo.rules["kind"], detailNode, book.source, null, null)
                    .ifBlank { book.kind },
                lastChapter = evaluateString(book.source.ruleBookInfo.rules["lastChapter"], detailNode, book.source, null, null)
                    .ifBlank { book.lastChapter },
                tocUrl = tocUrl,
                runtimeVariables = detailNode.variables.toMap()
            )
        }.fold(
            onSuccess = { MediaEngineResult.Success(it) },
            onFailure = { MediaEngineResult.Failure(MediaEngineFailure.NetworkError(it.message ?: "media detail failed.")) }
        )
    }

    fun chapters(detail: MediaSourceBookDetail): MediaEngineResult<List<MediaSourceChapter>> {
        return runCatching {
            val chapters = ArrayList<MediaSourceChapter>()
            val visited = LinkedHashSet<String>()
            val pending = ArrayDeque<String>()
            pending.add(detail.tocUrl)
            while (pending.isNotEmpty() && visited.size < MAX_TOC_PAGES) {
                val tocUrl = pending.removeFirst()
                if (tocUrl.isBlank() || !visited.add(tocUrl)) continue
                val page = loadChapterPage(detail, tocUrl, allowNextToc = true)
                chapters.addAll(page.chapters)
                page.nextUrls
                    .filter { it.isNotBlank() && it !in visited }
                    .forEach { pending.add(it) }
            }
            chapters
                .distinctBy { chapter -> "${chapter.name}|${chapter.chapterUrl}" }
                .mapIndexed { index, chapter -> chapter.copy(index = index) }
        }.fold(
            onSuccess = { MediaEngineResult.Success(it) },
            onFailure = { MediaEngineResult.Failure(MediaEngineFailure.NetworkError(it.message ?: "media chapters failed.")) }
        )
    }

    fun rawContent(chapter: MediaSourceChapter): MediaEngineResult<String> {
        return runCatching {
            loadRawContent(chapter)
        }.fold(
            onSuccess = { MediaEngineResult.Success(it) },
            onFailure = { MediaEngineResult.Failure(MediaEngineFailure.NetworkError(it.message ?: "media content failed.")) }
        )
    }

    private fun buildSearchRequest(source: MediaSourceDefinition, keyword: String, page: Int): LegadoMediaUrlRequest {
        val rawSearchUrl = source.searchUrl.orEmpty()
        val rawRequest = if (rawSearchUrl.hasExecutableRule()) {
            val variables = linkedMapOf(
                "key" to keyword,
                "page" to page.toString()
            )
            val output = scriptRuntime.evaluateSearchRequest(
                script = renderSearchTemplate(executableRuleBody(rawSearchUrl), keyword, page),
                baseUrl = source.baseUrl(),
                source = source,
                keyword = keyword,
                page = page,
                variables = variables
            )
            buildScriptRequestValue(output)
        } else {
            renderSearchTemplate(rawSearchUrl, keyword, page)
        }
        return LegadoMediaUrlRequest.parse(rawRequest, source.baseUrl(), source.headers)
    }

    private fun buildScriptRequestValue(output: LegadoMediaScriptOutput): String {
        val value = output.value.trim()
        val scopedUrl = output.url.trim()
        val primary = value.ifBlank { scopedUrl }
        if (primary.isBlank()) return ""
        val shouldMergeScopedOptions = scopedUrl.isNotBlank() &&
            (value.isBlank() || value == scopedUrl)
        if (!shouldMergeScopedOptions) return primary
        val options = buildRequestOptions(output)
        return if (options.isBlank()) primary else "$primary,$options"
    }

    private fun buildRequestOptions(output: LegadoMediaScriptOutput): String {
        val parts = ArrayList<String>()
        val method = output.method.takeIf { it.isNotBlank() }
            ?: output.body.takeIf { it.isNotBlank() }?.let { "POST" }
        method?.let { parts.add(""""method":"$it"""") }
        output.body.takeIf { it.isNotBlank() }?.let { parts.add(""""body":${it.jsonString()}""") }
        if (output.headers.isNotEmpty()) {
            val headers = output.headers.entries.joinToString(",") { entry ->
                """"${entry.key}":${entry.value.jsonString()}"""
            }
            parts.add(""""headers":{$headers}""")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",", "{", "}") ?: ""
    }

    private fun parseSearchBook(
        source: MediaSourceDefinition,
        node: MediaLegadoRuleEvaluator.RuleNode
    ): MediaSourceBook? {
        val name = evaluateString(source.ruleSearch.rules["name"], node, source, null, null)
        val rawBookUrl = evaluateString(source.ruleSearch.rules["bookUrl"], node, source, null, null)
        if (name.isBlank() || rawBookUrl.isBlank()) return null
        return MediaSourceBook(
            source = source,
            name = name,
            author = evaluateString(source.ruleSearch.rules["author"], node, source, null, null),
            bookUrl = LegadoMediaUrlRequest.resolveRuleUrl(node.baseUrl, rawBookUrl),
            coverUrl = evaluateString(source.ruleSearch.rules["coverUrl"], node, source, null, null)
                .let { resolvePlainUrl(node.baseUrl, it) },
            intro = evaluateString(source.ruleSearch.rules["intro"], node, source, null, null),
            kind = evaluateString(source.ruleSearch.rules["kind"], node, source, null, null),
            lastChapter = evaluateString(source.ruleSearch.rules["lastChapter"], node, source, null, null)
        )
    }

    private fun loadChapterPage(
        detail: MediaSourceBookDetail,
        tocUrl: String,
        allowNextToc: Boolean
    ): ChapterPage {
        val request = LegadoMediaUrlRequest.parse(
            rawValue = tocUrl,
            baseUrl = detail.book.source.baseUrl(),
            defaultHeaders = detail.book.source.headers
        )
        val response = fetchPage(request)
        val context = evaluator.parseBody(response.body, response.finalUrl)
        context.variables.putAll(detail.runtimeVariables)
        context.variables.putIfAbsent("url", detail.book.bookUrl)
        context.variables.putIfAbsent("bookUrl", detail.book.bookUrl)
        context.variables.putIfAbsent("tocUrl", detail.tocUrl)
        val chapterListRule = detail.book.source.ruleToc.rules["chapterList"].orEmpty()
        val reversePage = chapterListRule.trimStart().startsWith("-")
        val nodes = evaluateList(
            rule = chapterListRule.cleanTocListRule(),
            context = context,
            source = detail.book.source,
            chapter = null,
            initialResult = response.body
        ).let { if (reversePage) it.asReversed() else it }
        val chapters = nodes.mapIndexedNotNull { index, node ->
            node.variables.putAll(context.variables)
            val name = evaluateString(
                rule = detail.book.source.ruleToc.rules["chapterName"],
                node = node,
                source = detail.book.source,
                chapter = null,
                initialResult = null
            )
            val rawUrl = evaluateString(
                rule = detail.book.source.ruleToc.rules["chapterUrl"],
                node = node,
                source = detail.book.source,
                chapter = null,
                initialResult = null
            )
            if (name.isBlank() || rawUrl.isBlank()) {
                null
            } else {
                MediaSourceChapter(
                    source = detail.book.source,
                    book = detail.book,
                    index = index,
                    name = name,
                    chapterUrl = LegadoMediaUrlRequest.resolveRuleUrl(response.finalUrl, rawUrl),
                    runtimeVariables = node.variables.toMap()
                )
            }
        }
        val nextUrls = if (!allowNextToc) {
            emptyList()
        } else {
            evaluateStringList(
                rule = detail.book.source.ruleToc.rules["nextTocUrl"],
                node = rootNode(context),
                source = detail.book.source,
                chapter = null,
                initialResult = response.body
            ).map { LegadoMediaUrlRequest.resolveRuleUrl(response.finalUrl, it) }
                .filter { it != response.finalUrl && it != tocUrl }
                .distinct()
        }
        return ChapterPage(chapters, nextUrls)
    }

    private fun resolveDetailNode(
        initRule: String?,
        context: MediaLegadoRuleEvaluator.BodyContext,
        root: MediaLegadoRuleEvaluator.RuleNode,
        source: MediaSourceDefinition
    ): MediaLegadoRuleEvaluator.RuleNode {
        if (initRule.isNullOrBlank()) return root
        val trimmed = initRule.trimStart()
        if (trimmed.startsWith("@put:")) {
            evaluator.string(trimmed, root)
            return root
        }
        if (trimmed.hasExecutableRule()) {
            val output = evaluateString(trimmed, root, source, null, context.document?.outerHtml())
            return nodesFromRawResult(output, context.baseUrl, root.variables).firstOrNull() ?: root
        }
        return evaluator.list(initRule, context).firstOrNull() ?: root
    }

    private fun applyBookInfoPutRules(
        source: MediaSourceDefinition,
        detailNode: MediaLegadoRuleEvaluator.RuleNode
    ) {
        source.ruleBookInfo.rules.values
            .asSequence()
            .map { it.trimStart() }
            .filter { it.startsWith("@put:") }
            .forEach { evaluator.string(it, detailNode) }
    }

    private fun evaluateList(
        rule: String?,
        context: MediaLegadoRuleEvaluator.BodyContext,
        source: MediaSourceDefinition,
        chapter: MediaSourceChapter?,
        initialResult: Any?
    ): List<MediaLegadoRuleEvaluator.RuleNode> {
        if (rule.isNullOrBlank()) return emptyList()
        val steps = splitRule(rule)
        if (steps.none { it.isJs }) return evaluator.list(rule, context)
        var result: Any? = initialResult
        var nodes: List<MediaLegadoRuleEvaluator.RuleNode> = emptyList()
        var lastStepWasStatic = false
        steps.forEachIndexed { index, step ->
            if (step.isJs) {
                result = scriptRuntime.evaluate(
                    script = renderScriptTemplate(step.value, rootNode(context)),
                    result = result,
                    baseUrl = context.baseUrl,
                    source = source,
                    chapter = chapter,
                    variables = context.variables
                )
                lastStepWasStatic = false
            } else {
                val stepContext = if (result == null) {
                    context
                } else {
                    evaluator.parseBody(result.toString(), context.baseUrl).copyVariablesFrom(context)
                }
                nodes = evaluator.list(step.value, stepContext)
                result = if (steps.getOrNull(index + 1)?.isJs == true) {
                    MediaScriptRuleNodeList(nodes)
                } else {
                    nodes.joinToString("\n") { node ->
                        node.json?.toString() ?: node.element?.outerHtml().orEmpty()
                    }
                }
                lastStepWasStatic = true
            }
        }
        if (lastStepWasStatic && nodes.isNotEmpty()) return nodes
        return nodesFromRawResult(result?.toString().orEmpty(), context.baseUrl, context.variables)
    }

    private fun loadRawContent(chapter: MediaSourceChapter): String {
        val content = StringBuilder()
        var nextUrl = chapter.chapterUrl
        val visited = LinkedHashSet<String>()
        while (nextUrl.isNotBlank() && visited.size < MAX_CONTENT_PAGES && visited.add(nextUrl)) {
            val request = LegadoMediaUrlRequest.parse(
                rawValue = nextUrl,
                baseUrl = chapter.chapterUrl,
                defaultHeaders = chapter.source.headers
            )
            val sourceRegex = chapter.source.ruleContent.rules["sourceRegex"]
                ?.takeIf { it.isNotBlank() }
                ?: if (chapter.source.sourceType == MediaSourceType.AUDIO && request.webView) {
                    DEFAULT_AUDIO_SOURCE_REGEX
                } else {
                    null
                }
            val response = fetchPage(
                request = request,
                sourceRegex = sourceRegex,
                javaScript = chapter.source.ruleContent.rules["webJs"]
            )
            val context = evaluator.parseBody(response.body, response.finalUrl)
            context.variables.putAll(chapter.runtimeVariables)
            context.variables.putIfAbsent("url", chapter.book.bookUrl)
            context.variables.putIfAbsent("bookUrl", chapter.book.bookUrl)
            context.variables.putIfAbsent("chapterUrl", chapter.chapterUrl)
            val root = rootNode(context)
            val pageContent = evaluateString(
                rule = chapter.source.ruleContent.rules["content"],
                node = root,
                source = chapter.source,
                chapter = chapter,
                initialResult = response.body
            )
                .let { LegadoMediaHtmlFormatter.formatKeepImg(it, response.finalUrl) }
            val resolvedContent = pageContent.ifBlank {
                response.body.takeIf { chapter.source.sourceType == MediaSourceType.AUDIO }
            }.orEmpty()
            if (resolvedContent.isNotBlank()) {
                if (content.isNotEmpty()) content.append('\n')
                content.append(resolvedContent)
            }
            nextUrl = evaluateStringList(
                rule = chapter.source.ruleContent.rules["nextContentUrl"],
                node = root,
                source = chapter.source,
                chapter = chapter,
                initialResult = response.body
            )
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { LegadoMediaUrlRequest.resolveRuleUrl(response.finalUrl, it) }
                .orEmpty()
        }
        return applyReplaceRegex(content.toString(), chapter.source.ruleContent.rules["replaceRegex"])
    }

    private fun fetchPage(
        request: LegadoMediaUrlRequest,
        sourceRegex: String? = null,
        javaScript: String? = null
    ): MediaHttpResponse {
        request.decodedDataUrl()?.let { body ->
            return MediaHttpResponse(request.url, body, request.headers)
        }
        if (request.webView) {
            LegadoMediaWebViewFetcher.fetch(
                request = request,
                sourceRegex = sourceRegex,
                javaScript = javaScript
            )
                ?.takeIf { it.body.isNotBlank() }
                ?.let { return it }
        }
        return fetcher.fetch(request.toMediaHttpRequest())
    }

    private fun LegadoMediaUrlRequest.decodedDataUrl(): String? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return ""
        val metadata = url.substring(0, comma)
        val payload = url.substring(comma + 1)
        val charsetName = charset
            ?: metadata.substringAfter("charset=", "")
                .substringBefore(";")
                .takeIf { it.isNotBlank() }
            ?: "UTF-8"
        return runCatching {
            val bytes = if (metadata.contains(";base64", ignoreCase = true)) {
                Base64.getDecoder().decode(payload)
            } else {
                URLDecoder.decode(payload, charsetName).toByteArray(Charset.forName(charsetName))
            }
            String(bytes, Charset.forName(charsetName))
        }.getOrDefault("")
    }

    private fun evaluateStringList(
        rule: String?,
        node: MediaLegadoRuleEvaluator.RuleNode,
        source: MediaSourceDefinition,
        chapter: MediaSourceChapter?,
        initialResult: Any?
    ): List<String> {
        if (rule.isNullOrBlank()) return emptyList()
        return evaluateString(rule, node, source, chapter, initialResult)
            .toRuleStringList()
            .distinct()
    }

    private fun evaluateString(
        rule: String?,
        node: MediaLegadoRuleEvaluator.RuleNode,
        source: MediaSourceDefinition,
        chapter: MediaSourceChapter?,
        initialResult: Any?
    ): String {
        if (rule.isNullOrBlank()) return ""
        val steps = splitRule(rule)
        if (steps.none { it.isJs }) {
            return evaluateStaticString(rule, node)
        }
        var result: Any? = initialResult
        steps.forEach { step ->
            result = if (step.isJs) {
                scriptRuntime.evaluate(
                    script = renderScriptTemplate(step.value, node),
                    result = result,
                    baseUrl = node.baseUrl,
                    source = source,
                    chapter = chapter,
                    variables = node.variables
                )
            } else {
                val stepNode = if (result == null) {
                    node
                } else {
                    rootNode(evaluator.parseBody(result.toString(), node.baseUrl).copyVariablesFrom(node))
                }
                evaluateStaticString(step.value, stepNode)
            }
        }
        return result?.toString().orEmpty()
    }

    private fun renderScriptTemplate(script: String, node: MediaLegadoRuleEvaluator.RuleNode): String {
        return TEMPLATE_PATTERN.replace(script) { match ->
            evaluateStaticString(match.value, node)
        }
    }

    private fun splitRule(rule: String): List<RuleStep> {
        val steps = ArrayList<RuleStep>()
        val matcher = JS_PATTERN.matcher(rule)
        var start = 0
        while (matcher.find()) {
            if (matcher.start() > start) {
                rule.substring(start, matcher.start()).trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { steps.add(RuleStep(it, false)) }
            }
            steps.add(RuleStep(matcher.group(2) ?: matcher.group(1).orEmpty(), true))
            start = matcher.end()
        }
        if (rule.length > start) {
            rule.substring(start).trim()
                .takeIf { it.isNotBlank() }
                ?.let { steps.add(RuleStep(it, false)) }
        }
        return steps
    }

    private fun String.cleanTocListRule(): String {
        val trimmed = trimStart()
        if (trimmed.startsWith("-") || trimmed.startsWith("+")) {
            return trimmed.substring(1).trimStart()
        }
        return this
    }

    private fun String.toRuleStringList(): List<String> {
        val value = trim()
        if (value.isBlank()) return emptyList()
        val parsed = runCatching { com.google.gson.JsonParser.parseString(value) }.getOrNull()
        if (parsed != null && parsed.isJsonArray) {
            return parsed.asJsonArray
                .mapNotNull { element ->
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                }
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        return value.lineSequence()
            .flatMap { line -> line.split("\n").asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun nodesFromRawResult(
        raw: String,
        baseUrl: String,
        variables: Map<String, String>
    ): List<MediaLegadoRuleEvaluator.RuleNode> {
        if (raw.isBlank()) return emptyList()
        val parsed = evaluator.parseBody(raw, baseUrl)
        val parsedJson = parsed.json
        val parsedDocument = parsed.document
        return when {
            parsedJson?.isJsonArray == true -> parsedJson.asJsonArray.map {
                MediaLegadoRuleEvaluator.RuleNode(it, null, baseUrl, LinkedHashMap(variables))
            }
            parsedJson != null -> listOf(MediaLegadoRuleEvaluator.RuleNode(parsedJson, null, baseUrl, LinkedHashMap(variables)))
            parsedDocument != null -> {
                val children = parsedDocument.body().children()
                if (children.isNotEmpty()) {
                    children.map { MediaLegadoRuleEvaluator.RuleNode(null, it, baseUrl, LinkedHashMap(variables)) }
                } else {
                    listOf(rootNode(parsed.copyVariablesFromValues(variables)))
                }
            }
            else -> emptyList()
        }
    }

    private fun evaluateStaticString(rule: String, node: MediaLegadoRuleEvaluator.RuleNode): String {
        val trimmed = rule.trim()
        val xpathRule = when {
            trimmed.startsWith("@XPath:", ignoreCase = true) -> trimmed.substringAfter(":")
            trimmed.startsWith("//") -> trimmed
            else -> null
        }
        return if (xpathRule != null) evaluateXPathLite(xpathRule, node) else evaluator.string(rule, node)
    }

    private fun evaluateXPathLite(rule: String, node: MediaLegadoRuleEvaluator.RuleNode): String {
        val root = node.element ?: return ""
        val path = rule.trim()
        if (!path.startsWith("//")) return ""
        val attr = path.substringAfterLast("/@", missingDelimiterValue = "")
        val selectorPath = if (attr.isBlank()) path else path.substringBeforeLast("/@")
        val segments = selectorPath.removePrefix("//").split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return ""
        var current = listOf(root)
        segments.forEachIndexed { index, segment ->
            val selector = xpathSegmentToCss(segment)
            if (selector.isBlank()) return ""
            current = current.flatMap { element ->
                if (index == 0) {
                    element.select(selector)
                } else {
                    element.children().select(selector)
                }
            }
        }
        return current.joinToString("\n") { element ->
            if (attr.isBlank()) element.text() else element.attr(attr)
        }.trim()
    }

    private fun List<Element>.select(selector: String): List<Element> {
        return flatMap { it.select(selector) }
    }

    private fun xpathSegmentToCss(segment: String): String {
        val tag = segment.substringBefore("[")
        if (tag.isBlank()) return ""
        val attrFilter = XPATH_ATTR.find(segment)?.let { match ->
            """[${match.groupValues[1]}="${match.groupValues[2]}"]"""
        }.orEmpty()
        val indexFilter = XPATH_INDEX.findAll(segment)
            .mapNotNull { it.groupValues[1].toIntOrNull()?.minus(1) }
            .lastOrNull()
            ?.let { ":eq($it)" }
            .orEmpty()
        return "$tag$attrFilter$indexFilter"
    }

    private fun rootNode(context: MediaLegadoRuleEvaluator.BodyContext): MediaLegadoRuleEvaluator.RuleNode {
        return MediaLegadoRuleEvaluator.RuleNode(
            json = context.json,
            element = context.document,
            baseUrl = context.baseUrl,
            variables = context.variables
        )
    }

    private fun MediaLegadoRuleEvaluator.BodyContext.copyVariablesFrom(
        source: MediaLegadoRuleEvaluator.BodyContext
    ): MediaLegadoRuleEvaluator.BodyContext {
        variables.putAll(source.variables)
        return this
    }

    private fun MediaLegadoRuleEvaluator.BodyContext.copyVariablesFrom(
        source: MediaLegadoRuleEvaluator.RuleNode
    ): MediaLegadoRuleEvaluator.BodyContext {
        variables.putAll(source.variables)
        return this
    }

    private fun MediaLegadoRuleEvaluator.BodyContext.copyVariablesFromValues(
        source: Map<String, String>
    ): MediaLegadoRuleEvaluator.BodyContext {
        variables.putAll(source)
        return this
    }

    private fun applyReplaceRegex(content: String, replaceRegex: String?): String {
        val rule = replaceRegex ?: return content.trim()
        val parts = rule.split("##")
        if (parts.size < 2) return content.trim()
        var value = content
        var index = 1
        while (index < parts.size) {
            val pattern = parts[index]
            val replacement = parts.getOrNull(index + 1) ?: ""
            if (pattern.isNotBlank()) {
                value = runCatching { Regex(pattern).replace(value, replacement) }.getOrElse { value }
            }
            index += 2
        }
        return value.trim()
    }

    private fun MediaSourceDefinition.baseUrl(): String {
        return sourceUrl.substringBefore("##").substringBefore("#").let { base ->
            if (base.endsWith("/")) base else "$base/"
        }
    }

    private fun String.hasExecutableRule(): Boolean {
        return contains("<js>", ignoreCase = true) || contains("@js:", ignoreCase = true)
    }

    private fun executableRuleBody(rule: String): String {
        val trimmed = rule.trim()
        return when {
            trimmed.startsWith("<js>", ignoreCase = true) -> trimmed
                .substringAfter(">", "")
                .substringBeforeLast("</js>", "")
            trimmed.startsWith("@js:", ignoreCase = true) -> trimmed.substringAfter(":", "")
            else -> trimmed
        }
    }

    private fun renderSearchTemplate(raw: String, keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return raw
            .replace("{{key}}", encoded)
            .replace("{{Key}}", encoded)
            .replace("{{page}}", page.toString())
            .replace("{{Page}}", page.toString())
            .replace("{{encodeURIComponent(key)}}", encoded)
            .replace("{{encodeURI(key)}}", encoded)
            .replace("{{java.encodeURIComponent(key)}}", encoded)
            .replace("{{java.encodeURI(key)}}", encoded)
    }

    private fun resolvePlainUrl(baseUrl: String, rawValue: String): String {
        if (rawValue.isBlank()) return ""
        return LegadoMediaUrlRequest.resolveRuleUrl(baseUrl, rawValue).substringBefore(",{")
    }

    private fun String.jsonString(): String {
        return buildString {
            append('"')
            this@jsonString.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    private data class RuleStep(
        val value: String,
        val isJs: Boolean
    )

    private data class ChapterPage(
        val chapters: List<MediaSourceChapter>,
        val nextUrls: List<String>
    )

    private companion object {
        private const val MAX_TOC_PAGES = 80
        private const val MAX_CONTENT_PAGES = 10
        private const val DEFAULT_AUDIO_SOURCE_REGEX = ".*\\.(mp3|m4a|aac|ogg|wav|flac|m3u8|mp4).*"
        private val JS_PATTERN: Pattern = Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE)
        private val TEMPLATE_PATTERN = Regex("""\{\{[\s\S]+?\}\}""")
        private val XPATH_ATTR = Regex("""\[@([^=\]]+)=["']([^"']+)["']]""")
        private val XPATH_INDEX = Regex("""\[(\d+)]""")
    }
}
