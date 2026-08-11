package com.ldp.reader.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWrongChapterScanGenerationTest {
    @Test
    fun `starting replacement work makes the old cancellation callback stale`() {
        val generation = ReaderWrongChapterScanGeneration()
        val first = generation.next()

        val replacement = generation.next()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(replacement))
    }

    @Test
    fun `clearing the reader invalidates the current work`() {
        val generation = ReaderWrongChapterScanGeneration()
        val current = generation.next()

        generation.invalidate()

        assertFalse(generation.isCurrent(current))
    }
}
