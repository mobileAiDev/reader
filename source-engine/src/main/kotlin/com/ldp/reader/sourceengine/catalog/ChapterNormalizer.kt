package com.ldp.reader.sourceengine.catalog

import com.ldp.reader.sourceengine.model.NormalizedChapterTitle
import java.util.Locale

internal enum class ChapterNormalizerRegexWork {
    WHITESPACE,
    DUPLICATED_CHAPTER_PREFIX,
    DUPLICATED_CHINESE_DIGIT,
    MALFORMED_CHAPTER_SUFFIX,
    CHINESE_ORDINAL,
    CATALOG_MARKER_SUFFIX,
    CHAPTER_AFTER_VOLUME,
    STRAY_CHINESE_DIGIT,
    FALLBACK_ORDINAL,
    TITLE_KEY_SEPARATOR
}

internal fun interface ChapterNormalizerWorkProbe {
    fun onRegexScan(work: ChapterNormalizerRegexWork)
}

class ChapterNormalizer private constructor(
    private val hotPatterns: HotPatterns,
    private val normalizeProbe: ((String) -> Unit)?,
    private val workProbe: ChapterNormalizerWorkProbe?
) {
    constructor() : this(SHARED_HOT_PATTERNS, null, null)

    internal constructor(hotRegexCompiler: (String) -> Regex) : this(HotPatterns(hotRegexCompiler), null, null)

    internal constructor(
        hotRegexCompiler: (String) -> Regex,
        normalizeProbe: (String) -> Unit
    ) : this(HotPatterns(hotRegexCompiler), normalizeProbe, null)

    internal constructor(
        hotRegexCompiler: (String) -> Regex,
        normalizeProbe: (String) -> Unit,
        workProbe: ChapterNormalizerWorkProbe
    ) : this(HotPatterns(hotRegexCompiler), normalizeProbe, workProbe)

    fun normalize(rawTitle: String): NormalizedChapterTitle {
        normalizeProbe?.invoke(rawTitle)
        val whitespaceNormalized = rawTitle
            .replace('\u3000', ' ')
            .replace('\u00a0', ' ')
            .trim()
            .let(::normalizeWhitespaceIfNeeded)
        val analyzed = repairAndAnalyzeDisplayTitle(whitespaceNormalized)
        val displayTitle = analyzed.displayTitle
        val ordinal = if (analyzed.hasChineseOrdinalMatch) {
            analyzed.chineseOrdinal
        } else {
            extractFallbackOrdinal(displayTitle)
        }
        val key = ordinal?.let { "n:$it" } ?: titleKey(displayTitle)
        return NormalizedChapterTitle(
            rawTitle = rawTitle,
            displayTitle = displayTitle,
            key = key,
            ordinal = ordinal
        )
    }

    private fun extractFallbackOrdinal(title: String): Int? {
        ORDINAL_PATTERNS.forEach { pattern ->
            workProbe?.onRegexScan(ChapterNormalizerRegexWork.FALLBACK_ORDINAL)
            val match = pattern.find(title)
            if (match != null) {
                return parseOrdinalToken(match.groupValues[1])
            }
        }
        return null
    }

    private fun repairAndAnalyzeDisplayTitle(title: String): DisplayTitleAnalysis {
        var normalized = title
        if (mayContainDuplicatedChapterPrefix(normalized)) {
            workProbe?.onRegexScan(ChapterNormalizerRegexWork.DUPLICATED_CHAPTER_PREFIX)
            normalized = normalized.replace(hotPatterns.duplicatedChapterPrefix, "第")
        }
        if (mayContainDuplicatedChineseDigit(normalized)) {
            workProbe?.onRegexScan(ChapterNormalizerRegexWork.DUPLICATED_CHINESE_DIGIT)
            normalized = normalized.replace(hotPatterns.duplicatedChineseDigit, "$1")
        }
        if (mayContainMalformedChapterSuffix(normalized)) {
            workProbe?.onRegexScan(ChapterNormalizerRegexWork.MALFORMED_CHAPTER_SUFFIX)
            normalized = normalized.replace(hotPatterns.malformedChapterSuffix, "章 ")
        }
        normalized = normalized.trim()

        val matches = scanChineseOrdinals(normalized)
        val selectedMatch = matches.lastChapter ?: matches.lastVolume
        val selectedOrdinal = selectedMatch?.let { match -> parseOrdinalToken(match.groupValues[1]) }
        val chapterMatch = matches.lastChapter
            ?: return DisplayTitleAnalysis(normalized, selectedOrdinal, selectedMatch != null)
        val hasVolumeBeforeChapter = matches.firstVolumeStart?.let { start ->
            start < chapterMatch.range.first
        } == true
        val prefixBeforeChapter = normalized.substring(0, chapterMatch.range.first).trim()
        val hasCatalogMarkerPrefix = prefixBeforeChapter.isNotEmpty() &&
            hasCatalogMarkerBeforeChapter(prefixBeforeChapter)
        if (!hasVolumeBeforeChapter && !hasCatalogMarkerPrefix) {
            return DisplayTitleAnalysis(normalized, selectedOrdinal, true)
        }

        workProbe?.onRegexScan(ChapterNormalizerRegexWork.CHAPTER_AFTER_VOLUME)
        val displayTitle = normalized.substring(chapterMatch.range.first)
            .replace(hotPatterns.chapterAfterVolume, "章 ")
            .let { value ->
                if ((selectedOrdinal ?: 0) >= LONG_CATALOG_ORDINAL) {
                    workProbe?.onRegexScan(ChapterNormalizerRegexWork.STRAY_CHINESE_DIGIT)
                    STRAY_CHINESE_DIGIT_AFTER_CHAPTER.replace(value) { match ->
                        match.groupValues[1] + " "
                    }
                } else {
                    value
                }
            }
            .let(::normalizeWhitespaceIfNeeded)
            .trim()
        return DisplayTitleAnalysis(displayTitle, selectedOrdinal, true)
    }

    private fun scanChineseOrdinals(title: String): ChineseOrdinalMatches {
        workProbe?.onRegexScan(ChapterNormalizerRegexWork.CHINESE_ORDINAL)
        var lastChapter: MatchResult? = null
        var lastVolume: MatchResult? = null
        var firstVolumeStart: Int? = null
        CHINESE_ORDINAL_PATTERN.findAll(title).forEach { match ->
            if (match.groupValues[2] == "卷") {
                if (firstVolumeStart == null) firstVolumeStart = match.range.first
                lastVolume = match
            } else {
                lastChapter = match
            }
        }
        return ChineseOrdinalMatches(lastChapter, lastVolume, firstVolumeStart)
    }

    private fun normalizeWhitespaceIfNeeded(title: String): String {
        if (!needsWhitespaceNormalization(title)) return title
        workProbe?.onRegexScan(ChapterNormalizerRegexWork.WHITESPACE)
        return title.replace(hotPatterns.whitespace, " ")
    }

    private fun needsWhitespaceNormalization(title: String): Boolean {
        var previousWhitespace = false
        title.forEach { char ->
            val regexWhitespace = char == ' ' || char == '\t' || char == '\n' ||
                char == '\u000B' || char == '\u000C' || char == '\r'
            if (!regexWhitespace) {
                previousWhitespace = false
            } else {
                if (char != ' ' || previousWhitespace) return true
                previousWhitespace = true
            }
        }
        return false
    }

    private fun mayContainDuplicatedChapterPrefix(title: String): Boolean {
        val firstPrefix = title.indexOf('第')
        return firstPrefix >= 0 && title.indexOf('第', firstPrefix + 1) >= 0
    }

    private fun mayContainDuplicatedChineseDigit(title: String): Boolean {
        for (index in 0 until title.lastIndex) {
            val char = title[index]
            if (char == title[index + 1] && char in CHINESE_DIGIT_CHARS) return true
        }
        return false
    }

    private fun mayContainMalformedChapterSuffix(title: String): Boolean {
        val chapterSuffix = title.indexOf('章')
        return chapterSuffix >= 0 && title.indexOf('第', chapterSuffix + 1) >= 0
    }

    private fun hasCatalogMarkerBeforeChapter(prefix: String): Boolean {
        if (prefix.trim(' ', ':', '：', '-', '_', '·') == "录") return true
        workProbe?.onRegexScan(ChapterNormalizerRegexWork.CATALOG_MARKER_SUFFIX)
        return CATALOG_MARKER_SUFFIX_PATTERN.containsMatchIn(prefix)
    }

    private fun parseOrdinalToken(raw: String): Int? {
        val value = normalizeDigits(raw.trim())
        if (value.all { it.isDigit() }) {
            return value.toIntOrNull()
        }
        return parseChineseNumber(value)
    }

    private fun normalizeDigits(raw: String): String {
        return raw.map { char ->
            when (char) {
                in '０'..'９' -> '0' + (char - '０')
                else -> char
            }
        }.joinToString("")
    }

    private fun parseChineseNumber(raw: String): Int? {
        var result = 0
        var section = 0
        var number = 0
        raw.forEach { char ->
            val digit = CHINESE_DIGITS[char]
            if (digit != null) {
                number = digit
                return@forEach
            }
            val unit = CHINESE_UNITS[char] ?: return null
            if (unit == 10000) {
                result += (section + number) * unit
                section = 0
                number = 0
            } else {
                section += (if (number == 0) 1 else number) * unit
                number = 0
            }
        }
        val parsed = result + section + number
        return parsed.takeIf { it > 0 }
    }

    private fun titleKey(title: String): String {
        workProbe?.onRegexScan(ChapterNormalizerRegexWork.TITLE_KEY_SEPARATOR)
        return title
            .lowercase(Locale.ROOT)
            .replace(hotPatterns.titleKeySeparator, "")
            .ifBlank { title }
    }

    private data class DisplayTitleAnalysis(
        val displayTitle: String,
        val chineseOrdinal: Int?,
        val hasChineseOrdinalMatch: Boolean
    )

    private data class ChineseOrdinalMatches(
        val lastChapter: MatchResult?,
        val lastVolume: MatchResult?,
        val firstVolumeStart: Int?
    )

    private class HotPatterns(compile: (String) -> Regex) {
        val whitespace = compile("""\s+""")
        val duplicatedChapterPrefix = compile("""第\s*第""")
        val duplicatedChineseDigit =
            compile("""([零〇一二两三四五六七八九壹贰叁肆伍陆柒捌玖])\1(?=\s*章)""")
        val malformedChapterSuffix = compile("""章\s*第\s+""")
        val chapterAfterVolume = compile("""章\s*第\s*""")
        val titleKeySeparator = compile("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》]+""")
    }

    companion object {
        private val SHARED_HOT_PATTERNS = HotPatterns { pattern -> Regex(pattern) }
        private val CHINESE_ORDINAL_PATTERN =
            Regex("""第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*([章节回话卷])""")
        private const val LONG_CATALOG_ORDINAL = 1_000
        private val STRAY_CHINESE_DIGIT_AFTER_CHAPTER =
            Regex("""^(第\s*[0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\s*[章节回话])\s*([一二两三四五六七八九壹贰叁肆伍陆柒捌玖])(?=[\u4e00-\u9fff])""")
        private val CATALOG_MARKER_SUFFIX_PATTERN =
            Regex("""(?i)(?:VIP\s*章节目录|章节目录|VIP\s*章节|免费章节|最新章节|正文卷?|VIP卷|默认卷|卷[_-]?)\s*$""")
        private val ORDINAL_PATTERNS = listOf(
            Regex("""^\s*([0-9０-９]+)\s*[.、]\s*"""),
            Regex("""^\s*([0-9０-９]+)\s*(?:chapter|chap\.?|ch\.?)\b""", RegexOption.IGNORE_CASE),
            Regex("""^\s*(?:chapter|chap\.?|ch\.?)\s*([0-9０-９]+)\b""", RegexOption.IGNORE_CASE),
            Regex("""^\s*([0-9０-９]{1,5})\s+(?![年月日])""")
        )

        private val CHINESE_DIGITS = mapOf(
            '零' to 0,
            '〇' to 0,
            '一' to 1,
            '二' to 2,
            '两' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '七' to 7,
            '八' to 8,
            '九' to 9,
            '壹' to 1,
            '贰' to 2,
            '叁' to 3,
            '肆' to 4,
            '伍' to 5,
            '陆' to 6,
            '柒' to 7,
            '捌' to 8,
            '玖' to 9
        )
        private val CHINESE_DIGIT_CHARS = CHINESE_DIGITS.keys

        private val CHINESE_UNITS = mapOf(
            '十' to 10,
            '拾' to 10,
            '百' to 100,
            '佰' to 100,
            '千' to 1000,
            '仟' to 1000,
            '万' to 10000
        )
    }
}
