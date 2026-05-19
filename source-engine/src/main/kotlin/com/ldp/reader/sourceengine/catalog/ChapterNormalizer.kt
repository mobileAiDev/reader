package com.ldp.reader.sourceengine.catalog

import com.ldp.reader.sourceengine.model.NormalizedChapterTitle
import java.util.Locale

class ChapterNormalizer {
    fun normalize(rawTitle: String): NormalizedChapterTitle {
        val displayTitle = rawTitle
            .replace('\u3000', ' ')
            .replace('\u00a0', ' ')
            .trim()
            .replace(Regex("""\s+"""), " ")
        val ordinal = extractOrdinal(displayTitle)
        val key = ordinal?.let { "n:$it" } ?: titleKey(displayTitle)
        return NormalizedChapterTitle(
            rawTitle = rawTitle,
            displayTitle = displayTitle,
            key = key,
            ordinal = ordinal
        )
    }

    private fun extractOrdinal(title: String): Int? {
        ORDINAL_PATTERNS.forEach { pattern ->
            val match = pattern.find(title)
            if (match != null) {
                return parseOrdinalToken(match.groupValues[1])
            }
        }
        return null
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
        return title
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》]+"""), "")
            .ifBlank { title }
    }

    companion object {
        private val ORDINAL_PATTERNS = listOf(
            Regex("""第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*[章节回话卷]"""),
            Regex("""^\s*([0-9０-９]+)\s*[.、]\s*""")
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
