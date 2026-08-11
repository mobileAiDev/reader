package com.ldp.reader.sourceengine.catalog

import com.ldp.reader.sourceengine.model.CanonicalChapter
import com.ldp.reader.sourceengine.model.CanonicalChapterList
import com.ldp.reader.sourceengine.model.ChapterOrdinalRange
import com.ldp.reader.sourceengine.model.NormalizedChapterTitle
import com.ldp.reader.sourceengine.model.SourceChapter
import java.util.Locale
import java.util.concurrent.CancellationException

internal fun interface ChapterListFusionWorkProbe {
    fun onCatalogOrdinalVisits(count: Int)

    fun onCatalogTitlePenaltyEvaluations(count: Int) = Unit

    fun onTitleSuffixRegexFallbacks(count: Int) = Unit

    fun onNormalizeTitleVisit() = Unit
}

class ChapterListFusion internal constructor(
    private val normalizer: ChapterNormalizer,
    private val workProbe: ChapterListFusionWorkProbe?
) {
    constructor(normalizer: ChapterNormalizer = ChapterNormalizer()) : this(normalizer, null)

    private val navigationPollutionFilter = CatalogNavigationPollutionFilter(normalizer)

    fun fuse(chapterLists: List<List<SourceChapter>>): CanonicalChapterList {
        val normalizedTitlesByRawTitle = HashMap<String, NormalizedChapterTitle>()
        val normalizeTitle: (String) -> NormalizedChapterTitle = { rawTitle ->
            workProbe?.onNormalizeTitleVisit()
            if (Thread.currentThread().isInterrupted) {
                throw CancellationException("chapter list fusion cancelled")
            }
            normalizedTitlesByRawTitle.getOrPut(rawTitle) { normalizer.normalize(rawTitle) }
        }
        val groups = LinkedHashMap<String, ChapterGroup>()
        var firstOrder = 0
        val orderedChapters = chapterLists.flatMap { chapters ->
            annotateCatalogCycles(
                sanitizeCatalogEntries(chapters, normalizeTitle),
                normalizeTitle
            )
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
                    displayTitle = bestDisplayTitle(group, normalizeTitle),
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

    private fun sanitizeCatalogOrder(
        chapters: List<SourceChapter>,
        normalizeTitle: (String) -> NormalizedChapterTitle
    ): List<SourceChapter> {
        if (chapters.size < MIN_DUPLICATED_LATEST_PREFIX_CATALOG_SIZE) return chapters
        val normalizedTitles = chapters.map { chapter -> normalizeTitle(chapter.name) }
        duplicatedNonOrdinalLatestPrefix(chapters, normalizedTitles)?.let { match ->
            return chapters.drop(match.prefixEnd)
        }
        val ordinals = normalizedTitles.map { title -> title.ordinal }
        duplicatedLatestPrefixEnd(ordinals)?.let { prefixEnd ->
            return chapters.drop(prefixEnd)
        }
        if (chapters.size < MIN_RESTART_CATALOG_SIZE) return chapters
        recentUpdatePrefixEnd(ordinals)?.let { prefixEnd ->
            return chapters.drop(prefixEnd)
        }
        if (looksLikeLatestFirstCatalog(ordinals)) {
            return chapters.asReversed()
        }
        return chapters
    }

    private fun recentUpdatePrefixEnd(ordinals: List<Int?>): Int? {
        val suffixFirstOrdinal = arrayOfNulls<Int>(ordinals.size + 1)
        val suffixHasAscendingMainCatalog = BooleanArray(ordinals.size + 1)
        var nextVisibleOrdinal: Int? = null
        var ascendingRun = 0
        var maximumAscendingRun = 0
        for (index in ordinals.lastIndex downTo 0) {
            workProbe?.onCatalogOrdinalVisits(1)
            val ordinal = ordinals[index]
            if (ordinal != null) {
                ascendingRun = if (nextVisibleOrdinal?.let { next -> next >= ordinal } == true) {
                    ascendingRun + 1
                } else {
                    1
                }
                maximumAscendingRun = maxOf(maximumAscendingRun, ascendingRun)
                nextVisibleOrdinal = ordinal
            }
            suffixFirstOrdinal[index] = nextVisibleOrdinal
            suffixHasAscendingMainCatalog[index] = maximumAscendingRun >= MIN_ASCENDING_RUN
        }

        var prefixVisibleCount = 0
        var prefixFirstOrdinal: Int? = null
        var prefixContainsRestartOrdinal = false
        for (index in 1 until ordinals.size) {
            workProbe?.onCatalogOrdinalVisits(1)
            ordinals[index - 1]?.let { ordinal ->
                prefixVisibleCount++
                if (prefixFirstOrdinal == null) prefixFirstOrdinal = ordinal
                if (ordinal <= RESTART_ORDINAL_MAX) prefixContainsRestartOrdinal = true
            }
            val firstOrdinal = prefixFirstOrdinal ?: continue
            if (
                prefixVisibleCount > MAX_RECENT_PREFIX_CHAPTERS ||
                firstOrdinal < RECENT_PREFIX_MIN_ORDINAL ||
                prefixContainsRestartOrdinal
            ) {
                return null
            }
            val firstSuffixOrdinal = suffixFirstOrdinal[index] ?: continue
            if (
                firstSuffixOrdinal <= RESTART_ORDINAL_MAX &&
                suffixHasAscendingMainCatalog[index]
            ) {
                return index
            }
        }
        return null
    }

    private fun duplicatedLatestPrefixEnd(ordinals: List<Int?>): Int? {
        val visibleOrdinalBuffer = IntArray(ordinals.size)
        var visibleOrdinalCount = 0
        var firstNullIndex = ordinals.size
        ordinals.forEachIndexed { index, ordinal ->
            workProbe?.onCatalogOrdinalVisits(1)
            if (ordinal == null) {
                if (firstNullIndex == ordinals.size) firstNullIndex = index
            } else {
                visibleOrdinalBuffer[visibleOrdinalCount++] = ordinal
            }
        }
        val maximumPrefixSize = minOf(firstNullIndex, visibleOrdinalCount / 2)
        if (maximumPrefixSize < MIN_DUPLICATED_LATEST_PREFIX_CHAPTERS) return null

        val visibleOrdinals = visibleOrdinalBuffer.copyOf(visibleOrdinalCount)
        val suffixHasAscendingMainCatalog = BooleanArray(visibleOrdinalCount + 1)
        var ascendingRun = 0
        var maximumAscendingRun = 0
        for (index in visibleOrdinals.lastIndex downTo 0) {
            workProbe?.onCatalogOrdinalVisits(1)
            ascendingRun = if (
                index < visibleOrdinals.lastIndex &&
                visibleOrdinals[index + 1] >= visibleOrdinals[index]
            ) {
                ascendingRun + 1
            } else {
                1
            }
            maximumAscendingRun = maxOf(maximumAscendingRun, ascendingRun)
            suffixHasAscendingMainCatalog[index] = maximumAscendingRun >= MIN_DUPLICATED_LATEST_MAIN_RUN
        }

        // With a fully numbered prefix, comparing its reverse with the first numbered suffix
        // entries is exactly an even-palindrome check over the first 2 * prefixSize ordinals.
        val palindromeRadii = evenPalindromeRadii(visibleOrdinals)
        for (prefixSize in MIN_DUPLICATED_LATEST_PREFIX_CHAPTERS..maximumPrefixSize) {
            workProbe?.onCatalogOrdinalVisits(2)
            if (visibleOrdinals[prefixSize - 2] < visibleOrdinals[prefixSize - 1]) {
                break
            }
            if (
                suffixHasAscendingMainCatalog[prefixSize] &&
                palindromeRadii[prefixSize] >= prefixSize
            ) {
                return prefixSize
            }
        }
        return null
    }

    private fun evenPalindromeRadii(values: IntArray): IntArray {
        val radii = IntArray(values.size)
        var left = 0
        var right = -1
        for (center in values.indices) {
            var radius = if (center > right) {
                0
            } else {
                minOf(radii[left + right - center + 1], right - center + 1)
            }
            while (center - radius - 1 >= 0 && center + radius < values.size) {
                workProbe?.onCatalogOrdinalVisits(2)
                if (values[center - radius - 1] != values[center + radius]) break
                radius++
            }
            radii[center] = radius
            if (center + radius - 1 > right) {
                left = center - radius
                right = center + radius - 1
            }
        }
        return radii
    }

    private fun duplicatedNonOrdinalLatestPrefix(
        chapters: List<SourceChapter>,
        normalizedTitles: List<NormalizedChapterTitle>
    ): DuplicatedLatestPrefix? {
        val lastUrlPositions = HashMap<String, Int>()
        val lastTitlePositions = HashMap<String, Int>()
        chapters.forEachIndexed { index, chapter ->
            chapter.chapterUrl.trim().takeIf { url -> url.isNotBlank() }?.let { url ->
                lastUrlPositions[url] = index
            }
            lastTitlePositions[normalizedTitles[index].key] = index
        }
        val prefixEnd = normalizedTitles.indexOfFirst { title -> title.ordinal != null }
        if (prefixEnd < MIN_DUPLICATED_LATEST_PREFIX_CHAPTERS || prefixEnd > MAX_RECENT_PREFIX_CHAPTERS) {
            return null
        }
        val prefixTitles = normalizedTitles.take(prefixEnd)
        val firstMainOrdinal = normalizedTitles[prefixEnd].ordinal ?: return null
        if (firstMainOrdinal > RESTART_ORDINAL_MAX) return null
        val suffixOrdinals = normalizedTitles.drop(prefixEnd).mapNotNull { title -> title.ordinal }
        if (!hasAscendingMainCatalog(suffixOrdinals, MIN_DUPLICATED_LATEST_MAIN_RUN)) return null

        val repeatedPositions = chapters.take(prefixEnd).mapIndexed { index, chapter ->
            val chapterUrl = chapter.chapterUrl.trim()
            if (chapterUrl.isNotBlank()) {
                lastUrlPositions[chapterUrl]
                    ?.takeIf { position -> position >= prefixEnd }
                    ?: -1
            } else {
                lastTitlePositions[prefixTitles[index].key]
                    ?.takeIf { position -> position >= prefixEnd }
                    ?: -1
            }
        }
        if (repeatedPositions.any { position -> position < 0 }) return null
        if (repeatedPositions.distinct().size != repeatedPositions.size) return null
        if (!repeatedPositions.zipWithNext().all { (current, next) -> current > next }) return null
        return DuplicatedLatestPrefix(prefixEnd = prefixEnd)
    }

    private fun sanitizeCatalogEntries(
        chapters: List<SourceChapter>,
        normalizeTitle: (String) -> NormalizedChapterTitle
    ): List<SourceChapter> {
        val ordered = sanitizeCatalogOrder(chapters, normalizeTitle)
            .filterNot { chapter -> isHardAnnouncementEntry(chapter.name, normalizeTitle) }
        if (ordered.size < MIN_ANNOUNCEMENT_FILTER_CATALOG_SIZE) return ordered
        return ordered
            .filterNot { chapter -> isAnnouncementEntry(chapter.name, normalizeTitle) }
            .let { chapters ->
                navigationPollutionFilter.filter(
                    entries = chapters,
                    titleOf = { chapter -> chapter.name },
                    normalizedTitleOf = { chapter -> normalizeTitle(chapter.name) }
                ).entries
            }
            .dedupeRepeatedUrls()
    }

    private fun List<SourceChapter>.dedupeRepeatedUrls(): List<SourceChapter> {
        val seenUrls = LinkedHashSet<String>()
        return filter { chapter ->
            val normalizedUrl = chapter.chapterUrl.trim()
            normalizedUrl.isBlank() || seenUrls.add(normalizedUrl)
        }
    }

    private fun annotateCatalogCycles(
        chapters: List<SourceChapter>,
        normalizeTitle: (String) -> NormalizedChapterTitle
    ): List<CatalogChapter> {
        val result = ArrayList<CatalogChapter>(chapters.size)
        var cycle = 0
        var previousOrdinal: Int? = null
        chapters.forEach { chapter ->
            val title = normalizeTitle(chapter.name)
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
        commonChapterTitleSuffix(title)?.let { suffix ->
            return titleKey(suffix)
        }
        workProbe?.onTitleSuffixRegexFallbacks(1)
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

    private fun commonChapterTitleSuffix(title: String): String? {
        if (title.length < 3 || title[0] != '第') return null
        val numeralKind = when {
            title[1].isCatalogDigit() -> CatalogNumeralKind.DIGIT
            title[1].isCatalogChineseNumeral() -> CatalogNumeralKind.CHINESE
            else -> return null
        }
        var markerIndex = 2
        while (markerIndex < title.length) {
            val isSameNumeralKind = when (numeralKind) {
                CatalogNumeralKind.DIGIT -> title[markerIndex].isCatalogDigit()
                CatalogNumeralKind.CHINESE -> title[markerIndex].isCatalogChineseNumeral()
            }
            if (!isSameNumeralKind) break
            markerIndex++
        }
        if (markerIndex >= title.length || title[markerIndex] != '章') return null
        val suffix = title.substring(markerIndex + 1)
        if ('第' in suffix) return null
        return suffix
    }

    private fun Char.isCatalogDigit(): Boolean {
        return this in '0'..'9' || this in '０'..'９'
    }

    private fun Char.isCatalogChineseNumeral(): Boolean {
        return this in CHINESE_ORDINAL_CHARACTERS
    }

    private fun bestDisplayTitle(
        group: ChapterGroup,
        normalizeTitle: (String) -> NormalizedChapterTitle
    ): String {
        val firstSourceUrl = group.sourceChapters.firstOrNull()?.source?.sourceUrl
            ?: return group.displayTitle
        if (group.sourceChapters.all { chapter -> chapter.source.sourceUrl == firstSourceUrl }) {
            return group.displayTitle
        }
        return group.sourceChapters
            .map { chapter -> normalizeTitle(chapter.name).displayTitle }
            .minWithOrNull(compareBy<String> { catalogTitlePenalty(it) }.thenBy { it.length })
            ?: group.displayTitle
    }

    private fun catalogTitlePenalty(title: String): Int {
        workProbe?.onCatalogTitlePenaltyEvaluations(1)
        var penalty = 0
        if (MALFORMED_TITLE_PATTERNS.any { pattern -> pattern.containsMatchIn(title) }) penalty += 100
        if (title.contains("卷") && CHINESE_ORDINAL_PATTERN.findAll(title).count() > 1) penalty += 12
        if (!CHINESE_ORDINAL_PATTERN.containsMatchIn(title) && !NUMERIC_PREFIX_PATTERN.containsMatchIn(title)) penalty += 30
        return penalty + (title.length / 20)
    }

    private fun isAnnouncementEntry(
        title: String,
        normalizeTitle: (String) -> NormalizedChapterTitle
    ): Boolean {
        val normalized = normalizeTitle(title)
        if (normalized.ordinal != null) return false
        val key = normalized.key
        return ANNOUNCEMENT_TITLE_MARKERS.any { marker -> key.contains(marker) }
    }

    private fun isHardAnnouncementEntry(
        title: String,
        normalizeTitle: (String) -> NormalizedChapterTitle
    ): Boolean {
        val normalized = normalizeTitle(title)
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

    private fun isCatalogOrdinalRestart(previous: Int, current: Int): Boolean {
        return previous > current && current <= RESTART_ORDINAL_MAX
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
            .replace(TITLE_KEY_SEPARATOR_PATTERN, "")
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

    private data class DuplicatedLatestPrefix(val prefixEnd: Int)

    private enum class CatalogNumeralKind {
        DIGIT,
        CHINESE
    }

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
        private const val MIN_LATEST_FIRST_CATALOG_ORDINALS = 8
        private const val MIN_LATEST_FIRST_DESCENDING_PERCENT = 80
        private const val MAX_LATEST_FIRST_ASCENDING_PERCENT = 10
        private const val MAX_RECENT_PREFIX_CHAPTERS = 80
        private const val MIN_LONG_MAIN_CATALOG_SIZE = 500
        private const val MIN_LONG_MAIN_CATALOG_ORDINAL = 500
        private const val MIN_LONG_MAIN_RESTART_GAP = 300
        private const val MAX_TRAILING_DUPLICATE_BLOCK_CHAPTERS = 180
        private const val MAX_TRAILING_DUPLICATE_RESTART_ORDINAL = 120
        private const val MAX_TRAILING_DUPLICATE_ORDINAL = 180
        private val ORDINAL_PREFIX_PATTERNS = listOf(
            Regex("""^\s*第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*[章节回话卷]\s*"""),
            Regex("""^\s*([0-9０-９]+)\s*[.、]\s*"""),
            Regex("""^\s*([0-9０-９]{1,5})\s+""")
        )
        private val CHINESE_ORDINAL_PATTERN =
            Regex("""第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*([章节回话卷])""")
        private val NUMERIC_PREFIX_PATTERN = Regex("""^\s*[0-9０-９]+\s*[.、]\s*""")
        private const val CHINESE_ORDINAL_CHARACTERS =
            "零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟"
        private val TITLE_KEY_SEPARATOR_PATTERN =
            Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉]+""")
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
    }
}
