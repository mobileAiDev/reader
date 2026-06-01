package com.ldp.reader.sourceengine.content.v8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V8TailBoundarySelectorTest {
    @Test
    fun downgradesIsolatedWrongBeforeCredibleBadTailBoundary() {
        val marks = (20..25).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            listOf(mark(26, V8ChapterMarkState.WRONG)) +
            (27..78).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            (79..83).map { index -> mark(index, V8ChapterMarkState.WRONG) }

        val stable = V8TailBoundarySelector.stabilize(marks)

        assertEquals(V8ChapterMarkState.INCONCLUSIVE, stable.single { mark -> mark.chapterIndex == 26 }.state)
        assertTrue(stable.single { mark -> mark.chapterIndex == 26 }
            .reasons.any { reason -> reason.contains("isolated before credible bad-tail boundary") })
        assertEquals(V8ChapterMarkState.WRONG, stable.single { mark -> mark.chapterIndex == 79 }.state)
        assertEquals(80, V8TailBoundarySelector.firstBadTailOrdinal(stable))
    }

    @Test
    fun removesWrongWhenNoCredibleBadTailBoundaryExists() {
        val marks = (10..17).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            listOf(mark(18, V8ChapterMarkState.WRONG)) +
            (19..40).map { index -> mark(index, V8ChapterMarkState.NORMAL) }

        val stable = V8TailBoundarySelector.stabilize(marks)

        assertEquals(V8ChapterMarkState.INCONCLUSIVE, stable.single { mark -> mark.chapterIndex == 18 }.state)
        assertEquals(null, V8TailBoundarySelector.firstBadTailOrdinal(stable))
    }

    @Test
    fun downgradesSingleNearTailWrongWhenCleanGuardExists() {
        val marks = (90..97).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            listOf(mark(98, V8ChapterMarkState.WRONG))

        val stable = V8TailBoundarySelector.stabilize(marks)

        assertEquals(V8ChapterMarkState.INCONCLUSIVE, stable.single { mark -> mark.chapterIndex == 98 }.state)
        assertEquals(null, V8TailBoundarySelector.firstBadTailOrdinal(stable))
    }

    @Test
    fun keepsNearTailBadClusterWhenCleanGuardExists() {
        val marks = (90..97).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            listOf(
                mark(98, V8ChapterMarkState.WRONG),
                mark(99, V8ChapterMarkState.WRONG)
            )

        val stable = V8TailBoundarySelector.stabilize(marks)

        assertEquals(V8ChapterMarkState.WRONG, stable.single { mark -> mark.chapterIndex == 98 }.state)
        assertEquals(V8ChapterMarkState.WRONG, stable.single { mark -> mark.chapterIndex == 99 }.state)
        assertEquals(99, V8TailBoundarySelector.firstBadTailOrdinal(stable))
    }

    @Test
    fun closesIsolatedNormalHoleInsideCredibleBadTail() {
        val marks = (20..27).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            listOf(
                mark(28, V8ChapterMarkState.WRONG),
                mark(29, V8ChapterMarkState.WRONG),
                mark(30, V8ChapterMarkState.NORMAL),
                mark(31, V8ChapterMarkState.WRONG),
                mark(32, V8ChapterMarkState.WRONG)
            )

        val stable = V8TailBoundarySelector.stabilize(marks)

        assertEquals(V8ChapterMarkState.WRONG, stable.single { mark -> mark.chapterIndex == 30 }.state)
        assertTrue(stable.single { mark -> mark.chapterIndex == 30 }
            .reasons.any { reason -> reason.contains("closed isolated normal hole inside credible bad-tail") })
    }

    @Test
    fun expandsSuspiciousNormalPreludeBeforeCredibleBadTail() {
        val marks = (386..394).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            listOf(
                mark(395, V8ChapterMarkState.NORMAL, confidence = 0.67),
                mark(396, V8ChapterMarkState.NORMAL, confidence = 0.68)
            ) +
            (397..404).map { index -> mark(index, V8ChapterMarkState.WRONG) }

        val stable = V8TailBoundarySelector.stabilize(marks)

        assertEquals(V8ChapterMarkState.NORMAL, stable.single { mark -> mark.chapterIndex == 394 }.state)
        assertEquals(V8ChapterMarkState.WRONG, stable.single { mark -> mark.chapterIndex == 395 }.state)
        assertEquals(V8ChapterMarkState.WRONG, stable.single { mark -> mark.chapterIndex == 396 }.state)
        assertTrue(stable.single { mark -> mark.chapterIndex == 395 }
            .reasons.any { reason -> reason.contains("expanded suspicious normal prelude") })
        assertEquals(396, V8TailBoundarySelector.firstBadTailOrdinal(stable))
    }

    @Test
    fun keepsConfidentNormalPreludeBeforeCredibleBadTail() {
        val marks = (386..396).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            (397..404).map { index -> mark(index, V8ChapterMarkState.WRONG) }

        val stable = V8TailBoundarySelector.stabilize(marks)

        assertEquals(V8ChapterMarkState.NORMAL, stable.single { mark -> mark.chapterIndex == 395 }.state)
        assertEquals(V8ChapterMarkState.NORMAL, stable.single { mark -> mark.chapterIndex == 396 }.state)
        assertEquals(398, V8TailBoundarySelector.firstBadTailOrdinal(stable))
    }

    @Test
    fun keepsLongNormalRecoveryInsideBadTailBoundary() {
        val marks = (20..27).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
            listOf(
                mark(28, V8ChapterMarkState.WRONG),
                mark(29, V8ChapterMarkState.WRONG),
                mark(30, V8ChapterMarkState.NORMAL),
                mark(31, V8ChapterMarkState.NORMAL),
                mark(32, V8ChapterMarkState.NORMAL),
                mark(33, V8ChapterMarkState.WRONG),
                mark(34, V8ChapterMarkState.WRONG)
            )

        val stable = V8TailBoundarySelector.stabilize(marks)

        assertEquals(V8ChapterMarkState.NORMAL, stable.single { mark -> mark.chapterIndex == 30 }.state)
        assertEquals(V8ChapterMarkState.NORMAL, stable.single { mark -> mark.chapterIndex == 31 }.state)
        assertEquals(V8ChapterMarkState.NORMAL, stable.single { mark -> mark.chapterIndex == 32 }.state)
    }

    private fun mark(
        index: Int,
        state: V8ChapterMarkState,
        confidence: Double = 0.9
    ): V8ChapterMarkResult {
        return V8ChapterMarkResult(
            chapterIndex = index,
            chapterTitle = "Chapter ${index + 1}",
            state = state,
            confidence = confidence,
            qualityType = null,
            suggestionState = if (state == V8ChapterMarkState.WRONG) {
                V8NovelStateOutputType.POLLUTED_SUFFIX
            } else {
                V8NovelStateOutputType.NORMAL
            },
            action = if (state == V8ChapterMarkState.WRONG) V8CleanAction.MARK_ONLY else V8CleanAction.KEEP,
            reasons = emptyList()
        )
    }
}
