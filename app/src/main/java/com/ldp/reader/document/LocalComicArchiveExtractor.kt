package com.ldp.reader.document

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipException
import java.util.zip.ZipFile

data class ExtractedComicPage(
    val sourceEntryName: String,
    val file: File
)

sealed interface ComicArchiveExtractionResult {
    data class Success(val pages: List<ExtractedComicPage>) : ComicArchiveExtractionResult
    data class Failure(
        val reason: ComicArchiveFailureReason,
        val message: String
    ) : ComicArchiveExtractionResult
}

enum class ComicArchiveFailureReason {
    UNSAFE_ENTRY,
    LIMIT_EXCEEDED,
    INSUFFICIENT_STORAGE,
    NO_IMAGES,
    INVALID_IMAGE,
    INVALID_ARCHIVE,
    IO_ERROR
}

object ComicArchiveFailureCopy {
    const val DAMAGED_FILE = "漫画文件已损坏"
    const val UNSUPPORTED_IMAGE = "包含不支持的图片"
    const val FILE_TOO_LARGE = "文件过大，暂时无法打开"
    const val INSUFFICIENT_STORAGE = "存储空间不足"

    fun messageFor(reason: ComicArchiveFailureReason): String {
        return when (reason) {
            ComicArchiveFailureReason.UNSAFE_ENTRY,
            ComicArchiveFailureReason.INVALID_ARCHIVE,
            ComicArchiveFailureReason.IO_ERROR -> DAMAGED_FILE

            ComicArchiveFailureReason.NO_IMAGES,
            ComicArchiveFailureReason.INVALID_IMAGE -> UNSUPPORTED_IMAGE

            ComicArchiveFailureReason.LIMIT_EXCEEDED -> FILE_TOO_LARGE
            ComicArchiveFailureReason.INSUFFICIENT_STORAGE -> INSUFFICIENT_STORAGE
        }
    }
}

data class ComicArchiveLimits(
    val maxEntries: Int = 2_000,
    val maxEntryNameBytes: Int = 1_024,
    val maxArchiveBytes: Long = 128L * 1024L * 1024L,
    val maxImageBytes: Long = 32L * 1024L * 1024L,
    val maxTotalImageBytes: Long = 256L * 1024L * 1024L,
    val maxCompressionRatio: Double = 200.0,
    val maxImageDimension: Int = 8_192,
    val maxImagePixels: Long = 16_000_000L,
    val maxGifFrames: Int = 1_000,
    val maxGifBlocks: Int = 100_000,
    val maxTotalGifFramePixels: Long = 64_000_000L
)

object ComicArchiveStoragePolicy {
    const val INSUFFICIENT_STORAGE_MESSAGE = "可用存储空间不足，无法安全解压漫画"

    fun requiredWorkingBytes(limits: ComicArchiveLimits): Long {
        require(limits.maxArchiveBytes >= 0L)
        require(limits.maxTotalImageBytes >= 0L)
        return if (limits.maxArchiveBytes > Long.MAX_VALUE - limits.maxTotalImageBytes) {
            Long.MAX_VALUE
        } else {
            limits.maxArchiveBytes + limits.maxTotalImageBytes
        }
    }

    fun hasEnoughSpace(availableBytes: Long, limits: ComicArchiveLimits): Boolean {
        return availableBytes >= requiredWorkingBytes(limits)
    }
}

object ComicArchiveCachePolicy {
    const val STALE_AFTER_MILLIS = 24L * 60L * 60L * 1_000L

    fun shouldDeleteDirectory(
        candidateName: String,
        candidatePath: String,
        currentPath: String,
        lastModifiedMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (!UUID_DIRECTORY_NAME.matches(candidateName)) return false
        if (candidatePath == currentPath || lastModifiedMillis <= 0L) return false
        if (nowMillis < STALE_AFTER_MILLIS) return false
        return lastModifiedMillis <= nowMillis - STALE_AFTER_MILLIS
    }

    private val UUID_DIRECTORY_NAME = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
}

