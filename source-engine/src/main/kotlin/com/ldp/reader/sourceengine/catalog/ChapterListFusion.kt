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
        val hasNonOrdinalChapters = groups.values.any { it.ordinal == null }
        val canonicalChapters = groups.values
            .map { group ->
                CanonicalChapter(
                    key = group.key,
                    displayTitle = bestDisplayTitle(group),
                    ordinal = group.ordinal,
                    sourceChapters = group.sourceChapters.sortedBy { it.index }
                )
            }
            .sortedWith { left, right ->
                if (hasOrdinalRestarts || hasNonOrdinalChapters) {
                    compareValues(groups.getValue(left.key).firstOrder, groups.getValue(right.key).firstOrder)
                } else {
                    compareChapterOrder(left, right, groups)
                }
            }
            .let { trimTrailingRestartedDuplicateBlock(it) }
            .let { trimTrailingDuplicateOfEarlyCatalog(it) }
            .let { trimTrailingLowOrdinalTailByPosition(it) }
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
        if (chapters.size >= MIN_DUPLICATED_LATEST_PREFIX_CATALOG_SIZE) {
            val ordinals = chapters.map { normalizer.normalize(it.name).ordinal }
            duplicatedLatestPrefixEnd(ordinals)?.let { prefixEnd ->
                return chapters.drop(prefixEnd)
            }
        }
        if (chapters.size < MIN_RESTART_CATALOG_SIZE) return chapters
        val ordinals = chapters.map { normalizer.normalize(it.name).ordinal }
        recentUpdatePrefixEnd(ordinals)?.let { prefixEnd ->
            return chapters.drop(prefixEnd)
        }
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
        if (looksLikeLatestFirstCatalog(ordinals)) {
            return chapters.asReversed()
        }
        return chapters
    }

    private fun recentUpdatePrefixEnd(ordinals: List<Int?>): Int? {
        for (index in 1 until ordinals.size) {
            val suffixOrdinals = ordinals.drop(index).filterNotNull()
            if (suffixOrdinals.firstOrNull()?.let { it > RESTART_ORDINAL_MAX } != false) {
                continue
            }
            if (
                looksLikeRecentUpdatePrefix(ordinals.take(index)) &&
                hasAscendingMainCatalog(suffixOrdinals)
            ) {
                return index
            }
        }
        return null
    }

    private fun duplicatedLatestPrefixEnd(ordinals: List<Int?>): Int? {
        for (index in MIN_DUPLICATED_LATEST_PREFIX_CHAPTERS until ordinals.size) {
            val prefixOrdinals = ordinals.take(index).filterNotNull()
            val suffixOrdinals = ordinals.drop(index).filterNotNull()
            if (prefixOrdinals.size != index) continue
            if (suffixOrdinals.size < prefixOrdinals.size) continue
            if (!isDescendingLatestPrefix(prefixOrdinals)) continue
            if (!hasAscendingMainCatalog(suffixOrdinals, MIN_DUPLICATED_LATEST_MAIN_RUN)) continue
            if (prefixOrdinals.asReversed() == suffixOrdinals.take(prefixOrdinals.size)) {
                return index
            }
        }
        return null
    }

    private fun sanitizeCatalogEntries(chapters: List<SourceChapter>): List<SourceChapter> {
        val ordered = sanitizeCatalogOrder(chapters)
            .filterNot { chapter -> isHardAnnouncementEntry(chapter.name) }
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
        val chapterMatch = CHINESE_ORDINAL_PATTERN.findAll(title)
            .filter { match -> match.groupValues[2] != "卷" }
            .lastOrNull()
        if (chapterMatch != null) {
            return titleKey(title.substring(chapterMatch.range.last + 1))
        }
        return titleKey(
            ORDINAL_PREFIX_PATTERNS.fold(title) { value, pattern ->
                pattern.replace(value, "")
            }
        )
    }

    private fun bestDisplayTitle(group: ChapterGroup): String {
        return group.sourceChapters
            .map { chapter -> normalizer.normalize(chapter.name).displayTitle }
            .minWithOrNull(compareBy<String> { catalogTitlePenalty(it) }.thenBy { it.length })
            ?: group.displayTitle
    }

    private fun catalogTitlePenalty(title: String): Int {
        var penalty = 0
        if (MALFORMED_TITLE_PATTERNS.any { pattern -> pattern.containsMatchIn(title) }) penalty += 100
        if (title.contains("卷") && CHINESE_ORDINAL_PATTERN.findAll(title).count() > 1) penalty += 12
        if (!CHINESE_ORDINAL_PATTERN.containsMatchIn(title) && !NUMERIC_PREFIX_PATTERN.containsMatchIn(title)) penalty += 30
        return penalty + (title.length / 20)
    }

    private fun isAnnouncementEntry(title: String): Boolean {
        val normalized = normalizer.normalize(title)
        if (normalized.ordinal != null) return false
        val key = normalized.key
        return ANNOUNCEMENT_TITLE_MARKERS.any { marker -> key.contains(marker) }
    }

    private fun isHardAnnouncementEntry(title: String): Boolean {
        val normalized = normalizer.normalize(title)
        if (normalized.ordinal != null) return false
        val key = normalized.key
        return HARD_ANNOUNCEMENT_TITLE_MARKERS.any { marker -> key.contains(marker) }
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

    private fun trimTrailingRestartedDuplicateBlock(chapters: List<CanonicalChapter>): List<CanonicalChapter> {
        if (chapters.count { it.ordinal != null } < MIN_TRAILING_EXTRA_FILTER_ORDINALS) return chapters
        var previousOrdinal: Int? = null
        for (index in chapters.indices) {
            val currentOrdinal = chapters[index].ordinal
            if (currentOrdinal == null) {
                continue
            }
            val lastOrdinal = previousOrdinal
            previousOrdinal = currentOrdinal
            if (lastOrdinal == null) {
                continue
            }
            if (
                lastOrdinal < MIN_LONG_MAIN_CATALOG_ORDINAL ||
                currentOrdinal > MAX_TRAILING_DUPLICATE_RESTART_ORDINAL ||
                lastOrdinal - currentOrdinal < MIN_LONG_MAIN_RESTART_GAP
            ) {
                continue
            }
            if (!hasEarlierSameOrdinalTitle(chapters, index, currentOrdinal)) {
                continue
            }

            val tail = chapters.drop(index)
            val tailOrdinals = tail.mapNotNull { it.ordinal }
            val tailMaxOrdinal = tailOrdinals.maxOrNull() ?: continue
            if (
                tail.size <= MAX_TRAILING_DUPLICATE_BLOCK_CHAPTERS &&
                tailMaxOrdinal <= MAX_TRAILING_DUPLICATE_ORDINAL
            ) {
                return chapters.take(index)
            }
        }
        return chapters
    }

    private fun trimTrailingDuplicateOfEarlyCatalog(chapters: List<CanonicalChapter>): List<CanonicalChapter> {
        if (chapters.count { it.ordinal != null } < MIN_TRAILING_EXTRA_FILTER_ORDINALS) return chapters
        val searchStart = (chapters.size - MAX_TRAILING_DUPLICATE_BLOCK_CHAPTERS).coerceAtLeast(0)
        for (index in searchStart until chapters.size) {
            val ordinal = chapters[index].ordinal ?: continue
            if (index < MIN_LONG_MAIN_CATALOG_SIZE || ordinal > MAX_TRAILING_DUPLICATE_RESTART_ORDINAL) {
                continue
            }
            if (!hasEarlierSameOrdinalTitle(chapters, index, ordinal)) {
                continue
            }
            val tail = chapters.drop(index)
            val tailMaxOrdinal = tail.mapNotNull { it.ordinal }.maxOrNull() ?: continue
            if (tailMaxOrdinal <= MAX_TRAILING_DUPLICATE_ORDINAL) {
                return chapters.take(index)
            }
        }
        return chapters
    }

    private fun hasEarlierSameOrdinalTitle(
        chapters: List<CanonicalChapter>,
        index: Int,
        ordinal: Int
    ): Boolean {
        val title = titleKey(chapters[index].displayTitle)
        for (candidateIndex in 0 until index) {
            val candidate = chapters[candidateIndex]
            if (candidate.ordinal == ordinal && titleKey(candidate.displayTitle) == title) {
                return true
            }
        }
        return false
    }

    private fun trimTrailingLowOrdinalTailByPosition(chapters: List<CanonicalChapter>): List<CanonicalChapter> {
        if (chapters.size < MIN_LONG_MAIN_CATALOG_SIZE) return chapters
        val searchStart = (chapters.size - MAX_TRAILING_DUPLICATE_BLOCK_CHAPTERS).coerceAtLeast(0)
        for (index in searchStart until chapters.size) {
            val ordinal = chapters[index].ordinal ?: continue
            if (index < MIN_LONG_MAIN_CATALOG_SIZE || ordinal > MAX_TRAILING_DUPLICATE_RESTART_ORDINAL) {
                continue
            }
            val tail = chapters.drop(index)
            if (tail.size < MIN_TRAILING_DUPLICATE_BLOCK_CHAPTERS) {
                continue
            }
            val tailOrdinals = tail.mapNotNull { it.ordinal }
            if (tailOrdinals.isEmpty() || (tailOrdinals.maxOrNull() ?: 0) > MAX_TRAILING_DUPLICATE_ORDINAL) {
                continue
            }
            if (!looksLikeVolumeRestartTail(tail)) {
                return chapters.take(index)
            }
        }
        return chapters
    }

    private fun looksLikeVolumeRestartTail(tail: List<CanonicalChapter>): Boolean {
        val sampled = tail.take(VOLUME_RESTART_SAMPLE_CHAPTERS)
        if (sampled.size < VOLUME_RESTART_MIN_MARKED_CHAPTERS) return false
        val marked = sampled.count { chapter ->
            VOLUME_RESTART_TITLE_MARKERS.any { marker -> chapter.displayTitle.contains(marker) }
        }
        return marked >= VOLUME_RESTART_MIN_MARKED_CHAPTERS
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

    private fun isDescendingLatestPrefix(ordinals: List<Int>): Boolean {
        return ordinals.size >= MIN_DUPLICATED_LATEST_PREFIX_CHAPTERS &&
            ordinals.zipWithNext().all { (current, next) -> current >= next }
    }

    private fun hasAscendingMainCatalog(
        ordinals: List<Int?>,
        minimumRun: Int = MIN_ASCENDING_RUN
    ): Boolean {
        val visibleOrdinals = ordinals.filterNotNull()
        if (visibleOrdinals.size < minimumRun) return false
        var run = 1
        visibleOrdinals.zipWithNext().forEach { (current, next) ->
            if (next == current + 1 || next == current) {
                run++
                if (run >= minimumRun) return true
            } else if (next > current) {
                run++
                if (run >= minimumRun) return true
            } else {
                run = 1
            }
        }
        return false
    }

    private fun looksLikeLatestFirstCatalog(ordinals: List<Int?>): Boolean {
        val visibleOrdinals = ordinals.filterNotNull()
        if (visibleOrdinals.size < MIN_LATEST_FIRST_CATALOG_ORDINALS) return false
        if (visibleOrdinals.first() <= visibleOrdinals.last()) return false
        val pairs = visibleOrdinals.zipWithNext()
        if (pairs.isEmpty()) return false
        val descending = pairs.count { (current, next) -> current > next }
        val ascending = pairs.count { (current, next) -> next > current }
        return descending * 100 >= pairs.size * MIN_LATEST_FIRST_DESCENDING_PERCENT &&
            ascending * 100 <= pairs.size * MAX_LATEST_FIRST_ASCENDING_PERCENT
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
        private const val MIN_DUPLICATED_LATEST_PREFIX_CATALOG_SIZE = 5
        private const val MIN_DUPLICATED_LATEST_PREFIX_CHAPTERS = 2
        private const val MIN_DUPLICATED_LATEST_MAIN_RUN = 3
        private const val MIN_ANNOUNCEMENT_FILTER_CATALOG_SIZE = 20
        private const val MIN_TRAILING_EXTRA_FILTER_ORDINALS = 20
        private const val RECENT_PREFIX_MIN_ORDINAL = 30
        private const val RESTART_ORDINAL_MAX = 20
        private const val RESTART_GAP_MIN = 20
        private const val MIN_LATEST_FIRST_CATALOG_ORDINALS = 8
        private const val MIN_LATEST_FIRST_DESCENDING_PERCENT = 80
        private const val MAX_LATEST_FIRST_ASCENDING_PERCENT = 10
        private const val MAX_RECENT_PREFIX_CHAPTERS = 80
        private const val MAX_TRAILING_SIDE_STORY_CHAPTERS = 80
        private const val MAX_TRAILING_SIDE_STORY_ORDINAL = 120
        private const val MIN_LONG_MAIN_CATALOG_SIZE = 500
        private const val MIN_LONG_MAIN_CATALOG_ORDINAL = 500
        private const val MIN_LONG_MAIN_RESTART_GAP = 300
        private const val MIN_TRAILING_DUPLICATE_BLOCK_CHAPTERS = 5
        private const val MAX_TRAILING_DUPLICATE_BLOCK_CHAPTERS = 180
        private const val MAX_TRAILING_DUPLICATE_RESTART_ORDINAL = 120
        private const val MAX_TRAILING_DUPLICATE_ORDINAL = 180
        private const val VOLUME_RESTART_SAMPLE_CHAPTERS = 10
        private const val VOLUME_RESTART_MIN_MARKED_CHAPTERS = 3
        private val VOLUME_RESTART_TITLE_MARKERS = listOf("第二卷", "第三卷", "第四卷", "第二部", "第三部", "第四部")
        private val ORDINAL_PREFIX_PATTERNS = listOf(
            Regex("""^\s*第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*[章节回话卷]\s*"""),
            Regex("""^\s*([0-9０-９]+)\s*[.、]\s*"""),
            Regex("""^\s*([0-9０-９]{1,5})\s+""")
        )
        private val CHINESE_ORDINAL_PATTERN =
            Regex("""第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*([章节回话卷])""")
        private val NUMERIC_PREFIX_PATTERN = Regex("""^\s*[0-9０-９]+\s*[.、]\s*""")
        private val MALFORMED_TITLE_PATTERNS = listOf(
            Regex("""第\s*第"""),
            Regex("""章\s*第"""),
            Regex("""降真仙"""),
            Regex("""一一章""")
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
            "感谢",
            "打赏",
            "中奖",
            "中奖名单",
            "发布",
            "本站",
            "推荐"
        )
        private val HARD_ANNOUNCEMENT_TITLE_MARKERS = listOf(
            "欢迎收藏",
            "请收藏",
            "收藏本站"
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
