package com.ldp.reader.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineWrongChapterScanPolicyTest {
    @Test
    fun `local txt never starts source-backed full catalog analysis`() {
        assertFalse(
            SourceEngineWrongChapterScanPolicy.shouldStart(
                featureEnabled = true,
                sourceBackedNovel = false,
                catalogSize = 240
            )
        )
    }

    @Test
    fun `source-backed catalog starts analysis once catalog is available`() {
        assertTrue(
            SourceEngineWrongChapterScanPolicy.shouldStart(
                featureEnabled = true,
                sourceBackedNovel = true,
                catalogSize = 240
            )
        )
    }
}
