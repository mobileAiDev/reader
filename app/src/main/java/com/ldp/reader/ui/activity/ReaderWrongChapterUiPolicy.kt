package com.ldp.reader.ui.activity

import com.ldp.reader.R
import com.ldp.reader.source.SourceEngineV8ScanPhase

internal data class ReaderWrongChapterUiState(
    val showToggle: Boolean,
    val showStatus: Boolean,
    val showLoading: Boolean,
    val statusTextRes: Int,
    val statusProcessed: Int? = null,
    val statusTarget: Int? = null,
    val statusWrong: Int? = null
)

internal object ReaderWrongChapterUiPolicy {
    fun resolve(
        featureEnabled: Boolean,
        sourceBackedNovel: Boolean,
        analysisRunning: Boolean,
        processed: Int = 0,
        target: Int = 0,
        wrong: Int = 0,
        phase: SourceEngineV8ScanPhase? = null
    ): ReaderWrongChapterUiState {
        val available = featureEnabled && sourceBackedNovel
        val effectivePhase = phase ?: SourceEngineV8ScanPhase.RUNNING.takeIf { analysisRunning }
        val hasMeasuredProgress = effectivePhase == SourceEngineV8ScanPhase.RUNNING &&
            target > 0 && processed in 0..target
        return ReaderWrongChapterUiState(
            showToggle = available,
            showStatus = available && effectivePhase != null,
            showLoading = available && effectivePhase == SourceEngineV8ScanPhase.RUNNING,
            statusTextRes = when {
                effectivePhase == SourceEngineV8ScanPhase.COMPLETED && wrong > 0 -> {
                    R.string.reader_wrong_chapter_analysis_completed_with_suspects
                }
                effectivePhase == SourceEngineV8ScanPhase.COMPLETED && wrong <= 0 -> {
                    R.string.reader_wrong_chapter_analysis_completed_clean
                }
                effectivePhase == SourceEngineV8ScanPhase.CACHE_HIT -> {
                    R.string.reader_wrong_chapter_analysis_recent_result
                }
                effectivePhase == SourceEngineV8ScanPhase.FAILED -> {
                    R.string.reader_wrong_chapter_analysis_failed
                }
                effectivePhase == SourceEngineV8ScanPhase.CANCELLED -> {
                    R.string.reader_wrong_chapter_analysis_cancelled
                }
                hasMeasuredProgress -> R.string.reader_wrong_chapter_analysis_progress
                else -> R.string.reader_wrong_chapter_analysis_running
            },
            statusProcessed = processed.takeIf { hasMeasuredProgress },
            statusTarget = target.takeIf { hasMeasuredProgress },
            statusWrong = wrong.takeIf {
                effectivePhase == SourceEngineV8ScanPhase.COMPLETED && wrong > 0
            }
        )
    }
}
