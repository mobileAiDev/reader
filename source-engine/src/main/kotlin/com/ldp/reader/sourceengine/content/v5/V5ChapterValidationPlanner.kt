package com.ldp.reader.sourceengine.content.v5

class V5ChapterValidationPlanner(
    private val qualityGate: ChapterQualityGate = ChapterQualityGate()
) {
    fun selectChapters(
        chapters: List<V5ValidationChapter>,
        diagnosticSink: V5DiagnosticSink = V5DiagnosticSink.None,
        readContent: (position: Int, chapter: V5ValidationChapter) -> String
    ): V5ChapterValidationPlan {
        val chapterCount = chapters.size
        if (chapterCount <= 0) {
            val diagnostics = ArrayList<String>()
            diagnostics.emitV5Diagnostic(diagnosticSink, "v5.plan.empty")
            return V5ChapterValidationPlan(
                analysisPositions = emptyList(),
                targetPositions = emptySet(),
                contextPositions = emptySet(),
                targetIndexes = emptySet(),
                contextIndexes = emptySet(),
                rolesByPosition = emptyMap(),
                rolesByChapterIndex = emptyMap(),
                diagnostics = diagnostics,
                usableContext = 0
            )
        }
        val tailStart = tailTargetStart(chapterCount)
        val diagnostics = ArrayList<String>()
        diagnostics.emitV5Diagnostic(
            diagnosticSink,
            "v5.plan.start",
            "chapters" to chapterCount,
            "tailStart" to tailStart,
            "tailRiskWindow" to (chapterCount - tailStart)
        )
        val storyChapterTitleCount = chapters.count { chapter ->
            V5CatalogTitleClassifier.isStoryChapterTitle(chapter.title)
        }
        val requireStoryChapterTitle = storyChapterTitleCount >= MIN_CHAPTER_TITLE_GATE_MATCHES &&
            storyChapterTitleCount * 100 >= chapterCount * CHAPTER_TITLE_GATE_PERCENT
        diagnostics.emitV5Diagnostic(
            diagnosticSink,
            "v5.plan.title_gate",
            "storyLike" to storyChapterTitleCount,
            "chapters" to chapterCount,
            "required" to requireStoryChapterTitle
        )
        val skippedByTitle = chapters
            .withIndex()
            .filter { (_, chapter) ->
                V5CatalogTitleClassifier.shouldSkipBeforeContent(
                    title = chapter.title,
                    requireStoryChapterTitle = requireStoryChapterTitle
                )
            }
            .map { (position, _) -> position }
            .toSet()
        if (skippedByTitle.isNotEmpty()) {
            diagnostics.emitV5Diagnostic(
                diagnosticSink,
                "v5.plan.title_skip",
                "count" to skippedByTitle.size,
                "positions" to skippedByTitle.sorted().joinToString(","),
                "indexes" to skippedByTitle.sorted().map { position -> chapters[position].index }.joinToString(",")
            )
        }
        val targetRolesByPosition = selectTargetProbePositions(chapterCount, tailStart)
            .filterKeys { position -> position !in skippedByTitle }
            .toMutableMap()
        val baseTargetPositions = targetRolesByPosition.keys.toList()
        if (tailStart > 0) {
            baseTargetPositions
                .flatMap { center -> ((center - TARGET_NEIGHBOR_RADIUS)..(center + TARGET_NEIGHBOR_RADIUS)).toList() }
                .filter { position ->
                    position in 0 until chapterCount &&
                        position !in skippedByTitle
                }
                .forEach { position -> targetRolesByPosition.putIfAbsent(position, ROLE_TARGET_NEIGHBOR) }
        }
        val targetPositions = targetRolesByPosition.keys.sorted()
        val targetPositionSet = targetPositions.toSet()
        val nearContextStart = (tailStart - NEAR_CONTEXT_SPAN).coerceAtLeast(0)
        val nearContextPositions = evenlySpacedPositions(nearContextStart, tailStart, NEAR_CONTEXT_PROBE_COUNT)
            .filter { position -> position !in skippedByTitle }
        val midContextStart = (chapterCount * 35 / 100).coerceAtMost(nearContextStart)
        val midContextPositions = evenlySpacedPositions(midContextStart, nearContextStart, MID_CONTEXT_PROBE_COUNT)
            .filter { position -> position !in skippedByTitle }
        val longAnchorPositions = evenlySpacedPositions(0, midContextStart, LONG_ANCHOR_PROBE_COUNT)
            .filter { position -> position !in skippedByTitle }

        val rolesByPosition = LinkedHashMap<Int, String>()
        longAnchorPositions.forEach { position -> rolesByPosition[position] = ROLE_LONG_ANCHOR }
        midContextPositions.forEach { position -> rolesByPosition[position] = ROLE_MID_CONTEXT }
        nearContextPositions.forEach { position -> rolesByPosition[position] = ROLE_NEAR_CONTEXT }
        targetRolesByPosition.forEach { (position, role) -> rolesByPosition[position] = role }
        if (tailStart == 0) {
            targetPositions
                .flatMap { center ->
                    ((center - SPARSE_TARGET_CONTEXT_RADIUS)..(center + SPARSE_TARGET_CONTEXT_RADIUS)).toList()
                }
                .filter { position ->
                    position in 0 until chapterCount &&
                        position !in targetPositionSet &&
                        position !in skippedByTitle
                }
                .forEach { position -> rolesByPosition.putIfAbsent(position, ROLE_TARGET_NEIGHBOR_CONTEXT) }
        }
        diagnostics.emitV5Diagnostic(
            diagnosticSink,
            "v5.plan.targets",
            "count" to targetPositionSet.size,
            "positions" to targetPositions.joinToString(","),
            "indexes" to targetPositionSet.map { position -> chapters[position].index }.joinToString(",")
        )
        val contextIndexes = rolesByPosition
            .filterKeys { position -> position !in targetPositionSet }
            .map { (position, _) -> chapters[position].index }
            .toMutableSet()
        var usableContext = usableContextCount(chapters, contextIndexes, readContent)
        diagnostics.emitV5Diagnostic(
            diagnosticSink,
            "v5.plan.context.initial",
            "long" to longAnchorPositions.size,
            "mid" to midContextPositions.size,
            "near" to nearContextPositions.size,
            "targetNeighbors" to rolesByPosition.values.count { role -> role == ROLE_TARGET_NEIGHBOR },
            "context" to contextIndexes.size,
            "usable" to usableContext
        )
        val selectedPositions = rolesByPosition.keys.toMutableSet()
        val backfill = rawContextBackfillPositions(
            chapterCount = chapterCount,
            tailStart = tailStart,
            excludedPositions = selectedPositions,
            targetPositions = targetPositionSet,
            skippedPositions = skippedByTitle,
            maxAttempts = MAX_CONTEXT_BACKFILL_ATTEMPTS
        )
        diagnostics.emitV5Diagnostic(
            diagnosticSink,
            "v5.plan.backfill.start",
            "usableContext" to usableContext,
            "candidates" to backfill.size
        )
        for (position in backfill) {
            val chapter = chapters[position]
            val quality = inspect(chapter, readContent(position, chapter))
            diagnostics.emitV5Diagnostic(
                diagnosticSink,
                "v5.plan.backfill.probe",
                "position" to position,
                "chapter" to chapter.index,
                "quality" to quality.type,
                "cleanChars" to quality.metrics.cleanedChars
            )
            if (quality.usableForStory) {
                rolesByPosition[position] = ROLE_MEMORY_BACKFILL
                contextIndexes.add(chapter.index)
                usableContext += 1
                diagnostics.emitV5Diagnostic(
                    diagnosticSink,
                    "v5.plan.backfill.accept",
                    "chapter" to chapter.index,
                    "usableContext" to usableContext
                )
                if (usableContext >= MIN_USABLE_CONTEXT_CHAPTERS) break
            }
        }
        diagnostics.emitV5Diagnostic(
            diagnosticSink,
            "v5.plan.backfill.finish",
            "usableContext" to usableContext,
            "analysis" to rolesByPosition.size
        )

        val selected = rolesByPosition.keys.sorted()
        val targetIndexes = targetPositionSet.map { position -> chapters[position].index }.toSet()
        val rolesByChapterIndex = selected.associate { position ->
            chapters[position].index to rolesByPosition.getValue(position)
        }
        diagnostics.emitV5Diagnostic(
            diagnosticSink,
            "v5.plan.finish",
            "analysis" to selected.size,
            "target" to targetIndexes.size,
            "context" to contextIndexes.size,
            "usableContext" to usableContext
        )
        return V5ChapterValidationPlan(
            analysisPositions = selected,
            targetPositions = targetPositionSet,
            contextPositions = selected.filter { position -> position !in targetPositionSet }.toSet(),
            targetIndexes = targetIndexes,
            contextIndexes = contextIndexes,
            rolesByPosition = rolesByPosition.toMap(),
            rolesByChapterIndex = rolesByChapterIndex,
            diagnostics = diagnostics,
            usableContext = usableContext
        )
    }

    private fun selectTargetProbePositions(chapterCount: Int, tailStart: Int): Map<Int, String> {
        val selected = LinkedHashMap<Int, String>()
        if (tailStart > 0) {
            (tailStart until chapterCount).forEach { position -> selected[position] = ROLE_TARGET_TAIL }
        } else {
            selectSparseTailProbePositions(chapterCount, tailStart)
                .forEach { position -> selected[position] = ROLE_TARGET_TAIL }
        }

        var offset = TARGET_EXTENDED_MIN_OFFSET
        while (offset <= chapterCount) {
            val position = chapterCount - offset
            if (position >= 0) selected.putIfAbsent(position, ROLE_TARGET_EXTENDED)
            offset *= 2
        }
        val recentStart = (chapterCount - TARGET_RECENT_CHAPTERS).coerceAtLeast(tailStart)
        (recentStart until chapterCount).forEach { position -> selected[position] = ROLE_TARGET_RECENT }
        return selected.toSortedMap()
    }

    private fun tailTargetStart(chapterCount: Int): Int {
        if (chapterCount <= CONTEXT_RESERVE_CHAPTERS + 1) return 0
        return (chapterCount - TAIL_RISK_WINDOW_CHAPTERS - TAIL_BOUNDARY_BACKTRACK_CHAPTERS)
            .coerceAtLeast(CONTEXT_RESERVE_CHAPTERS)
    }

    private fun selectSparseTailProbePositions(chapterCount: Int, tailStart: Int): List<Int> {
        val riskWindowSize = chapterCount - tailStart
        val offsets = ArrayList<Int>()
        var offset = 1
        while (offset <= riskWindowSize) {
            offsets.add(offset)
            offset *= 2
        }
        if (chapterCount > MIN_USABLE_CONTEXT_CHAPTERS) offsets.add(MIN_USABLE_CONTEXT_CHAPTERS)
        return offsets
            .filter { oneBasedOffset -> oneBasedOffset in 1..chapterCount }
            .distinct()
            .sorted()
            .map { oneBasedOffset -> chapterCount - oneBasedOffset }
    }

    private fun rawContextBackfillPositions(
        chapterCount: Int,
        tailStart: Int,
        excludedPositions: Set<Int>,
        targetPositions: Set<Int>,
        skippedPositions: Set<Int>,
        maxAttempts: Int
    ): List<Int> {
        if (chapterCount <= 0 || maxAttempts <= 0) return emptyList()
        val endExclusive = tailStart.takeIf { value -> value > 0 } ?: (chapterCount * 7 / 10).coerceAtLeast(1)
        val selected = LinkedHashSet<Int>()
        fun add(position: Int) {
            if (position in 0 until endExclusive &&
                position !in excludedPositions &&
                position !in targetPositions &&
                position !in skippedPositions
            ) {
                selected.add(position)
            }
        }

        var offset = 1
        while (offset <= endExclusive && selected.size < maxAttempts / 2) {
            add(endExclusive - offset)
            offset *= 2
        }
        val nearCount = minOf(24, maxAttempts)
        val nearStart = (endExclusive - nearCount * 3).coerceAtLeast(0)
        evenlySpacedPositions(nearStart, endExclusive, nearCount).asReversed().forEach(::add)
        evenlySpacedPositions(0, endExclusive, maxAttempts).forEach(::add)
        return selected.take(maxAttempts)
    }

    private fun usableContextCount(
        chapters: List<V5ValidationChapter>,
        contextIndexes: Set<Int>,
        readContent: (position: Int, chapter: V5ValidationChapter) -> String
    ): Int {
        return chapters
            .asSequence()
            .withIndex()
            .filter { (_, chapter) -> chapter.index in contextIndexes }
            .count { (position, chapter) -> inspect(chapter, readContent(position, chapter)).usableForStory }
    }

    private fun inspect(chapter: V5ValidationChapter, content: String): ChapterQualityResult {
        return qualityGate.inspect(
            ChapterInput(
                index = chapter.index,
                title = chapter.title,
                content = content
            )
        )
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
        const val TAIL_BOUNDARY_BACKTRACK_CHAPTERS = 2
        const val TARGET_RECENT_CHAPTERS = 2
        const val TARGET_NEIGHBOR_RADIUS = 2
        const val SPARSE_TARGET_CONTEXT_RADIUS = 1
        const val TARGET_EXTENDED_MIN_OFFSET = 256
        const val NEAR_CONTEXT_SPAN = 300
        const val NEAR_CONTEXT_PROBE_COUNT = 8
        const val MID_CONTEXT_PROBE_COUNT = 2
        const val LONG_ANCHOR_PROBE_COUNT = 1
        const val MIN_USABLE_CONTEXT_CHAPTERS = 8
        const val CONTEXT_RESERVE_CHAPTERS = 32
        const val MAX_CONTEXT_BACKFILL_ATTEMPTS = 256
        const val MIN_CHAPTER_TITLE_GATE_MATCHES = 8
        const val CHAPTER_TITLE_GATE_PERCENT = 50

        const val ROLE_LONG_ANCHOR = "LONG_ANCHOR"
        const val ROLE_MID_CONTEXT = "MID_CONTEXT"
        const val ROLE_NEAR_CONTEXT = "NEAR_CONTEXT"
        const val ROLE_TARGET_RECENT = "TARGET_RECENT"
        const val ROLE_TARGET_TAIL = "TARGET_TAIL"
        const val ROLE_TARGET_EXTENDED = "TARGET_EXTENDED"
        const val ROLE_TARGET_NEIGHBOR = "TARGET_NEIGHBOR"
        const val ROLE_TARGET_NEIGHBOR_CONTEXT = "TARGET_NEIGHBOR_CONTEXT"
        const val ROLE_MEMORY_BACKFILL = "MEMORY_BACKFILL"
    }
}

data class V5ValidationChapter(
    val index: Int,
    val title: String
)

data class V5ChapterValidationPlan(
    val analysisPositions: List<Int>,
    val targetPositions: Set<Int>,
    val contextPositions: Set<Int>,
    val targetIndexes: Set<Int>,
    val contextIndexes: Set<Int>,
    val rolesByPosition: Map<Int, String>,
    val rolesByChapterIndex: Map<Int, String>,
    val diagnostics: List<String>,
    val usableContext: Int
)
