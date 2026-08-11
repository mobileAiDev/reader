package com.ldp.reader.document

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

internal sealed interface LocalTextImportWriteResult {
    data class Success(val byteCount: Long) : LocalTextImportWriteResult
    data object Empty : LocalTextImportWriteResult
    data object TooLarge : LocalTextImportWriteResult
    data object LineTooLong : LocalTextImportWriteResult
}

internal object LocalTextImportWriter {
    const val DEFAULT_MAX_BYTES = 256L * 1024L * 1024L
    const val DEFAULT_MAX_LINE_BYTES = 1024 * 1024

    fun replaceWith(
        input: InputStream,
        target: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
        cancellationCheck: () -> Unit = ::ensureThreadIsActive
    ): LocalTextImportWriteResult {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        require(maxLineBytes > 0) { "maxLineBytes must be positive" }
        cancellationCheck()
        val parent = target.absoluteFile.parentFile
            ?: throw IOException("Import target has no parent directory")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            throw IOException("Unable to create import directory")
        }
        val temporary = File.createTempFile(".${target.name}.", ".part", parent)
        return try {
            val result = writeTemporary(
                input,
                temporary,
                maxBytes,
                maxLineBytes,
                cancellationCheck
            )
            if (result is LocalTextImportWriteResult.Success) {
                cancellationCheck()
                if (!temporary.renameTo(target)) {
                    throw IOException("Unable to replace imported text")
                }
            }
            result
        } finally {
            temporary.delete()
        }
    }

    private fun writeTemporary(
        input: InputStream,
        temporary: File,
        maxBytes: Long,
        maxLineBytes: Int,
        cancellationCheck: () -> Unit
    ): LocalTextImportWriteResult {
        return FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val lineLengthValidator = LineLengthValidator(maxLineBytes)
            var bytesWritten = 0L
            while (true) {
                cancellationCheck()
                val remaining = maxBytes - bytesWritten
                val allowedRead = if (remaining >= buffer.size) {
                    buffer.size
                } else {
                    remaining.toInt() + 1
                }
                val read = input.read(buffer, 0, allowedRead)
                if (read < 0) break
                if (read == 0) continue
                if (read.toLong() > remaining) {
                    return@use LocalTextImportWriteResult.TooLarge
                }
                for (index in 0 until read) {
                    if (!lineLengthValidator.accept(buffer[index])) {
                        return@use LocalTextImportWriteResult.LineTooLong
                    }
                }
                output.write(buffer, 0, read)
                bytesWritten += read
                cancellationCheck()
            }
            if (!lineLengthValidator.finish()) {
                return@use LocalTextImportWriteResult.LineTooLong
            }
            if (bytesWritten == 0L) {
                LocalTextImportWriteResult.Empty
            } else {
                cancellationCheck()
                output.fd.sync()
                LocalTextImportWriteResult.Success(bytesWritten)
            }
        }
    }

    /**
     * Counts encoded line bytes without decoding or retaining line contents.
     *
     * UTF-16 has to be inspected as complete code units: one byte of an ordinary character may
     * equal 0A/0D even though the character itself is not LF/CR. BOM-less input keeps the legacy
     * byte-oriented path because its encoding cannot be identified reliably during a stream copy.
     */
    private class LineLengthValidator(private val maxLineBytes: Int) {
        private enum class Mode {
            BYTE_ORIENTED,
            UTF16_LE,
            UTF16_BE
        }

        private val bomProbe = ByteArray(UTF8_BOM.size)
        private var bomProbeSize = 0
        private var mode: Mode? = null
        private var currentLineBytes = 0
        private var firstUtf16Byte = NO_PENDING_BYTE

        fun accept(byte: Byte): Boolean {
            val resolvedMode = mode
            return if (resolvedMode == null) {
                probeBom(byte)
            } else {
                acceptBodyByte(resolvedMode, byte.toInt() and BYTE_MASK)
            }
        }

        fun finish(): Boolean {
            if (mode == null && !resolveByteOrientedProbe()) {
                return false
            }
            if (firstUtf16Byte != NO_PENDING_BYTE) {
                firstUtf16Byte = NO_PENDING_BYTE
                return addLineBytes(1)
            }
            return true
        }

        private fun probeBom(byte: Byte): Boolean {
            bomProbe[bomProbeSize] = byte
            bomProbeSize += 1
            return when (bomProbeSize) {
                1 -> {
                    val first = unsigned(bomProbe[0])
                    if (first == UTF16_LE_BOM_FIRST ||
                        first == UTF16_BE_BOM_FIRST ||
                        first == UTF8_BOM_FIRST
                    ) {
                        true
                    } else {
                        resolveByteOrientedProbe()
                    }
                }

                2 -> when {
                    unsigned(bomProbe[0]) == UTF16_LE_BOM_FIRST &&
                        unsigned(bomProbe[1]) == UTF16_LE_BOM_SECOND -> {
                        mode = Mode.UTF16_LE
                        bomProbeSize = 0
                        true
                    }

                    unsigned(bomProbe[0]) == UTF16_BE_BOM_FIRST &&
                        unsigned(bomProbe[1]) == UTF16_BE_BOM_SECOND -> {
                        mode = Mode.UTF16_BE
                        bomProbeSize = 0
                        true
                    }

                    unsigned(bomProbe[0]) == UTF8_BOM_FIRST &&
                        unsigned(bomProbe[1]) == UTF8_BOM_SECOND -> true

                    else -> resolveByteOrientedProbe()
                }

                UTF8_BOM.size -> {
                    if (bomProbe.contentEquals(UTF8_BOM)) {
                        mode = Mode.BYTE_ORIENTED
                        bomProbeSize = 0
                        true
                    } else {
                        resolveByteOrientedProbe()
                    }
                }

                else -> error("BOM probe exceeded its fixed buffer")
            }
        }

        private fun resolveByteOrientedProbe(): Boolean {
            mode = Mode.BYTE_ORIENTED
            for (index in 0 until bomProbeSize) {
                if (!acceptBodyByte(Mode.BYTE_ORIENTED, unsigned(bomProbe[index]))) {
                    bomProbeSize = 0
                    return false
                }
            }
            bomProbeSize = 0
            return true
        }

        private fun acceptBodyByte(resolvedMode: Mode, byte: Int): Boolean {
            if (resolvedMode == Mode.BYTE_ORIENTED) {
                return if (byte == CR_CODE || byte == LF_CODE) {
                    currentLineBytes = 0
                    true
                } else {
                    addLineBytes(1)
                }
            }

            if (firstUtf16Byte == NO_PENDING_BYTE) {
                firstUtf16Byte = byte
                return true
            }

            val codeUnit = if (resolvedMode == Mode.UTF16_LE) {
                firstUtf16Byte or (byte shl BITS_PER_BYTE)
            } else {
                (firstUtf16Byte shl BITS_PER_BYTE) or byte
            }
            firstUtf16Byte = NO_PENDING_BYTE
            return if (codeUnit == CR_CODE || codeUnit == LF_CODE) {
                currentLineBytes = 0
                true
            } else {
                addLineBytes(UTF16_CODE_UNIT_BYTES)
            }
        }

        private fun addLineBytes(count: Int): Boolean {
            if (currentLineBytes > maxLineBytes - count) {
                return false
            }
            currentLineBytes += count
            return true
        }

        private fun unsigned(byte: Byte): Int = byte.toInt() and BYTE_MASK
    }

    private fun ensureThreadIsActive() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("Local TXT import was cancelled")
        }
    }

    private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private const val UTF8_BOM_FIRST = 0xef
    private const val UTF8_BOM_SECOND = 0xbb
    private const val UTF16_LE_BOM_FIRST = 0xff
    private const val UTF16_LE_BOM_SECOND = 0xfe
    private const val UTF16_BE_BOM_FIRST = 0xfe
    private const val UTF16_BE_BOM_SECOND = 0xff
    private const val UTF16_CODE_UNIT_BYTES = 2
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xff
    private const val NO_PENDING_BYTE = -1
    private const val CR_CODE = '\r'.code
    private const val LF_CODE = '\n'.code
}
