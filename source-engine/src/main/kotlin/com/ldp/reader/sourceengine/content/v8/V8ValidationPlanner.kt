package com.ldp.reader.sourceengine.content.v8

class V8ValidationPlanner(
    private val qualityGate: V8ChapterQualityGate = V8ChapterQualityGate()
) {
    fun selectChapters(
        chapters: List<V8ValidationChapter>,
        diagnosticSink: V8DiagnosticSink = V8DiagnosticSink.None,
        readContent: (position: Int, chapter: V8ValidationChapter) -> String
    ): V8ValidationPlan {
        if (chapters.isEmpty()) {
            diagnosticSink.record(v8DiagnosticLine("v8.plan.empty"))
            return V8ValidationPlan.EMPTY
        }

        val skippedByTitle = skippedTitlePositions(chapters)
        val targetPositions = chapters.indices
            .filter { position -> position !in skippedByTitle }
            .toSet()
        val contextPositions = selectContextPositions(chapters.size, skippedByTitle, readContent, chapters)
        val rolesByPosition = LinkedHashMap<Int, String>()
        contextPositions.forEach { position -> rolesByPosition[position] = ROLE_CONTEXT }
        targetPositions.forEach { position -> rolesByPosition.putIfAbsent(position, ROLE_TARGET) }

        diagnosticSink.record(
            v8DiagnosticLine(
                "v8.plan.finish",
                "chapters" to chapters.size,
                "target" to targetPositions.size,
                "context" to contextPositions.size,
                "usableContext" to contextPositions.size,
                "skipped" to skippedByTitle.size
            )
        )

        return V8ValidationPlan(
            analysisPositions = rolesByPosition.keys.sorted(),
            targetPositions = targetPositions,
            contextPositions = contextPositions,
            targetIndexes = targetPositions.map { position -> chapters[position].index }.toSet(),
            contextIndexes = contextPositions.map { position -> chapters[position].index }.toSet(),
            rolesByPosition = rolesByPosition.toMap(),
            rolesByChapterIndex = rolesByPosition.mapKeys { (position, _) -> chapters[position].index },
            usableContext = contextPositions.size
        )
    }

    fun initialTargetIndexes(
        plan: V8ValidationPlan,
        chapters: List<V8ValidationChapter>
    ): Set<Int> {
        if (chapters.isEmpty() || plan.targetIndexes.isEmpty()) return emptySet()
        val selected = LinkedHashSet<Int>()
        val denseStart = (chapters.size - DENSE_TAIL_CHAPTERS).coerceAtLeast(0)
        chapters.drop(denseStart).forEach { chapter ->
            if (chapter.index in plan.targetIndexes) selected.add(chapter.index)
        }
        TAIL_OFFSETS.forEach { oneBasedOffset ->
            val position = chapters.size - oneBasedOffset
            if (position in chapters.indices) {
                val chapterIndex = chapters[position].index
                if (chapterIndex in plan.targetIndexes) selected.add(chapterIndex)
            }
        }
        return selected
    }

    fun expandedTargetIndexes(
        chapters: List<V8ValidationChapter>,
        currentTargetIndexes: Set<Int>,
        marks: List<V8ChapterMarkResult>
    ): Set<Int> {
        if (chapters.isEmpty() || currentTargetIndexes.isEmpty()) return currentTargetIndexes
        val chapterPositionByIndex = chapters
            .mapIndexed { position, chapter -> chapter.index to position }
            .toMap()
        val earliestBadPosition = expansionClusterStartPosition(chapterPositionByIndex, marks)
            ?: return currentTargetIndexes

        val cleanGuardStart = (earliestBadPosition - CLEAN_BOUNDARY_GUARD_CHAPTERS).coerceAtLeast(0)
        val markedStatesByPosition = marks
            .mapNotNull { mark ->
                chapterPositionByIndex[mark.chapterIndex]?.let { position -> position to mark.state }
            }
            .toMap()
        val guardPositions = (cleanGuardStart until earliestBadPosition).toList()
        val hasCleanGuardBeforeBad = guardPositions.isNotEmpty() &&
            guardPositions.all { position -> markedStatesByPosition[position] == V8ChapterMarkState.NORMAL }
        if (hasCleanGuardBeforeBad) return currentTargetIndexes

        val tailDistance = (chapters.size - earliestBadPosition).coerceAtLeast(1)
        val backtrack = tailDistance.coerceIn(MIN_BACKTRACK_CHAPTERS, MAX_BACKTRACK_CHAPTERS)
        val denseStart = (earliestBadPosition - backtrack).coerceAtLeast(0)
        val selected = LinkedHashSet<Int>()
        selected.addAll(currentTargetIndexes)
        for (position in denseStart until chapters.size) {
            selected.add(chapters[position].index)
        }
        return selected
    }

    private fun expansionClusterStartPosition(
        chapterPositionByIndex: Map<Int, Int>,
        marks: List<V8ChapterMarkResult>
    ): Int? {
        val badPositions = marks
            .asSequence()
            .filter { mark -> mark.state.isBadForTail }
            .mapNotNull { mark -> chapterPositionByIndex[mark.chapterIndex] }
            .sorted()
            .toList()
        if (badPositions.isEmpty()) return null

        val clusters = ArrayList<BadCluster>()
        var start = badPositions.first()
        var end = start
        var count = 1
        badPositions.drop(1).forEach { position ->
            if (position - end <= BAD_CLUSTER_MAX_GAP) {
                end = position
                count += 1
            } else {
                clusters += BadCluster(start, end, count)
                start = position
                end = position
                count = 1
            }
        }
        clusters += BadCluster(start, end, count)

        return clusters
            .filter { cluster ->
                cluster.count >= MIN_EXPAND_BAD_CLUSTER_MARKS
            }
            .maxWithOrNull(compareBy<BadCluster> { cluster -> cluster.count }.thenBy { cluster -> cluster.end })
            ?.start
    }

    private fun skippedTitlePositions(chapters: List<V8ValidationChapter>): Set<Int> {
        return chapters
            .withIndex()
            .filter { (_, chapter) ->
                V8CatalogTitleClassifier.shouldSkipBeforeContent(chapter.title)
            }
            .map { (position, _) -> position }
            .toSet()
    }

    private fun selectContextPositions(
        chapterCount: Int,
        skippedByTitle: Set<Int>,
        readContent: (position: Int, chapter: V8ValidationChapter) -> String,
        chapters: List<V8ValidationChapter>
    ): Set<Int> {
        val tailStart = (chapterCount - TAIL_RISK_WINDOW_CHAPTERS).coerceAtLeast(0)
        val endExclusive = tailStart.coerceAtLeast((chapterCount * 7 / 10).coerceAtLeast(1))
        val candidates = LinkedHashSet<Int>()
        evenlySpacedPositions(0, endExclusive, 4).forEach(candidates::add)
        evenlySpacedPositions((endExclusive - 120).coerceAtLeast(0), endExclusive, 8).forEach(candidates::add)
        var offset = 1
        while (offset <= endExclusive && candidates.size < MAX_CONTEXT_CANDIDATES) {
            candidates.add(endExclusive - offset)
            offset *= 2
        }
        val selected = LinkedHashSet<Int>()
        candidates
            .asSequence()
            .filter { position -> position in 0 until chapterCount }
            .filter { position -> position !in skippedByTitle }
            .forEach { position ->
                if (selected.size >= MIN_USABLE_CONTEXT_CHAPTERS) return@forEach
                val chapter = chapters[position]
                val quality = qualityGate.inspect(
                    V8ChapterInput(
                        index = chapter.index,
                        title = chapter.title,
                        content = readContent(position, chapter)
                    )
                )
                if (quality.usableForStory) selected.add(position)
            }
        return selected
    }

    private fun evenlySpacedPositions(startInclusive: Int, endExclusive: Int, count: Int): List<Int> {
        val size = endExclusive - startInclusive
        if (size <= 0) return emptyList()
        if (size <= count) return (startInclusive until endExclusive).toList()
        return (0 until count).map { index ->
            startInclusive + ((size - 1).toLong() * index / (count - 1).coerceAtLeast(1)).toInt()
        }.distinct()
    }

    companion object {
        const val TAIL_RISK_WINDOW_CHAPTERS = 160
        const val MIN_USABLE_CONTEXT_CHAPTERS = 8
        const val ROLE_CONTEXT = "CONTEXT"
        const val ROLE_TARGET = "TARGET"
        private const val DENSE_TAIL_CHAPTERS = 16
        private val TAIL_OFFSETS = intArrayOf(24, 32, 48, 64)
        private const val MIN_BACKTRACK_CHAPTERS = 32
        private const val MAX_BACKTRACK_CHAPTERS = 192
        private const val CLEAN_BOUNDARY_GUARD_CHAPTERS = 8
        private const val MIN_EXPAND_BAD_CLUSTER_MARKS = 2
        private const val BAD_CLUSTER_MAX_GAP = 24
        private const val MAX_CONTEXT_CANDIDATES = 64
    }
}

