package com.ldp.reader.sourceengine.model

data class SourceDiagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val path: String,
    val message: String
)
