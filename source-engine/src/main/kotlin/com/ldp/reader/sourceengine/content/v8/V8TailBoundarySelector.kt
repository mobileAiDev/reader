package com.ldp.reader.sourceengine.content.v8

object V8TailBoundarySelector {
    fun stabilize(marks: List<V8ChapterMarkResult>): List<V8ChapterMarkResult> {
        val boundary = firstCredibleBadTailIndex(marks) ?: return marks.map { mark ->
            if (mark.state == V8ChapterMarkState.WRONG) mark.downgradeIsolatedWrong() else mark
        }
        val boundaryStable = marks.map { mark ->
            if (mark.chapterIndex < boundary && mark.state == V8ChapterMarkState.WRONG) {
                mark.downgradeIsolatedWrong()
            } else {
                mark
            }
        }
        return stabilizeCredibleBadTail(boundaryStable, boundary)
    }

    fun refreshCachedStableMarks(marks: List<V8ChapterMarkResult>): List<V8ChapterMarkResult> {
        val boundary = firstCredibleBadTailIndex(marks) ?: return marks
        return stabilizeCredibleBadTail(marks, boundary)
    }

    fun firstBadTailOrdinal(marks: List<V8ChapterMarkResult>): Int? {
        return firstCredibleBadTailIndex(marks)?.let { index -> index + 1 }
    }

    fun firstCredibleBadTailIndex(marks: List<V8ChapterMarkResult>): Int? {
        val sorted = marks.sortedBy { mark -> mark.chapterIndex }
        val lastIndex = sorted.lastOrNull()?.chapterIndex ?: return null
        return sorted
            .asSequence()
            .filter { mark -> mark.state.isBadForTail }
            .map { mark -> mark.chapterIndex }
            .firstOrNull { index ->
                hasCleanGuard(sorted, index) && hasSustainedBadTail(sorted, index, lastIndex)
            }
    }

    private fun hasCleanGuard(sorted: List<V8ChapterMarkResult>, candidateIndex: Int): Boolean {
        val previous = sorted
            .asSequence()
            .filter { mark -> mark.chapterIndex < candidateIndex }
            .toList()
            .takeLast(CLEAN_GUARD_CHAPTERS)
        if (previous.size < MIN_CLEAN_GUARD_MARKS) return false
        if (previous.any { mark -> mark.state.isBadForTail }) return false
        return previous.count { mark -> mark.state == V8ChapterMarkState.NORMAL } >= MIN_CLEAN_GUARD_NORMALS
    }

    private fun hasSustainedBadTail(
        sorted: List<V8ChapterMarkResult>,
        candidateIndex: Int,
        lastIndex: Int
    ): Boolean {
        val tailMarks = sorted.filter { mark -> mark.chapterIndex >= candidateIndex }
        if (tailMarks.isEmpty()) return false
        val badCount = tailMarks.count { mark -> mark.state.isBadForTail }
        val markedBadRatio = badCount.toDouble() / tailMarks.size
        val nearObservedTail = lastIndex - candidateIndex + 1 <= NEAR_TAIL_CHAPTERS
        val minBadCount = if (nearObservedTail) MIN_NEAR_TAIL_BAD_MARKS else MIN_SUSTAINED_BAD_MARKS
        val minBadRatio = if (nearObservedTail) NEAR_TAIL_BAD_RATIO else SUSTAINED_BAD_RATIO
        return badCount >= minBadCount && markedBadRatio >= minBadRatio
    }

    private fun V8ChapterMarkResult.downgradeIsolatedWrong(): V8ChapterMarkResult {
        return copy(
            state = V8ChapterMarkState.INCONCLUSIVE,
            confidence = confidence.coerceAtMost(0.49),
            suggestionState = V8NovelStateOutputType.UNCERTAIN,
            action = V8CleanAction.KEEP,
            reasons = (reasons + "v8 isolated before credible bad-tail boundary").take(12)
        )
    }

    private fun stabilizeCredibleBadTail(
        marks: List<V8ChapterMarkResult>,
        boundary: Int
    ): List<V8ChapterMarkResult> {
        return marks
    }

    private const val CLEAN_GUARD_CHAPTERS = 8
    private const val MIN_CLEAN_GUARD_MARKS = 6
    private const val MIN_CLEAN_GUARD_NORMALS = 6
    private const val MIN_NEAR_TAIL_BAD_MARKS = 2
    private const val MIN_SUSTAINED_BAD_MARKS = 3
    private const val SUSTAINED_BAD_RATIO = 0.55
    private const val NEAR_TAIL_CHAPTERS = 16
    private const val NEAR_TAIL_BAD_RATIO = 0.34
}
