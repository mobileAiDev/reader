package com.ldp.reader.sourceengine.content

import com.ldp.reader.sourceengine.catalog.ChapterTitleCanonicalizer
import com.ldp.reader.sourceengine.model.CleanContent
import org.jsoup.parser.Parser

class ContentCleaner(
    private val scorer: ContentQualityScorer = ContentQualityScorer(),
    private val belongingChecker: ContentBelongingChecker = DeterministicContentBelongingChecker()
) {
    fun clean(
        rawContent: String,
        chapterTitle: String = "",
        bookName: String = "",
        author: String = "",
        referenceContents: List<String> = emptyList(),
        bookFingerprint: BookContentFingerprint? = null
    ): CleanContent = cleanPages(
        rawPages = listOf(rawContent),
        chapterTitle = chapterTitle,
        bookName = bookName,
        author = author,
        referenceContents = referenceContents,
        bookFingerprint = bookFingerprint
    )

    fun cleanPages(
        rawPages: List<String>,
        chapterTitle: String = "",
        bookName: String = "",
        author: String = "",
        referenceContents: List<String> = emptyList(),
        bookFingerprint: BookContentFingerprint? = null
    ): CleanContent {
        val rawContent = rawPages.joinToString("\n")
        val normalizedPages = rawPages.map(::normalizeHtmlText)
        val decodedChapterTitle = decodeHtmlEntities(chapterTitle)
        val keptLines = ArrayList<String>()
        val markers = ArrayList<String>()
        var removedLineCount = 0
        var duplicateLineCount = 0
        normalizedPages.forEach { normalizedPage ->
            var inLeadingTitleRun = decodedChapterTitle.isNotBlank()
            normalizedPage.lines()
                .map { it.trim().replace(Regex("""[ \t]{2,}"""), " ").removeLeadingContentArtifacts().trim() }
                .filter { it.isNotBlank() }
                .forEach lineLoop@{ line ->
                    if (inLeadingTitleRun && sameTitle(line, decodedChapterTitle)) {
                        removedLineCount++
                        markers.add("chapter-title")
                        return@lineLoop
                    }
                    inLeadingTitleRun = false
                    val marker = pollutionMarker(line)
                    if (marker != null) {
                        removedLineCount++
                        markers.add(marker)
                        return@lineLoop
                    }
                    if (keptLines.lastOrNull() == line) {
                        removedLineCount++
                        duplicateLineCount++
                        return@lineLoop
                    }
                    keptLines.add(line)
                }
        }
        val cleaned = keptLines.joinToString("\n").trim()
        val belongingReport = belongingChecker.inspect(
            ContentInspectionInput(
                cleanedContent = cleaned,
                chapterTitle = decodedChapterTitle,
                bookName = bookName,
                author = author,
                referenceContents = referenceContents,
                bookFingerprint = bookFingerprint
            )
        )
        return CleanContent(
            rawContent = rawContent,
            cleanedContent = cleaned,
            report = scorer.score(
                rawLength = rawContent.length,
                cleanedLength = cleaned.length,
                paragraphCount = keptLines.size,
                removedLineCount = removedLineCount,
                duplicateLineCount = duplicateLineCount,
                pollutionMarkers = markers,
                belongingReport = belongingReport,
                cleanupRatioLength = normalizedPages.joinToString("\n").length
            )
        )
    }

    private fun normalizeHtmlText(raw: String): String {
        val withLineBreaks = raw
            .replace(Regex("""(?i)<\s*br\s*/?\s*>"""), "\n")
            .replace(Regex("""(?i)</\s*p\s*>"""), "\n")
            .replace(Regex("""(?i)</\s*div\s*>"""), "\n")
            .replace(Regex("""(?i)</\s*li\s*>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace('\u00a0', ' ')
            .replace("&nbsp;", " ")
        return decodeHtmlEntities(withLineBreaks)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(INLINE_CARET_MARKDOWN_ARTIFACT_REGEX, "")
    }

    private fun pollutionMarker(line: String): String? {
        return POLLUTION_RULES.firstOrNull { rule ->
            line.length <= rule.maxLineLength && rule.pattern.containsMatchIn(line)
        }?.marker
    }

    private fun sameTitle(line: String, chapterTitle: String): Boolean {
        return titleKey(ChapterTitleCanonicalizer.canonicalizeKnownUpdateDecoration(decodeHtmlEntities(line))) ==
            titleKey(ChapterTitleCanonicalizer.canonicalizeKnownUpdateDecoration(decodeHtmlEntities(chapterTitle)))
    }

    private fun decodeHtmlEntities(value: String): String {
        return Parser.unescapeEntities(
            value.replace('\u00a0', ' ').replace("&nbsp;", " "),
            false
        )
    }

    private fun String.removeLeadingContentArtifacts(): String {
        return replace(LEADING_EQUALS_ARTIFACT_REGEX, "")
            .replace(LEADING_READ_CONTENT_ARTIFACT_REGEX, "")
    }

    private fun titleKey(value: String): String {
        return value
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》]+"""), "")
            .lowercase()
    }

    private data class PollutionRule(
        val marker: String,
        val pattern: Regex,
        val maxLineLength: Int = Int.MAX_VALUE
    )

    companion object {
        private val LEADING_EQUALS_ARTIFACT_REGEX = Regex("""^(?:=\s*){2,}""")
        private val LEADING_READ_CONTENT_ARTIFACT_REGEX =
            Regex("""^(?i:read_content(?:_[a-z0-9]+)?\s*\(\s*\)\s*;?\s*)+""")
        private val INLINE_CARET_MARKDOWN_ARTIFACT_REGEX = Regex(
            """(?<=[\p{IsHan}，。！？、；：“”‘’）】])\^\^\h*\*\*(?=[\p{IsHan}“‘（【])(?![^\n]*\*\*)"""
        )
        private val POLLUTION_RULES = listOf(
            PollutionRule("url", Regex("""(?i)(https?://|www\.|m\.[a-z0-9-]+\.|\.com|\.net|\.org|\.info|\.cc|\.xyz|最新网址|最新地址|网址)""")),
            PollutionRule("ad", Regex("""(广告|无弹窗|弹窗广告|阅读模式|章节错误|报错)"""), 90),
            PollutionRule("bookmark", Regex("""(请收藏|加入书签|收藏本站|方便下次阅读)"""), 90),
            PollutionRule("site-ad", Regex("""(记住.{0,40}全网最快|101\s*看书网|全网最快.{0,40}(小说|小説|看书))"""), 180),
            PollutionRule("vote", Regex("""(推荐票|月票|求票|投票)"""), 90),
            PollutionRule("navigation", Regex("""(上一章|下一章|返回目录|点击下一页|本章未完)"""), 90),
            PollutionRule("mobile", Regex("""(手机用户|手机版|客户端|APP|app下载|微信公众号)"""), 90)
        )
    }
}
