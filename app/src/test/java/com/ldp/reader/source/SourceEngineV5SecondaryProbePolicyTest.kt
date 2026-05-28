package com.ldp.reader.source

import com.ldp.reader.sourceengine.content.v5.ChapterQualityType
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkResult
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineV5SecondaryProbePolicyTest {
    @Test
    fun inconclusiveTailMarksTriggerSecondaryProbeButNormalDoesNot() {
        assertTrue(SourceEngineV5SecondaryProbePolicy.shouldProbeTailSecondary(mark(V5ChapterMarkState.INCONCLUSIVE)))
        assertTrue(SourceEngineV5SecondaryProbePolicy.shouldProbeTailSecondary(mark(V5ChapterMarkState.WRONG)))
        assertTrue(SourceEngineV5SecondaryProbePolicy.shouldProbeTailSecondary(mark(V5ChapterMarkState.NON_STORY)))
        assertTrue(SourceEngineV5SecondaryProbePolicy.shouldProbeTailSecondary(mark(V5ChapterMarkState.BAD_EXTRACTION)))
        assertFalse(SourceEngineV5SecondaryProbePolicy.shouldProbeTailSecondary(mark(V5ChapterMarkState.NORMAL)))
    }

    private fun mark(state: V5ChapterMarkState): V5ChapterMarkResult {
        return V5ChapterMarkResult(
            chapterIndex = 12,
            chapterTitle = "第12章",
            state = state,
            confidence = 0.8,
            qualityType = ChapterQualityType.CLEAN_STORY,
            suggestionState = null,
            action = null,
            reasons = emptyList()
        )
    }
}
