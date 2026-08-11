package com.ldp.reader.utils

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

enum class Charset(private val charsetName: String) {
    UTF8("UTF-8"),
    UTF16LE("UTF-16LE"),
    UTF16BE("UTF-16BE"),
    GBK("GBK");

    fun getName(): String = charsetName

    companion object {
        const val BLANK: Byte = 0x0a
    }
}

/**
 * Distinguishes UTF-8 text containing a few damaged bytes from legacy encodings.
 *
 * The detector validates the complete stream in one bounded-memory pass. A small number of broken
 * bytes may be tolerated when several valid multibyte UTF-8 characters remain. Two-byte sequences
 * count as evidence as well, so a lightly damaged Cyrillic, Greek, or accented-Latin file does not
 * fall back to GBK wholesale.
 * A completely valid UTF-8 stream always stays UTF-8, including text made only from two-byte
 * characters and very short files. Some unmarked byte sequences are valid in both UTF-8 and GBK;
 * those are inherently undecidable, so the reader follows the modern UTF-8 default.
 */
internal object Utf8ContentDetector {
    @Throws(IOException::class)
    fun looksLikeUtf8(
        openStream: () -> InputStream,
        cancellationCheck: () -> Unit = {}
    ): Boolean {
        val scan = scan(openStream, cancellationCheck)
        if (scan.invalidCharacters == 0L) {
            return true
        }

        return scan.validMultibyteCharacters >= MIN_UTF8_MULTIBYTE_EVIDENCE_CHARACTERS &&
            scan.invalidCharacters * 100L <= scan.totalCharacters * MAX_REPLACEMENT_PERCENT
    }

    @Throws(IOException::class)
    private fun scan(
        openStream: () -> InputStream,
        cancellationCheck: () -> Unit
    ): Utf8Scan {
        var totalCharacters = 0L
        var invalidCharacters = 0L
        var validMultibyteCharacters = 0L
        var bytesSinceCancellation = 0

        PushbackInputStream(
            BufferedInputStream(openStream(), SCAN_BUFFER_SIZE),
            MAX_LOOKAHEAD_BYTES
        ).use { input ->
            val continuation = ByteArray(MAX_LOOKAHEAD_BYTES)
            while (true) {
                if (bytesSinceCancellation >= CANCELLATION_CHECK_BYTES) {
                    cancellationCheck()
                    bytesSinceCancellation = 0
                }
                val first = input.read()
                if (first == -1) break
                bytesSinceCancellation += 1
                totalCharacters += 1
                if (first <= ASCII_MAX) continue

                val expectedLength = expectedSequenceLength(first)
                if (expectedLength == 0) {
                    invalidCharacters += 1
                    continue
                }

                val continuationSize = expectedLength - 1
                var readCount = 0
                while (readCount < continuationSize) {
                    val next = input.read()
                    if (next == -1) break
                    continuation[readCount] = next.toByte()
                    readCount += 1
                    bytesSinceCancellation += 1
                }
                val valid = readCount == continuationSize &&
                    isValidSequence(first, continuation, continuationSize)
                if (!valid) {
                    if (readCount > 0) {
                        input.unread(continuation, 0, readCount)
                        bytesSinceCancellation -= readCount
                    }
                    invalidCharacters += 1
                    continue
                }

                validMultibyteCharacters += 1
            }
        }
        cancellationCheck()
        return Utf8Scan(
            totalCharacters = totalCharacters,
            invalidCharacters = invalidCharacters,
            validMultibyteCharacters = validMultibyteCharacters
        )
    }

    private fun expectedSequenceLength(first: Int): Int = when (first) {
        in 0xc2..0xdf -> 2
        in 0xe0..0xef -> 3
        in 0xf0..0xf4 -> 4
        else -> 0
    }

    private fun isValidSequence(
        first: Int,
        continuation: ByteArray,
        continuationSize: Int
    ): Boolean {
        val second = continuation[0].toInt() and 0xff
        if (second !in 0x80..0xbf) return false
        when (first) {
            0xe0 -> if (second !in 0xa0..0xbf) return false
            0xed -> if (second !in 0x80..0x9f) return false
            0xf0 -> if (second !in 0x90..0xbf) return false
            0xf4 -> if (second !in 0x80..0x8f) return false
        }
        for (index in 1 until continuationSize) {
            if ((continuation[index].toInt() and 0xff) !in 0x80..0xbf) {
                return false
            }
        }
        return true
    }

    private data class Utf8Scan(
        val totalCharacters: Long,
        val invalidCharacters: Long,
        val validMultibyteCharacters: Long
    )

    private const val MIN_UTF8_MULTIBYTE_EVIDENCE_CHARACTERS = 4L
    private const val MAX_REPLACEMENT_PERCENT = 10L
    private const val ASCII_MAX = 0x7f
    private const val SCAN_BUFFER_SIZE = 8 * 1024
    private const val MAX_LOOKAHEAD_BYTES = 3
    private const val CANCELLATION_CHECK_BYTES = 8 * 1024
}
