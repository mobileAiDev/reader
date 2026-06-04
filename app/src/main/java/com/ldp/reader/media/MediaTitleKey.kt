package com.ldp.reader.media

import java.util.Locale

internal object MediaTitleKey {
    fun normalized(value: String): String {
        return normalize(value)
    }

    fun normalizedForQuery(value: String, query: String): String {
        return normalize(bestSegment(value, normalize(query)))
    }

    fun consensusKey(kind: ReaderMediaKind, query: String, title: String): String {
        if (kind == ReaderMediaKind.NOVEL) return normalize(title)
        val queryKey = normalize(query)
        val titleKey = audioEditionKey(kind, queryKey, normalize(bestSegment(title, queryKey)))
        if (titleKey.isNotBlank()) return titleKey
        return normalize(title)
    }

    fun matchesQuery(kind: ReaderMediaKind, queryRaw: String, titleRaw: String): Boolean {
        if (kind == ReaderMediaKind.NOVEL) return true
        val query = normalize(queryRaw)
        if (kind == ReaderMediaKind.AUDIO && hasEmbeddedQuerySegment(titleRaw, query)) return false
        val title = normalizedForQuery(titleRaw, queryRaw)
        return normalizedTitleMatchesQuery(kind, query, title)
    }

    fun normalizedTitleMatchesQuery(kind: ReaderMediaKind, query: String, title: String): Boolean {
        if (query.isBlank() || title.isBlank()) return false
        if (title == query) return true
        if (title.startsWith(query)) return true
        if (queryContainsTitleAsSameWorkVariant(query, title)) return true
        if (kind == ReaderMediaKind.AUDIO) return false
        return title.contains(query)
    }

    fun queryContainsTitleAsSameWorkVariant(query: String, title: String): Boolean {
        if (query.isBlank() || title.isBlank() || query == title) return false
        if (!query.startsWith(title)) return false
        val suffix = query.removePrefix(title)
        if (suffix.isBlank()) return false
        if (CONTINUATION_SUFFIX.containsMatchIn(suffix)) return false
        return SAME_WORK_VERSION_SUFFIX.matches(suffix)
    }

    private fun audioEditionKey(kind: ReaderMediaKind, queryKey: String, titleKey: String): String {
        if (kind != ReaderMediaKind.AUDIO || queryKey.isBlank()) return titleKey
        if (!titleKey.startsWith(queryKey) || titleKey == queryKey) return titleKey
        val suffix = titleKey.removePrefix(queryKey)
        if (suffix.startsWith("之")) return titleKey
        return if (AUDIO_VERSION_SUFFIX.containsMatchIn(suffix)) queryKey else titleKey
    }

    private fun hasEmbeddedQuerySegment(value: String, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return false
        val parts = value.split(TITLE_SEGMENT_SEPARATOR)
            .map { normalize(it) }
            .filter { it.isNotBlank() }
        if (parts.size < 2) return false
        val first = parts.first()
        if (normalizedTitleMatchesQuery(ReaderMediaKind.AUDIO, normalizedQuery, first)) return false
        return parts.drop(1).any { segment ->
            segment == normalizedQuery || segment.startsWith(normalizedQuery) || segment.contains(normalizedQuery)
        }
    }

    private fun bestSegment(value: String, normalizedQuery: String): String {
        val parts = value.split(TITLE_SEGMENT_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return value
        if (normalizedQuery.isBlank()) return parts.first()
        return parts.maxWithOrNull(compareBy<String> { segmentScore(normalize(it), normalizedQuery) }
            .thenBy { -normalize(it).length })
            ?.takeIf { segmentScore(normalize(it), normalizedQuery) > 0 }
            ?: parts.first()
    }

    private fun segmentScore(title: String, query: String): Int {
        if (title.isBlank() || query.isBlank()) return 0
        return when {
            title == query -> 1_000
            title.startsWith(query) -> 900 - surplusPenalty(query, title)
            title.contains(query) -> 800 - surplusPenalty(query, title)
            queryContainsTitleAsSameWorkVariant(query, title) -> 700
            else -> 0
        }
    }

    private fun surplusPenalty(query: String, title: String): Int {
        return ((title.length - query.length).coerceAtLeast(0) * 18).coerceAtMost(420)
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(MEDIA_NOISE_WORDS, "")
            .replace(TITLE_PUNCTUATION, "")
            .trim()
    }

    private const val MIN_MEANINGFUL_CHARS = 2
    private val TITLE_SEGMENT_SEPARATOR = Regex("""\s*(?:[|｜/／]|-{1,2}|—|–|:|：|\r?\n)\s*""")
    private val MEDIA_NOISE_WORDS = Regex(
        """(?i)(漫画|有声小说|有声书|有声|听书|广播剧|评书|真人讲述|全集|完结|连载|最新|章节|目录|免费|完整版|多人播讲|多人播|多人|主播|播音|原著)"""
    )
    private val AUDIO_VERSION_SUFFIX = Regex("""^.{1,12}(版|配音|演播|播讲)$""")
    private val SAME_WORK_VERSION_SUFFIX = Regex("""^(.{1,12}(版|配音|演播|播讲|朗读|解说)|[上中下]部|[上中下]季)$""")
    private val CONTINUATION_SUFFIX = Regex("""^(之|续|外传|前传|后传|番外|番外篇|第[0-9一二三四五六七八九十]+(部|季|卷|篇)|第二|第三|新)""")
    private val TITLE_PUNCTUATION = Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+""")
}
