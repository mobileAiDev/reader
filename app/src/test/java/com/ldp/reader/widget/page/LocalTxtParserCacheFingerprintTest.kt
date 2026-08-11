package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

class LocalTxtParserCacheFingerprintTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parserCopyUpgradeInvalidatesPreviouslyCachedVirtualChapterTitles() {
        val file = temporaryFolder.newFile("copy-version.txt")
        file.writeText("没有显式章节标题。", Charsets.UTF_8)

        assertTrue(LocalTxtParserCacheVersionStore.fingerprint(file).startsWith("6:"))
    }

    @Test
    fun fingerprintChangesForSameSecondReplacementAndLengthChanges() {
        val file = temporaryFolder.newFile("same-path.txt")
        file.writeText("第一章 旧内容")
        assertTrue(file.setLastModified(1_700_000_000_100L))
        val original = LocalTxtParserCacheVersionStore.fingerprint(file)

        file.writeText("第一章 新内容")
        assertTrue(file.setLastModified(1_700_000_000_900L))
        val sameSecondReplacement = LocalTxtParserCacheVersionStore.fingerprint(file)
        file.writeText("第一章 新内容，长度变化")
        assertTrue(file.setLastModified(1_700_000_000_900L))
        val changedLength = LocalTxtParserCacheVersionStore.fingerprint(file)

        assertNotEquals(original, sameSecondReplacement)
        assertNotEquals(sameSecondReplacement, changedLength)
    }

    @Test
    fun fingerprintChangesWhenContentChangesButLengthAndTimestampArePreserved() {
        val file = temporaryFolder.newFile("same-metadata.txt")
        val timestamp = 1_700_000_000_500L
        file.writeText("第一章 甲内容", Charsets.UTF_8)
        assertTrue(file.setLastModified(timestamp))
        val original = LocalTxtParserCacheVersionStore.fingerprint(file)

        file.writeText("第一章 乙内容", Charsets.UTF_8)
        assertTrue(file.setLastModified(timestamp))
        val replacement = LocalTxtParserCacheVersionStore.fingerprint(file)

        assertNotEquals(original, replacement)
    }

    @Test
    fun encodingPreferenceParticipatesInTheParserCacheIdentity() {
        val fingerprint = "6:4:1700000000500:f147cc35"

        val automatic = LocalTxtParserCacheVersionStore.cacheIdentity(
            fingerprint,
            LocalTxtEncodingPreference.AUTO
        )
        val forcedGbk = LocalTxtParserCacheVersionStore.cacheIdentity(
            fingerprint,
            LocalTxtEncodingPreference.GBK
        )

        assertNotEquals(automatic, forcedGbk)
        assertEquals("$fingerprint:encoding=auto", automatic)
        assertEquals("$fingerprint:encoding=gbk", forcedGbk)
    }

    @Test
    fun fingerprintChangesForEqualLengthReplacementOutsideLegacySampleRanges() {
        val file = temporaryFolder.newFile("large-same-metadata.txt")
        val timestamp = 1_700_000_000_500L
        val originalBytes = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
        file.writeBytes(originalBytes)
        assertTrue(file.setLastModified(timestamp))
        val original = LocalTxtParserCacheVersionStore.fingerprint(file)

        RandomAccessFile(file, "rw").use { output ->
            output.seek(REPLACEMENT_OFFSET.toLong())
            output.write(originalBytes[REPLACEMENT_OFFSET].toInt() xor 0xff)
        }
        assertTrue(file.setLastModified(timestamp))
        assertEquals(originalBytes.size.toLong(), file.length())
        assertEquals(timestamp, file.lastModified())
        val replacement = LocalTxtParserCacheVersionStore.fingerprint(file)

        assertNotEquals(original, replacement)
    }

    @Test
    fun fingerprintRejectsAFileThatChangesAcrossBothBoundedAttempts() {
        val physicalFile = temporaryFolder.newFile("changing-during-fingerprint.txt")
        physicalFile.writeText("第一章 内容\n正文。", Charsets.UTF_8)
        var lengthReads = 0
        val changingFile = object : java.io.File(physicalFile.absolutePath) {
            override fun length(): Long {
                lengthReads += 1
                return physicalFile.length() + lengthReads
            }
        }

        val error = try {
            LocalTxtParserCacheVersionStore.fingerprint(changingFile)
            throw AssertionError("Expected LocalTxtParseException")
        } catch (expected: LocalTxtParseException) {
            expected
        }

        assertEquals(4, lengthReads)
        assertTrue(error.message.orEmpty().contains("changed"))
    }

    @Test
    fun fileFingerprintPropagatesCancellationDuringTheFullContentDigest() {
        val file = temporaryFolder.newFile("cancel-file-fingerprint.txt")
        file.writeBytes(ByteArray(256 * 1024) { 1 })
        val cancellation = CancellationException("stop file digest")
        var checks = 0

        try {
            LocalTxtParserCacheVersionStore.fingerprint(
                file = file,
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
    }

    @Test
    fun snapshotFingerprintPropagatesCancellationDuringTheFullContentDigest() {
        val file = temporaryFolder.newFile("cancel-snapshot-fingerprint.txt")
        file.writeBytes(ByteArray(256 * 1024) { 2 })
        val cancellation = CancellationException("stop snapshot digest")
        var checks = 0

        LocalTxtFileSnapshot.open(file).use { snapshot ->
            try {
                LocalTxtParserCacheVersionStore.fingerprint(
                    snapshot = snapshot,
                    cancellationCheck = {
                        checks += 1
                        if (checks >= 3) throw cancellation
                    }
                )
                throw AssertionError("Expected cancellation")
            } catch (actual: CancellationException) {
                assertTrue(actual === cancellation)
            }
        }

        assertTrue(checks >= 3)
    }

    private companion object {
        const val REPLACEMENT_OFFSET = 16 * 1024 + 123
    }
}
