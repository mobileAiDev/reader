package com.ldp.reader.algorithmtest.core

import kotlin.math.max

class ChapterQualityGate {
    fun inspect(chapter: ChapterInput): ChapterQualityResult {
        val normalizedTitle = cleanTitle(chapter.title)
        val normalizedText = normalizeRaw(chapter.content)
        val lines = normalizedText
            .split('\n')
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }

        val keptLines = ArrayList<String>()
        val reasons = ArrayList<String>()
        var removedLines = 0
        var shellLines = 0
        var codeLines = 0
        var navLines = 0
        var siteLines = 0
        var metaLines = 0

        lines.forEachIndexed { index, rawLine ->
            val lineSignals = classifyLine(rawLine)
            if (lineSignals.shell) shellLines += 1
            if (lineSignals.code) codeLines += 1
            if (lineSignals.nav) navLines += 1
            if (lineSignals.site) siteLines += 1
            if (lineSignals.meta) metaLines += 1

            val edgeLine = isEdgeLine(index, lines.size)
            val narrative = isNarrativeLine(rawLine)
            val removeWholeLine = lineSignals.code ||
                lineSignals.recommendation ||
                lineSignals.nav && (edgeLine || !narrative) ||
                lineSignals.site && (edgeLine || !narrative) ||
                lineSignals.ad && !narrative

            if (removeWholeLine) {
                removedLines += 1
                return@forEachIndexed
            }

            val stripped = stripInlineShell(rawLine).trim()
            if (stripped.isBlank()) {
                removedLines += 1
            } else {
                keptLines.add(stripped)
            }
        }

        val cleanText = normalizeParagraphs(keptLines.joinToString("\n"))
        val metrics = buildMetrics(
            originalText = normalizedText,
            cleanText = cleanText,
            totalLines = lines.size,
            removedLines = removedLines,
            shellLines = shellLines,
            codeLines = codeLines,
            navLines = navLines,
            siteLines = siteLines,
            metaLines = metaLines
        )

        val type = classifyChapter(normalizedTitle, cleanText, metrics, reasons)
        if (metrics.removedChars > 0 && type == ChapterQualityType.CLEAN_WITH_TRIM) {
            reasons.add("trimmed shell chars=${metrics.removedChars} lines=${metrics.removedLines}")
        }
        if (metrics.codeLines > 0) reasons.add("code/page chrome lines=${metrics.codeLines}")
        if (metrics.siteLines > 0) reasons.add("site shell lines=${metrics.siteLines}")
        if (metrics.navLines > 0) reasons.add("navigation shell lines=${metrics.navLines}")
        if (metrics.metaLines > 0 && type == ChapterQualityType.NON_STORY) {
            reasons.add("non-story meta signals=${metrics.metaLines}")
        }