private data class BadCluster(
    val start: Int,
    val end: Int,
    val count: Int
)

object V8CatalogTitleClassifier {
    fun shouldSkipBeforeContent(title: String): Boolean {
        val compact = compactTitle(title)
        if (compact.isBlank()) return true
        return !isStoryChapterTitle(title)
    }

    fun isStoryChapterTitle(title: String): Boolean {
        val spaced = title
            .replace('\u3000', ' ')
            .replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (spaced.isBlank()) return false
        val compact = compactTitle(spaced)
        return storyChapterPatterns.any { pattern -> pattern.containsMatchIn(compact) } ||
            spacedStoryChapterPatterns.any { pattern -> pattern.containsMatchIn(spaced) }
    }

    private fun compactTitle(title: String): String {
        return title.replace(Regex("""\s+"""), "").trim()
    }

    private val storyChapterPatterns = listOf(
        Regex("""第[0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章节回篇话卷部节]"""),
        Regex("""^[0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章节回篇话卷部节](?:$|[：:、，,.\-\s].*)"""),
        Regex("""^(?:chapter|chap\.?|ch\.?)[0-9０-９]+""", RegexOption.IGNORE_CASE),
        Regex("""^番(?:外)?[0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+"""),
        Regex("""^(?:楔子|序章|引子|序幕|终章|尾声)(?:$|[：:、，,.\-].*)""")
    )

    private val spacedStoryChapterPatterns = listOf(
        Regex("""^\s*[0-9０-９]{1,5}\s*[.、]\s*\S+"""),
        Regex("""^\s*[0-9０-９]{1,5}\s+(?![年月日])\S+"""),
        Regex("""^\s*(?:chapter|chap\.?|ch\.?)\s*[0-9０-９]+""", RegexOption.IGNORE_CASE)
    )
}

data class V8ValidationChapter(
    val index: Int,
    val title: String
)

data class V8ValidationPlan(
    val analysisPositions: List<Int>,
    val targetPositions: Set<Int>,
    val contextPositions: Set<Int>,
    val targetIndexes: Set<Int>,
    val contextIndexes: Set<Int>,
    val rolesByPosition: Map<Int, String>,
    val rolesByChapterIndex: Map<Int, String>,
    val usableContext: Int
) {
    companion object {
        val EMPTY = V8ValidationPlan(
            analysisPositions = emptyList(),
            targetPositions = emptySet(),
            contextPositions = emptySet(),
            targetIndexes = emptySet(),
            contextIndexes = emptySet(),
            rolesByPosition = emptyMap(),
            rolesByChapterIndex = emptyMap(),
            usableContext = 0
        )
    }
}
