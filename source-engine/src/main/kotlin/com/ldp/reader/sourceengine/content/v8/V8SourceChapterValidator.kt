package com.ldp.reader.sourceengine.content.v8

class V8SourceChapterValidator(
    semanticModel: V8SemanticModel,
    private val config: V8PsbmtConfig = V8PsbmtConfig()
) {
    private val detector = V8PsbmtDetector(config, semanticModel)
    private val semanticCacheStatsProvider = semanticModel as? V8SemanticCacheStatsProvider
    private val qualityGate = V8ChapterQualityGate()

    fun validate(request: V8SourceRunRequest): V8SourceRunResult {
        val startedAtMs = System.currentTimeMillis()
        request.diagnosticSink.record(
            v8DiagnosticLine(
                "v8.run.start",
                "title" to request.title,
                "author" to request.author,
                "source" to request.sourceKey,
                "chapters" to request.chapters.size,
                "markable" to request.markableChapterIndexes.orEmpty().size
            )
        )

        val sorted = request.chapters.sortedBy { chapter -> chapter.index }
        val semanticStatsBefore = semanticCacheStatsProvider?.cacheStats()
        val qualityStartedAtMs = System.currentTimeMillis()
        val qualityByIndex = sorted.associate { chapter -> chapter.index to qualityGate.inspect(chapter) }
        val qualityMs = System.currentTimeMillis() - qualityStartedAtMs
        val markableIndexes = request.markableChapterIndexes ?: sorted.map { chapter -> chapter.index }.toSet()
        val referenceChapters = sorted.filter { chapter ->
            chapter.index in request.contextChapterIndexes &&
                qualityByIndex.getValue(chapter.index).usableForStory
        }
        val marks = ArrayList<V8ChapterMarkResult>()
        var detectorMs = 0L
        var detectorCacheHits = 0

        sorted.forEach { chapter ->
            val quality = qualityByIndex.getValue(chapter.index)
            if (chapter.index !in markableIndexes) {
                return@forEach
            }

            val mark = if (!quality.usableForStory) {
                qualityMark(chapter, quality)
            } else {
                val next = sorted
                    .asSequence()
                    .filter { candidate -> candidate.index > chapter.index }
                    .take(config.maxFutureChapters)
                    .toList()
                val detectorStartedAtMs = System.currentTimeMillis()
                val result = detector.detect(
                    V8PsbmtInput(
                        previousChapters = referenceChapters
                            .filter { previous -> previous.index < chapter.index }
                            .map { previous ->
                                V8ChapterContext(previous.index, previous.title, previous.content)
                            },
                        current = V8ChapterContext(chapter.index, chapter.title, chapter.content),
                        nextChapters = next.map { future ->
                            V8ChapterContext(future.index, future.title, future.content)
                        }
                    )
                )
                detectorMs += System.currentTimeMillis() - detectorStartedAtMs
                if (result.cacheHit) detectorCacheHits += 1
                resultMark(chapter, quality, result)
            }

            marks.add(mark)
        }

        val stabilizeStartedAtMs = System.currentTimeMillis()
        val stableMarks = V8TailBoundarySelector.stabilize(marks)
        val stabilizeMs = System.currentTimeMillis() - stabilizeStartedAtMs
        stableMarks
            .filter { mark -> mark.state != V8ChapterMarkState.NORMAL }
            .forEach { mark ->
                request.diagnosticSink.record(
                    v8DiagnosticLine(
                        "v8.mark.abnormal",
                        "source" to request.sourceKey,
                        "chapter" to mark.chapterIndex,
                        "state" to mark.state,
                        "confidence" to "%.3f".format(mark.confidence),
                        "suggestion" to mark.suggestionState
                    )
                )
            }

        val counts = stableMarks.groupingBy { mark -> mark.state }.eachCount()
        val semanticStats = semanticStatsBefore?.let { before ->
            semanticCacheStatsProvider?.cacheStats()?.deltaSince(before)
        }
        val result = V8SourceRunResult(
            title = request.title,
            author = request.author,
            sourceKey = request.sourceKey,
            marks = stableMarks,
            planningMarks = marks
        )
        val totalMs = System.currentTimeMillis() - startedAtMs
        request.diagnosticSink.record(
            v8DiagnosticLine(
                "v8.perf",
                "source" to request.sourceKey,
                "qualityMs" to qualityMs,
                "detectorMs" to detectorMs,
                "cacheHits" to detectorCacheHits,
                "stabilizeMs" to stabilizeMs,
                "memHits" to semanticStats?.memoryHits,
                "diskHits" to semanticStats?.diskHits,
                "onnxRuns" to semanticStats?.onnxRuns,
                "diskWrites" to semanticStats?.diskWrites,
                "diskEvictions" to semanticStats?.diskEvictions,
                "ms" to totalMs
            )
        )
        request.diagnosticSink.record(
            v8DiagnosticLine(
                "v8.mark.finish",
                "source" to request.sourceKey,
                "normal" to (counts[V8ChapterMarkState.NORMAL] ?: 0),
                "wrong" to (counts[V8ChapterMarkState.WRONG] ?: 0),
                "nonStory" to (counts[V8ChapterMarkState.NON_STORY] ?: 0),
                "badExtraction" to (counts[V8ChapterMarkState.BAD_EXTRACTION] ?: 0),
                "inconclusive" to (counts[V8ChapterMarkState.INCONCLUSIVE] ?: 0),
                "latestObserved" to result.latestObservedOrdinal,
                "latestNormal" to result.latestNormalOrdinal,
                "badTail" to result.firstBadTailOrdinal,
                "qualityMs" to qualityMs,
                "detectorMs" to detectorMs,
                "detectorCacheHits" to detectorCacheHits,
                "stabilizeMs" to stabilizeMs,
                "semanticMemoryHits" to semanticStats?.memoryHits,
                "semanticDiskHits" to semanticStats?.diskHits,
                "semanticOnnxRuns" to semanticStats?.onnxRuns,
                "semanticDiskWrites" to semanticStats?.diskWrites,
                "semanticDiskEvictions" to semanticStats?.diskEvictions,
                "ms" to totalMs
            )
        )
        return result
    }

    private fun qualityMark(chapter: V8ChapterInput, quality: V8ChapterQualityResult): V8ChapterMarkResult {
        val state = when (quality.type) {
            V8ChapterQualityType.NON_STORY -> V8ChapterMarkState.NON_STORY
            V8ChapterQualityType.BAD_EXTRACTION -> V8ChapterMarkState.BAD_EXTRACTION
            V8ChapterQualityType.TOO_SHORT_UNCERTAIN,
            V8ChapterQualityType.MIXED_EXTRACTION_UNCERTAIN -> V8ChapterMarkState.INCONCLUSIVE
            V8ChapterQualityType.CLEAN_STORY,
            V8ChapterQualityType.CLEAN_WITH_TRIM -> V8ChapterMarkState.NORMAL
        }
        return V8ChapterMarkResult(
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            state = state,
            confidence = quality.confidence,
            qualityType = quality.type,
            suggestionState = when (state) {
                V8ChapterMarkState.NON_STORY -> V8NovelStateOutputType.NON_STORY
                V8ChapterMarkState.BAD_EXTRACTION -> V8NovelStateOutputType.BAD_EXTRACTION
                V8ChapterMarkState.INCONCLUSIVE -> V8NovelStateOutputType.UNCERTAIN
                else -> V8NovelStateOutputType.NORMAL
            },
            action = if (state == V8ChapterMarkState.NORMAL) V8CleanAction.KEEP else V8CleanAction.MARK_ONLY,
            reasons = quality.reasons.take(12)
        )
    }

    private fun resultMark(
        chapter: V8ChapterInput,
        quality: V8ChapterQualityResult,
        result: V8PsbmtResult
    ): V8ChapterMarkResult {
        val state = when (result.status) {
            V8PsbmtStatus.NORMAL -> V8ChapterMarkState.NORMAL
            V8PsbmtStatus.WRONG_CONFIRMED -> V8ChapterMarkState.WRONG
            V8PsbmtStatus.SUSPECT_RECHECK_REQUIRED,
            V8PsbmtStatus.INSUFFICIENT_CONTEXT,
            V8PsbmtStatus.SOURCE_QUALITY_PROBLEM -> V8ChapterMarkState.INCONCLUSIVE
        }
        return V8ChapterMarkResult(
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            state = state,
            confidence = result.confidence,
            qualityType = quality.type,
            suggestionState = when (state) {
                V8ChapterMarkState.NORMAL -> V8NovelStateOutputType.NORMAL
                V8ChapterMarkState.WRONG -> V8NovelStateOutputType.POLLUTED_SUFFIX
                else -> V8NovelStateOutputType.UNCERTAIN
            },
            action = when (state) {
                V8ChapterMarkState.NORMAL -> V8CleanAction.KEEP
                V8ChapterMarkState.WRONG -> V8CleanAction.MARK_ONLY
                else -> V8CleanAction.KEEP
            },
            reasons = v8Reasons(result)
        )
    }

    private fun v8Reasons(result: V8PsbmtResult): List<String> {
        return buildList {
            add("v8 status=${result.status} type=${result.type} offset=${result.offset}")
            add("v8 confidence=${"%.4f".format(result.confidence)} ms=${result.ms}")
            if (result.cacheHit) add("v8 detectorCacheHit=true")
            result.evidence["suffixRepeatRatio"]?.let { value -> add("v8 suffixRepeatRatio=$value") }
            result.evidence["localRupture"]?.let { value -> add("v8 localRupture=$value") }
            result.evidence["futureRescue"]?.let { value -> add("v8 futureRescue=$value") }
            result.evidence["fragmentTail"]?.let { value -> add("v8 fragmentTail=$value") }
        }.take(12)
    }
}

data class V8SourceRunRequest(
    val title: String,
    val author: String,
    val sourceKey: String,
    val chapters: List<V8ChapterInput>,
    val markableChapterIndexes: Set<Int>? = null,
    val contextChapterIndexes: Set<Int> = emptySet(),
    val diagnosticSink: V8DiagnosticSink = V8DiagnosticSink.None
)

data class V8SourceRunResult(
    val title: String,
    val author: String,
    val sourceKey: String,
    val marks: List<V8ChapterMarkResult>,
    val planningMarks: List<V8ChapterMarkResult>
) {
    val latestObservedOrdinal: Int
        get() = marks.maxOfOrNull { mark -> mark.chapterIndex + 1 } ?: 0

    val latestNormalOrdinal: Int
        get() = marks
            .filter { mark -> mark.state == V8ChapterMarkState.NORMAL }
            .maxOfOrNull { mark -> mark.chapterIndex + 1 }
            ?: 0

    val firstBadTailOrdinal: Int?
        get() = V8TailBoundarySelector.firstBadTailOrdinal(marks)
}