class LocalComicArchiveExtractor(
    private val limits: ComicArchiveLimits = ComicArchiveLimits()
) {
    fun extract(
        input: InputStream,
        outputDirectory: File,
        checkCancelled: () -> Unit = {}
    ): ComicArchiveExtractionResult {
        checkCancelled()
        if ((!outputDirectory.exists() && !outputDirectory.mkdirs()) || !outputDirectory.isDirectory) {
            return ComicArchiveExtractionResult.Failure(
                reason = ComicArchiveFailureReason.IO_ERROR,
                message = "漫画压缩包读取失败"
            )
        }
        val archive = try {
            File.createTempFile("comic-", ".zip", outputDirectory)
        } catch (error: Exception) {
            return ComicArchiveExtractionResult.Failure(
                reason = ComicArchiveFailureReason.IO_ERROR,
                message = "漫画压缩包读取失败"
            )
        }
        val createdPages = ArrayList<File>()
        return try {
            archive.outputStream().use { output -> copyArchiveWithLimit(input, output, checkCancelled) }
            checkCancelled()
            ZipFile(archive).use { zip ->
                val allEntries = ArrayList<java.util.zip.ZipEntry>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    if (allEntries.size == limits.maxEntries) {
                        return ComicArchiveExtractionResult.Failure(
                            reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                            message = "压缩包条目数量超过安全上限（${limits.maxEntries}）"
                        )
                    }
                    allEntries += entries.nextElement()
                }
                allEntries.firstOrNull { entry ->
                    checkCancelled()
                    entry.name.toByteArray(Charsets.UTF_8).size > limits.maxEntryNameBytes
                }?.let {
                    return ComicArchiveExtractionResult.Failure(
                        reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                        message = "压缩包条目名称超过安全上限（${limits.maxEntryNameBytes} 字节）"
                    )
                }
                allEntries.firstOrNull {
                    checkCancelled()
                    isUnsafeEntryName(it.name)
                }?.let { entry ->
                    return ComicArchiveExtractionResult.Failure(
                        reason = ComicArchiveFailureReason.UNSAFE_ENTRY,
                        message = "压缩包包含不安全路径：${entry.name}"
                    )
                }
                val imageEntries = allEntries.asSequence()
                    .onEach { checkCancelled() }
                    .filterNot { it.isDirectory }
                    .filter { isImage(it.name) }
                    .sortedWith(compareByNaturalName { it.name })
                    .toList()
                if (imageEntries.isEmpty()) {
                    return ComicArchiveExtractionResult.Failure(
                        reason = ComicArchiveFailureReason.NO_IMAGES,
                        message = "压缩包中没有可读取的图片"
                    )
                }
                imageEntries.firstOrNull {
                    checkCancelled()
                    it.size > limits.maxImageBytes
                }?.let { entry ->
                    return ComicArchiveExtractionResult.Failure(
                        reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                        message = "图片 ${entry.name} 超过单页解压上限"
                    )
                }
                imageEntries.firstOrNull { entry ->
                    checkCancelled()
                    entry.size > 0 &&
                        (entry.compressedSize <= 0 || entry.size.toDouble() / entry.compressedSize > limits.maxCompressionRatio)
                }?.let { entry ->
                    return ComicArchiveExtractionResult.Failure(
                        reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                        message = "图片 ${entry.name} 的压缩比超过安全上限"
                    )
                }
                var totalImageBytes = 0L
                imageEntries.forEach { entry ->
                    checkCancelled()
                    if (entry.size > limits.maxTotalImageBytes - totalImageBytes) {
                        return ComicArchiveExtractionResult.Failure(
                            reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                            message = "漫画图片总解压大小超过安全上限"
                        )
                    }
                    if (entry.size > 0) totalImageBytes += entry.size
                }
                var extractedTotalImageBytes = 0L
                val pages = imageEntries.mapIndexed { index, entry ->
                    checkCancelled()
                    val extension = entry.name.substringAfterLast('.').lowercase(Locale.ROOT)
                    val output = File(outputDirectory, "page-${(index + 1).toString().padStart(6, '0')}.$extension")
                    createdPages += output
                    val copiedImage = zip.getInputStream(entry).use { source ->
                        output.outputStream().use { target ->
                            copyImageWithLimits(
                                source = source,
                                target = target,
                                entryName = entry.name,
                                totalBytesBeforeEntry = extractedTotalImageBytes,
                                checkCancelled = checkCancelled
                            )
                        }
                    }
                    if ((entry.size >= 0 && copiedImage.bytes != entry.size) ||
                        (entry.crc >= 0 && copiedImage.crc != entry.crc)
                    ) {
                        throw ComicExtractionException(
                            reason = ComicArchiveFailureReason.INVALID_ARCHIVE,
                            message = "漫画压缩包损坏或格式不正确"
                        )
                    }
                    extractedTotalImageBytes += copiedImage.bytes
                    checkCancelled()
                    val imageInspection = inspectImageDimensions(
                        file = output,
                        extension = extension,
                        entryName = entry.name,
                        checkCancelled = checkCancelled
                    )
                    if (imageInspection == null) {
                        throw ComicExtractionException(
                            reason = ComicArchiveFailureReason.INVALID_IMAGE,
                            message = "图片文件损坏：${entry.name}"
                        )
                    }
                    val unsafeDimensions = imageInspection.unsafeDimensions
                    if (unsafeDimensions != null) {
                        throw ComicExtractionException(
                            reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                            message = "图片 ${entry.name} 的尺寸超过安全上限" +
                                "（${unsafeDimensions.width}×${unsafeDimensions.height}）"
                        )
                    }
                    ExtractedComicPage(entry.name, output)
                }
                ComicArchiveExtractionResult.Success(pages)
            }
        } catch (error: CancellationException) {
            createdPages.forEach { it.delete() }
            throw error
        } catch (error: ComicExtractionException) {
            createdPages.forEach { it.delete() }
            ComicArchiveExtractionResult.Failure(
                reason = error.reason,
                message = error.message.orEmpty()
            )
        } catch (error: ZipException) {
            createdPages.forEach { it.delete() }
            ComicArchiveExtractionResult.Failure(
                reason = ComicArchiveFailureReason.INVALID_ARCHIVE,
                message = "漫画压缩包损坏或格式不正确"
            )
        } catch (error: Exception) {
            createdPages.forEach { it.delete() }
            ComicArchiveExtractionResult.Failure(
                reason = ComicArchiveFailureReason.IO_ERROR,
                message = "漫画压缩包读取失败"
            )
        } finally {
            archive.delete()
        }
    }

    private fun copyArchiveWithLimit(
        source: InputStream,
        target: java.io.OutputStream,
        checkCancelled: () -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var archiveBytes = 0L
        while (archiveBytes < limits.maxArchiveBytes) {
            checkCancelled()
            val allowedRead = minOf(buffer.size.toLong(), limits.maxArchiveBytes - archiveBytes).toInt()
            val read = source.read(buffer, 0, allowedRead)
            if (read < 0) return
            if (read == 0) continue
            target.write(buffer, 0, read)
            archiveBytes += read
        }
        checkCancelled()
        if (source.read() >= 0) {
            throw ComicExtractionException(
                reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                message = "漫画压缩包大小超过安全上限"
            )
        }
    }

    private fun copyImageWithLimits(
        source: InputStream,
        target: java.io.OutputStream,
        entryName: String,
        totalBytesBeforeEntry: Long,
        checkCancelled: () -> Unit
    ): CopiedImage {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val crc = CRC32()
        var imageBytes = 0L
        while (true) {
            checkCancelled()
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (read > limits.maxImageBytes - imageBytes) {
                throw ComicExtractionException(
                    reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                    message = "图片 $entryName 超过单页解压上限"
                )
            }
            if (read > limits.maxTotalImageBytes - totalBytesBeforeEntry - imageBytes) {
                throw ComicExtractionException(
                    reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                    message = "漫画图片总解压大小超过安全上限"
                )
            }
            target.write(buffer, 0, read)
            crc.update(buffer, 0, read)
            imageBytes += read
        }
        checkCancelled()
        return CopiedImage(bytes = imageBytes, crc = crc.value)
    }

    private fun isUnsafeEntryName(name: String): Boolean {
        if (name.isBlank() || '\u0000' in name) return true
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') || WINDOWS_DRIVE_PATH.matches(normalized)) return true
        return normalized.split('/').any { it == ".." }
    }

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif")
    }

    private fun inspectImageDimensions(
        file: File,
        extension: String,
        entryName: String,
        checkCancelled: () -> Unit
    ): ImageInspection? {
        checkCancelled()
        if (!file.isFile) return null
        return try {
            val header = ByteArray(12)
            val headerSize = file.inputStream().use { it.read(header) }
            when (extension) {
                "jpg", "jpeg" ->
                    if (headerSize >= 3 &&
                        header[0].unsigned() == 0xff &&
                        header[1].unsigned() == 0xd8 &&
                        header[2].unsigned() == 0xff &&
                        file.endsWith(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
                    ) {
                        file.jpegDimensions(checkCancelled)?.toInspection()
                    } else {
                        null
                    }
                "png" ->
                    if (headerSize >= PNG_SIGNATURE.size &&
                        header.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
                    ) {
                        file.inspectPng(checkCancelled)
                    } else {
                        null
                    }
                "gif" ->
                    if (headerSize >= 6 &&
                        (header.copyOf(6).contentEquals("GIF87a".toByteArray()) ||
                            header.copyOf(6).contentEquals("GIF89a".toByteArray())) &&
                        file.endsWith(byteArrayOf(0x3b))
                    ) {
                        file.inspectGifDimensions(entryName, checkCancelled)
                    } else {
                        null
                    }
                "webp" ->
                    if (headerSize >= 12 &&
                        header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                        header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) &&
                        header.riffDeclaredFileSize() == file.length()
                    ) {
                        file.webpDimensions()?.toInspection()
                    } else {
                        null
                    }
                else -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ComicExtractionException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun File.inspectPng(checkCancelled: () -> Unit): ImageInspection? {
        if (length() < PNG_MINIMUM_FILE_BYTES) return null
        return RandomAccessFile(this, "r").use { file ->
            val signature = ByteArray(PNG_SIGNATURE.size).also(file::readFully)
            if (!signature.contentEquals(PNG_SIGNATURE)) return@use null

            val inflater = Inflater()
            val compressedBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val inflatedBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var dimensions: ImageDimensions? = null
            var scanlines: PngScanlineValidator? = null
            var bitDepth = -1
            var colorType = -1
            var paletteSeen = false
            var idatSeen = false
            var idatEnded = false
            var iendSeen = false
            var chunkIndex = 0
            try {
                while (file.filePointer < file.length()) {
                    checkCancelled()
                    if (file.length() - file.filePointer < PNG_CHUNK_OVERHEAD_BYTES) return@use null
                    val chunkLength = file.readInt().toLong() and UINT_MASK
                    if (chunkLength > file.length() - file.filePointer - PNG_CHUNK_TYPE_AND_CRC_BYTES) {
                        return@use null
                    }
                    val typeBytes = ByteArray(PNG_CHUNK_TYPE_BYTES).also(file::readFully)
                    if (typeBytes.any { byte -> !byte.isAsciiLetter() }) return@use null
                    val type = String(typeBytes, Charsets.US_ASCII)
                    val crc = CRC32().apply { update(typeBytes) }
                    var unsafeDimensions: ImageDimensions? = null

                    when (type) {
                        "IHDR" -> {
                            if (chunkIndex != 0 || dimensions != null || chunkLength != PNG_IHDR_BYTES) {
                                return@use null
                            }
                            val header = ByteArray(PNG_IHDR_BYTES.toInt()).also(file::readFully)
                            crc.update(header)
                            val parsed = ImageDimensions(
                                width = header.readBigEndianUnsignedInt(0),
                                height = header.readBigEndianUnsignedInt(4)
                            ).takeIf { it.isPositive } ?: return@use null
                            bitDepth = header[8].unsigned()
                            colorType = header[9].unsigned()
                            if (!isSupportedPngColorMode(bitDepth, colorType) ||
                                header[10].unsigned() != PNG_COMPRESSION_METHOD ||
                                header[11].unsigned() != PNG_FILTER_METHOD ||
                                header[12].unsigned() !in PNG_INTERLACE_METHODS
                            ) {
                                return@use null
                            }
                            dimensions = parsed
                            unsafeDimensions = parsed.takeIf { it.exceeds(limits) }
                            if (unsafeDimensions == null) {
                                scanlines = PngScanlineValidator.create(
                                    width = parsed.width,
                                    height = parsed.height,
                                    bitsPerPixel = pngBitsPerPixel(bitDepth, colorType),
                                    interlaced = header[12].unsigned() == PNG_INTERLACE_ADAM7
                                ) ?: return@use null
                            }
                        }
                        "PLTE" -> {
                            val paletteEntries = chunkLength / PNG_PALETTE_ENTRY_BYTES
                            if (dimensions == null || idatSeen || paletteSeen ||
                                chunkLength !in PNG_PALETTE_BYTES_RANGE ||
                                chunkLength % PNG_PALETTE_ENTRY_BYTES != 0L ||
                                colorType == PNG_COLOR_GRAYSCALE ||
                                colorType == PNG_COLOR_GRAYSCALE_ALPHA ||
                                colorType == PNG_COLOR_INDEXED &&
                                (bitDepth !in PNG_INDEXED_BIT_DEPTHS || paletteEntries > (1 shl bitDepth))
                            ) {
                                return@use null
                            }
                            if (!file.consumePngChunkData(
                                    chunkLength,
                                    crc,
                                    compressedBuffer,
                                    checkCancelled
                                )
                            ) {
                                return@use null
                            }
                            paletteSeen = true
                        }
                        "IDAT" -> {
                            val validator = scanlines ?: return@use null
                            if (idatEnded || (colorType == PNG_COLOR_INDEXED && !paletteSeen)) {
                                return@use null
                            }
                            idatSeen = true
                            var remaining = chunkLength
                            while (remaining > 0L) {
                                checkCancelled()
                                val count = minOf(remaining, compressedBuffer.size.toLong()).toInt()
                                file.readFully(compressedBuffer, 0, count)
                                crc.update(compressedBuffer, 0, count)
                                if (!inflater.consumePngData(
                                        compressedBuffer,
                                        count,
                                        inflatedBuffer,
                                        validator,
                                        checkCancelled
                                    )
                                ) {
                                    return@use null
                                }
                                remaining -= count
                            }
                        }
                        "IEND" -> {
                            if (dimensions == null || chunkLength != 0L || !idatSeen || iendSeen) {
                                return@use null
                            }
                            iendSeen = true
                        }
                        else -> {
                            if (typeBytes[0].unsigned() and PNG_ANCILLARY_BIT == 0) return@use null
                            if (!file.consumePngChunkData(
                                    chunkLength,
                                    crc,
                                    compressedBuffer,
                                    checkCancelled
                                )
                            ) {
                                return@use null
                            }
                            if (idatSeen) idatEnded = true
                        }
                    }

                    val expectedCrc = file.readInt().toLong() and UINT_MASK
                    if (crc.value != expectedCrc) return@use null
                    unsafeDimensions?.let { return@use ImageInspection(unsafeDimensions = it) }

                    if (type == "IEND") {
                        val validator = scanlines ?: return@use null
                        if (file.filePointer != file.length() ||
                            !inflater.finished() || inflater.remaining != 0 || !validator.isComplete
                        ) {
                            return@use null
                        }
                        break
                    }
                    if (type != "IDAT" && idatSeen) idatEnded = true
                    chunkIndex++
                }
                if (iendSeen) ImageInspection(unsafeDimensions = null) else null
            } catch (_: DataFormatException) {
                null
            } finally {
                inflater.end()
            }
        }
    }

    private fun RandomAccessFile.consumePngChunkData(
        byteCount: Long,
        crc: CRC32,
        buffer: ByteArray,
        checkCancelled: () -> Unit
    ): Boolean {
        var remaining = byteCount
        while (remaining > 0L) {
            checkCancelled()
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            readFully(buffer, 0, count)
            crc.update(buffer, 0, count)
            remaining -= count
        }
        return true
    }

    @Throws(DataFormatException::class)
    private fun Inflater.consumePngData(
        input: ByteArray,
        inputSize: Int,
        output: ByteArray,
        validator: PngScanlineValidator,
        checkCancelled: () -> Unit
    ): Boolean {
        if (finished() || !needsInput()) return false
        setInput(input, 0, inputSize)
        while (!needsInput()) {
            checkCancelled()
            val inflated = inflate(output)
            if (inflated > 0) {
                if (!validator.consume(output, inflated)) return false
                continue
            }
            if (finished()) break
            if (needsDictionary() || !needsInput()) return false
        }
        return remaining == 0
    }

    private fun isSupportedPngColorMode(bitDepth: Int, colorType: Int): Boolean {
        return when (colorType) {
            PNG_COLOR_GRAYSCALE -> bitDepth in setOf(1, 2, 4, 8, 16)
            PNG_COLOR_TRUECOLOR -> bitDepth == 8 || bitDepth == 16
            PNG_COLOR_INDEXED -> bitDepth in setOf(1, 2, 4, 8)
            PNG_COLOR_GRAYSCALE_ALPHA, PNG_COLOR_TRUECOLOR_ALPHA -> bitDepth == 8 || bitDepth == 16
            else -> false
        }
    }

    private fun pngBitsPerPixel(bitDepth: Int, colorType: Int): Int {
        val channels = when (colorType) {
            PNG_COLOR_GRAYSCALE, PNG_COLOR_INDEXED -> 1
            PNG_COLOR_TRUECOLOR -> 3
            PNG_COLOR_GRAYSCALE_ALPHA -> 2
            PNG_COLOR_TRUECOLOR_ALPHA -> 4
            else -> return 0
        }
        return bitDepth * channels
    }

    private fun ByteArray.readBigEndianUnsignedInt(offset: Int): Long {
        return (this[offset].unsigned().toLong() shl 24) or
            (this[offset + 1].unsigned().toLong() shl 16) or
            (this[offset + 2].unsigned().toLong() shl 8) or
            this[offset + 3].unsigned().toLong()
    }

    private fun Byte.isAsciiLetter(): Boolean {
        val value = unsigned()
        return value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code
    }

    private fun ImageDimensions.toInspection(): ImageInspection {
        return ImageInspection(unsafeDimensions = takeIf { it.exceeds(limits) })
    }

    private fun File.inspectGifDimensions(
        entryName: String,
        checkCancelled: () -> Unit
    ): ImageInspection? {
        checkCancelled()
        if (length() < GIF_MINIMUM_FILE_BYTES) return null
        return RandomAccessFile(this, "r").use { file ->
            file.seek(6)
            val logicalScreen = ImageDimensions(
                width = file.readLittleEndianUnsignedShort(),
                height = file.readLittleEndianUnsignedShort()
            ).takeIf { it.isPositive } ?: return@use null
            if (logicalScreen.exceeds(limits)) throwGifDimensionLimit(entryName, logicalScreen)

            val budget = GifBudget()
            val logicalScreenPacked = file.readUnsignedByte()
            if (!file.skipBytesWithinFile(2) ||
                !file.skipGifColorTable(logicalScreenPacked, budget, entryName)
            ) {
                return@use null
            }

            while (file.filePointer < file.length()) {
                checkCancelled()
                when (file.readUnsignedByte()) {
                    GIF_TRAILER ->
                        return@use if (budget.frames > 0 && file.filePointer == file.length()) {
                            ImageInspection(unsafeDimensions = null)
                        } else {
                            null
                        }
                    GIF_EXTENSION_INTRODUCER -> {
                        budget.consumeBlock(entryName)
                        if (file.filePointer >= file.length()) return@use null
                        file.readUnsignedByte()
                        if (!file.skipGifSubBlocks(budget, entryName, checkCancelled)) return@use null
                    }
                    GIF_IMAGE_SEPARATOR -> {
                        budget.consumeBlock(entryName)
                        val frame = ImageDimensions(
                            left = file.readLittleEndianUnsignedShortOrNull() ?: return@use null,
                            top = file.readLittleEndianUnsignedShortOrNull() ?: return@use null,
                            width = file.readLittleEndianUnsignedShortOrNull() ?: return@use null,
                            height = file.readLittleEndianUnsignedShortOrNull() ?: return@use null
                        ).takeIf { it.isPositive } ?: return@use null
                        if (frame.exceeds(limits)) throwGifDimensionLimit(entryName, frame)
                        if (!frame.fitsWithin(logicalScreen)) return@use null
                        budget.consumeFrame(entryName, frame)
                        if (file.filePointer >= file.length()) return@use null
                        val imagePacked = file.readUnsignedByte()
                        if (!file.skipGifColorTable(imagePacked, budget, entryName) ||
                            file.filePointer >= file.length()
                        ) {
                            return@use null
                        }
                        val lzwMinimumCodeSize = file.readUnsignedByte()
                        if (lzwMinimumCodeSize !in GIF_LZW_CODE_SIZE_RANGE ||
                            !file.skipGifSubBlocks(
                                budget = budget,
                                entryName = entryName,
                                checkCancelled = checkCancelled,
                                requireData = true
                            )
                        ) {
                            return@use null
                        }
                    }
                    else -> return@use null
                }
            }
            null
        }
    }

    private fun throwGifDimensionLimit(entryName: String, dimensions: ImageDimensions): Nothing {
        throw ComicExtractionException(
            reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
            message = "图片 $entryName 的尺寸超过安全上限" +
                "（${dimensions.width}×${dimensions.height}）"
        )
    }

    private fun GifBudget.consumeBlock(entryName: String) {
        if (blocks >= limits.maxGifBlocks) {
            throw ComicExtractionException(
                reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                message = "图片 $entryName 的 GIF 数据块数量超过安全上限（${limits.maxGifBlocks}）"
            )
        }
        blocks++
    }

    private fun GifBudget.consumeFrame(entryName: String, frame: ImageDimensions) {
        if (frames >= limits.maxGifFrames) {
            throw ComicExtractionException(
                reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                message = "图片 $entryName 的 GIF 帧数超过安全上限（${limits.maxGifFrames}）"
            )
        }
        val framePixels = frame.width * frame.height
        if (framePixels > limits.maxTotalGifFramePixels ||
            totalFramePixels > limits.maxTotalGifFramePixels - framePixels
        ) {
            throw ComicExtractionException(
                reason = ComicArchiveFailureReason.LIMIT_EXCEEDED,
                message = "图片 $entryName 的 GIF 累计帧像素超过安全上限" +
                    "（${limits.maxTotalGifFramePixels}）"
            )
        }
        frames++
        totalFramePixels += framePixels
    }

    private fun RandomAccessFile.skipGifColorTable(
        packed: Int,
        budget: GifBudget,
        entryName: String
    ): Boolean {
        if (packed and GIF_COLOR_TABLE_FLAG == 0) return true
        budget.consumeBlock(entryName)
        val tableBytes = 3L shl ((packed and GIF_COLOR_TABLE_SIZE_MASK) + 1)
        return skipBytesWithinFile(tableBytes)
    }

    private fun RandomAccessFile.skipGifSubBlocks(
        budget: GifBudget,
        entryName: String,
        checkCancelled: () -> Unit,
        requireData: Boolean = false
    ): Boolean {
        var hasData = false
        while (filePointer < length()) {
            checkCancelled()
            val blockSize = readUnsignedByte().toLong()
            if (blockSize == 0L) return hasData || !requireData
            budget.consumeBlock(entryName)
            if (!skipBytesWithinFile(blockSize)) return false
            hasData = true
        }
        return false
    }

    private fun RandomAccessFile.readLittleEndianUnsignedShortOrNull(): Long? {
        if (length() - filePointer < 2) return null
        return readLittleEndianUnsignedShort()
    }

    private fun RandomAccessFile.readLittleEndianUnsignedShort(): Long {
        return readUnsignedByte().toLong() or (readUnsignedByte().toLong() shl 8)
    }

    private fun RandomAccessFile.skipBytesWithinFile(byteCount: Long): Boolean {
        if (byteCount < 0 || byteCount > length() - filePointer) return false
        seek(filePointer + byteCount)
        return true
    }

    private fun File.jpegDimensions(checkCancelled: () -> Unit): ImageDimensions? {
        return RandomAccessFile(this, "r").use { file ->
            if (file.length() < 4 || file.readUnsignedShort() != JPEG_START_OF_IMAGE_HEADER) return@use null
            while (file.filePointer < file.length()) {
                checkCancelled()
                if (file.readUnsignedByte() != JPEG_MARKER_PREFIX) continue
                var marker: Int
                do {
                    if (file.filePointer >= file.length()) return@use null
                    marker = file.readUnsignedByte()
                } while (marker == JPEG_MARKER_PREFIX)
                if (marker == 0 || marker == JPEG_TEMPORARY ||
                    marker == JPEG_START_OF_IMAGE_MARKER || marker in JPEG_RESTART_MARKERS
                ) {
                    continue
                }
                if (marker == JPEG_END_OF_IMAGE || marker == JPEG_START_OF_SCAN) return@use null
                if (file.filePointer + 2 > file.length()) return@use null
                val segmentLength = file.readUnsignedShort()
                if (segmentLength < 2) return@use null
                val segmentEnd = file.filePointer + segmentLength - 2L
                if (segmentEnd > file.length()) return@use null
                if (marker in JPEG_START_OF_FRAME_MARKERS) {
                    if (segmentLength < 8) return@use null
                    file.readUnsignedByte()
                    val height = file.readUnsignedShort().toLong()
                    val width = file.readUnsignedShort().toLong()
                    return@use ImageDimensions(width, height).takeIf { it.isPositive }
                }
                file.seek(segmentEnd)
            }
            null
        }
    }

    private fun File.webpDimensions(): ImageDimensions? {
        if (length() < 20) return null
        return RandomAccessFile(this, "r").use { file ->
            file.seek(12)
            val chunkType = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
            val chunkSize = file.readLittleEndianUnsignedInt()
            val chunkDataStart = file.filePointer
            if (chunkSize > file.length() - chunkDataStart) return@use null
            when (chunkType) {
                "VP8X" -> {
                    if (chunkSize < 10) return@use null
                    file.seek(chunkDataStart + 4)
                    ImageDimensions(
                        width = file.readLittleEndianUnsigned24() + 1,
                        height = file.readLittleEndianUnsigned24() + 1
                    )
                }
                "VP8L" -> {
                    if (chunkSize < 5 || file.readUnsignedByte() != WEBP_LOSSLESS_SIGNATURE) return@use null
                    val first = file.readUnsignedByte()
                    val second = file.readUnsignedByte()
                    val third = file.readUnsignedByte()
                    val fourth = file.readUnsignedByte()
                    ImageDimensions(
                        width = 1L + first + ((second and 0x3f) shl 8),
                        height = 1L + (second ushr 6) + (third shl 2) + ((fourth and 0x0f) shl 10)
                    )
                }
                "VP8 " -> {
                    if (chunkSize < 10) return@use null
                    file.seek(chunkDataStart + 3)
                    val signature = ByteArray(3).also(file::readFully)
                    if (!signature.contentEquals(WEBP_LOSSY_SIGNATURE)) return@use null
                    val width = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
                    val height = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
                    ImageDimensions(
                        width = (width and 0x3fff).toLong(),
                        height = (height and 0x3fff).toLong()
                    ).takeIf { it.isPositive }
                }
                else -> null
            }
        }
    }

    private fun File.endsWith(suffix: ByteArray): Boolean {
        if (length() < suffix.size) return false
        return RandomAccessFile(this, "r").use { file ->
            file.seek(length() - suffix.size)
            ByteArray(suffix.size).also(file::readFully).contentEquals(suffix)
        }
    }

    private fun ByteArray.riffDeclaredFileSize(): Long {
        val riffPayloadSize =
            this[4].unsigned().toLong() or
                (this[5].unsigned().toLong() shl 8) or
                (this[6].unsigned().toLong() shl 16) or
                (this[7].unsigned().toLong() shl 24)
        return riffPayloadSize + 8
    }

    private fun RandomAccessFile.readLittleEndianUnsignedInt(): Long {
        return readUnsignedByte().toLong() or
            (readUnsignedByte().toLong() shl 8) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 24)
    }

    private fun RandomAccessFile.readLittleEndianUnsigned24(): Long {
        return readUnsignedByte().toLong() or
            (readUnsignedByte().toLong() shl 8) or
            (readUnsignedByte().toLong() shl 16)
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff

    private fun <T> compareByNaturalName(name: (T) -> String): Comparator<T> {
        return Comparator { left, right -> naturalCompare(name(left), name(right)) }
    }

    private fun naturalCompare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]
            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftEnd = left.indexAfterDigits(leftIndex)
                val rightEnd = right.indexAfterDigits(rightIndex)
                val leftNumber = left.substring(leftIndex, leftEnd).trimStart('0').ifEmpty { "0" }
                val rightNumber = right.substring(rightIndex, rightEnd).trimStart('0').ifEmpty { "0" }
                if (leftNumber.length != rightNumber.length) return leftNumber.length.compareTo(rightNumber.length)
                val numberComparison = leftNumber.compareTo(rightNumber)
                if (numberComparison != 0) return numberComparison
                leftIndex = leftEnd
                rightIndex = rightEnd
            } else {
                val charComparison = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
                if (charComparison != 0) return charComparison
                leftIndex++
                rightIndex++
            }
        }
        return left.length.compareTo(right.length)
    }

    private fun String.indexAfterDigits(start: Int): Int {
        var index = start
        while (index < length && this[index].isDigit()) index++
        return index
    }

    private companion object {
        const val UINT_MASK = 0xffff_ffffL
        const val PNG_IHDR = 0x49484452
        const val PNG_MINIMUM_FILE_BYTES = 57L
        const val PNG_CHUNK_TYPE_BYTES = 4
        const val PNG_IHDR_BYTES = 13L
        const val PNG_CHUNK_OVERHEAD_BYTES = 12L
        const val PNG_CHUNK_TYPE_AND_CRC_BYTES = 8L
        const val PNG_COMPRESSION_METHOD = 0
        const val PNG_FILTER_METHOD = 0
        const val PNG_INTERLACE_ADAM7 = 1
        val PNG_INTERLACE_METHODS = 0..1
        val PNG_PALETTE_BYTES_RANGE = 3L..768L
        const val PNG_ANCILLARY_BIT = 0x20
        const val PNG_PALETTE_ENTRY_BYTES = 3L
        val PNG_INDEXED_BIT_DEPTHS = setOf(1, 2, 4, 8)
        const val PNG_COLOR_GRAYSCALE = 0
        const val PNG_COLOR_TRUECOLOR = 2
        const val PNG_COLOR_INDEXED = 3
        const val PNG_COLOR_GRAYSCALE_ALPHA = 4
        const val PNG_COLOR_TRUECOLOR_ALPHA = 6
        const val GIF_MINIMUM_FILE_BYTES = 14L
        const val GIF_EXTENSION_INTRODUCER = 0x21
        const val GIF_IMAGE_SEPARATOR = 0x2c
        const val GIF_TRAILER = 0x3b
        const val GIF_COLOR_TABLE_FLAG = 0x80
        const val GIF_COLOR_TABLE_SIZE_MASK = 0x07
        val GIF_LZW_CODE_SIZE_RANGE = 2..8
        const val JPEG_MARKER_PREFIX = 0xff
        const val JPEG_START_OF_IMAGE_HEADER = 0xffd8
        const val JPEG_START_OF_IMAGE_MARKER = 0xd8
        const val JPEG_END_OF_IMAGE = 0xd9
        const val JPEG_START_OF_SCAN = 0xda
        const val JPEG_TEMPORARY = 0x01
        const val WEBP_LOSSLESS_SIGNATURE = 0x2f
        val JPEG_RESTART_MARKERS = 0xd0..0xd7
        val JPEG_START_OF_FRAME_MARKERS = setOf(
            0xc0, 0xc1, 0xc2, 0xc3,
            0xc5, 0xc6, 0xc7,
            0xc9, 0xca, 0xcb,
            0xcd, 0xce, 0xcf
        )
        val WEBP_LOSSY_SIGNATURE = byteArrayOf(0x9d.toByte(), 0x01, 0x2a)
        val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:/.*")
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a
        )
    }
}

