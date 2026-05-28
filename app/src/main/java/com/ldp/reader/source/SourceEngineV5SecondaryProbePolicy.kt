package com.ldp.reader.source

import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkResult
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkState

internal object SourceEngineV5SecondaryProbePolicy {
    fun shouldProbeTailSecondary(mark: V5ChapterMarkResult): Boolean {
        return mark.state.isBadForTail || mark.state == V5ChapterMarkState.INCONCLUSIVE
    }
}
