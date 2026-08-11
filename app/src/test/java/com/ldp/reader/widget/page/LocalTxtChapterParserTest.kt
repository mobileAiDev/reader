package com.ldp.reader.widget.page

import com.ldp.reader.model.bean.BookChapterBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.concurrent.CancellationException

class LocalTxtChapterParserTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8ChapterAcross512KiBBoundaryKeepsExactOffsetsAndNoReplacementCharacters() {
        val file = temporaryFolder.newFile("boundary.txt")
        val charset = Charsets.UTF_8
        val firstTitle = "第一章 开始\n"
        val targetTitle = "第五十一章 地之上、天之下\n"
        val firstTitleBytes = firstTitle.toByteArray(charset)
        val targetTitleBytes = targetTitle.toByteArray(charset)
        val targetTitleStart = BLOCK_SIZE - 4
        val bodyPadding = ByteArray(targetTitleStart - firstTitleBytes.size) { 'a'.code.toByte() }
        bodyPadding[bodyPadding.lastIndex] = '\n'.code.toByte()
        val targetBody = "密林中群兽环伺，夜色漆黑不见东南西北。\n".toByteArray(charset)

        file.writeBytes(firstTitleBytes + bodyPadding + targetTitleBytes + targetBody)

        val result = LocalTxtChapterParser.parse(file, charset, bufferSize = BLOCK_SIZE)

