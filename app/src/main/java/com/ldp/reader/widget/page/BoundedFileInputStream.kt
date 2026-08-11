package com.ldp.reader.widget.page

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.min

/** Reads one byte range without allocating the whole chapter in memory. */
internal class BoundedFileInputStream(
    file: File,
    start: Long,
    end: Long
) : InputStream() {
    private val input = FileInputStream(file)
    private var remaining: Long

    init {
        require(start >= 0L) { "start must not be negative" }
        require(end >= start) { "end must not be before start" }
        val fileSize = input.channel.size()
        val position = min(start, fileSize)
        input.channel.position(position)
        remaining = min(end, fileSize) - position
    }

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = input.read()
        if (value < 0) {
            remaining = 0L
        } else {
            remaining -= 1
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L) return -1
        val boundedLength = min(length.toLong(), remaining).toInt()
        val count = input.read(buffer, offset, boundedLength)
        if (count < 0) {
            remaining = 0L
        } else {
            remaining -= count.toLong()
        }
        return count
    }

    override fun skip(byteCount: Long): Long {
        if (remaining <= 0L || byteCount <= 0L) return 0L
        val skipped = input.skip(min(byteCount, remaining))
        remaining -= skipped
        return skipped
    }

    override fun available(): Int {
        return min(min(input.available().toLong(), remaining), Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        input.close()
    }
}
