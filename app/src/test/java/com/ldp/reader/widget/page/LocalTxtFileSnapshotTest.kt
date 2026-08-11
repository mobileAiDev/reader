package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

class LocalTxtFileSnapshotTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsedOffsetsKeepReadingOriginalFileAfterPathIsAtomicallyReplaced() {
        val source = temporaryFolder.newFile("book.txt")
        source.writeText("第一章 A\nA正文保持原样。\n", Charsets.UTF_8)
        val replacement = temporaryFolder.newFile("replacement.txt")
        replacement.writeText("第一章 B\nB正文已经替换。\n", Charsets.UTF_8)

        createImmutableSnapshot(source).use { snapshot ->
            val chapter = LocalTxtChapterParser.parse(snapshot, Charsets.UTF_8).chapters.single()

            Files.move(
                replacement.toPath(),
                source.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )

            val body = BufferedReader(
                InputStreamReader(snapshot.openRange(chapter.start, chapter.end), Charsets.UTF_8)
            ).use { it.readText() }

            assertEquals("A正文保持原样。\n", body)
            assertEquals("第一章 B\nB正文已经替换。\n", source.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun parsedOffsetsKeepReadingOriginalBytesAfterSourceIsOverwrittenInPlace() {
        val source = temporaryFolder.newFile("overwritten.txt")
        val original = "第一章 A\nA正文保持原样。\n"
        val replacement = "第一章 B\nB正文已经替换。\n"
        assertEquals(original.toByteArray(Charsets.UTF_8).size, replacement.toByteArray(Charsets.UTF_8).size)
        source.writeText(original, Charsets.UTF_8)

        createImmutableSnapshot(source).use { snapshot ->
            val chapter = LocalTxtChapterParser.parse(snapshot, Charsets.UTF_8).chapters.single()
            RandomAccessFile(source, "rw").use { file ->
                file.seek(0L)
                file.write(replacement.toByteArray(Charsets.UTF_8))
            }

            val body = BufferedReader(
                InputStreamReader(snapshot.openRange(chapter.start, chapter.end), Charsets.UTF_8)
            ).use { it.readText() }

            assertEquals("A正文保持原样。\n", body)
            assertEquals(replacement, source.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun parsedOffsetsKeepReadingCompleteOriginalBytesAfterSourceIsTruncatedInPlace() {
        val source = temporaryFolder.newFile("immutable-truncated.txt")
        source.writeText("第一章 A\n完整正文仍可读取。\n", Charsets.UTF_8)

        createImmutableSnapshot(source).use { snapshot ->
            val chapter = LocalTxtChapterParser.parse(snapshot, Charsets.UTF_8).chapters.single()
            RandomAccessFile(source, "rw").use { file ->
                file.setLength(4L)
            }

            val body = BufferedReader(
                InputStreamReader(snapshot.openRange(chapter.start, chapter.end), Charsets.UTF_8)
            ).use { it.readText() }

            assertEquals("完整正文仍可读取。\n", body)
        }
    }

    @Test
    fun closeReliablyRejectsNewChapterReaders() {
        val source = temporaryFolder.newFile("closed.txt")
        source.writeText("正文。", Charsets.UTF_8)
        val snapshot = LocalTxtFileSnapshot.open(source)

        snapshot.close()

        try {
            snapshot.openRange(0L, snapshot.length)
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Closed snapshots must not create a reader for stale offsets.
        }
    }

    @Test
    fun fingerprintRejectsSnapshotWhoseOpenFileGrowsInPlace() {
        val source = temporaryFolder.newFile("growing.txt")
        source.writeText("第一章 A\n正文。\n", Charsets.UTF_8)

        LocalTxtFileSnapshot.open(source).use { snapshot ->
            source.appendText("增长", Charsets.UTF_8)

            assertFingerprintChangeRejected(snapshot)
        }
    }

    @Test
    fun fingerprintRejectsSnapshotWhoseOpenFileIsTruncatedInPlace() {
        val source = temporaryFolder.newFile("truncated.txt")
        source.writeText("第一章 A\n正文正文正文。\n", Charsets.UTF_8)

        LocalTxtFileSnapshot.open(source).use { snapshot ->
            RandomAccessFile(source, "rw").use { file ->
                file.setLength(snapshot.length / 2L)
            }

            assertFingerprintChangeRejected(snapshot)
        }
    }

    @Test
    fun immutableCopyCancellationPropagatesAndDeletesPartialSnapshot() {
        val source = temporaryFolder.newFile("cancel-copy.txt")
        source.writeBytes(ByteArray(256 * 1024) { 7 })
        val snapshots = temporaryFolder.newFolder("cancel-copy-snapshots")
        val cancellation = CancellationException("stop snapshot copy")
        var checks = 0

        try {
            LocalTxtFileSnapshot.createImmutableCopy(
                file = source,
                snapshotDirectory = snapshots,
                cancellationCheck = {
                    checks += 1
                    if (checks >= 3) throw cancellation
                }
            )
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
        }

        assertTrue(checks >= 3)
        assertTrue(snapshots.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun strictCharsetScanPropagatesCancellation() {
        val source = temporaryFolder.newFile("cancel-charset.txt")
        source.writeBytes(ByteArray(256 * 1024) { 'a'.code.toByte() })
        val cancellation = CancellationException("stop charset scan")
        var checks = 0

        LocalTxtFileSnapshot.open(source).use { snapshot ->
            try {
                LocalTxtSnapshotCharsetDetector.detect(
                    snapshot = snapshot,
                    cancellationCheck = {
                        checks += 1
                        if (checks >= 4) throw cancellation
                    }
                )
                throw AssertionError("Expected cancellation")
            } catch (actual: CancellationException) {
                assertTrue(actual === cancellation)
            }
        }

        assertTrue(checks >= 4)
    }

    private fun assertFingerprintChangeRejected(snapshot: LocalTxtFileSnapshot) {
        try {
            LocalTxtParserCacheVersionStore.fingerprint(snapshot)
            throw AssertionError("Expected LocalTxtParseException")
        } catch (expected: LocalTxtParseException) {
            assertTrue(expected.message.orEmpty().contains("changed"))
        }
    }

    private fun createImmutableSnapshot(source: java.io.File): LocalTxtFileSnapshot {
        return LocalTxtFileSnapshot.createImmutableCopy(
            source,
            temporaryFolder.newFolder("snapshots-${System.nanoTime()}")
        )
    }
}
