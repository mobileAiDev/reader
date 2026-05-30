package com.ldp.reader.source

internal const val SOURCE_ENGINE_INTEGRITY_MARK_SCHEMA_VERSION = 35
internal const val SOURCE_ENGINE_RUNTIME_READABLE_CONTENT_REASON = "runtime readable content v2"

private val currentSchemaToken =
    "source-integrity-schema=$SOURCE_ENGINE_INTEGRITY_MARK_SCHEMA_VERSION"

internal fun sourceIntegrityPersistedReason(reasons: List<String>): String {
    return currentSchemaToken + "|" + reasons.joinToString("|")
}

internal fun isCurrentSourceIntegrityReason(reason: String?): Boolean {
    return reason?.startsWith(currentSchemaToken + "|") == true
}

internal fun isCurrentSourceIntegrityAnalysisReason(reason: String?): Boolean {
    return isCurrentSourceIntegrityReason(reason) &&
        reason?.contains(SOURCE_ENGINE_RUNTIME_READABLE_CONTENT_REASON) != true
}
