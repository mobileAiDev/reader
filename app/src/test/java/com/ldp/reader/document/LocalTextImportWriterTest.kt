package com.ldp.reader.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class LocalTextImportWriterTest {
    @Test
    fun emptyInputDoesNotCreateATarget() = withImportDirectory("empty-new") { directory ->
        val target = directory.resolve("book.txt")

        val result = LocalTextImportWriter.replaceWith(
            input = ByteArrayInputStream(byteArrayOf()),
            target = target,
            maxBytes = 8
        )

        assertEquals(LocalTextImportWriteResult.Empty, result)
        assertFalse(target.exists())
        assertNoTemporaryFiles(directory)
    }

    @Test
    fun emptyInputKeepsAnExistingGoodTarget() = withImportDirectory("empty-existing") { directory ->
        val original = "existing good book".toByteArray()
        val target = directory.resolve("book.txt").apply { writeBytes(original) }

        val result = LocalTextImportWriter.replaceWith(
            input = ByteArrayInputStream(byteArrayOf()),
            target = target,
            maxBytes = 8
        )

        assertEquals(LocalTextImportWriteResult.Empty, result)
        assertArrayEquals(original, target.readBytes())
        assertNoTemporaryFiles(directory)
    }

    @Test
    fun oversizedInputReadsOnlyOneBytePastTheLimitAndKeepsExistingTarget() =
        withImportDirectory("oversized") { directory ->
            val original = "existing good book".toByteArray()
            val target = directory.resolve("book.txt").apply { writeBytes(original) }
            val input = CountingInputStream(ByteArrayInputStream(ByteArray(64) { it.toByte() }))

            val result = LocalTextImportWriter.replaceWith(
                input = input,
                target = target,
                maxBytes = 8
            )

            assertEquals(LocalTextImportWriteResult.TooLarge, result)
            assertEquals(9L, input.bytesRead)
            assertArrayEquals(original, target.readBytes())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun oversizedInputDoesNotLeaveAPartialTarget() = withImportDirectory("oversized-new") { directory ->
        val target = directory.resolve("book.txt")

        val result = LocalTextImportWriter.replaceWith(
            input = ByteArrayInputStream(ByteArray(9)),
            target = target,
            maxBytes = 8
        )

        assertEquals(LocalTextImportWriteResult.TooLarge, result)
        assertFalse(target.exists())
        assertNoTemporaryFiles(directory)
    }

    @Test
    fun pathologicalSingleLineIsRejectedDuringCopyWithoutLeavingAShelfTarget() =
        withImportDirectory("line-too-long") { directory ->
            val target = directory.resolve("book.txt")

            val result = LocalTextImportWriter.replaceWith(
                input = ByteArrayInputStream(ByteArray(65) { 'x'.code.toByte() }),
                target = target,
                maxBytes = 128,
                maxLineBytes = 64
            )

            assertEquals(LocalTextImportWriteResult.LineTooLong, result)
            assertFalse(target.exists())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun largeTextWithBoundedLinesStillImportsNormally() =
        withImportDirectory("bounded-lines") { directory ->
            val target = directory.resolve("book.txt")
            val content = buildString {
                repeat(4) {
                    append("x".repeat(64))
                    append('\n')
                }
            }.toByteArray()

            val result = LocalTextImportWriter.replaceWith(
                input = ByteArrayInputStream(content),
                target = target,
                maxBytes = content.size.toLong(),
                maxLineBytes = 64
            )

            assertEquals(LocalTextImportWriteResult.Success(content.size.toLong()), result)
            assertArrayEquals(content, target.readBytes())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun pathologicalReplacementKeepsTheExistingReadableTarget() =
        withImportDirectory("line-too-long-existing") { directory ->
            val original = "existing readable book\n".toByteArray()
            val target = directory.resolve("book.txt").apply { writeBytes(original) }

            val result = LocalTextImportWriter.replaceWith(
                input = ByteArrayInputStream(ByteArray(65) { 'x'.code.toByte() }),
                target = target,
                maxBytes = 128,
                maxLineBytes = 64
            )

            assertEquals(LocalTextImportWriteResult.LineTooLong, result)
            assertArrayEquals(original, target.readBytes())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun utf16LeBomPathologicalLineCannotHideBehindNewlineShapedCharacterBytes() =
        withImportDirectory("utf16le-line-too-long") { directory ->
            val target = directory.resolve("book.txt")
            // U+0A41 and U+0D42 contain 0A/0D as the high byte in UTF-16LE, but neither
            // character is a line separator. The logical line is 68 bytes after the BOM.
            val content = utf16WithBom(
                text = "\u0A41\u0D42".repeat(17),
                charset = Charsets.UTF_16LE,
                bom = byteArrayOf(0xff.toByte(), 0xfe.toByte())
            )

            val result = LocalTextImportWriter.replaceWith(
                input = ChunkedInputStream(ByteArrayInputStream(content), maxChunkBytes = 1),
                target = target,
                maxBytes = content.size.toLong(),
                maxLineBytes = 64
            )

            assertEquals(LocalTextImportWriteResult.LineTooLong, result)
            assertFalse(target.exists())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun utf16BeBomPathologicalLineCannotHideBehindNewlineShapedCharacterBytes() =
        withImportDirectory("utf16be-line-too-long") { directory ->
            val target = directory.resolve("book.txt")
            // U+410A and U+420D contain 0A/0D as the low byte in UTF-16BE, but neither
            // character is a line separator. The logical line is 68 bytes after the BOM.
            val content = utf16WithBom(
                text = "\u410A\u420D".repeat(17),
                charset = Charsets.UTF_16BE,
                bom = byteArrayOf(0xfe.toByte(), 0xff.toByte())
            )

            val result = LocalTextImportWriter.replaceWith(
                input = ChunkedInputStream(ByteArrayInputStream(content), maxChunkBytes = 1),
                target = target,
                maxBytes = content.size.toLong(),
                maxLineBytes = 64
            )

            assertEquals(LocalTextImportWriteResult.LineTooLong, result)
            assertFalse(target.exists())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun utf16BomTextPreservesCharactersContainingNewlineShapedBytesAndRealLineBreaks() =
        withImportDirectory("utf16-real-line-breaks") { directory ->
            listOf(
                Triple(
                    "\u0A41\u0D42".repeat(8) + "\n" + "\u0D42\u0A41".repeat(8),
                    Charsets.UTF_16LE,
                    byteArrayOf(0xff.toByte(), 0xfe.toByte())
                ),
                Triple(
                    "\u410A\u420D".repeat(8) + "\r\n" + "\u420D\u410A".repeat(8),
                    Charsets.UTF_16BE,
                    byteArrayOf(0xfe.toByte(), 0xff.toByte())
                )
            ).forEachIndexed { index, (text, charset, bom) ->
                val target = directory.resolve("book-$index.txt")
                val content = utf16WithBom(text, charset, bom)

                val result = LocalTextImportWriter.replaceWith(
                    input = ByteArrayInputStream(content),
                    target = target,
                    maxBytes = content.size.toLong(),
                    maxLineBytes = 32
                )

                assertEquals(LocalTextImportWriteResult.Success(content.size.toLong()), result)
                assertArrayEquals(content, target.readBytes())
            }
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun readFailureRemovesPartialTemporaryDataAndKeepsExistingTarget() =
        withImportDirectory("io-failure") { directory ->
            val original = "existing good book".toByteArray()
            val target = directory.resolve("book.txt").apply { writeBytes(original) }
            val input = FailingInputStream("partial input".toByteArray())

            try {
                LocalTextImportWriter.replaceWith(input = input, target = target, maxBytes = 64)
                fail("Expected the input failure to be propagated")
            } catch (_: IOException) {
            }

            assertArrayEquals(original, target.readBytes())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun readFailureDoesNotLeaveAPartialTarget() = withImportDirectory("io-failure-new") { directory ->
        val target = directory.resolve("book.txt")

        try {
            LocalTextImportWriter.replaceWith(
                input = FailingInputStream("partial input".toByteArray()),
                target = target,
                maxBytes = 64
            )
            fail("Expected the input failure to be propagated")
        } catch (_: IOException) {
        }

        assertFalse(target.exists())
        assertNoTemporaryFiles(directory)
    }

    @Test
    fun successfulCopySafelyReplacesExistingTarget() = withImportDirectory("success") { directory ->
        val imported = "replacement text".toByteArray()
        val target = directory.resolve("book.txt").apply { writeText("old text") }

        val result = LocalTextImportWriter.replaceWith(
            input = ByteArrayInputStream(imported),
            target = target,
            maxBytes = imported.size.toLong()
        )

        assertEquals(LocalTextImportWriteResult.Success(imported.size.toLong()), result)
        assertArrayEquals(imported, target.readBytes())
        assertNoTemporaryFiles(directory)
    }

    @Test
    fun cancellationPropagatesAndRemovesPartialTemporaryDataWithoutReplacingTarget() =
        withImportDirectory("cancelled") { directory ->
            val original = "existing good book".toByteArray()
            val target = directory.resolve("book.txt").apply { writeBytes(original) }
            val input = CountingInputStream(ByteArrayInputStream(ByteArray(128 * 1024) { 1 }))
            val cancellation = CancellationException("simulated cancellation")

            try {
                LocalTextImportWriter.replaceWith(
                    input = input,
                    target = target,
                    maxBytes = 256L * 1024L,
                    cancellationCheck = {
                        if (input.bytesRead > 0L) throw cancellation
                    }
                )
                fail("Expected cancellation to be propagated")
            } catch (actual: CancellationException) {
                assertTrue(actual === cancellation)
            }

            assertTrue(input.bytesRead > 0L)
            assertArrayEquals(original, target.readBytes())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun interruptedRunInterruptibleWorkerUsesTheDefaultCancellationCheck() =
        withImportDirectory("interrupted") { directory ->
            val target = directory.resolve("book.txt")

            try {
                Thread.currentThread().interrupt()
                LocalTextImportWriter.replaceWith(
                    input = ByteArrayInputStream("content".toByteArray()),
                    target = target
                )
                fail("Expected the interrupted worker to cancel")
            } catch (_: CancellationException) {
                // runInterruptible cancels blocking work by interrupting its worker thread.
            } finally {
                Thread.interrupted()
            }

            assertFalse(target.exists())
            assertNoTemporaryFiles(directory)
        }

    @Test
    fun defaultLimitIsBoundedAt256MiB() {
        assertEquals(256L * 1024L * 1024L, LocalTextImportWriter.DEFAULT_MAX_BYTES)
    }

    private fun withImportDirectory(name: String, test: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("local-text-import-$name").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertNoTemporaryFiles(directory: java.io.File) {
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    private fun utf16WithBom(
        text: String,
        charset: java.nio.charset.Charset,
        bom: ByteArray
    ): ByteArray = bom + text.toByteArray(charset)

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            return super.read().also { if (it >= 0) bytesRead++ }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        }
    }

    private class ChunkedInputStream(
        input: InputStream,
        private val maxChunkBytes: Int
    ) : FilterInputStream(input) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return super.read(buffer, offset, minOf(length, maxChunkBytes))
        }
    }

    private class FailingInputStream(private val initialBytes: ByteArray) : InputStream() {
        private var delivered = false

        override fun read(): Int = throw IOException("simulated input failure")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delivered) throw IOException("simulated input failure")
            val count = minOf(initialBytes.size, length)
            initialBytes.copyInto(buffer, offset, endIndex = count)
            delivered = true
            return count
        }
    }
}