private data class ImageDimensions(
    val width: Long,
    val height: Long,
    val left: Long = 0,
    val top: Long = 0
) {
    val isPositive: Boolean
        get() = width > 0 && height > 0

    fun exceeds(limits: ComicArchiveLimits): Boolean {
        return width > limits.maxImageDimension ||
            height > limits.maxImageDimension ||
            width > limits.maxImagePixels / height
    }

    fun fitsWithin(logicalScreen: ImageDimensions): Boolean {
        return width <= logicalScreen.width &&
            height <= logicalScreen.height &&
            left <= logicalScreen.width - width &&
            top <= logicalScreen.height - height
    }
}

private data class ImageInspection(
    val unsafeDimensions: ImageDimensions?
)

private class PngScanlineValidator private constructor(
    private val rowPayloadBytes: LongArray
) {
    private var rowIndex = 0
    private var payloadBytesRemaining = 0L

    val isComplete: Boolean
        get() = rowIndex == rowPayloadBytes.size && payloadBytesRemaining == 0L

    fun consume(bytes: ByteArray, size: Int): Boolean {
        var offset = 0
        while (offset < size) {
            if (rowIndex >= rowPayloadBytes.size) return false
            if (payloadBytesRemaining == 0L) {
                if (bytes[offset].unsignedValue() !in PNG_FILTER_TYPES) return false
                payloadBytesRemaining = rowPayloadBytes[rowIndex]
                offset++
                if (payloadBytesRemaining == 0L) rowIndex++
                continue
            }
            val consumed = minOf(payloadBytesRemaining, (size - offset).toLong())
            payloadBytesRemaining -= consumed
            offset += consumed.toInt()
            if (payloadBytesRemaining == 0L) rowIndex++
        }
        return true
    }

    companion object {
        private val PNG_FILTER_TYPES = 0..4
        private val ADAM7_START_X = intArrayOf(0, 4, 0, 2, 0, 1, 0)
        private val ADAM7_START_Y = intArrayOf(0, 0, 4, 0, 2, 0, 1)
        private val ADAM7_STEP_X = intArrayOf(8, 8, 4, 4, 2, 2, 1)
        private val ADAM7_STEP_Y = intArrayOf(8, 8, 8, 4, 4, 2, 2)

        fun create(
            width: Long,
            height: Long,
            bitsPerPixel: Int,
            interlaced: Boolean
        ): PngScanlineValidator? {
            if (width <= 0L || height <= 0L || bitsPerPixel <= 0) return null
            val rows = ArrayList<Long>()
            if (!interlaced) {
                val payload = rowPayloadBytes(width, bitsPerPixel) ?: return null
                repeat(height.toInt()) { rows += payload }
            } else {
                for (pass in ADAM7_START_X.indices) {
                    val passWidth = passExtent(width, ADAM7_START_X[pass], ADAM7_STEP_X[pass])
                    val passHeight = passExtent(height, ADAM7_START_Y[pass], ADAM7_STEP_Y[pass])
                    if (passWidth == 0L || passHeight == 0L) continue
                    val payload = rowPayloadBytes(passWidth, bitsPerPixel) ?: return null
                    repeat(passHeight.toInt()) { rows += payload }
                }
            }
            return PngScanlineValidator(rows.toLongArray())
        }

        private fun passExtent(total: Long, start: Int, step: Int): Long {
            if (total <= start.toLong()) return 0L
            return (total - start + step - 1L) / step
        }

        private fun rowPayloadBytes(width: Long, bitsPerPixel: Int): Long? {
            if (width > (Long.MAX_VALUE - 7L) / bitsPerPixel) return null
            return (width * bitsPerPixel + 7L) / 8L
        }
    }
}

private fun Byte.unsignedValue(): Int = toInt() and 0xff

private data class GifBudget(
    var blocks: Int = 0,
    var frames: Int = 0,
    var totalFramePixels: Long = 0
)

private class ComicExtractionException(
    val reason: ComicArchiveFailureReason,
    message: String
) : Exception(message)

private data class CopiedImage(
    val bytes: Long,
    val crc: Long
)
