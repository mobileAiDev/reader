package com.ldp.reader.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderProcessDiagnosticsPolicyTest {

    @Test
    fun `reader memory sampling is limited to entry 5s 15s and 30s`() {
        assertEquals(
            listOf(0L, 5_000L, 15_000L, 30_000L),
            ReaderProcessDiagnosticsPolicy.memorySampleDelaysMs
        )
    }

    @Test
    fun `process exit history is bounded`() {
        assertEquals(5, ReaderProcessDiagnosticsPolicy.exitHistoryLimit)
    }

    @Test
    fun `exit mapper preserves system facts and gives readable labels`() {
        val mapped = ReaderProcessDiagnosticsMapper.exitFields(
            ReaderExitInfoSnapshot(
                reason = 3,
                status = 9,
                importance = 100,
                pssKb = 126_400L,
                rssKb = 178_200L,
                timestampMs = 1_723_265_478_000L,
                description = "low memory\npressure"
            )
        )

        assertEquals(
            linkedMapOf(
                "reason" to 3,
                "reasonName" to "LOW_MEMORY",
                "status" to 9,
                "importance" to 100,
                "importanceName" to "FOREGROUND",
                "pssKb" to 126_400L,
                "rssKb" to 178_200L,
                "timestampMs" to 1_723_265_478_000L,
                "description" to "low memory pressure"
            ),
            mapped
        )
    }

    @Test
    fun `memory mapper exposes java native graphics and system pressure facts`() {
        val mapped = ReaderProcessDiagnosticsMapper.memoryFields(
            ReaderMemorySnapshot(
                sample = "15s",
                elapsedMs = 15_043L,
                javaUsedBytes = 80_000_000L,
                javaMaxBytes = 268_435_456L,
                nativeHeapAllocatedBytes = 42_000_000L,
                totalPssKb = 148_000,
                summaryJavaHeapKb = 82_000,
                summaryNativeHeapKb = 43_000,
                summaryGraphicsKb = 16_000,
                availMemBytes = 1_200_000_000L,
                thresholdBytes = 226_000_000L,
                lowMemory = false,
                sourceEngineBook = true
            )
        )

        assertEquals(
            linkedMapOf(
                "sample" to "15s",
                "elapsedMs" to 15_043L,
                "javaUsedBytes" to 80_000_000L,
                "javaMaxBytes" to 268_435_456L,
                "nativeHeapAllocatedBytes" to 42_000_000L,
                "totalPssKb" to 148_000,
                "summaryJavaHeapKb" to 82_000,
                "summaryNativeHeapKb" to 43_000,
                "summaryGraphicsKb" to 16_000,
                "availMemBytes" to 1_200_000_000L,
                "thresholdBytes" to 226_000_000L,
                "lowMemory" to false,
                "sourceEngineBook" to true
            ),
            mapped
        )
    }

    @Test
    fun `structured log is single line and stable for bridge grep`() {
        assertEquals(
            "kind=process_exit reason=3 description=low_memory_pressure",
            ReaderProcessDiagnosticsMapper.structuredLine(
                kind = "process_exit",
                fields = linkedMapOf(
                    "reason" to 3,
                    "description" to "low memory\npressure"
                )
            )
        )
    }
}
