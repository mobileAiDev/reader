package com.ldp.reader.source

import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineFullCatalogScanSessionTest {
    @Test
    fun `full catalog scan covers every eligible chapter beyond one hundred`() {
        val session = SourceEngineFullCatalogScanSession(
            orderedTargetIndexes = (1..127).toList(),
            cachedTargetIndexes = emptySet(),
            cachedMarks = emptyList(),
            batchSize = 16
        )

        val processed = linkedSetOf<Int>()
        while (true) {
            val batch = session.nextBatch() ?: break
            processed += batch
            session.acceptBatch(batch, emptyList())
        }

        assertEquals((1..127).toSet(), processed)
        assertEquals(127, session.progress.processed)
        assertEquals(127, session.progress.target)
        assertTrue(session.progress.completed)
    }

    @Test
    fun `processed and target progress is monotonic through completion`() {
        val session = SourceEngineFullCatalogScanSession(
            orderedTargetIndexes = (1..105).toList(),
            cachedTargetIndexes = (1..7).toSet(),
            cachedMarks = emptyList(),
            batchSize = 13
        )
        val snapshots = mutableListOf(session.progress)

        while (true) {
            val batch = session.nextBatch() ?: break
            session.acceptBatch(batch, emptyList())
            snapshots += session.progress
        }

        assertTrue(snapshots.zipWithNext().all { (left, right) -> right.processed >= left.processed })
        assertTrue(snapshots.all { progress -> progress.target == 105 })
        assertEquals(105, snapshots.last().processed)
        assertTrue(snapshots.last().completed)
    }

    @Test
    fun `wrong mark discovered by a batch remains in the merged result`() {
        val session = SourceEngineFullCatalogScanSession(
            orderedTargetIndexes = (1..20).toList(),
            cachedTargetIndexes = emptySet(),
            cachedMarks = emptyList(),
            batchSize = 10
        )
        val first = requireNotNull(session.nextBatch())

        session.acceptBatch(first, listOf(mark(7, V8ChapterMarkState.WRONG)))

        assertEquals(V8ChapterMarkState.WRONG, session.mergedMarks.single { mark -> mark.chapterIndex == 7 }.state)
        assertEquals(1, session.progress.wrong)
    }

    @Test
    fun `complete cache hit does not enter a running scan`() {
        val cachedTargets = (1..120).toSet()
        val session = SourceEngineFullCatalogScanSession(
            orderedTargetIndexes = cachedTargets.toList(),
            cachedTargetIndexes = cachedTargets,
            cachedMarks = listOf(mark(99, V8ChapterMarkState.WRONG)),
            batchSize = 16
        )

        assertFalse(session.progress.running)
        assertTrue(session.progress.cacheHit)
        assertEquals(120, session.progress.processed)
        assertEquals(null, session.nextBatch())
    }

    @Test
    fun `cancelled scan resumes from cached covered targets`() {
        val firstSession = SourceEngineFullCatalogScanSession(
            orderedTargetIndexes = (1..40).toList(),
            cachedTargetIndexes = emptySet(),
            cachedMarks = emptyList(),
            batchSize = 10
        )
        val firstBatch = requireNotNull(firstSession.nextBatch())
        firstSession.acceptBatch(firstBatch, listOf(mark(4, V8ChapterMarkState.WRONG)))
        firstSession.cancel()

        val resumed = SourceEngineFullCatalogScanSession(
            orderedTargetIndexes = (1..40).toList(),
            cachedTargetIndexes = firstSession.processedTargetIndexes,
            cachedMarks = firstSession.mergedMarks,
            batchSize = 10
        )

        assertEquals((11..20).toSet(), resumed.nextBatch())
        assertEquals(V8ChapterMarkState.WRONG, resumed.mergedMarks.single().state)
        assertEquals(10, resumed.progress.processed)
    }

    private fun mark(index: Int, state: V8ChapterMarkState): V8ChapterMarkResult {
        return V8ChapterMarkResult(
            chapterIndex = index,
            chapterTitle = "第${index}章",
            state = state,
            confidence = 0.9,
            qualityType = null,
            suggestionState = null,
            action = null,
            reasons = listOf("test")
        )
    }
}
