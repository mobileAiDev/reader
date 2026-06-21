package com.ldp.reader.media.legado

internal object MediaChineseTextConverter {
    fun simplifiedToTraditional(value: String): String {
        return fallbackSimplifiedToTraditional(value)
    }

    fun traditionalToSimplified(value: String): String {
        return fallbackTraditionalToSimplified(value)
    }

    private fun fallbackSimplifiedToTraditional(value: String): String {
        return value.map { char -> FALLBACK_SIMPLIFIED_TO_TRADITIONAL[char] ?: char }.joinToString("")
    }

    private fun fallbackTraditionalToSimplified(value: String): String {
        return value.map { char -> FALLBACK_TRADITIONAL_TO_SIMPLIFIED[char] ?: char }.joinToString("")
    }

    private val FALLBACK_SIMPLIFIED_TO_TRADITIONAL = mapOf(
        '万' to '萬',
        '书' to '書',
        '传' to '傳',
        '剑' to '劍',
        '动' to '動',
        '医' to '醫',
        '参' to '參',
        '发' to '發',
        '变' to '變',
        '后' to '後',
        '来' to '來',
        '无' to '無',
        '诡' to '詭',
        '秘' to '秘',
        '罗' to '羅',
        '苍' to '蒼',
        '门' to '門',
        '阅' to '閱',
        '长' to '長',
        '陆' to '陸',
        '镇' to '鎮',
        '斗' to '鬥',
        '龙' to '龍'
    )
    private val FALLBACK_TRADITIONAL_TO_SIMPLIFIED = FALLBACK_SIMPLIFIED_TO_TRADITIONAL.entries
        .associate { (simple, traditional) -> traditional to simple }
}