        return ChapterQualityResult(
            chapterIndex = chapter.index,
            chapterTitle = normalizedTitle,
            type = type,
            cleanText = cleanText,
            confidence = confidenceFor(type, metrics),
            reasons = reasons.distinct().take(8),
            metrics = metrics
        )
    }

    private fun classifyChapter(
        title: String,
        cleanText: String,
        metrics: QualityMetrics,
        reasons: MutableList<String>
    ): ChapterQualityType {
        val numberedTitle = isNumberedChapterTitle(title)
        val strongTitleMeta = strongNonStoryTitlePatterns.any { pattern -> pattern.containsMatchIn(title) }
        val weakTitleMeta = weakNonStoryTitlePatterns.any { pattern -> pattern.containsMatchIn(title) }
        val bodyMetaHits = nonStoryBodyPatterns.count { pattern -> pattern.containsMatchIn(cleanText) }
        val hasStoryShape = metrics.cleanedChars >= 180 &&
            metrics.narrativeSentenceCount >= 2 &&
            metrics.chineseCharRatio >= 0.55

        if (!numberedTitle && strongTitleMeta && (bodyMetaHits >= 1 || !hasStoryShape || metrics.cleanedChars <= 1_200)) {
            reasons.add("title/body indicate non-story chapter")
            return ChapterQualityType.NON_STORY
        }
        if (!numberedTitle && weakTitleMeta && (bodyMetaHits >= 1 || !hasStoryShape || metrics.cleanedChars <= 1_200)) {
            reasons.add("weak title meta indicates non-story chapter")
            return ChapterQualityType.NON_STORY
        }
        if (bodyMetaHits >= 4 && metrics.narrativeSentenceCount <= 3) {
            reasons.add("body meta dominates narrative")
            return ChapterQualityType.NON_STORY
        }

        val severeCode = metrics.totalLines > 0 && metrics.codeLines >= max(3, metrics.totalLines / 4)
        val severeShell = metrics.shellLineRatio >= 0.55 || metrics.removedCharRatio >= 0.65
        if ((severeCode || severeShell) && !hasStoryShape) {
            reasons.add("page shell dominates and clean story is insufficient")
            return ChapterQualityType.BAD_EXTRACTION
        }
        if (metrics.cleanedChars < 120) {
            reasons.add("clean text too short after shell removal")
            return if (metrics.shellLines > 0) ChapterQualityType.BAD_EXTRACTION else ChapterQualityType.TOO_SHORT_UNCERTAIN
        }
        if (!hasStoryShape) {
            reasons.add("clean text lacks stable narrative shape")
            return ChapterQualityType.MIXED_EXTRACTION_UNCERTAIN
        }
        return if (metrics.removedChars > 0 || metrics.removedLines > 0) {
            ChapterQualityType.CLEAN_WITH_TRIM
        } else {
            ChapterQualityType.CLEAN_STORY
        }
    }

    private fun buildMetrics(
        originalText: String,
        cleanText: String,
        totalLines: Int,
        removedLines: Int,
        shellLines: Int,
        codeLines: Int,
        navLines: Int,
        siteLines: Int,
        metaLines: Int
    ): QualityMetrics {
        val originalChars = originalText.length
        val cleanedChars = cleanText.length
        val removedChars = (originalChars - cleanedChars).coerceAtLeast(0)
        val chineseChars = cleanText.count { ch -> ch in '\u4e00'..'\u9fff' }
        val narrativeSentences = sentenceEndPattern.split(cleanText)
            .map { sentence -> sentence.trim() }
            .count { sentence -> sentence.length >= 8 && !isMostlyShell(sentence) }
        return QualityMetrics(
            originalChars = originalChars,
            cleanedChars = cleanedChars,
            removedChars = removedChars,
            totalLines = totalLines,
            removedLines = removedLines,
            shellLines = shellLines,
            codeLines = codeLines,
            navLines = navLines,
            siteLines = siteLines,
            metaLines = metaLines,
            chineseCharRatio = if (cleanedChars == 0) 0.0 else chineseChars.toDouble() / cleanedChars,
            narrativeSentenceCount = narrativeSentences,
            shellLineRatio = if (totalLines == 0) 0.0 else shellLines.toDouble() / totalLines,
            removedCharRatio = if (originalChars == 0) 0.0 else removedChars.toDouble() / originalChars
        )
    }

    private fun classifyLine(line: String): LineSignals {
        val code = codePatterns.any { pattern -> pattern.containsMatchIn(line) }
        val url = urlPatterns.any { pattern -> pattern.containsMatchIn(line) }
        val nav = navPatterns.any { pattern -> pattern.containsMatchIn(line) }
        val site = sitePatterns.any { pattern -> pattern.containsMatchIn(line) }
        val ad = adPatterns.any { pattern -> pattern.containsMatchIn(line) }
        val recommendation = recommendationPatterns.any { pattern -> pattern.containsMatchIn(line) }
        val meta = strongNonStoryTitlePatterns.any { pattern -> pattern.containsMatchIn(line) } ||
            weakNonStoryTitlePatterns.any { pattern -> pattern.containsMatchIn(line) } ||
            nonStoryBodyPatterns.any { pattern -> pattern.containsMatchIn(line) }
        return LineSignals(
            shell = code || url || nav || site || ad || recommendation,
            code = code,
            nav = nav,
            site = site || url,
            ad = ad,
            recommendation = recommendation,
            meta = meta
        )
    }

    private fun stripInlineShell(line: String): String {
        var value = line
        inlineShellPatterns.forEach { pattern -> value = value.replace(pattern, "") }
        return value
    }

    private fun isNarrativeLine(line: String): Boolean {
        val chineseChars = line.count { ch -> ch in '\u4e00'..'\u9fff' }
        val punctuation = line.count { ch -> ch == '。' || ch == '！' || ch == '？' || ch == '”' || ch == '；' }
        return chineseChars >= 16 && punctuation >= 1 && !isMostlyShell(line)
    }

    private fun isMostlyShell(line: String): Boolean {
        val signals = classifyLineWithoutMeta(line)
        if (!signals.shell) return false
        val chineseChars = line.count { ch -> ch in '\u4e00'..'\u9fff' }
        val shellWeight = listOf(signals.code, signals.nav, signals.site, signals.ad, signals.recommendation)
            .count { value -> value }
        return shellWeight >= 2 || chineseChars < 18
    }

    private fun classifyLineWithoutMeta(line: String): LineSignals {
        val code = codePatterns.any { pattern -> pattern.containsMatchIn(line) }
        val url = urlPatterns.any { pattern -> pattern.containsMatchIn(line) }
        val nav = navPatterns.any { pattern -> pattern.containsMatchIn(line) }
        val site = sitePatterns.any { pattern -> pattern.containsMatchIn(line) }
        val ad = adPatterns.any { pattern -> pattern.containsMatchIn(line) }
        val recommendation = recommendationPatterns.any { pattern -> pattern.containsMatchIn(line) }
        return LineSignals(
            shell = code || url || nav || site || ad || recommendation,
            code = code,
            nav = nav,
            site = site || url,
            ad = ad,
            recommendation = recommendation,
            meta = false
        )
    }

    private fun isEdgeLine(index: Int, size: Int): Boolean {
        if (size <= 4) return true
        return index < 6 || index >= size - 8 || index < size / 8 || index >= size * 7 / 8
    }

    private fun isNumberedChapterTitle(title: String): Boolean {
        return Regex("""第[零一二三四五六七八九十百千万\d]+[章节卷部]""").containsMatchIn(title)
    }

    private fun confidenceFor(type: ChapterQualityType, metrics: QualityMetrics): Double {
        return when (type) {
            ChapterQualityType.CLEAN_STORY -> 0.95
            ChapterQualityType.CLEAN_WITH_TRIM -> (0.82 + metrics.removedCharRatio.coerceAtMost(0.12)).coerceAtMost(0.92)
            ChapterQualityType.NON_STORY -> 0.88
            ChapterQualityType.BAD_EXTRACTION -> (0.80 + metrics.shellLineRatio.coerceAtMost(0.18)).coerceAtMost(0.98)
            ChapterQualityType.TOO_SHORT_UNCERTAIN -> 0.58
            ChapterQualityType.MIXED_EXTRACTION_UNCERTAIN -> 0.62
        }
    }

    private fun normalizeRaw(value: String): String {
        return value
            .replace(Regex("""(?is)<script\b.*?</script>"""), "\n")
            .replace(Regex("""(?is)<style\b.*?</style>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "\n")
            .replace(Regex("""(?i)&nbsp;|&ensp;|&emsp;|&#160;"""), " ")
            .replace(Regex("""(?i)&amp;"""), "&")
            .replace(Regex("""(?i)&lt;"""), "<")
            .replace(Regex("""(?i)&gt;"""), ">")
            .replace('\u3000', ' ')
            .replace(Regex("""[ \t\r]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun normalizeParagraphs(value: String): String {
        return value
            .replace(Regex("""[ \t\r]+"""), " ")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun cleanTitle(value: String): String {
        return value.replace(Regex("""\s+"""), " ").trim()
    }

    private data class LineSignals(
        val shell: Boolean,
        val code: Boolean,
        val nav: Boolean,
        val site: Boolean,
        val ad: Boolean,
        val recommendation: Boolean,
        val meta: Boolean
    )

    companion object {
        private val sentenceEndPattern = Regex("""[。！？；\n]+""")

        private val urlPatterns = listOf(
            Regex("""(?i)https?://\S+"""),
            Regex("""(?i)\bwww\.[\w.-]+\.\w+\S*"""),
            Regex("""(?i)\b[\w.-]+\.(?:com|net|org|cn|cc|top|vip|info|xyz|me|io|co|la|tv|site|fun|ink|one|book|wang)\S*""")
        )

        private val codePatterns = listOf(
            Regex("""(?i)\b(?:script|document|window|function|var |let |const |return |ajax|xhr|XMLHttpRequest|JSON\.stringify|localStorage|sessionStorage|setTimeout|decodeURIComponent)\b"""),
            Regex("""(?i)(?:</?\w+[^>]*>|class=|id=|href=|onclick=|src=|rel=|stylesheet)"""),
            Regex("""(?i)(?:\.appendChild|\.getElementById|\.querySelector|xhr\.send|fetch\(|eval\()""")
        )

        private val sitePatterns = listOf(
            Regex("""(?:笔趣阁|新笔趣阁|顶点小说|书趣阁|书海阁|书荒阁|书香阁|书迷楼|书包网|八一中文|八零电子书|六月中文|三七中文|四五中文|零点看书|爱下书|爱读书|棉花糖小说|飞卢小说|纵横中文网|起点中文网|创世中文网|云起书院|潇湘书院|红袖添香|晋江文学城|17K小说网|逐浪小说|塔读文学|番茄小说|七猫中文|掌阅|QQ阅读|纵横小说|刺猬猫|SF轻小说|磨铁中文网|黑岩阅读|网易云阅读)"""),
            Regex("""(?:小说网|中文网|文学网|阅读网|小说阅读网|书院|书城|书屋|书阁|看书网|电子书|轻小说|小说吧|书库|书楼)"""),
            Regex("""(?:全文免费阅读|免费阅读全文|最新章节|章节目录|无弹窗阅读|无广告阅读|手机阅读|wap版|手机版)""")
        )

        private val navPatterns = listOf(
            Regex("""^(?:登录|首页|目录|书详情|夜间|设置|手机|推荐|举报指南)$"""),
            Regex("""(?:上一章|下一章|返回目录|回到目录|章节目录|加入书架|加入收藏|投推荐票|推荐本书|阅读记录|书签|章节报错|开始阅读|继续阅读)"""),
            Regex("""(?:上一页|下一页|第\d+页|本章未完|点击下一页继续阅读|请翻页|返回书页|目录页)""")
        )

        private val adPatterns = listOf(
            Regex("""(?:订阅本章|请收藏本站|请记住本站|收藏本站|最新网址|最新地址|永久地址|备用网址|无弹窗|无广告|更新最快|首发最新|求收藏|求推荐票|求月票|app下载|下载APP|关注公众号|微信公众号|QQ群|书友群|打赏|投票|催更|防丢失|防迷路)"""),
            Regex("""(?:喜欢.+请大家收藏|方便下次阅读|本站域名|本站网址|本书来自|转载自|版权归作者所有|仅供交流学习)""")
        )

        private val recommendationPatterns = listOf(
            Regex("""(?:相关推荐|热门推荐|猜你喜欢|同类推荐|本周推荐|大家都在看|编辑推荐|精品推荐|新书推荐|完本推荐|排行榜|最近阅读|书友还看过)"""),
            Regex("""(?:傅总娇妻|总裁|豪门|娇妻|王妃|赘婿).*(?:推荐|阅读|最新章节)""")
        )

        private val inlineShellPatterns = listOf(
            Regex("""(?i)https?://\S+"""),
            Regex("""(?i)\bwww\.[\w.-]+\.\w+\S*"""),
            Regex("""(?:请收藏本站|请记住本站|收藏本站|最新网址[:：]?\S*|最新地址[:：]?\S*|无弹窗阅读|无广告阅读|手机阅读|app下载|下载APP|关注公众号|微信公众号|书友群[:：]?\S*|QQ群[:：]?\S*)"""),
            Regex("""(?:本章未完，?请翻页|点击下一页继续阅读|上一章|下一章|返回目录|加入书架)""")
        )

        private val strongNonStoryTitlePatterns = listOf(
            Regex("""(?:请假|请假条|休息一天|停更|更新说明|更新调整|通知|公告|作者的话|作者说|上架感言|完本感言|完结感言|新书|番外说明|中奖|抽奖|求票|求月票|致谢|写在最后|后记|完结|写给书友|书友的一封信|茶话会)""")
        )

        private val weakNonStoryTitlePatterns = listOf(
            Regex("""(?:补更|加更|月票|推荐票|感谢|打赏|盟主|白银盟|黄金盟)""")
        )

        private val nonStoryBodyPatterns = listOf(
            Regex("""(?:今天请假|明天恢复|休息一天|身体不舒服|状态不好|更新会晚|更新推迟|卡文|整理大纲|请一天假)"""),
            Regex("""(?:新书已发|新书发布|新书起航|求收藏|求追读|求月票|求推荐票|月票抽奖|中奖名单|感谢大家|谢谢大家|书友群|QQ群|完本感言|完结感言|上架感言|写给书友|书友的一封信|茶话会)""")
        )
    }
}
