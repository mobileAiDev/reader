package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FilterReader
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class ReaderChapterPaginatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shortMixedLatinCjkFrontMatterKeepsNaturalAsciiSpacing() {
        val pages = ReaderChapterPaginator.paginate(
            chapter = TxtChapter().apply { title = "序章" },
            reader = BufferedReader(
                StringReader(
                    "QA 512KiB 边界章节测试\n" +
                        "作者：Codex QA\n"
                )
            ),
            spec = testSpec()
        )

        assertEquals(
            listOf(
                "序章",
                "\u3000\u3000QA 512KiB 边界章节测试\n",
                "\u3000\u3000作者：Codex QA\n"
            ),
            pages.single().lines
        )
        assertEquals(1, pages.single().titleLines)
    }

    @Test
    fun bodyParagraphFormattingPreservesCjkUrlsPunctuationAndExplicitLineStructure() {
        val cases = mapOf(
            "普通中文正文，保持原样。" to "\u3000\u3000普通中文正文，保持原样。\n",
            "作者：Codex QA" to "\u3000\u3000作者：Codex QA\n",
            "访问 https://Example.com/A?q=1&b=Two。" to
                "\u3000\u3000访问 https://Example.com/A?q=1&b=Two。\n",
            "" to "\n",
            "   " to "\n"
        )

        cases.forEach { (raw, expected) ->
            assertEquals(raw, expected, ReaderBodyParagraphFormatter.format(raw))
        }
    }

    @Test
    fun explicitBlankBodyLineKeepsItsVerticalSlot() {
        val pages = ReaderChapterPaginator.paginate(
            chapter = TxtChapter().apply { title = "序章" },
            reader = BufferedReader(StringReader("第一段\n\n第二段\n")),
            spec = testSpec(visibleHeight = 31, bodyBreakCount = Int.MAX_VALUE)
        )

        assertEquals(2, pages.size)
        assertEquals(
            listOf("序章", "\u3000\u3000第一段\n"),
            pages[0].lines
        )
        assertEquals(listOf("\u3000\u3000第二段\n"), pages[1].lines)
    }

    @Test
    fun lineTallerThanViewportStillPaginatesOneLineWithoutEmptyPageStorm() {
        val pages = ReaderChapterPaginator.paginate(
            chapter = TxtChapter().apply { title = "" },
            reader = BufferedReader(StringReader("第一行\n第二行\n")),
            spec = testSpec(
                visibleHeight = 48,
                bodyBreakCount = Int.MAX_VALUE
            ).copy(bodyLineHeight = 96)
        )

        assertEquals(2, pages.size)
        assertTrue(pages.all { page -> page.lines.orEmpty().isNotEmpty() })
        assertEquals(
            ReaderBodyParagraphFormatter.format("第一行") +
                ReaderBodyParagraphFormatter.format("第二行"),
            pages.flatMap { page -> page.lines.orEmpty() }.joinToString(separator = "")
        )
    }

    @Test
    fun measuredInkHeightPaginatesToTheSameBaselineAdvanceUsedForDrawing() {
        val bodyLayout = ReaderPageGeometry.resolveTextLineLayout(
            textSizePx = 60f,
            fontMetricsTopPx = -66f,
            fontMetricsBottomPx = 18f,
            configuredLineIntervalPx = 30,
            configuredParagraphIntervalPx = 60
        )
        val pages = ReaderChapterPaginator.paginate(
            chapter = TxtChapter().apply { title = "" },
            reader = BufferedReader(StringReader("甲乙")),
            spec = testSpec(
                visibleHeight = 174,
                bodyBreakCount = 1
            ).copy(
                bodyLineHeight = bodyLayout.inkHeight,
                bodyInterval = bodyLayout.lineGap,
                bodyParagraphSpacing = bodyLayout.paragraphGap,
                formatBodyParagraph = { paragraph -> paragraph }
            )
        )

        assertEquals(84, bodyLayout.inkHeight)
        assertEquals(6, bodyLayout.lineGap)
        assertEquals(36, bodyLayout.paragraphGap)
        assertEquals(listOf("甲", "乙"), pages.single().lines)
    }

    @Test
    fun representativeFontScalesAndReaderSizesKeepInkInsidePagedAndScrollContentBand() {
        val density = 3f
        val displayHeight = 2_992
        val contentTop = 168
        val contentBottomPadding = 24
        val safeBottomInset = 72
        val footerMinimum = 84
        val footerBottomMargin = 9

        listOf(1f, 1.5f, 2f).forEach { fontScale ->
            val tipSizePx = 12f * density * fontScale
            val tipTop = -1.05f * tipSizePx
            val tipBottom = 0.28f * tipSizePx
            val footerAreaHeight = ReaderPageGeometry.resolveFooterAreaHeight(
                minimumAreaHeightPx = footerMinimum,
                textMetricsTopPx = tipTop,
                textMetricsBottomPx = tipBottom,
                batteryHeightPx = tipSizePx,
                bottomMarginPx = footerBottomMargin
            )
            val pageGeometry = ReaderPageGeometry.resolveVertical(
                displayHeightPx = displayHeight,
                contentTopPx = contentTop,
                contentBottomPaddingPx = contentBottomPadding,
                footerAreaHeightPx = footerAreaHeight,
                safeBottomInsetPx = safeBottomInset
            )
            val footerTextTop =
                pageGeometry.footerBottom - footerBottomMargin - (tipBottom - tipTop)

            if (fontScale == 1f) {
                assertEquals(footerMinimum, footerAreaHeight)
            }
            assertTrue(pageGeometry.contentBottom < footerTextTop)

            listOf(28, 40, 60).forEach { readerSizeSp ->
                val textSizePx = readerSizeSp * density * fontScale
                val bodyTop = -1.05f * textSizePx
                val bodyBottom = 0.28f * textSizePx
                val lineInterval = (textSizePx / 2f).toInt()
                val paragraphInterval = textSizePx.toInt()
                val bodyLayout = ReaderPageGeometry.resolveTextLineLayout(
                    textSizePx = textSizePx,
                    fontMetricsTopPx = bodyTop,
                    fontMetricsBottomPx = bodyBottom,
                    configuredLineIntervalPx = lineInterval,
                    configuredParagraphIntervalPx = paragraphInterval
                )
                assertEquals(
                    textSizePx.toInt() + lineInterval,
                    bodyLayout.inkHeight + bodyLayout.lineGap
                )
                assertEquals(
                    textSizePx.toInt() + paragraphInterval,
                    bodyLayout.inkHeight + bodyLayout.paragraphGap
                )
                val pages = ReaderChapterPaginator.paginate(
                    chapter = TxtChapter().apply { title = "" },
                    reader = BufferedReader(StringReader("正".repeat(800))),
                    spec = testSpec(
                        visibleHeight = pageGeometry.contentHeight,
                        bodyBreakCount = 20
                    ).copy(
                        bodyLineHeight = bodyLayout.inkHeight,
                        bodyInterval = bodyLayout.lineGap,
                        bodyParagraphSpacing = bodyLayout.paragraphGap,
                        formatBodyParagraph = { paragraph -> paragraph }
                    )
                )

                assertTrue(pages.isNotEmpty())
                assertTrue(pages.all { page -> page.lines.orEmpty().isNotEmpty() })
                pages.forEach { page ->
                    var baseline = -bodyTop
                    var lastInkBottom = 0f
                    page.lines.orEmpty().forEach { line ->
                        lastInkBottom = baseline + bodyBottom
                        baseline += textSizePx.toInt() + if (line.endsWith("\n")) {
                            paragraphInterval
                        } else {
                            lineInterval
                        }
                    }
                    assertTrue(
                        "fontScale=$fontScale readerSizeSp=$readerSizeSp bottom=$lastInkBottom " +
                            "contentHeight=${pageGeometry.contentHeight}",
                        lastInkBottom <= pageGeometry.contentHeight + 0.01f ||
                            (page.lines.orEmpty().size == 1 &&
                                bodyLayout.inkHeight > pageGeometry.contentHeight)
                    )

                    listOf(false, true).forEach { scrollMode ->
                        val canvasTop = ReaderPageGeometry.resolveContentCanvasTop(
                            contentTopPx = pageGeometry.contentTop,
                            scrollMode = scrollMode
                        )
                        val scrollTranslation = if (scrollMode) pageGeometry.contentTop else 0
                        assertEquals(pageGeometry.contentTop, canvasTop + scrollTranslation)
                        assertTrue(
                            canvasTop + scrollTranslation + lastInkBottom <=
                                pageGeometry.contentBottom + 0.01f ||
                                (page.lines.orEmpty().size == 1 &&
                                    bodyLayout.inkHeight > pageGeometry.contentHeight)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun longMixedBodyLineStillPaginatesWithoutChangingCodePoints() {
        val raw = "中文ABC https://Example.com/P?q=1&b=Two。".repeat(8)
        val pages = ReaderChapterPaginator.paginate(
            chapter = TxtChapter().apply { title = "序章" },
            reader = BufferedReader(StringReader(raw)),
            spec = testSpec(visibleHeight = 35, bodyBreakCount = 12)
        )
        val renderedBody = pages
            .flatMap { page -> page.lines.orEmpty().drop(page.titleLines) }
            .joinToString(separator = "")

        assertTrue(pages.size > 1)
        assertEquals(ReaderBodyParagraphFormatter.format(raw), renderedBody)
    }

    @Test
    fun twentyFourMiBChapterPaginatesOffCallerThreadAndCanBeCancelled() {
        val chapterFile = temporaryFolder.newFile("large-chapter.txt")
        writeLargeChapter(chapterFile)
        val callerThread = Thread.currentThread()
        val paginationThread = AtomicReference<Thread>()
        val checks = AtomicInteger()
        val reachedCancellationPoint = CountDownLatch(1)
        val allowCancellationCheck = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "txt-pagination-worker")
        }

        try {
            val future = executor.submit<MutableList<TxtPage>> {
                BufferedReader(FileReader(chapterFile)).use { reader ->
                    ReaderChapterPaginator.paginate(
                        chapter = TxtChapter().apply { title = "大章" },
                        reader = reader,
                        spec = testSpec(),
                        checkCancelled = {
                            paginationThread.compareAndSet(null, Thread.currentThread())
                            if (checks.incrementAndGet() == 50) {
                                reachedCancellationPoint.countDown()
                                allowCancellationCheck.await(5, TimeUnit.SECONDS)
                            }
                            if (cancelled.get()) throw CancellationException("cancelled")
                        }
                    )
                }
            }

            assertTrue(reachedCancellationPoint.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            allowCancellationCheck.countDown()
            try {
                future.get(5, TimeUnit.SECONDS)
                throw AssertionError("Expected pagination cancellation")
            } catch (expected: ExecutionException) {
                assertTrue(expected.cause is CancellationException)
            }

            assertNotEquals(callerThread, paginationThread.get())
            assertTrue(checks.get() < 100)
        } finally {
            allowCancellationCheck.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun overBudgetChapterFailsWithoutReturningPartialPages() {
        val chapterFile = temporaryFolder.newFile("over-budget-chapter.txt")
        writeLargeChapter(chapterFile)
        val countingReader = CountingReader(FileReader(chapterFile))

        val error = try {
            BufferedReader(countingReader).use { reader ->
                ReaderChapterPaginator.paginate(
                    chapter = TxtChapter().apply { title = "超大章" },
                    reader = reader,
                    spec = testSpec()
                )
            }
            throw AssertionError("Expected ReaderChapterPaginationLimitException")
        } catch (expected: ReaderChapterPaginationLimitException) {
            expected
        }

        assertTrue(error.message.orEmpty().contains("pagination limit"))
        assertTrue(countingReader.charactersRead < LARGE_CHAPTER_BYTES / 2L)
    }

    @Test
    fun fourMiBChapterRemainsWithinPaginationBudget() {
        val body = "x".repeat(4 * 1024 * 1024)

        val pages = ReaderChapterPaginator.paginate(
            chapter = TxtChapter().apply { title = "正常大章" },
            reader = BufferedReader(StringReader(body)),
            spec = testSpec()
        )

        assertTrue(pages.isNotEmpty())
    }

    @Test
    fun stalePageLoadGenerationCannotPublish() {
        val generations = LatestPageLoadGeneration()
        val older = generations.next()
        val newer = generations.next()

        assertFalse(generations.isLatest(older))
        assertTrue(generations.isLatest(newer))
        generations.invalidate()
        assertFalse(generations.isLatest(newer))
    }

    private fun testSpec(
        visibleHeight: Int = 100,
        bodyBreakCount: Int = 80
    ): ReaderChapterPaginationSpec {
        return ReaderChapterPaginationSpec(
            visibleHeight = visibleHeight,
            titleLineHeight = 10,
            bodyLineHeight = 10,
            titleInterval = 0,
            bodyInterval = 0,
            titleParagraphSpacing = 0,
            bodyParagraphSpacing = 0,
            breakTitle = { text -> min(text.length, 80) },
            breakBody = { text -> min(text.length, bodyBreakCount) },
            formatBodyParagraph = ReaderBodyParagraphFormatter::format
        )
    }

    private fun writeLargeChapter(file: java.io.File) {
        val line = ("body".repeat(1_000) + "\n").toByteArray(Charsets.UTF_8)
        FileOutputStream(file).buffered().use { output ->
            var written = 0L
            while (written < LARGE_CHAPTER_BYTES) {
                output.write(line)
                written += line.size
            }
        }
    }

    private class CountingReader(delegate: Reader) : FilterReader(delegate) {
        var charactersRead = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) charactersRead += 1L
            return value
        }

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) charactersRead += count.toLong()
            return count
        }
    }

    private companion object {
        const val LARGE_CHAPTER_BYTES = 24L * 1024L * 1024L
    }
}
