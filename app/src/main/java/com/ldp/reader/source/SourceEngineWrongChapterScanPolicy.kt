package com.ldp.reader.source

internal object SourceEngineWrongChapterScanPolicy {
    fun shouldStart(
        featureEnabled: Boolean,
        sourceBackedNovel: Boolean,
        catalogSize: Int
    ): Boolean {
        return featureEnabled && sourceBackedNovel && catalogSize > 0
    }
}
