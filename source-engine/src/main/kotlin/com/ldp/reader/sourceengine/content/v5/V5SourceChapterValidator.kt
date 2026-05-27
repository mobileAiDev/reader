package com.ldp.reader.sourceengine.content.v5

class V5SourceChapterValidator(
    private val tailExpander: V5TailContiguousPollutionExpander = V5TailContiguousPollutionExpander(),
    private val analyzerFactory: () -> NovelPollutionAnalyzer = { NovelPollutionAnalyzer() }
) {
    fun validate(request: V5SourceRunRequest): V5SourceRunResult {
        val startedAtMs = System.currentTimeMillis()
        request.diagnosticSink.record(
            v5DiagnosticLine(
                "v5.run.start",
                "title" to request.title,
                "author" to request.author,
                "source" to request.sourceKey,
                "chapters" to request.chapters.size,
                "seed" to request.seedChapterIndexes.orEmpty().size
            )
        )
        val report = analyzerFactory().analyze(
            title = request.title,
            author = request.author,
            chapters = request.chapters,
            seedChapterIndexes = request.seedChapterIndexes,
            retainChunkScores = request.retainReportChunkScores,
            progress = { stage ->
                request.diagnosticSink.record(v5DiagnosticLine("v5.run.progress", "stage" to stage))
                request.progress?.invoke(stage)
            }
        )
        request.diagnosticSink.record(
            v5DiagnosticLine(
                "v5.run.analyze.finish",
                "source" to request.sourceKey,
                "chunks" to report.chunkCount,
                "quality" to report.qualityResults.size,
                "suggestions" to report.suggestions.size,
                "ms" to (System.currentTimeMillis() - startedAtMs)
            )
        )
        val qualityByIndex = report.qualityResults.associateBy { result -> result.chapterIndex }
        val suggestionByIndex = report.suggestions
            .groupBy { suggestion -> suggestion.chapterIndex }
            .mapValues { (_, suggestions) -> suggestions.maxByOrNull { suggestion -> suggestion.confidence } }
        val latestChapterIndex = request.chapters.maxOfOrNull { chapter -> chapter.index } ?: -1
        val foreignSignalIndexes = request.chapters
            .filter { chapter -> chapter.contentQualitySignal.isForeignContentForV5() }
            .map { chapter -> chapter.index }
        val rawMarks = request.chapters
            .sortedBy { chapter -> chapter.index }
            .map { chapter ->
                val quality = qualityByIndex[chapter.index]
                val suggestion = suggestionByIndex[chapter.index]
                val contentQualitySignal = chapter.contentQualitySignal
                val contentQualityWrong = shouldPromoteForeignContentSignal(
                    chapterIndex = chapter.index,
                    signal = contentQualitySignal,
                    suggestion = suggestion,
                    latestChapterIndex = latestChapterIndex,
                    foreignSignalIndexes = foreignSignalIndexes
                )
                val state = stateFor(quality, suggestion, contentQualityWrong)
                V5ChapterMarkResult(
                    chapterIndex = chapter.index,
                    chapterTitle = quality?.chapterTitle ?: chapter.title,
                    state = state,
                    confidence = confidenceFor(state, quality, suggestion, contentQualitySignal, contentQualityWrong),
                    qualityType = quality?.type,
                    suggestionState = suggestion?.stateType ?: NovelStateOutputType.POLLUTED_RUN.takeIf { contentQualityWrong },
                    action = suggestion?.action ?: CleanAction.MARK_ONLY.takeIf { contentQualityWrong },
                    reasons = ((quality?.reasons ?: emptyList()) +
                        contentQualityReasons(contentQualitySignal, contentQualityWrong) +
                        (suggestion?.reasons ?: emptyList()))
                        .distinct()
                        .take(12)
                    )
            }
        val expansion = tailExpander.expand(rawMarks, report.boundaryBackfillCandidates)
        if (expansion.filledChapterIndexes.isNotEmpty()) {
            request.diagnosticSink.record(
                v5DiagnosticLine(
                    "v5.tail.expand",
                    "source" to request.sourceKey,
                    "start" to expansion.startChapterIndex,
                    "segment" to expansion.segmentSize,
                    "bad" to expansion.badCount,
                    "density" to "%.3f".format(expansion.density),
                    "filled" to expansion.filledChapterIndexes.joinToString(",")
                )
            )
        }
        if (expansion.boundaryFilledChapterIndexes.isNotEmpty()) {
            request.diagnosticSink.record(
                v5DiagnosticLine(
                    "v5.tail.boundary_backfill",
                    "source" to request.sourceKey,
                    "filled" to expansion.boundaryFilledChapterIndexes.joinToString(",")
                )
            )
        }
        if (expansion.gapFilledChapterIndexes.isNotEmpty()) {
            request.diagnosticSink.record(
                v5DiagnosticLine(
                    "v5.tail.internal_gap_fill",
                    "source" to request.sourceKey,
                    "filled" to expansion.gapFilledChapterIndexes.joinToString(",")
                )
            )
        }
        val marks = request.markableChapterIndexes
            ?.let { markable -> expansion.marks.filter { mark -> mark.chapterIndex in markable } }
            ?: expansion.marks
        val counts = marks.groupingBy { mark -> mark.state }.eachCount()
        marks
            .filter { mark -> mark.state != V5ChapterMarkState.NORMAL }
            .forEach { mark ->
                request.diagnosticSink.record(
                    v5DiagnosticLine(
                        "v5.mark.abnormal",
                        "source" to request.sourceKey,
                        "chapter" to mark.chapterIndex,
                        "state" to mark.state,
                        "confidence" to "%.3f".format(mark.confidence),
                        "quality" to mark.qualityType,
                        "suggestion" to mark.suggestionState
                    )
                )
            }
        val result = V5SourceRunResult(
            title = request.title,
            author = request.author,
            sourceKey = request.sourceKey,
            marks = marks,
            report = report
        )
        request.diagnosticSink.record(
            v5DiagnosticLine(
                "v5.mark.finish",
                "source" to request.sourceKey,
                "normal" to (counts[V5ChapterMarkState.NORMAL] ?: 0),
                "wrong" to (counts[V5ChapterMarkState.WRONG] ?: 0),
                "nonStory" to (counts[V5ChapterMarkState.NON_STORY] ?: 0),
                "badExtraction" to (counts[V5ChapterMarkState.BAD_EXTRACTION] ?: 0),
                "inconclusive" to (counts[V5ChapterMarkState.INCONCLUSIVE] ?: 0),
                "latestObserved" to result.latestObservedOrdinal,
                "latestNormal" to result.latestNormalOrdinal,
                "badTail" to result.firstBadTailOrdinal
            )
        )
        return result
    }

    private fun stateFor(
        quality: ChapterQualityResult?,
        suggestion: CleanSuggestion?,
        contentQualityWrong: Boolean
    ): V5ChapterMarkState {
        return when (quality?.type) {
            ChapterQualityType.NON_STORY -> V5ChapterMarkState.NON_STORY
            ChapterQualityType.BAD_EXTRACTION -> V5ChapterMarkState.BAD_EXTRACTION
            else -> if (contentQualityWrong) {
                V5ChapterMarkState.WRONG
            } else {
                when (quality?.type) {
                    ChapterQualityType.TOO_SHORT_UNCERTAIN,
                    ChapterQualityType.MIXED_EXTRACTION_UNCERTAIN -> V5ChapterMarkState.INCONCLUSIVE
                    ChapterQualityType.CLEAN_STORY,
                    ChapterQualityType.CLEAN_WITH_TRIM -> if (suggestion == null) {
                        V5ChapterMarkState.NORMAL
                    } else {
                        V5ChapterMarkState.WRONG
                    }
                    null -> V5ChapterMarkState.INCONCLUSIVE
                    ChapterQualityType.NON_STORY,
                    ChapterQualityType.BAD_EXTRACTION -> error("handled above")
                }
            }
        }
    }

    private fun shouldPromoteForeignContentSignal(
        chapterIndex: Int,
        signal: V5ContentQualitySignal?,
        suggestion: CleanSuggestion?,
        latestChapterIndex: Int,
        foreignSignalIndexes: List<Int>
    ): Boolean {
        if (!signal.isForeignContentForV5()) return false
        if (suggestion != null) return true
        if (latestChapterIndex - chapterIndex in 0 until FOREIGN_CONTENT_TAIL_PROMOTION_WINDOW) return true
        return foreignSignalIndexes.any { otherIndex ->
            otherIndex != chapterIndex &&
                kotlin.math.abs(otherIndex - chapterIndex) <= FOREIGN_CONTENT_CLUSTER_WINDOW
        }
    }

    private fun V5ContentQualitySignal?.isForeignContentForV5(): Boolean {
        return this != null &&
            coherenceScore < FOREIGN_CONTENT_COHERENCE_THRESHOLD &&
            warnings.any { warning -> warning == FOREIGN_CONTENT_WARNING }
    }

    private fun contentQualityReasons(signal: V5ContentQualitySignal?, promoted: Boolean): List<String> {
        val value = signal?.takeIf { promoted && it.isForeignContentForV5() } ?: return emptyList()
        return listOf(
            "content quality foreign-book warning: " +
                "score=${value.qualityScore} " +
                "coherence=${value.coherenceScore} " +
                "cleaned=${value.cleanedLength} " +
                "warnings=${value.warnings.joinToString(",")}"
        )
    }

    private fun contentQualityConfidence(signal: V5ContentQualitySignal?, promoted: Boolean): Double {
        val value = signal?.takeIf { promoted && it.isForeignContentForV5() } ?: return 0.0
        val coherenceGap = (FOREIGN_CONTENT_COHERENCE_THRESHOLD - value.coherenceScore)
            .coerceIn(0, FOREIGN_CONTENT_COHERENCE_THRESHOLD)
        return (0.86 + coherenceGap / 100.0).coerceAtMost(0.98)
    }

    private fun confidenceFor(
        state: V5ChapterMarkState,
        quality: ChapterQualityResult?,
        suggestion: CleanSuggestion?,
        contentQualitySignal: V5ContentQualitySignal?,
        contentQualityWrong: Boolean
    ): Double {
        return when (state) {
            V5ChapterMarkState.WRONG -> maxOf(
                suggestion?.confidence ?: 0.0,
                contentQualityConfidence(contentQualitySignal, contentQualityWrong),
                quality?.confidence ?: 0.0
            )
            else -> quality?.confidence ?: suggestion?.confidence ?: 0.0
        }
    }

    companion object {
        private const val FOREIGN_CONTENT_WARNING = "content-may-belong-to-other-book"
        private const val FOREIGN_CONTENT_COHERENCE_THRESHOLD = 70
        private const val FOREIGN_CONTENT_TAIL_PROMOTION_WINDOW = 24
        private const val FOREIGN_CONTENT_CLUSTER_WINDOW = 2
    }
}

