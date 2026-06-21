package com.ldp.reader.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.lang.reflect.UndeclaredThrowableException

class ComicImagePlaybackProbeTest {
    @Test
    fun probeReadsFullRangeBytesBeforeClosingResponse() {
        val imageBytes = ByteArray(2_048) { index -> (index % 251).toByte() }.also {
            it[0] = 0xff.toByte()
            it[1] = 0xd8.toByte()
            it[2] = 0xff.toByte()
            it[3] = 0xe0.toByte()
        }

        val result = ComicImagePlaybackProbe.readProbeBytes(ByteArrayInputStream(imageBytes))

        assertEquals(2_048, result.bytesRead)
        assertEquals("", result.error)
        assertEquals(0xff.toByte(), result.buffer[0])
        assertEquals(0xd8.toByte(), result.buffer[1])
    }

    @Test
    fun probeReadClassifiesInterruptedReadAsCancellation() {
        val result = ComicImagePlaybackProbe.readProbeBytes(
            object : InputStream() {
                override fun read(): Int = throw InterruptedIOException()
            }
        )

        assertEquals(0, result.bytesRead)
        assertTrue(result.cancelled)
        assertTrue(result.error.startsWith("probe_cancelled"))
    }

    @Test
    fun probeReadClassifiesWrappedInterruptedReadAsCancellation() {
        val result = ComicImagePlaybackProbe.readProbeBytes(
            object : InputStream() {
                override fun read(): Int = throw UndeclaredThrowableException(InterruptedException())
            }
        )

        assertEquals(0, result.bytesRead)
        assertTrue(result.cancelled)
        assertTrue(result.error.startsWith("probe_cancelled"))
    }
}
