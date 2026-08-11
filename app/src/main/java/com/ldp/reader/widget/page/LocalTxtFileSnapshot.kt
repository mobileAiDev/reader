package com.ldp.reader.widget.page

import com.ldp.reader.utils.Charset as ReaderCharset
import com.ldp.reader.utils.Utf8ContentDetector
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import kotlin.math.min

/**
 * One open file description shared by catalog parsing and chapter readers.
 *
 * Replacing [sourceFile] changes the path, not this already-open description, so byte offsets and
 * bytes always belong to the same file generation for the lifetime of a reading session.
 */
internal class LocalTxtFileSnapshot private constructor(
    val sourceFile: File,
    private val owner: FileInputStream,
    private val channel: FileChannel,
    val length: Long,
    val lastModified: Long,
    private val deleteOnClose: File?
) : Closeable {

    @Throws(IOException::class)
    fun openRange(start: Long, end: Long): InputStream {
        if (!channel.isOpen) {
            throw IOException("TXT file snapshot is closed")
        }
        require(start >= 0L) { "start must not be negative" }
        require(end >= start) { "end must not be before start" }
        val boundedStart = min(start, length)
        val boundedEnd = min(end, length)
        return SnapshotRangeInputStream(channel, boundedStart, boundedEnd)
    }

    @Throws(IOException::class)
    fun openStream(): InputStream = openRange(0L, length)

    @Throws(IOException::class)
    fun currentLength(): Long {
        if (!channel.isOpen) {
            throw IOException("TXT file snapshot is closed")
        }
        return channel.size()
    }

    override fun close() {
        try {
            owner.close()
        } finally {
            deleteOnClose?.delete()
        }
    }

    companion object {
        @Throws(IOException::class)
        fun open(file: File): LocalTxtFileSnapshot {
            return openBackingFile(
                sourceFile = file,
                backingFile = file,
                lastModified = file.lastModified(),
                deleteOnClose = null
            )
        }

        /**
         * Copies [file] into an app-private file and validates the complete copy against the
         * current source path. The returned handle therefore remains immutable even when another
         * process overwrites or truncates the original inode in place.
         */
        @Throws(IOException::class)
        fun createImmutableCopy(
            file: File,
            snapshotDirectory: File,
            cancellationCheck: () -> Unit = {}
        ): LocalTxtFileSnapshot {
            cancellationCheck()
            if (!snapshotDirectory.exists() && !snapshotDirectory.mkdirs()) {
                throw IOException("Could not create local TXT snapshot directory")
            }
            val logicalLastModified = file.lastModified()
            val snapshotFile = File.createTempFile(SNAPSHOT_PREFIX, SNAPSHOT_SUFFIX, snapshotDirectory)
            var snapshot: LocalTxtFileSnapshot? = null
            return try {
                FileInputStream(file).use { input ->
                    FileOutputStream(snapshotFile).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            cancellationCheck()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                output.write(buffer, 0, count)
                                cancellationCheck()
                            }
                        }
                        cancellationCheck()
                        output.fd.sync()
                    }
                }
                cancellationCheck()
                snapshot = openBackingFile(
                    sourceFile = file,
                    backingFile = snapshotFile,
                    lastModified = logicalLastModified,
                    deleteOnClose = snapshotFile
                )
                cancellationCheck()
                val snapshotFingerprint = LocalTxtParserCacheVersionStore.fingerprint(
                    snapshot,
                    cancellationCheck
                )
                cancellationCheck()
                val sourceFingerprint = LocalTxtParserCacheVersionStore.fingerprint(
                    file,
                    cancellationCheck
                )
                cancellationCheck()
                if (snapshotFingerprint != sourceFingerprint) {
                    throw LocalTxtParseException("TXT file changed while its immutable snapshot was created")
                }
                snapshot
            } catch (error: Throwable) {
                if (snapshot != null) {
                    snapshot.close()
                } else {
                    snapshotFile.delete()
                }
                throw error
            }
        }

        private fun openBackingFile(
            sourceFile: File,
            backingFile: File,
            lastModified: Long,
            deleteOnClose: File?
        ): LocalTxtFileSnapshot {
            val owner = FileInputStream(backingFile)
            return try {
                val channel = owner.channel
                LocalTxtFileSnapshot(
                    sourceFile = sourceFile,
                    owner = owner,
                    channel = channel,
                    length = channel.size(),
                    lastModified = lastModified,
                    deleteOnClose = deleteOnClose
                )
            } catch (error: Throwable) {
                owner.close()
                deleteOnClose?.delete()
                throw error
            }
        }

        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val SNAPSHOT_PREFIX = "reader-txt-"
        private const val SNAPSHOT_SUFFIX = ".snapshot"
    }
}

/**
 * Uses one directory per app process. On the first reader session in a new process, snapshot files
 * left by terminated older processes are removed before a new large private copy is created.
 */
internal object LocalTxtSnapshotDirectoryStore {
    private val processDirectoryName = "process-${UUID.randomUUID()}"

