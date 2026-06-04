package com.ldp.reader.sourceengine.legado

import com.ldp.reader.sourceengine.EngineFailure
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.catalog.ChapterListFusion
import com.ldp.reader.sourceengine.content.BookContentFingerprint
import com.ldp.reader.sourceengine.content.ContentCleaner
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.CanonicalChapterList
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceBookDetail
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.sourceengine.model.SourceSearchAttempt
import com.ldp.reader.sourceengine.model.SourceSearchReport

class LegadoSourceEngine(
    private val fetcher: HttpFetcher = JdkHttpFetcher(),
    private val urlBuilder: LegadoUrlBuilder = LegadoUrlBuilder(),
    private val evaluator: LegadoRuleEvaluator = LegadoRuleEvaluator(urlBuilder),
    private val chapterListFusion: ChapterListFusion = ChapterListFusion(),
    private val contentCleaner: ContentCleaner = ContentCleaner()
) {
    fun search(
        sources: List<BookSource>,
        keyword: String,
        maxSources: Int = 12,
        page: Int = 1
    ): EngineResult<SourceSearchReport> {
        if (keyword.isBlank()) {
            return EngineResult.Failure(EngineFailure.ContractViolation("keyword is required."))
        }
        val books = ArrayList<SourceBook>()
        val attempts = ArrayList<SourceSearchAttempt>()
        sources.asSequence()
            .filter { it.enabled }
            .filter { !it.searchUrl.isNullOrBlank() }
            .filter { it.ruleSearch.rules.containsKey("bookList") }
            .take(maxSources)
            .forEach { source ->
                runCatching {
                    val request = urlBuilder.buildRequest(source, source.searchUrl.orEmpty(), keyword, page)
                    val response = fetcher.fetch(request)
                    val context = evaluator.parseBody(response.body, response.finalUrl)
                    val nodes = evaluator.list(source.ruleSearch.rules["bookList"], context)
                    val sourceBooks = nodes.mapNotNull { node -> parseSearchBook(source, node) }
                    books.addAll(sourceBooks)
                    attempts.add(SourceSearchAttempt(source.sourceName, true, sourceBooks.size, request.url))
                }.onFailure { error ->
                    attempts.add(
                        SourceSearchAttempt(
                            source.sourceName,
                            false,
                            0,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        return EngineResult.Success(SourceSearchReport(books, attempts))
    }

    fun getBookDetail(book: SourceBook): EngineResult<SourceBookDetail> {
        return runCatching {
            val response = fetcher.fetch(HttpRequest(book.bookUrl, headers = book.source.headers))
            val context = evaluator.parseBody(response.body, response.finalUrl)
            context.variables.putIfAbsent("url", book.bookUrl)
            context.variables.putIfAbsent("bookUrl", book.bookUrl)
            val rootNode = rootNode(context)
            val initRule = book.source.ruleBookInfo.rules["init"].orEmpty()
            val detailNode = if (initRule.trimStart().startsWith("@put:")) {
                evaluator.string(initRule, rootNode)
                rootNode
            } else {
                initRule.takeIf { it.isNotBlank() }
                    ?.let { evaluator.list(it, context).firstOrNull() }
                    ?: rootNode
            }
            detailNode.variables.putIfAbsent("url", book.bookUrl)
            detailNode.variables.putIfAbsent("bookUrl", book.bookUrl)
            val tocUrl = evaluator.string(book.source.ruleBookInfo.rules["tocUrl"], detailNode)
                .ifBlank { book.bookUrl }
                .let { evaluator.resolveUrl(response.finalUrl, it) }
            val name = evaluator.string(book.source.ruleBookInfo.rules["name"], detailNode).ifBlank { book.name }
            val author = evaluator.string(book.source.ruleBookInfo.rules["author"], detailNode).ifBlank { book.author }
            val coverUrl = evaluator.string(book.source.ruleBookInfo.rules["coverUrl"], detailNode)
                .let { evaluator.resolveUrl(response.finalUrl, it) }
            val intro = evaluator.string(book.source.ruleBookInfo.rules["intro"], detailNode).ifBlank { book.intro }
            val kind = evaluator.string(book.source.ruleBookInfo.rules["kind"], detailNode).ifBlank { book.kind }
            val lastChapter = evaluator.string(book.source.ruleBookInfo.rules["lastChapter"], detailNode)
                .ifBlank { book.lastChapter }
            applyBookInfoPutRules(book.source, detailNode)
            SourceBookDetail(
                book = book,
                name = name,
                author = author,
                coverUrl = coverUrl,
                intro = intro,
                kind = kind,
                lastChapter = lastChapter,
                tocUrl = tocUrl,
                runtimeVariables = detailNode.variables.toMap()
            )
        }.fold(
            onSuccess = { EngineResult.Success(it) },
            onFailure = { EngineResult.Failure(EngineFailure.NetworkError(it.message ?: "getBookDetail failed.")) }
        )
    }

    fun getChapterList(detail: SourceBookDetail): EngineResult<List<SourceChapter>> {
        return runCatching {
            val request = urlBuilder.buildConfiguredRequest(detail.book.source, detail.tocUrl)
            val response = fetcher.fetch(request)
            val context = evaluator.parseBody(response.body, response.finalUrl)
            context.variables.putAll(detail.runtimeVariables)
            context.variables.putIfAbsent("url", detail.book.bookUrl)
            context.variables.putIfAbsent("bookUrl", detail.book.bookUrl)
            context.variables.putIfAbsent("tocUrl", detail.tocUrl)
            evaluator.list(detail.book.source.ruleToc.rules["chapterList"], context)
                .mapIndexedNotNull { index, node ->
                    val name = evaluator.string(detail.book.source.ruleToc.rules["chapterName"], node)
                    val url = evaluator.string(detail.book.source.ruleToc.rules["chapterUrl"], node)
                    if (name.isBlank() || url.isBlank()) {
                        null
                    } else {
                        SourceChapter(
                            source = detail.book.source,
                            book = detail.book,
                            index = index,
                            name = name,
                            chapterUrl = evaluator.resolveUrl(response.finalUrl, url),
                            runtimeVariables = node.variables.toMap()
                        )
                    }
                }
        }.fold(
            onSuccess = { EngineResult.Success(it) },
            onFailure = { EngineResult.Failure(EngineFailure.NetworkError(it.message ?: "getChapterList failed.")) }
        )
    }

    fun getCanonicalChapterList(detail: SourceBookDetail): EngineResult<CanonicalChapterList> {
        return when (val chapters = getChapterList(detail)) {
            is EngineResult.Success -> EngineResult.Success(chapterListFusion.fuse(listOf(chapters.value)))
            is EngineResult.Failure -> chapters
        }
    }

    fun getContent(chapter: SourceChapter): EngineResult<String> {
        return when (val content = getCleanContent(chapter)) {
            is EngineResult.Success -> EngineResult.Success(content.value.cleanedContent)
            is EngineResult.Failure -> content
        }
    }

    fun getRawContent(chapter: SourceChapter): EngineResult<String> {
        return runCatching {
            applyReplaceRegex(loadRawContent(chapter), chapter.source.ruleContent.rules["replaceRegex"])
        }.fold(
            onSuccess = { EngineResult.Success(it) },
            onFailure = { EngineResult.Failure(EngineFailure.NetworkError(it.message ?: "getRawContent failed.")) }
        )
    }

    fun getCleanContent(
        chapter: SourceChapter,
        referenceContents: List<String> = emptyList(),
        bookFingerprint: BookContentFingerprint? = null
    ): EngineResult<CleanContent> {
        return runCatching {
            val rawContent = loadRawContent(chapter)
            val sourceCleaned = applyReplaceRegex(rawContent, chapter.source.ruleContent.rules["replaceRegex"])
            contentCleaner.clean(
                rawContent = sourceCleaned,
                chapterTitle = chapter.name,
                bookName = chapter.book.name,
                author = chapter.book.author,
                referenceContents = referenceContents,
                bookFingerprint = bookFingerprint
            )
        }.fold(
            onSuccess = { EngineResult.Success(it) },
            onFailure = { EngineResult.Failure(EngineFailure.NetworkError(it.message ?: "getContent failed.")) }
        )
    }

    private fun loadRawContent(chapter: SourceChapter): String {
        val content = StringBuilder()
        var nextUrl = chapter.chapterUrl
        val visited = LinkedHashSet<String>()
        while (nextUrl.isNotBlank() && visited.size < MAX_CONTENT_PAGES && visited.add(nextUrl)) {
            val response = fetcher.fetch(HttpRequest(nextUrl, headers = chapter.source.headers))
            val context = evaluator.parseBody(response.body, response.finalUrl)
            context.variables.putAll(chapter.runtimeVariables)
            context.variables.putIfAbsent("url", chapter.book.bookUrl)
            context.variables.putIfAbsent("bookUrl", chapter.book.bookUrl)
            context.variables.putIfAbsent("chapterUrl", chapter.chapterUrl)
            val rootNode = rootNode(context)
            val pageContent = evaluator.string(chapter.source.ruleContent.rules["content"], rootNode)
            if (pageContent.isNotBlank()) {
                if (content.isNotEmpty()) content.append("\n")
                content.append(pageContent)
            }
            val nextRule = chapter.source.ruleContent.rules["nextContentUrl"]
            nextUrl = evaluator.string(nextRule, rootNode)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?.takeIf { it.isNotBlank() }
                ?.let { evaluator.resolveUrl(response.finalUrl, it) }
                .orEmpty()
        }
        return content.toString()
    }

    private fun parseSearchBook(
        source: BookSource,
        node: LegadoRuleEvaluator.RuleNode
    ): SourceBook? {
        val name = evaluator.string(source.ruleSearch.rules["name"], node)
        val bookUrl = evaluator.string(source.ruleSearch.rules["bookUrl"], node)
        if (name.isBlank() || bookUrl.isBlank()) return null
        return SourceBook(
            source = source,
            name = name,
            author = evaluator.string(source.ruleSearch.rules["author"], node),
            bookUrl = evaluator.resolveUrl(node.baseUrl, bookUrl),
            coverUrl = evaluator.string(source.ruleSearch.rules["coverUrl"], node)
                .let { evaluator.resolveUrl(node.baseUrl, it) },
            intro = evaluator.string(source.ruleSearch.rules["intro"], node),
            kind = evaluator.string(source.ruleSearch.rules["kind"], node),
            lastChapter = evaluator.string(source.ruleSearch.rules["lastChapter"], node)
        )
    }

    private fun applyBookInfoPutRules(
        source: BookSource,
        detailNode: LegadoRuleEvaluator.RuleNode
    ) {
        source.ruleBookInfo.rules.values
            .asSequence()
            .map { it.trimStart() }
            .filter { it.startsWith("@put:") }
            .forEach { evaluator.string(it, detailNode) }
    }

    private fun rootNode(context: LegadoRuleEvaluator.BodyContext): LegadoRuleEvaluator.RuleNode {
        return LegadoRuleEvaluator.RuleNode(
            json = context.json,
            element = context.document,
            baseUrl = context.baseUrl,
            variables = context.variables
        )
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

    companion object {
        private const val MAX_CONTENT_PAGES = 10
    }
}