data class V5SourceRunRequest(
    val title: String,
    val author: String,
    val sourceKey: String,
    val chapters: List<ChapterInput>,
    val seedChapterIndexes: Set<Int>? = null,
    val markableChapterIndexes: Set<Int>? = null,
    val retainReportChunkScores: Boolean = false,
    val progress: ((String) -> Unit)? = null,
    val diagnosticSink: V5DiagnosticSink = V5DiagnosticSink.None
)

data class V5SourceRunResult(
    val title: String,
    val author: String,
    val sourceKey: String,
    val marks: List<V5ChapterMarkResult>,
    val report: CleanReport
) {
    val latestObservedOrdinal: Int
        get() = marks.maxOfOrNull { mark -> mark.chapterIndex + 1 } ?: 0

    val latestNormalOrdinal: Int
        get() = marks
            .filter { mark -> mark.state == V5ChapterMarkState.NORMAL }
            .maxOfOrNull { mark -> mark.chapterIndex + 1 }
            ?: 0

    val firstBadTailOrdinal: Int?
        get() {
            if (marks.isEmpty()) return null
            val sorted = marks.sortedBy { mark -> mark.chapterIndex }
            val lastNormalPosition = sorted.indexOfLast { mark -> mark.state == V5ChapterMarkState.NORMAL }
            if (lastNormalPosition < 0 || lastNormalPosition == sorted.lastIndex) return null
            val tail = sorted.drop(lastNormalPosition + 1)
            return if (tail.any { mark -> mark.state.isBadForTail }) {
                tail.first().chapterIndex + 1
            } else {
                null
            }
        }
}

data class V5ChapterMarkResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val state: V5ChapterMarkState,
    val confidence: Double,
    val qualityType: ChapterQualityType?,
    val suggestionState: NovelStateOutputType?,
    val action: CleanAction?,
    val reasons: List<String>
)

enum class V5ChapterMarkState {
    NORMAL,
    WRONG,
    NON_STORY,
    BAD_EXTRACTION,
    INCONCLUSIVE;

    val isBadForTail: Boolean
        get() = this == WRONG || this == NON_STORY || this == BAD_EXTRACTION
}
