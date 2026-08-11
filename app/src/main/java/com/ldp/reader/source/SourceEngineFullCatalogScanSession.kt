package com.ldp.reader.source

import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult

enum class SourceEngineV8ScanPhase {
    RUNNING,
    COMPLETED,
    CACHE_HIT,
    CANCELLED,
    FAILED
}

data class SourceEngineV8ScanProgress(
    val phase: SourceEngineV8ScanPhase,
    val processed: Int,
    val target: Int,
    val wrong: Int = 0
) {
    val running: Boolean
        get() = phase == SourceEngineV8ScanPhase.RUNNING

    val completed: Boolean
        get() = phase == SourceEngineV8ScanPhase.COMPLETED || phase == SourceEngineV8ScanPhase.CACHE_HIT

    val cacheHit: Boolean
        get() = phase == SourceEngineV8ScanPhase.CACHE_HIT
}

/**
 * Stateful seam for a resumable full-catalog validation. The provider owns the network/model
 * work; this class owns bounded batching, covered-target accounting and mark merging.
 */
internal class SourceEngineFullCatalogScanSession(
    orderedTargetIndexes: List<Int>,
    cachedTargetIndexes: Set<Int>,
    cachedMarks: List<V8ChapterMarkResult>,
    private val batchSize: Int
) {
    private val orderedTargets = orderedTargetIndexes.distinct()
    private val targetSet = orderedTargets.toSet()
    private val processedTargets = LinkedHashSet(cachedTargetIndexes.filter { index -> index in targetSet })
    private val marksByChapterIndex = LinkedHashMap<Int, V8ChapterMarkResult>()
    private var phase = if (processedTargets.size == orderedTargets.size) {
        SourceEngineV8ScanPhase.CACHE_HIT
    } else {
        SourceEngineV8ScanPhase.RUNNING
    }

    init {
        require(batchSize > 0) { "batchSize must be positive" }
        cachedMarks.forEach { mark ->
            if (mark.chapterIndex in targetSet) marksByChapterIndex[mark.chapterIndex] = mark
        }
    }

    val progress: SourceEngineV8ScanProgress
        get() = SourceEngineV8ScanProgress(
            phase = phase,
            processed = processedTargets.size,
            target = orderedTargets.size,
            wrong = marksByChapterIndex.values.count { mark -> mark.state.isBadForTail }
        )

    val processedTargetIndexes: Set<Int>
        get() = LinkedHashSet(processedTargets)

    val mergedMarks: List<V8ChapterMarkResult>
        get() = marksByChapterIndex.values.toList()

    fun nextBatch(): Set<Int>? {
        if (phase != SourceEngineV8ScanPhase.RUNNING) return null
        return orderedTargets
            .asSequence()
            .filterNot { index -> index in processedTargets }
            .take(batchSize)
            .toCollection(LinkedHashSet())
            .takeIf { batch -> batch.isNotEmpty() }
    }

    fun acceptBatch(batch: Set<Int>, marks: List<V8ChapterMarkResult>) {
        check(phase == SourceEngineV8ScanPhase.RUNNING) { "scan is not running" }
        val expected = nextBatch().orEmpty()
        require(batch == expected) { "accepted batch must match the next pending batch" }
        marks.forEach { mark ->
            if (mark.chapterIndex in batch) marksByChapterIndex[mark.chapterIndex] = mark
        }
        processedTargets += batch
        if (processedTargets.size == orderedTargets.size) phase = SourceEngineV8ScanPhase.COMPLETED
    }

    fun cancel() {
        if (phase == SourceEngineV8ScanPhase.RUNNING) phase = SourceEngineV8ScanPhase.CANCELLED
    }

    fun fail() {
        if (phase == SourceEngineV8ScanPhase.RUNNING) phase = SourceEngineV8ScanPhase.FAILED
    }
}
