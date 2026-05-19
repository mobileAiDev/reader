package com.ldp.reader.sourceengine.catalog

import com.ldp.reader.sourceengine.model.CanonicalChapter
import com.ldp.reader.sourceengine.model.CanonicalChapterList
import com.ldp.reader.sourceengine.model.ChapterOrdinalRange
import com.ldp.reader.sourceengine.model.NormalizedChapterTitle
import com.ldp.reader.sourceengine.model.SourceChapter
import java.util.Locale

class ChapterListFusion(
    private val normalizer: ChapterNormalizer = ChapterNormalizer()
) {
    fun fuse(chapterLists: List<List<SourceChapter>>): CanonicalChapterList {
        val groups = LinkedHashMap<String, ChapterGroup>()
        var firstOrder = 0
        val orderedChapters = chapterLists.flatMap { chapters ->
            annotateCatalogCycles(sanitizeCatalogEntries(chapters))
        }
        orderedChapters.forEach { chapter ->
            val group = groups.getOrPut(chapter.key) {
                ChapterGroup(
                    key = chapter.key,
                    displayTitle = chapter.title.displayTitle,
                    ordinal = chapter.title.ordinal,
                    firstOrder = firstOrder++
                )
            }
            group.sourceChapters.add(chapter.chapter)
        }
        val hasOrdinalRestarts = orderedChapters.any { it.cycle > 0 }
        val canonicalChapters = groups.values
            .map { group ->
                CanonicalChapter(
                    key = group.key,
                    displayTitle = group.displayTitle,
                    ordinal = group.ordinal,
                    sourceChapters = group.sourceChapters.sortedBy { it.index }
                )
            }
            .sortedWith { left, right ->
                if (hasOrdinalRestarts) {
                    compareValues(groups.getValue(left.key).firstOrder, groups.getValue(right.key).firstOrder)
                } else {
                    compareChapterOrder(left, right, groups)
                }
            }
            .let { trimTrailingRestartedSideStory(it) }
            .let { trimTrailingNonOrdinalExtras(it) }
        return CanonicalChapterList(
            chapters = canonicalChapters,
            duplicateCount = canonicalChapters.sumOf { it.duplicateCount },
            missingOrdinalRanges = if (hasOrdinalRestarts) {
                emptyList()
            } else {
                missingRanges(canonicalChapters.mapNotNull { it.ordinal }.distinct().sorted())
            }
        )
    }

    private fun sanitizeCatalogOrder(chapters: List<SourceChapter>): List<SourceChapter> {
        if (chapters.size < MIN_RESTART_CATALOG_SIZE) return chapters
        val ordinals = chapters.map { normalizer.normalize(it.name).ordinal }
        for (index in 1 until ordinals.size) {
            val previous = ordinals[index - 1] ?: continue
            val current = ordinals[index] ?: continue
            if (
                isRecentUpdateRestart(previous, current) &&
                looksLikeRecentUpdatePrefix(ordinals.take(index)) &&
                hasAscendingMainCatalog(ordinals.drop(index))
            ) {
                return chapters.drop(index)
            }
        }
        return chapters
    }

    private fun sanitizeCatalogEntries(chapters: List<SourceChapter>): List<SourceChapter> {
        val ordered = sanitizeCatalogOrder(chapters)
        if (ordered.size < MIN_ANNOUNCEMENT_FILTER_CATALOG_SIZE) return ordered
        return ordered
            .filterNot { chapter -> isAnnouncementEntry(chapter.name) }
            .dedupeRepeatedUrls()
    }

    private fun List<SourceChapter>.dedupeRepeatedUrls(): List<SourceChapter> {
        val seenUrls = LinkedHashSet<String>()
        return filter { chapter ->
            val normalizedUrl = chapter.chapterUrl.trim()
            normalizedUrl.isBlank() || seenUrls.add(normalizedUrl)
        }
    }

    private fun annotateCatalogCycles(chapters: List<SourceChapter>): List<CatalogChapter> {
        val result = ArrayList<CatalogChapter>(chapters.size)
        var cycle = 0
        var previousOrdinal: Int? = null
        chapters.forEach { chapter ->
            val title = normalizer.normalize(chapter.name)
            val ordinal = title.ordinal
            val previous = previousOrdinal
            if (
                previous != null &&
                ordinal != null &&
                isCatalogOrdinalRestart(previous, ordinal)
            ) {
                cycle++
            }
            result.add(
                CatalogChapter(
                    chapter = chapter,
                    title = title,
                    cycle = cycle,
                    key = chapterKey(title, cycle)
                )
            )
            if (ordinal != null) {
                previousOrdinal = ordinal
            }
        }
        return result
    }

    private fun chapterKey(title: NormalizedChapterTitle, cycle: Int): String {
        val ordinal = title.ordinal
        if (ordinal == null) {
            return "c:$cycle:t:${titleKey(title.displayTitle)}"
        }
        val suffix = titleSuffix(title.displayTitle)
        return if (suffix.isBlank()) {
            "c:$cycle:n:$ordinal"
        } else {
            "c:$cycle:n:$ordinal:$suffix"
        }
    }

    private fun titleSuffix(title: String): String {
        return titleKey(
            ORDINAL_PREFIX_PATTERNS.fold(title) { value, pattern ->
                pattern.replace(value, "")
            }
        )
    }

    private fun isAnnouncementEntry(title: String): Boolean {
        val normalized = normalizer.normalize(title)
        if (normalized.ordinal != null) return false
        val key = normalized.key
        return ANNOUNCEMENT_TITLE_MARKERS.any { marker -> key.contains(marker) }
    }

    private fun compareChapterOrder(
        left: CanonicalChapter,
        right: CanonicalChapter,
        groups: Map<String, ChapterGroup>
    ): Int {
        val leftOrdinal = left.ordinal
        val rightOrdinal = right.ordinal
        return if (leftOrdinal != null && rightOrdinal != null) {
            compareValues(leftOrdinal, rightOrdinal).takeIf { it != 0 }
                ?: compareValues(groups.getValue(left.key).firstOrder, groups.getValue(right.key).firstOrder)
        } else {
            compareValues(groups.getValue(left.key).firstOrder, groups.getValue(right.key).firstOrder)
        }
    }

    private fun trimTrailingNonOrdinalExtras(chapters: List<CanonicalChapter>): List<CanonicalChapter> {
        if (chapters.count { it.ordinal != null } < MIN_TRAILING_EXTRA_FILTER_ORDINALS) return chapters
        var keepUntilExclusive = chapters.size
        while (keepUntilExclusive > 0) {
            val chapter = chapters[keepUntilExclusive - 1]
            if (chapter.ordinal != null || isTerminalChapterLike(chapter.displayTitle)) break
            keepUntilExclusive--
        }
        return chapters.take(keepUntilExclusive)
    }

    private fun trimTrailingRestartedSideStory(chapters: List<CanonicalChapter>): List<CanonicalChapter> {
        if (chapters.count { it.ordinal != null } < MIN_TRAILING_EXTRA_FILTER_ORDINALS) return chapters
        for (index in 1 until chapters.size) {
            val previous = chapters[index - 1]
            val current = chapters[index]
            val previousOrdinal = previous.ordinal ?: continue
            val currentOrdinal = current.ordinal ?: continue
            if (!isCatalogOrdinalRestart(previousOrdinal, currentOrdinal) || !isTerminalChapterLike(previous.displayTitle)) {
                continue
            }
            val tail = chapters.drop(index)
            val tailOrdinals = tail.mapNotNull { it.ordinal }
            if (
                tail.size <= MAX_TRAILING_SIDE_STORY_CHAPTERS &&
                (tailOrdinals.maxOrNull() ?: 0) <= MAX_TRAILING_SIDE_STORY_ORDINAL
            ) {
                return chapters.take(index)
            }
        }
        return chapters
    }

    private fun isTerminalChapterLike(title: String): Boolean {
        val key = titleKey(title)
        return TERMINAL_CHAPTER_MARKERS.any { marker -> key.contains(marker) }
    }

    private fun isRecentUpdateRestart(previous: Int, current: Int): Boolean {
        return previous >= RECENT_PREFIX_MIN_ORDINAL &&
            current <= RESTART_ORDINAL_MAX &&
            previous - current >= RESTART_GAP_MIN
    }

    private fun isCatalogOrdinalRestart(previous: Int, current: Int): Boolean {
        return previous > current && current <= RESTART_ORDINAL_MAX
    }

    private fun looksLikeRecentUpdatePrefix(ordinals: List<Int?>): Boolean {
        val visibleOrdinals = ordinals.filterNotNull()
        if (visibleOrdinals.isEmpty() || visibleOrdinals.size > MAX_RECENT_PREFIX_CHAPTERS) {
            return false
        }
        return visibleOrdinals.first() >= RECENT_PREFIX_MIN_ORDINAL &&
            visibleOrdinals.none { it <= RESTART_ORDINAL_MAX }
    }

    private fun hasAscendingMainCatalog(ordinals: List<Int?>): Boolean {
        val visibleOrdinals = ordinals.filterNotNull()
        if (visibleOrdinals.size < MIN_ASCENDING_RUN) return false
        var run = 1
        visibleOrdinals.zipWithNext().forEach { (current, next) ->
            if (next == current + 1 || next == current) {
                run++
                if (run >= MIN_ASCENDING_RUN) return true
            } else if (next > current) {
                run++
                if (run >= MIN_ASCENDING_RUN) return true
            } else {
                run = 1
            }
        }
        return false
    }

    private fun missingRanges(ordinals: List<Int>): List<ChapterOrdinalRange> {
        if (ordinals.size < 2) return emptyList()
        val ranges = ArrayList<ChapterOrdinalRange>()
        ordinals.zipWithNext().forEach { (current, next) ->
            if (next > current + 1) {
                ranges.add(ChapterOrdinalRange(current + 1, next - 1))
            }
        }
        return ranges
    }

    private fun titleKey(title: String): String {
        return title
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+"""), "")
            .ifBlank { title.trim() }
    }

    private data class CatalogChapter(
        val chapter: SourceChapter,
        val title: NormalizedChapterTitle,
        val cycle: Int,
        val key: String
    )

    private data class ChapterGroup(
        val key: String,
        val displayTitle: String,
        val ordinal: Int?,
        val firstOrder: Int,
        val sourceChapters: MutableList<SourceChapter> = ArrayList()
    )

    companion object {
        private const val MIN_RESTART_CATALOG_SIZE = 8
        private const val MIN_ASCENDING_RUN = 5
        private const val MIN_ANNOUNCEMENT_FILTER_CATALOG_SIZE = 20
        private const val MIN_TRAILING_EXTRA_FILTER_ORDINALS = 20
        private const val RECENT_PREFIX_MIN_ORDINAL = 30
        private const val RESTART_ORDINAL_MAX = 20
        private const val RESTART_GAP_MIN = 20
        private const val MAX_RECENT_PREFIX_CHAPTERS = 80
        private const val MAX_TRAILING_SIDE_STORY_CHAPTERS = 80
        private const val MAX_TRAILING_SIDE_STORY_ORDINAL = 120
        private val ORDINAL_PREFIX_PATTERNS = listOf(
            Regex("""^\s*第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*[章节回话卷]\s*"""),
            Regex("""^\s*([0-9０-９]+)\s*[.、]\s*""")
        )
        private val ANNOUNCEMENT_TITLE_MARKERS = listOf(
            "新书",
            "新书元尊",
            "上架感言",
            "完本感言",
            "完结感言",
            "感言",
            "请假",
            "请一天",
            "请一天假",
            "一天假",
            "今晚",
            "别等",
            "公告",
            "通知",
            "说明",
            "更新",
            "更新计划",
            "修改",
            "阅读须知",
            "求月票",
            "月票",
            "求推荐票",
            "推荐票",
            "加更",
            "一更",
            "两更",
            "三更",
            "已更",
            "欠更",
            "今天",
            "明天",
            "中午",
            "晚上",
            "抱歉",
            "休息",
            "防盗",
            "重复",
            "发布",
            "本站",
            "推荐"
        )
        private val TERMINAL_CHAPTER_MARKERS = listOf(
            "终章",
            "尾声",
            "大结局",
            "结局",
            "结束"
        )
    }
}
