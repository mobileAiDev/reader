package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class LocalTxtChapterSnapshotLoaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retriesInsteadOfPublishingOffsetsFromAFileThatChangedDuringParsing() {
        val file = temporaryFolder.newFile("replaced-during-parse.txt")
        file.writeText("第一章 旧内容\n正文。", Charsets.UTF_8)
        var parseCalls = 0

        val snapshot = LocalTxtChapterSnapshotLoader.loadStable(file) {
            parseCalls += 1
            val parsed = LocalTxtChapterParser.parse(file, Charsets.UTF_8, bufferSize = 4)
            if (parseCalls == 1) {
                file.writeText("第一章 新内容\n新正文更长。", Charsets.UTF_8)
            }
            parsed
        }

        assertEquals(2, parseCalls)
        assertEquals("第一章 新内容", snapshot.chapters.single().title)
        assertEquals(LocalTxtParserCacheVersionStore.fingerprint(file), snapshot.fingerprint)
    }

    @Test
    fun rejectsAFileThatKeepsChangingAcrossBothBoundedAttempts() {
        val file = temporaryFolder.newFile("keeps-changing.txt")
        file.writeText("第一章 内容\n正文。", Charsets.UTF_8)
        var parseCalls = 0

        val error = try {
            LocalTxtChapterSnapshotLoader.loadStable(file) {
                parseCalls += 1
                val parsed = LocalTxtChapterParser.parse(file, Charsets.UTF_8, bufferSize = 4)
                file.appendText("x", Charsets.UTF_8)
                parsed
            }
            throw AssertionError("Expected LocalTxtParseException")
        } catch (expected: LocalTxtParseException) {
            expected
        }

        assertEquals(2, parseCalls)
        assertTrue(error.message.orEmpty().contains("changed"))
    }

    @Test
    fun rejectsEqualLengthChangesOutsideLegacySamplesAfterTwoAttempts() {
        val file = temporaryFolder.newFile("same-metadata-keeps-changing.txt")
        val timestamp = 1_700_000_000_500L
        val prefix = "第一章 内容\n".toByteArray(Charsets.UTF_8)
        file.writeBytes(prefix + ByteArray(64 * 1024 - prefix.size) { 'a'.code.toByte() })
        assertTrue(file.setLastModified(timestamp))
        var parseCalls = 0

        val error = try {
            LocalTxtChapterSnapshotLoader.loadStable(file) {
                parseCalls += 1
                val parsed = LocalTxtChapterParser.parse(file, Charsets.UTF_8, bufferSize = 4 * 1024)
                RandomAccessFile(file, "rw").use { output ->
                    output.seek(REPLACEMENT_OFFSET.toLong())
                    val current = output.read()
                    output.seek(REPLACEMENT_OFFSET.toLong())
                    output.write(current xor 0x01)
                }
                assertTrue(file.setLastModified(timestamp))
                assertEquals((64 * 1024).toLong(), file.length())
                assertEquals(timestamp, file.lastModified())
                parsed
            }
            throw AssertionError("Expected LocalTxtParseException")
        } catch (expected: LocalTxtParseException) {
            expected
        }

        assertEquals(2, parseCalls)
        assertTrue(error.message.orEmpty().contains("changed"))
    }

    private companion object {
        const val REPLACEMENT_OFFSET = 16 * 1024 + 123
    }
}
