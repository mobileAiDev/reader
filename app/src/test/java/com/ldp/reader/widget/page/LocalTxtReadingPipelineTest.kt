package com.ldp.reader.widget.page

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.utils.Charset as ReaderCharset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlin.math.min

class LocalTxtReadingPipelineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sparseMalformedUtf8BytesDoNotTurnTheReadableChineseIntoGbkMojibake() {
        val source = temporaryFolder.newFile("mostly-utf8-pipeline.txt")
        source.writeBytes(
            "QA malformed UTF-8\n第1章 valid\nbody-before-invalid ".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0xf0.toByte(), 0x28, 0x8c.toByte(), 0x28) +
                " 中文\n第2章 tail\n".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), 0xff.toByte(), 0xfe.toByte())
        )

        createSnapshot(source).use { snapshot ->
            val detected = LocalTxtSnapshotCharsetDetector.detect(snapshot)
            assertEquals(ReaderCharset.UTF8, detected)
            val chapters = LocalTxtChapterSnapshotLoader.load(
                snapshot = snapshot,
                charset = Charset.forName(detected.getName()),
                bufferSize = PARSER_BUFFER_SIZE,
                virtualChapterBytes = 10 * 1024
            ).chapters

            assertEquals(listOf("第1章 valid", "第2章 tail"), chapters.map { it.title })
            val firstBody = BufferedReader(
                InputStreamReader(
                    snapshot.openRange(chapters.first().start, chapters.first().end),
                    detected.getName()
                )
            ).use { it.readText() }
            assertTrue(firstBody.contains("body-before-invalid"))
            assertTrue(firstBody.contains("中文"))
            assertTrue(firstBody.contains('\uFFFD'))
        }
    }

    @Test
    fun strictlyValidTwoByteUtf8TextStaysOnTheUtf8Path() {
        val source = temporaryFolder.newFile("two-byte-utf8-pipeline.txt")
        source.writeText("Привет мир · Ελληνικά · déjà vu", Charsets.UTF_8)

        createSnapshot(source).use { snapshot ->
            val detected = LocalTxtSnapshotCharsetDetector.detect(snapshot)
            assertEquals(ReaderCharset.UTF8, detected)
            val rendered = InputStreamReader(snapshot.openStream(), detected.getName()).use {
                it.readText()
            }
            assertEquals("Привет мир · Ελληνικά · déjà vu", rendered)
        }
    }

    @Test
    fun sparseDamageInTwoByteOnlyUtf8DoesNotTurnTheWholeBookIntoGbkMojibake() {
        val readableText = "Привет мир · Ελληνικά · déjà vu · français"
        val source = temporaryFolder.newFile("two-byte-utf8-with-sparse-damage.txt")
        source.writeBytes(
            readableText.toByteArray(Charsets.UTF_8) + byteArrayOf(0xff.toByte())
        )

        createSnapshot(source).use { snapshot ->
            val detected = LocalTxtSnapshotCharsetDetector.detect(snapshot)
            assertEquals(ReaderCharset.UTF8, detected)
            val rendered = InputStreamReader(snapshot.openStream(), detected.getName()).use {
                it.readText()
            }
            assertTrue(rendered.startsWith(readableText))
            assertTrue(rendered.endsWith('\uFFFD'))
        }
    }

    @Test
    fun singleCjkAndEmojiUtf8FilesStayOnTheUtf8Path() {
        listOf("一", "😀").forEachIndexed { index, text ->
            val source = temporaryFolder.newFile("short-utf8-pipeline-$index.txt")
            source.writeText(text, Charsets.UTF_8)

            createSnapshot(source).use { snapshot ->
                assertEquals(ReaderCharset.UTF8, LocalTxtSnapshotCharsetDetector.detect(snapshot))
            }
        }
    }

    @Test
    fun dualValidUnmarkedBytesUseTheUtf8DefaultInTheReadingPipeline() {
        val source = temporaryFolder.newFile("dual-valid-pipeline.txt")
        source.writeBytes(byteArrayOf(0xe4.toByte(), 0xbd.toByte(), 0xa0.toByte(), 0x40))

        createSnapshot(source).use { snapshot ->
            val detected = LocalTxtSnapshotCharsetDetector.detect(snapshot)
            assertEquals(ReaderCharset.UTF8, detected)
            val rendered = InputStreamReader(snapshot.openStream(), detected.getName()).use {
                it.readText()
            }
            assertEquals("你@", rendered)
        }
    }

    @Test
    fun explicitGbkPreferenceRecoversDualValidBytesThatAutoReadsAsUtf8() {
        val source = temporaryFolder.newFile("dual-valid-explicit-gbk.txt")
        source.writeBytes(byteArrayOf(0xe4.toByte(), 0xbd.toByte(), 0xa0.toByte(), 0x40))

        createSnapshot(source).use { snapshot ->
            val automatic = LocalTxtCharsetResolver.resolve(
                snapshot,
                LocalTxtEncodingPreference.AUTO
            )
            val forcedGbk = LocalTxtCharsetResolver.resolve(
                snapshot,
                LocalTxtEncodingPreference.GBK
            )

            assertEquals(ReaderCharset.UTF8, automatic)
            assertEquals(ReaderCharset.GBK, forcedGbk)
            assertEquals(
                "浣燖",
                InputStreamReader(snapshot.openStream(), forcedGbk.getName()).use { it.readText() }
            )
        }
    }

    @Test
    fun utf8TitleAcross512KiBBoundaryRendersOnceWithoutReplacementCharacters() {
        val source = temporaryFolder.newFile("utf8-boundary-pipeline.txt")
        val firstTitleLine = "第一章 开始\n"
        val targetTitle = "第五十一章 地之上、天之下"
        val targetTitleLine = "$targetTitle\n"
        val targetTitleStart = PARSER_BUFFER_SIZE - 4
        val firstTitleBytes = firstTitleLine.toByteArray(Charsets.UTF_8)
        val padding = ByteArray(targetTitleStart - firstTitleBytes.size) { 'a'.code.toByte() }
        padding[padding.lastIndex] = '\n'.code.toByte()
        source.writeBytes(
            firstTitleBytes +
                padding +
                targetTitleLine.toByteArray(Charsets.UTF_8) +
                "密林中群兽环伺。\n夜色漆黑，不见东南西北。\n".toByteArray(Charsets.UTF_8)
        )

        createSnapshot(source).use { snapshot ->
            val detected = LocalTxtSnapshotCharsetDetector.detect(snapshot)
            assertEquals(ReaderCharset.UTF8, detected)

            val catalog = LocalTxtChapterSnapshotLoader.load(
                snapshot = snapshot,
                charset = Charset.forName(detected.getName()),
                bufferSize = PARSER_BUFFER_SIZE,
                virtualChapterBytes = 10 * 1024
            ).chapters
            val targetChapter = catalog.single { it.title == targetTitle }
            assertEquals(targetTitleStart.toLong(), catalog.first().end)
            assertEquals(
                (targetTitleStart + targetTitleLine.toByteArray(Charsets.UTF_8).size).toLong(),
                targetChapter.start
            )

            val pages = paginate(snapshot, detected, targetChapter)
            val renderedLines = pages.flatMap { page -> page.lines.orEmpty() }
            val renderedText = renderedLines.joinToString(separator = "")

            assertTrue(pages.isNotEmpty())
            assertEquals(1, pages.sumOf { page -> page.titleLines })
            assertEquals(1, renderedLines.count { line -> line == targetTitle })
            assertEquals(1, renderedText.countOccurrences(targetTitle))
            assertFalse(renderedText.contains('\uFFFD'))
            assertTrue(pages.none { page -> page.title.orEmpty().contains('\uFFFD') })
            assertTrue(renderedText.contains("密林中群兽环伺"))
            assertTrue(renderedText.contains("夜色漆黑，不见东南西北"))
        }
    }

    @Test
    fun cachedReopenDirectoryOrdinalOpensRequestedMiddleChapterInsteadOfBookEnd() {
        val source = temporaryFolder.newFile("cached-reopen.txt")
        source.writeText(
            "第一章 开始\n" +
                "开篇正文唯一。\n" +
                "第五十一章 中间\n" +
                "中间正文唯一。\n" +
                "第一百章 末尾\n" +
                "末尾正文唯一。\n",
            Charsets.UTF_8
        )
        val cachedChapters = createSnapshot(source).use { firstSession ->
            val detected = LocalTxtSnapshotCharsetDetector.detect(firstSession)
            LocalTxtChapterSnapshotLoader.load(
                snapshot = firstSession,
                charset = Charset.forName(detected.getName()),
                bufferSize = 7,
                virtualChapterBytes = 10 * 1024
            ).chapters.map { chapter ->
                BookChapterBean().apply {
                    title = chapter.title
                    start = chapter.start
                    end = chapter.end
                }
            }
        }

        createSnapshot(source).use { reopenedSnapshot ->
            val detected = LocalTxtSnapshotCharsetDetector.detect(reopenedSnapshot)
            val reopenedCatalog = LocalTxtChapterMapper.fromCached(cachedChapters)
            val clickedAdapterPosition = 1
            val clickedChapter = reopenedCatalog[clickedAdapterPosition]
            val requestedOrdinal = ReaderChapterSelectionResolver.fullChapterPosition(
                clickedChapter,
                clickedAdapterPosition
            )
            val openedChapter = reopenedCatalog[requestedOrdinal]

            assertTrue(cachedChapters[clickedAdapterPosition].start > reopenedCatalog.lastIndex)
            assertEquals(clickedAdapterPosition, requestedOrdinal)
            assertNotEquals(cachedChapters[clickedAdapterPosition].start.toInt(), requestedOrdinal)
            assertEquals("第五十一章 中间", openedChapter.title)

            val pages = paginate(reopenedSnapshot, detected, openedChapter)
            val renderedText = pages
                .flatMap { page -> page.lines.orEmpty() }
                .joinToString(separator = "")

            assertEquals(1, renderedText.countOccurrences("第五十一章 中间"))
            assertTrue(renderedText.contains("中间正文唯一"))
            assertFalse(renderedText.contains("末尾正文唯一"))
            assertFalse(renderedText.contains('\uFFFD'))
        }
    }

    private fun createSnapshot(source: java.io.File): LocalTxtFileSnapshot {
        return LocalTxtFileSnapshot.createImmutableCopy(
            source,
            temporaryFolder.newFolder("snapshot-${System.nanoTime()}")
        )
    }

    private fun paginate(
        snapshot: LocalTxtFileSnapshot,
        charset: ReaderCharset,
        chapter: TxtChapter
    ): MutableList<TxtPage> {
        val reader = BufferedReader(
            InputStreamReader(
                snapshot.openRange(chapter.start, chapter.end),
                charset.getName()
            )
        )
        return ReaderChapterPaginator.paginate(
            chapter = chapter,
            reader = reader,
            spec = ReaderChapterPaginationSpec(
                visibleHeight = 40,
                titleLineHeight = 10,
                bodyLineHeight = 10,
                titleInterval = 0,
                bodyInterval = 0,
                titleParagraphSpacing = 0,
                bodyParagraphSpacing = 0,
                breakTitle = { text -> text.length },
                breakBody = { text -> min(text.length, 9) },
                formatBodyParagraph = ReaderBodyParagraphFormatter::format
            )
        )
    }

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var searchFrom = 0
        while (true) {
            val foundAt = indexOf(needle, searchFrom)
            if (foundAt < 0) return count
            count += 1
            searchFrom = foundAt + needle.length
        }
    }

    private companion object {
        const val PARSER_BUFFER_SIZE = 512 * 1024
    }
}
