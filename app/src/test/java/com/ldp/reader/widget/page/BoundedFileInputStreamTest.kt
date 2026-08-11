package com.ldp.reader.widget.page

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedFileInputStreamTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsOnlyRequestedRangeAndStopsAtEnd() {
        val file = temporaryFolder.newFile("range.txt")
        file.writeBytes("0123456789".toByteArray())

        BoundedFileInputStream(file, start = 3, end = 7).use { input ->
            assertArrayEquals("3456".toByteArray(), input.readBytes())
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun clampsEndToFileAndSupportsSmallBufferedReads() {
        val file = temporaryFolder.newFile("short.txt")
        file.writeBytes("abcdef".toByteArray())
        val chunks = ArrayList<String>()

        BoundedFileInputStream(file, start = 2, end = 100).use { input ->
            val buffer = ByteArray(2)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                chunks.add(String(buffer, 0, count))
            }
        }

        assertEquals(listOf("cd", "ef"), chunks)
    }

    @Test
    fun emptyRangeDoesNotReadUnderlyingContent() {
        val file = temporaryFolder.newFile("empty-range.txt")
        file.writeBytes(ByteArray(1024 * 1024) { 7 })

        BoundedFileInputStream(file, start = file.length(), end = file.length()).use { input ->
            assertEquals(0, input.available())
            assertEquals(-1, input.read())
            assertTrue(input.readBytes().isEmpty())
        }
    }
}
