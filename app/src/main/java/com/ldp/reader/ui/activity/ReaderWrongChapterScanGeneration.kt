package com.ldp.reader.ui.activity

/** Keeps callbacks from a replaced wrong-chapter scan from mutating the new scan. */
internal class ReaderWrongChapterScanGeneration {
    private var value = 0L

    @Synchronized
    fun next(): Long {
        value += 1L
        return value
    }

    @Synchronized
    fun invalidate() {
        value += 1L
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean = generation == value
}