    @Synchronized
    @Throws(IOException::class)
    fun forCacheDirectory(cacheDirectory: File): File {
        val root = File(cacheDirectory, ROOT_DIRECTORY_NAME)
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Could not create local TXT snapshot root directory")
        }
        if (!root.isDirectory) {
            throw IOException("Local TXT snapshot root is not a directory")
        }
        val canonicalRoot = root.canonicalFile
        removeTerminatedProcessSnapshots(canonicalRoot)
        val processDirectory = File(canonicalRoot, processDirectoryName)
        if (!processDirectory.exists() && !processDirectory.mkdirs()) {
            throw IOException("Could not create local TXT process snapshot directory")
        }
        return processDirectory
    }

    private fun removeTerminatedProcessSnapshots(root: File) {
        root.listFiles().orEmpty().forEach { child ->
            if (child.isFile && isSnapshotFile(child)) {
                child.delete()
                return@forEach
            }
            if (
                !child.isDirectory ||
                child.name == processDirectoryName ||
                !isOwnedProcessDirectory(child)
            ) {
                return@forEach
            }
            val canonicalChild = try {
                child.canonicalFile
            } catch (_: IOException) {
                return@forEach
            }
            if (canonicalChild.parentFile != root) {
                return@forEach
            }
            child.listFiles().orEmpty().forEach { staleFile ->
                if (staleFile.isFile && isSnapshotFile(staleFile)) {
                    staleFile.delete()
                }
            }
            child.delete()
        }
    }

    private fun isOwnedProcessDirectory(file: File): Boolean {
        return file.name.startsWith(PROCESS_DIRECTORY_PREFIX) ||
            file.name.startsWith(LEGACY_READER_DIRECTORY_PREFIX)
    }

    private fun isSnapshotFile(file: File): Boolean {
        return file.name.startsWith(SNAPSHOT_FILE_PREFIX) &&
            file.name.endsWith(SNAPSHOT_FILE_SUFFIX)
    }

    private const val ROOT_DIRECTORY_NAME = "local-txt-snapshots"
    private const val PROCESS_DIRECTORY_PREFIX = "process-"
    private const val LEGACY_READER_DIRECTORY_PREFIX = "reader-"
    private const val SNAPSHOT_FILE_PREFIX = "reader-txt-"
    private const val SNAPSHOT_FILE_SUFFIX = ".snapshot"
}

internal object LocalTxtSnapshotCharsetDetector {
    @Throws(IOException::class)
    fun detect(
        snapshot: LocalTxtFileSnapshot,
        cancellationCheck: () -> Unit = {}
    ): ReaderCharset {
        cancellationCheck()
        if (snapshot.length == 0L) {
            return ReaderCharset.GBK
        }
        val prefix = ByteArray(3)
        val prefixSize = snapshot.openStream().use { input -> input.read(prefix) }
        cancellationCheck()
        if (prefixSize >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte()) {
            return ReaderCharset.UTF16LE
        }
        if (prefixSize >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte()) {
            return ReaderCharset.UTF16BE
        }
        if (
            prefixSize >= 3 &&
            prefix[0] == 0xef.toByte() &&
            prefix[1] == 0xbb.toByte() &&
            prefix[2] == 0xbf.toByte()
        ) {
            return ReaderCharset.UTF8
        }
        return if (
            Utf8ContentDetector.looksLikeUtf8(snapshot::openStream, cancellationCheck)
        ) {
            ReaderCharset.UTF8
        } else {
            ReaderCharset.GBK
        }
    }
}

private class SnapshotRangeInputStream(
    private val channel: FileChannel,
    start: Long,
    end: Long
) : InputStream() {
    private var position = start
    private var remaining = end - start
    private var closed = false
    private val singleByte = ByteArray(1)

    override fun read(): Int {
        val count = read(singleByte, 0, 1)
        return if (count < 0) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureOpen()
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "offset and length must describe a valid buffer range"
        }
        if (length == 0) return 0
        if (remaining <= 0L) return -1
        val boundedLength = min(length.toLong(), remaining).toInt()
        val byteBuffer = ByteBuffer.wrap(buffer, offset, boundedLength)
        val count = channel.read(byteBuffer, position)
        if (count < 0) {
            remaining = 0L
            return -1
        }
        position += count.toLong()
        remaining -= count.toLong()
        return count
    }

    override fun skip(byteCount: Long): Long {
        ensureOpen()
        if (byteCount <= 0L || remaining <= 0L) return 0L
        val skipped = min(byteCount, remaining)
        position += skipped
        remaining -= skipped
        return skipped
    }

    override fun available(): Int {
        ensureOpen()
        return min(remaining, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        closed = true
    }

    private fun ensureOpen() {
        if (closed) {
            throw IOException("TXT snapshot range is closed")
        }
        if (!channel.isOpen) {
            throw IOException("TXT file snapshot is closed")
        }
    }
}
