package com.ldp.reader.sourceengine.content

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
        referenceContents: List<String> = emptyList()
    ): CleanContent {
        val normalized = normalizeHtmlText(rawContent)
        val rawLines = normalized.lines()
            .map { it.trim().replace(Regex("""[ \t]{2,}"""), " ") }
            .filter { it.isNotBlank() }
        val keptLines = ArrayList<String>()
        val markers = ArrayList<String>()
        var removedLineCount = 0
        var duplicateLineCount = 0
        rawLines.forEach { line ->
            if (keptLines.isEmpty() && chapterTitle.isNotBlank() && sameTitle(line, chapterTitle)) {
                removedLineCount++
                markers.add("chapter-title")
                return@forEach
            }
            val marker = pollutionMarker(line)
            if (marker != null) {
                removedLineCount++
                markers.add(marker)
                return@forEach
            }
            if (keptLines.lastOrNull() == line) {
                removedLineCount++
                duplicateLineCount++
                return@forEach
            }
            keptLines.add(line)
        }
        val cleaned = keptLines.joinToString("\n").trim()
        val belongingReport = belongingChecker.inspect(
            ContentInspectionInput(
                cleanedContent = cleaned,
                chapterTitle = chapterTitle,
                bookName = bookName,
                author = author,
                referenceContents = referenceContents
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
                belongingReport = belongingReport
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
        return Parser.unescapeEntities(withLineBreaks, false)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private fun pollutionMarker(line: String): String? {
        return POLLUTION_RULES.firstOrNull { rule ->
            line.length <= rule.maxLineLength && rule.pattern.containsMatchIn(line)
        }?.marker
    }

    private fun sameTitle(line: String, chapterTitle: String): Boolean {
        return titleKey(line) == titleKey(chapterTitle)
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
        private val POLLUTION_RULES = listOf(
            PollutionRule("url", Regex("""(?i)(https?://|www\.|\.com|\.net|\.org|最新网址|最新地址)""")),
            PollutionRule("ad", Regex("""(广告|无弹窗|弹窗广告|阅读模式|章节错误|报错)"""), 90),
            PollutionRule("bookmark", Regex("""(请收藏|加入书签|收藏本站|方便下次阅读)"""), 90),
            PollutionRule("vote", Regex("""(推荐票|月票|求票|投票)"""), 90),
            PollutionRule("navigation", Regex("""(上一章|下一章|返回目录|点击下一页|本章未完)"""), 90),
            PollutionRule("mobile", Regex("""(手机用户|客户端|APP|app下载|微信公众号)"""), 90)
        )
    }
}
