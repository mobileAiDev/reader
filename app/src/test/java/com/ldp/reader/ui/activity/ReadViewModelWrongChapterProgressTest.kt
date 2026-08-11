package com.ldp.reader.ui.activity

import com.ldp.reader.source.SourceEngineV8ScanPhase
import com.ldp.reader.source.SourceEngineV8ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadViewModelWrongChapterProgressTest {
    @Test
    fun `completed provider progress reaches observer as terminal true counts`() {
        val status = ReadViewModel.V8AnalysisStatus.fromProgress(
            trigger = "catalog-complete",
            ready = true,
            progress = SourceEngineV8ScanProgress(
                phase = SourceEngineV8ScanPhase.COMPLETED,
                processed = 127,
                target = 127,
                wrong = 1
            )
        )

        assertFalse(status.running)
        assertEquals(127, status.processed)
        assertEquals(127, status.target)
        assertEquals(1, status.wrong)
        assertEquals(SourceEngineV8ScanPhase.COMPLETED, status.phase)
    }

    @Test
    fun `cache hit reaches observer without pretending to scan`() {
        val status = ReadViewModel.V8AnalysisStatus.fromProgress(
            trigger = "catalog-complete",
            ready = true,
            progress = SourceEngineV8ScanProgress(
                phase = SourceEngineV8ScanPhase.CACHE_HIT,
                processed = 127,
                target = 127,
                wrong = 1
            )
        )

        assertFalse(status.running)
        assertTrue(status.cacheHit)
        assertEquals(SourceEngineV8ScanPhase.CACHE_HIT, status.phase)
    }

    @Test
    fun `chapter background work cannot erase a retained full catalog outcome`() {
        val completed = ReadViewModel.V8AnalysisStatus.fromProgress(
            trigger = "catalog-complete",
            ready = true,
            progress = SourceEngineV8ScanProgress(
                phase = SourceEngineV8ScanPhase.COMPLETED,
                processed = 127,
                target = 127,
                wrong = 3
            )
        )

        val retainedWhileBackgroundWorkRuns = ReadViewModel.V8AnalysisStatus.retainFullCatalogOutcome(
            previous = completed,
            candidate = ReadViewModel.V8AnalysisStatus(
                running = true,
                trigger = "chapter-finish"
            )
        )
        val retainedWhenBackgroundWorkStops = ReadViewModel.V8AnalysisStatus.retainFullCatalogOutcome(
            previous = retainedWhileBackgroundWorkRuns,
            candidate = ReadViewModel.V8AnalysisStatus(
                running = false,
                trigger = "chapter-finish",
                ready = true
            )
        )

        assertEquals(SourceEngineV8ScanPhase.COMPLETED, retainedWhileBackgroundWorkRuns.phase)
        assertEquals(SourceEngineV8ScanPhase.COMPLETED, retainedWhenBackgroundWorkStops.phase)
        assertEquals(3, retainedWhenBackgroundWorkStops.wrong)
    }

    @Test
    fun `a new full catalog run replaces the retained outcome`() {
        val completed = ReadViewModel.V8AnalysisStatus.fromProgress(
            trigger = "catalog-complete",
            ready = true,
            progress = SourceEngineV8ScanProgress(
                phase = SourceEngineV8ScanPhase.COMPLETED,
                processed = 127,
                target = 127
            )
        )
        val newRun = ReadViewModel.V8AnalysisStatus.fromProgress(
            trigger = "catalog-refresh",
            ready = null,
            progress = SourceEngineV8ScanProgress(
                phase = SourceEngineV8ScanPhase.RUNNING,
                processed = 32,
                target = 127
            )
        )

        val current = ReadViewModel.V8AnalysisStatus.retainFullCatalogOutcome(completed, newRun)

        assertTrue(current.running)
        assertEquals(SourceEngineV8ScanPhase.RUNNING, current.phase)
        assertEquals(32, current.processed)
    }
}
