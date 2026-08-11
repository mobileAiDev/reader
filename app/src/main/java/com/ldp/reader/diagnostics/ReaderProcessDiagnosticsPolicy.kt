package com.ldp.reader.diagnostics

internal object ReaderProcessDiagnosticsPolicy {
    val memorySampleDelaysMs: List<Long> = listOf(0L, 5_000L, 15_000L, 30_000L)
    const val exitHistoryLimit: Int = 5
}

internal data class ReaderExitInfoSnapshot(
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val timestampMs: Long,
    val description: String?
)

internal data class ReaderMemorySnapshot(
    val sample: String,
    val elapsedMs: Long,
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val totalPssKb: Int,
    val summaryJavaHeapKb: Int,
    val summaryNativeHeapKb: Int,
    val summaryGraphicsKb: Int,
    val availMemBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
    val sourceEngineBook: Boolean
)

internal object ReaderProcessDiagnosticsMapper {
    fun exitFields(snapshot: ReaderExitInfoSnapshot): LinkedHashMap<String, Any?> {
        return linkedMapOf(
            "reason" to snapshot.reason,
            "reasonName" to reasonName(snapshot.reason),
            "status" to snapshot.status,
            "importance" to snapshot.importance,
            "importanceName" to importanceName(snapshot.importance),
            "pssKb" to snapshot.pssKb,
            "rssKb" to snapshot.rssKb,
            "timestampMs" to snapshot.timestampMs,
            "description" to singleLine(snapshot.description)
        )
    }

    fun memoryFields(snapshot: ReaderMemorySnapshot): LinkedHashMap<String, Any?> {
        return linkedMapOf(
            "sample" to snapshot.sample,
            "elapsedMs" to snapshot.elapsedMs,
            "javaUsedBytes" to snapshot.javaUsedBytes,
            "javaMaxBytes" to snapshot.javaMaxBytes,
            "nativeHeapAllocatedBytes" to snapshot.nativeHeapAllocatedBytes,
            "totalPssKb" to snapshot.totalPssKb,
            "summaryJavaHeapKb" to snapshot.summaryJavaHeapKb,
            "summaryNativeHeapKb" to snapshot.summaryNativeHeapKb,
            "summaryGraphicsKb" to snapshot.summaryGraphicsKb,
            "availMemBytes" to snapshot.availMemBytes,
            "thresholdBytes" to snapshot.thresholdBytes,
            "lowMemory" to snapshot.lowMemory,
            "sourceEngineBook" to snapshot.sourceEngineBook
        )
    }

    fun structuredLine(kind: String, fields: Map<String, Any?>): String {
        return buildList {
            add("kind=${logToken(kind)}")
            fields.forEach { (name, value) ->
                add("${logToken(name)}=${logToken(value)}")
            }
        }.joinToString(" ")
    }

    private fun reasonName(reason: Int): String {
        return when (reason) {
            1 -> "EXIT_SELF"
            2 -> "SIGNALED"
            3 -> "LOW_MEMORY"
            4 -> "CRASH"
            5 -> "CRASH_NATIVE"
            6 -> "ANR"
            7 -> "INITIALIZATION_FAILURE"
            8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE_USAGE"
            10 -> "USER_REQUESTED"
            11 -> "USER_STOPPED"
            12 -> "DEPENDENCY_DIED"
            13 -> "OTHER"
            14 -> "FREEZER"
            15 -> "PACKAGE_STATE_CHANGE"
            16 -> "PACKAGE_UPDATED"
            else -> "UNKNOWN"
        }
    }

    private fun importanceName(importance: Int): String {
        return when (importance) {
            100 -> "FOREGROUND"
            125 -> "FOREGROUND_SERVICE"
            150 -> "TOP_SLEEPING_PRE_28"
            200 -> "VISIBLE"
            230 -> "PERCEPTIBLE"
            300 -> "SERVICE"
            325 -> "TOP_SLEEPING"
            400 -> "CACHED"
            1_000 -> "GONE"
            else -> "UNKNOWN"
        }
    }

    private fun singleLine(value: String?): String {
        return value.orEmpty()
            .replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun logToken(value: Any?): String {
        return singleLine(value?.toString())
            .replace(Regex("""[\s=:/\\#]+"""), "_")
            .take(240)
    }
}
