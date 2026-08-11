package com.ldp.reader.ui.activity

import com.ldp.reader.R
import com.ldp.reader.source.SourceEngineV8ScanPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWrongChapterUiPolicyTest {

    @Test
    fun `local txt hides controls that cannot affect its catalog`() {
        val state = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = true,
            sourceBackedNovel = false,
            analysisRunning = true
        )

        assertFalse(state.showToggle)
        assertFalse(state.showStatus)
        assertFalse(state.showLoading)
    }

    @Test
    fun `source backed novel uses honest indeterminate check copy until target is known`() {
        val state = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = true,
            sourceBackedNovel = true,
            analysisRunning = true
        )

        assertTrue(state.showToggle)
        assertTrue(state.showLoading)
        assertEquals(R.string.reader_wrong_chapter_analysis_running, state.statusTextRes)
    }

    @Test
    fun `source backed full scan uses provider processed and target progress`() {
        val state = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = true,
            sourceBackedNovel = true,
            analysisRunning = true,
            processed = 32,
            target = 127
        )

        assertEquals(R.string.reader_wrong_chapter_analysis_progress, state.statusTextRes)
        assertEquals(32, state.statusProcessed)
        assertEquals(127, state.statusTarget)
    }

    @Test
    fun `completed scan with no suspect chapters stays visible with a clear result`() {
        val state = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = true,
            sourceBackedNovel = true,
            analysisRunning = false,
            processed = 127,
            target = 127,
            wrong = 0,
            phase = SourceEngineV8ScanPhase.COMPLETED
        )

        assertTrue(state.showStatus)
        assertFalse(state.showLoading)
        assertEquals(R.string.reader_wrong_chapter_analysis_completed_clean, state.statusTextRes)
    }

    @Test
    fun `completed scan reports how many suspect chapters remain marked`() {
        val state = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = true,
            sourceBackedNovel = true,
            analysisRunning = false,
            processed = 127,
            target = 127,
            wrong = 3,
            phase = SourceEngineV8ScanPhase.COMPLETED
        )

        assertTrue(state.showStatus)
        assertFalse(state.showLoading)
        assertEquals(R.string.reader_wrong_chapter_analysis_completed_with_suspects, state.statusTextRes)
        assertEquals(3, state.statusWrong)
    }

    @Test
    fun `cache hit is explained as a recent result without internal terminology`() {
        val state = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = true,
            sourceBackedNovel = true,
            analysisRunning = false,
            processed = 127,
            target = 127,
            wrong = 3,
            phase = SourceEngineV8ScanPhase.CACHE_HIT
        )

        assertTrue(state.showStatus)
        assertFalse(state.showLoading)
        assertEquals(R.string.reader_wrong_chapter_analysis_recent_result, state.statusTextRes)
    }

    @Test
    fun `failed scan remains visible with a retryable user message`() {
        val state = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = true,
            sourceBackedNovel = true,
            analysisRunning = false,
            phase = SourceEngineV8ScanPhase.FAILED
        )

        assertTrue(state.showStatus)
        assertFalse(state.showLoading)
        assertEquals(R.string.reader_wrong_chapter_analysis_failed, state.statusTextRes)
    }

    @Test
    fun `cancelled scan remains visible and explains that checking stopped`() {
        val state = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = true,
            sourceBackedNovel = true,
            analysisRunning = false,
            phase = SourceEngineV8ScanPhase.CANCELLED
        )

        assertTrue(state.showStatus)
        assertFalse(state.showLoading)
        assertEquals(R.string.reader_wrong_chapter_analysis_cancelled, state.statusTextRes)
    }
}
