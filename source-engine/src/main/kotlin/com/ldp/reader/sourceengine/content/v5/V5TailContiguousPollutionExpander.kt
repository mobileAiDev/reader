package com.ldp.reader.sourceengine.content.v5

class V5TailContiguousPollutionExpander(
    private val minBadMarks: Int = 3,
    private val minBadDensity: Double = 0.45,
    private val maxTailMarks: Int = 160
) {
    fun expand(
        marks: List<V5ChapterMarkResult>,
        boundaryCandidates: List<V5BoundaryBackfillCandidate> = emptyList()
    ): V5TailExpansionResult {
        if (marks.size < minBadMarks) return V5TailExpansionResult(marks)
        val sorted = marks.sortedBy { mark -> mark.chapterIndex }
        val tail = sorted.takeLast(maxTailMarks)
        val candidate = tailClusterCandidate(tail)
        val forwardExpanded = if (candidate == null) {
            sorted
        } else {
            sorted.map { mark ->
                if (mark.chapterIndex >= candidate.startChapterIndex && !mark.state.isBadForTail) {
                    mark.copy(
                        state = V5ChapterMarkState.WRONG,
                        confidence = mark.confidence.coerceAtLeast(PROPAGATED_CONFIDENCE),
                        suggestionState = mark.suggestionState ?: NovelStateOutputType.POLLUTED_RUN,
                        action = mark.action ?: CleanAction.MARK_ONLY,
                        reasons = (
                            mark.reasons +
                                "tail contiguous pollution propagated from dense bad marks " +
                                "bad=${candidate.badCount}/${candidate.segmentSize}"
                            )
                            .distinct()
                            .take(12)
                    )
                } else {
                    mark
                }
            }
        }

        val filled = forwardExpanded
            .zip(sorted)
            .filter { (new, old) -> new.state != old.state }
            .map { (new, _) -> new.chapterIndex }
        val backwardBoundaryExpanded = backfillBoundaryCandidates(forwardExpanded, boundaryCandidates)
        val boundaryExpanded = fillForwardFragmentedBoundaryCandidates(backwardBoundaryExpanded, boundaryCandidates)
        val boundaryFilled = boundaryExpanded
            .zip(forwardExpanded)
            .filter { (new, old) -> new.state != old.state }
            .map { (new, _) -> new.chapterIndex }
        val gapExpanded = fillInternalWrongGaps(boundaryExpanded)
        val gapFilled = gapExpanded
            .zip(boundaryExpanded)
            .filter { (new, old) -> new.state != old.state }
            .map { (new, _) -> new.chapterIndex }
        return V5TailExpansionResult(
            marks = gapExpanded,
            startChapterIndex = candidate?.startChapterIndex,
            segmentSize = candidate?.segmentSize ?: 0,
            badCount = candidate?.badCount ?: 0,
            density = candidate?.density ?: 0.0,
            filledChapterIndexes = filled,
            boundaryFilledChapterIndexes = boundaryFilled,
            gapFilledChapterIndexes = gapFilled
        )
    }

    private fun fillInternalWrongGaps(marks: List<V5ChapterMarkResult>): List<V5ChapterMarkResult> {
        val sorted = marks.sortedBy { mark -> mark.chapterIndex }
        if (sorted.size < 3) return marks
        val fillIndexes = linkedSetOf<Int>()
        var offset = 0
        while (offset < sorted.size) {
            if (sorted[offset].state == V5ChapterMarkState.NORMAL) {
                val start = offset
                while (offset < sorted.size && sorted[offset].state == V5ChapterMarkState.NORMAL) offset += 1
                val end = offset - 1
                val gapSize = end - start + 1
                if (gapSize in 1..MAX_INTERNAL_GAP_FILL_MARKS) {
                    val leftRun = badRunBefore(sorted, start)
                    val rightRun = badRunAfter(sorted, end)
                    val gap = sorted.subList(start, end + 1)
                    if (shouldFillInternalGap(leftRun, rightRun, gapSize)) {
                        gap.forEach { mark -> fillIndexes.add(mark.chapterIndex) }
                    }
                }
            } else {
                offset += 1
            }
        }
        if (fillIndexes.isEmpty()) return marks
        return marks.map { mark ->
            if (mark.chapterIndex !in fillIndexes) return@map mark
            mark.copy(
                state = V5ChapterMarkState.WRONG,
                confidence = mark.confidence.coerceAtLeast(INTERNAL_GAP_FILL_CONFIDENCE),
                suggestionState = mark.suggestionState ?: NovelStateOutputType.POLLUTED_RUN,
                action = mark.action ?: CleanAction.MARK_ONLY,
                reasons = (
                    mark.reasons +
                        "internal pollution gap filled between neighboring wrong marks"
                    )
                    .distinct()
                    .take(12)
            )
        }
    }

    private fun fillForwardFragmentedBoundaryCandidates(
        marks: List<V5ChapterMarkResult>,
        boundaryCandidates: List<V5BoundaryBackfillCandidate>
    ): List<V5ChapterMarkResult> {
        val candidatesByIndex = boundaryCandidates
            .filter { candidate -> candidate.isStrongShortFragmentedCandidate() }
            .associateBy { candidate -> candidate.chapterIndex }
        if (candidatesByIndex.isEmpty()) return marks
        val sorted = marks.sortedBy { mark -> mark.chapterIndex }
        val fillByIndex = LinkedHashMap<Int, V5BoundaryBackfillCandidate>()
        sorted.forEachIndexed { offset, mark ->
            if (!mark.state.isBadForTail || !mark.hasShortFragmentedEvidence()) return@forEachIndexed
            var cursor = offset + 1
            var filledSteps = 0
            while (cursor < sorted.size && filledSteps < MAX_BOUNDARY_CHAIN_FORWARD_DISTANCE) {
                val next = sorted[cursor]
                if (next.state.isBadForTail) {
                    cursor += 1
                    continue
                }
                val candidate = candidatesByIndex[next.chapterIndex] ?: break
                fillByIndex.putIfAbsent(next.chapterIndex, candidate)
                filledSteps += 1
                cursor += 1
            }
        }
        if (fillByIndex.isEmpty()) return marks
        return marks.map { mark ->
            val candidate = fillByIndex[mark.chapterIndex] ?: return@map mark
            mark.copy(
                state = V5ChapterMarkState.WRONG,
                confidence = candidate.confidence.coerceAtLeast(BOUNDARY_BACKFILL_CONFIDENCE),
                suggestionState = candidate.stateType,
                action = candidate.action,
                reasons = (
                    mark.reasons +
                        candidate.reasons +
                        "short fragmented tail candidate chained from previous wrong mark"
                    )
                    .distinct()
                    .take(12)
            )
        }
    }

    private fun V5ChapterMarkResult.hasShortFragmentedEvidence(): Boolean {
        return reasons.any { reason ->
            reason.contains(V5_SHORT_FRAGMENTED_FULL_CHAPTER_REASON) ||
                reason.contains(V5_SHORT_FRAGMENTED_SEGMENT_REASON)
        }
    }

    private fun V5BoundaryBackfillCandidate.isStrongShortFragmentedCandidate(): Boolean {
        if (confidence < MIN_FORWARD_FRAGMENTED_CANDIDATE_CONFIDENCE) return false
        return reasons.any { reason ->
            reason.contains(V5_SHORT_FRAGMENTED_FULL_CHAPTER_REASON) ||
                reason.contains(V5_SHORT_FRAGMENTED_SEGMENT_REASON)
        }
    }

    private fun shouldFillInternalGap(
        leftRun: List<V5ChapterMarkResult>,
        rightRun: List<V5ChapterMarkResult>,
        gapSize: Int
    ): Boolean {
        if (leftRun.isEmpty() || rightRun.isEmpty()) return false
        val leftEdge = leftRun.last()
        val rightEdge = rightRun.first()
        val chapterDistance = rightEdge.chapterIndex - leftEdge.chapterIndex
        val fragmentedBridgeSupport =
            gapSize == 1 &&
            chapterDistance <= gapSize + 2 &&
                leftRun.size + rightRun.size >= 2 &&
                listOf(leftEdge, rightEdge).any { mark ->
                    mark.reasons.any { reason ->
                        reason.contains(V5_SHORT_FRAGMENTED_FULL_CHAPTER_REASON) ||
                            reason.contains(V5_SHORT_FRAGMENTED_SEGMENT_REASON)
                    }
                }
        return fragmentedBridgeSupport
    }

    private fun badRunBefore(
        marks: List<V5ChapterMarkResult>,
        startOffset: Int
    ): List<V5ChapterMarkResult> {
        val result = ArrayList<V5ChapterMarkResult>()
        var offset = startOffset - 1
        while (offset >= 0 && marks[offset].state.isBadForTail) {
            result.add(marks[offset])
            offset -= 1
        }
        return result.asReversed()
    }

    private fun badRunAfter(
        marks: List<V5ChapterMarkResult>,
        endOffset: Int
    ): List<V5ChapterMarkResult> {
        val result = ArrayList<V5ChapterMarkResult>()
        var offset = endOffset + 1
        while (offset < marks.size && marks[offset].state.isBadForTail) {
            result.add(marks[offset])
            offset += 1
        }
        return result
    }

    private fun backfillBoundaryCandidates(
        marks: List<V5ChapterMarkResult>,
        boundaryCandidates: List<V5BoundaryBackfillCandidate>
    ): List<V5ChapterMarkResult> {
        if (boundaryCandidates.isEmpty()) return marks
        val candidatesByIndex = boundaryCandidates
            .filter { candidate -> candidate.tailBackfillEligible }
            .associateBy { candidate -> candidate.chapterIndex }
        if (candidatesByIndex.isEmpty()) return marks
        val clusterStarts = confirmedClusterStarts(marks)
        if (clusterStarts.isEmpty()) return marks
        val candidateIndexes = candidatesByIndex.keys
        val chainedStarts = connectedBoundaryBackfillStarts(marks, clusterStarts, candidateIndexes)
        return marks.map { mark ->
            val boundaryCandidate = candidatesByIndex[mark.chapterIndex]
            if (mark.state.isBadForTail || boundaryCandidate == null) return@map mark
            val supportingStart = chainedStarts[mark.chapterIndex] ?: clusterStarts.firstOrNull { start ->
                val distance = mark.chapterIndex - start.chapterIndex
                distance in -MAX_BOUNDARY_BACKFILL_DISTANCE..MAX_BOUNDARY_FORWARD_FILL_DISTANCE &&
                    noCleanBarrierBetween(marks, mark.chapterIndex, start.chapterIndex, candidateIndexes)
            } ?: return@map mark
            mark.copy(
                state = V5ChapterMarkState.WRONG,
                confidence = boundaryCandidate.confidence.coerceAtLeast(BOUNDARY_BACKFILL_CONFIDENCE),
                suggestionState = boundaryCandidate.stateType,
                action = boundaryCandidate.action,
                reasons = (
                    mark.reasons +
                        boundaryCandidate.reasons +
                        "boundary backfilled from following wrong cluster start=${supportingStart.chapterIndex}"
                    )
                    .distinct()
                    .take(12)
            )
        }
    }

    private fun connectedBoundaryBackfillStarts(
        marks: List<V5ChapterMarkResult>,
        clusterStarts: List<V5ChapterMarkResult>,
        boundaryCandidateIndexes: Set<Int>
    ): Map<Int, V5ChapterMarkResult> {
        val sorted = marks.sortedBy { mark -> mark.chapterIndex }
        val offsetsByIndex = sorted
            .withIndex()
            .associate { (offset, mark) -> mark.chapterIndex to offset }
        val result = LinkedHashMap<Int, V5ChapterMarkResult>()
        clusterStarts.forEach { start ->
            var offset = offsetsByIndex[start.chapterIndex]?.minus(1) ?: return@forEach
            var candidateSteps = 0
            while (offset >= 0 && candidateSteps < MAX_BOUNDARY_CHAIN_BACKFILL_DISTANCE) {
                val mark = sorted[offset]
                if (mark.state.isBadForTail) {
                    offset -= 1
                    continue
                }
                if (mark.chapterIndex !in boundaryCandidateIndexes) break
                if (!noCleanBarrierBetween(sorted, mark.chapterIndex, start.chapterIndex, boundaryCandidateIndexes)) break
                result.putIfAbsent(mark.chapterIndex, start)
                candidateSteps += 1
                offset -= 1
            }
        }
        return result
    }

    private fun confirmedClusterStarts(marks: List<V5ChapterMarkResult>): List<V5ChapterMarkResult> {
        return marks.filter { mark ->
            if (!mark.state.isBadForTail) return@filter false
            val window = marks.filter { candidate ->
                candidate.chapterIndex >= mark.chapterIndex &&
                    candidate.chapterIndex - mark.chapterIndex <= FOLLOWING_CLUSTER_SPAN
            }
            window.count { candidate -> candidate.state.isBadForTail } >= MIN_FOLLOWING_BAD_MARKS
        }
    }

    private fun noCleanBarrierBetween(
        marks: List<V5ChapterMarkResult>,
        candidateIndex: Int,
        clusterStartIndex: Int,
        boundaryCandidateIndexes: Set<Int>
    ): Boolean {
        val low = minOf(candidateIndex, clusterStartIndex)
        val high = maxOf(candidateIndex, clusterStartIndex)
        val marksByIndex = marks.associateBy { mark -> mark.chapterIndex }
        return ((low + 1) until high).all { index ->
            val mark = marksByIndex[index] ?: return@all false
            mark.state.isBadForTail || index in boundaryCandidateIndexes
        }
    }

    private fun tailClusterCandidate(tail: List<V5ChapterMarkResult>): TailExpansionCandidate? {
        val lastBadOffset = tail.indexOfLast { mark -> mark.state.isBadForTail }
        if (lastBadOffset < 0 || lastBadOffset < tail.lastIndex - MAX_TRAILING_NORMAL_MARKS) return null

        var startOffset = lastBadOffset
        var consecutiveNormalGap = 0
        for (offset in lastBadOffset downTo 0) {
            val mark = tail[offset]
            if (mark.state.isBadForTail) {
                startOffset = offset
                consecutiveNormalGap = 0
            } else {
                consecutiveNormalGap += 1
                if (consecutiveNormalGap > MAX_INTERNAL_NORMAL_GAP) break
            }
        }

        val segment = tail.subList(startOffset, tail.size)
        val badCount = segment.count { mark -> mark.state.isBadForTail }
        val density = badCount.toDouble() / segment.size
        return if (badCount >= minBadMarks && density >= minBadDensity) {
            TailExpansionCandidate(segment.first().chapterIndex, segment.size, badCount, density)
        } else {
            null
        }
    }

    private data class TailExpansionCandidate(
        val startChapterIndex: Int,
        val segmentSize: Int,
        val badCount: Int,
        val density: Double
    )

    private companion object {
        private const val MAX_TRAILING_NORMAL_MARKS = 2
        private const val MAX_INTERNAL_NORMAL_GAP = 4
        private const val PROPAGATED_CONFIDENCE = 0.70
        private const val BOUNDARY_BACKFILL_CONFIDENCE = 0.70
        private const val INTERNAL_GAP_FILL_CONFIDENCE = 0.70
        private const val MAX_BOUNDARY_BACKFILL_DISTANCE = 4
        private const val MAX_BOUNDARY_FORWARD_FILL_DISTANCE = 4
        private const val MAX_BOUNDARY_CHAIN_BACKFILL_DISTANCE = 24
        private const val MAX_BOUNDARY_CHAIN_FORWARD_DISTANCE = 24
        private const val FOLLOWING_CLUSTER_SPAN = 8
        private const val MIN_FOLLOWING_BAD_MARKS = 2
        private const val MAX_INTERNAL_GAP_FILL_MARKS = 2
        private const val MIN_FORWARD_FRAGMENTED_CANDIDATE_CONFIDENCE = 0.86
    }
}

data class V5TailExpansionResult(
    val marks: List<V5ChapterMarkResult>,
    val startChapterIndex: Int? = null,
    val segmentSize: Int = 0,
    val badCount: Int = 0,
    val density: Double = 0.0,
    val filledChapterIndexes: List<Int> = emptyList(),
    val boundaryFilledChapterIndexes: List<Int> = emptyList(),
    val gapFilledChapterIndexes: List<Int> = emptyList()
)