        assertTrue(result.hasExplicitChapters)
        assertEquals(listOf("第一章 开始", "第五十一章 地之上、天之下"), result.chapters.map { it.title })
        assertEquals(targetTitleStart.toLong(), result.chapters[0].end)
        assertEquals((targetTitleStart + targetTitleBytes.size).toLong(), result.chapters[1].start)
        val decodedBody = readRange(file, result.chapters[1], charset)
        assertFalse(decodedBody.contains('\uFFFD'))
        assertFalse(decodedBody.startsWith("第五十一章"))
        assertTrue(decodedBody.startsWith("密林中群兽环伺"))
    }

    @Test
    fun explicitTitleLineIsMetadataAndNotRepeatedInChapterBody() {
        val file = temporaryFolder.newFile("title-once.txt")
        file.writeText("第一章 标题\n正文第一段。\n第二章 继续\n正文第二段。", Charsets.UTF_8)

        val result = LocalTxtChapterParser.parse(file, Charsets.UTF_8, bufferSize = 7)

        assertEquals(2, result.chapters.size)
        val firstBody = readRange(file, result.chapters[0], Charsets.UTF_8)
        val secondBody = readRange(file, result.chapters[1], Charsets.UTF_8)
        assertEquals("正文第一段。\n", firstBody)
        assertEquals("正文第二段。", secondBody)
    }

    @Test
    fun proseContainingChapterWordsDoesNotCreateFalseChapterBoundary() {
        val file = temporaryFolder.newFile("chapter-in-prose.txt")
        file.writeText(
            "这是第五十一章的开头。\n人物继续向前走。\n他们谈到第52章时会发生的事。",
            Charsets.UTF_8
        )

        val result = LocalTxtChapterParser.parse(file, Charsets.UTF_8, virtualChapterBytes = 8)

        assertFalse(result.hasExplicitChapters)
        assertTrue(result.chapters.none { it.title.contains("第五十一章的开头") })
    }

    @Test
    fun indentedArabicAndVolumePrefixedChapterTitlesRemainSupported() {
        val file = temporaryFolder.newFile("supported-headings.txt")
        file.writeText(
            "  第51章 地之上\n正文甲。\n第三卷 风暴 第五十二章 天之下\n正文乙。",
            Charsets.UTF_8
        )

        val result = LocalTxtChapterParser.parse(file, Charsets.UTF_8)

        assertTrue(result.hasExplicitChapters)
        assertEquals(
            listOf("第51章 地之上", "第三卷 风暴 第五十二章 天之下"),
            result.chapters.map { it.title.trim() }
        )
    }

    @Test
    fun supportedMultibyteCharsetsKeepChapterBoundariesAligned() {
        val cases = listOf(
            CharsetCase("utf8", Charsets.UTF_8, byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())),
            CharsetCase("gbk", Charset.forName("GBK"), byteArrayOf()),
            CharsetCase("utf16le", Charsets.UTF_16LE, byteArrayOf(0xff.toByte(), 0xfe.toByte())),
            CharsetCase("utf16be", Charsets.UTF_16BE, byteArrayOf(0xfe.toByte(), 0xff.toByte()))
        )

        cases.forEach { case ->
            val file = temporaryFolder.newFile("${case.name}.txt")
            val text = "第一章 开始\r\n正文甲。\r\n第二章 继续\r\n正文乙。"
            file.writeBytes(case.bom + text.toByteArray(case.charset))

            val result = LocalTxtChapterParser.parse(file, case.charset, bufferSize = 5)

            assertEquals(case.name, listOf("第一章 开始", "第二章 继续"), result.chapters.map { it.title })
            val bodies = result.chapters.map { readRange(file, it, case.charset) }
            assertTrue(case.name, bodies.none { it.contains('\uFFFD') })
            assertEquals(case.name, listOf("正文甲。\r\n", "正文乙。"), bodies)
        }
    }

    @Test
    fun cachedChaptersKeepByteOffsetsButUsePersistedListOrdinalForNavigation() {
        val cached = listOf(
            BookChapterBean().apply { title = "第一章"; start = 0; end = 524_288 },
            BookChapterBean().apply { title = "第五十一章"; start = 524_288; end = 1_048_576 },
            BookChapterBean().apply { title = "第一百章"; start = 1_048_576; end = 1_572_864 }
        )

        val mapped = LocalTxtChapterMapper.fromCached(cached)

        assertEquals(listOf(0, 1, 2), mapped.map { it.catalogIndex })
        assertEquals(cached.map { it.start }, mapped.map { it.start })
        assertEquals(cached.map { it.end }, mapped.map { it.end })
    }

    @Test
    fun emptyFileHasNoVirtualChapterAndPlainTextUsesAlignedVirtualChapter() {
        val empty = temporaryFolder.newFile("empty.txt")
        val plain = temporaryFolder.newFile("plain.txt")
        plain.writeText("没有显式章节标题。\n仍然可以阅读。", Charsets.UTF_8)

        assertTrue(LocalTxtChapterParser.parse(empty, Charsets.UTF_8).chapters.isEmpty())
        val result = LocalTxtChapterParser.parse(plain, Charsets.UTF_8, virtualChapterBytes = 8)
        assertFalse(result.hasExplicitChapters)
        assertTrue(result.chapters.isNotEmpty())
        assertEquals(
            result.chapters.indices.map { index -> "第 ${index + 1} 节" },
            result.chapters.map { it.title }
        )
        assertEquals(0L, result.chapters.first().start)
        assertEquals(plain.length(), result.chapters.last().end)
    }

    @Test
    fun pureCarriageReturnDelimitersAndMissingFinalDelimiterKeepChapterBodiesExact() {
        val cases = listOf(
            CharsetCase("utf8-cr", Charsets.UTF_8, byteArrayOf()),
            CharsetCase("gbk-cr", Charset.forName("GBK"), byteArrayOf()),
            CharsetCase("utf16le-cr", Charsets.UTF_16LE, byteArrayOf(0xff.toByte(), 0xfe.toByte())),
            CharsetCase("utf16be-cr", Charsets.UTF_16BE, byteArrayOf(0xfe.toByte(), 0xff.toByte()))
        )

        cases.forEach { case ->
            val file = temporaryFolder.newFile("${case.name}.txt")
            val text = "第一章 开始\r正文甲。\r第二章 继续\r正文乙。"
            file.writeBytes(case.bom + text.toByteArray(case.charset))

            val result = LocalTxtChapterParser.parse(file, case.charset, bufferSize = 3)

            assertEquals(case.name, listOf("第一章 开始", "第二章 继续"), result.chapters.map { it.title })
            assertEquals(
                case.name,
                listOf("正文甲。\r", "正文乙。"),
                result.chapters.map { readRange(file, it, case.charset) }
            )
        }
    }

    @Test
    fun rejectsPathologicalSingleLineBeforeItCanGrowWithoutBound() {
        val file = temporaryFolder.newFile("single-line.txt")
        file.writeBytes(ByteArray(65) { 'x'.code.toByte() })

        val error = try {
            LocalTxtChapterParser.parse(file, Charsets.UTF_8, bufferSize = 7, maxLineBytes = 64)
            throw AssertionError("Expected LocalTxtParseException")
        } catch (expected: LocalTxtParseException) {
            expected
        }

        assertEquals(LocalTxtParseFailure.LINE_TOO_LONG, error.failure)
        assertTrue(error.message.orEmpty().contains("64"))
    }

    @Test
    fun chapterScanPropagatesCancellationBeforeCompletingTheFullFile() {
        val file = temporaryFolder.newFile("cancel-scan.txt")
        file.writeBytes(ByteArray(256 * 1024) { 'a'.code.toByte() })
        val cancellation = CancellationException("stop chapter scan")
        var checks = 0

        try {
            LocalTxtChapterParser.parse(
                file = file,
                charset = Charsets.UTF_8,
                maxLineBytes = 512 * 1024,
                cancellationCheck = {
                    checks += 1
                    if (checks >= 5) throw cancellation
                }
            )
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
        }

        assertTrue(checks >= 5)
    }

    private fun readRange(file: File, chapter: ParsedLocalTxtChapter, charset: Charset): String {
        val bytes = ByteArray((chapter.end - chapter.start).toInt())
        RandomAccessFile(file, "r").use { stream ->
            stream.seek(chapter.start)
            stream.readFully(bytes)
        }
        return String(bytes, charset)
    }

    private data class CharsetCase(
        val name: String,
        val charset: Charset,
        val bom: ByteArray
    )

    private companion object {
        const val BLOCK_SIZE = 512 * 1024
    }
}
