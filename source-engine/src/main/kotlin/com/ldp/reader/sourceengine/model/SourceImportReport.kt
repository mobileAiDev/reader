package com.ldp.reader.sourceengine.model

data class SourceImportReport(
    val sources: List<BookSource>,
    val rejectedSources: List<SourceImportFailure>
) {
    val diagnosticCount: Int
        get() = sources.sumOf { it.diagnostics.size }
}
