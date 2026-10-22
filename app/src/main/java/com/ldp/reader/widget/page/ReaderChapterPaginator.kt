package com.ldp.reader.widget.page

import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal data class ReaderChapterPaginationSpec(
    val visibleHeight: Int,
    val titleLineHeight: Int,
    val bodyLineHeight: Int,
    val titleInterval: Int,
    val bodyInterval: Int,
    val titleParagraphSpacing: Int,
    val bodyParagraphSpacing: Int,
    val breakTitle: (String) -> Int,
    val breakBody: (String) -> Int,
    val formatBodyParagraph: (String) -> String
)

internal object ReaderChapterPaginator {
    const val MAX_NORMALIZED_CHARS = 8L * 1024L * 1024L
    const val MAX_PAGES = 20_000

    fun paginate(
        chapter: TxtChapter,
        reader: BufferedReader,
        spec: ReaderChapterPaginationSpec,
        checkCancelled: () -> Unit = {}
    ): MutableList<TxtPage> {
        require(spec.visibleHeight > 0) { "visibleHeight must be positive" }
        require(spec.titleLineHeight > 0) { "titleLineHeight must be positive" }
        require(spec.bodyLineHeight > 0) { "bodyLineHeight must be positive" }
        val pages = ArrayList<TxtPage>()
        val lines = ArrayList<String>()
        var remainingHeight = spec.visibleHeight
        var titleLinesCount = 0
        var showTitle = true
        var paragraph: String? = chapter.getTitle().orEmpty()
        var normalizedChars = paragraph.orEmpty().length.toLong()
        enforceCharacterBudget(normalizedChars)

        reader.use { input ->
            while (true) {
                checkCancelled()
                if (!showTitle) {
                    val rawParagraph = input.readLine() ?: break
                    paragraph = spec.formatBodyParagraph(rawParagraph)
                    normalizedChars += paragraph.orEmpty().length.toLong()
                    enforceCharacterBudget(normalizedChars)
                } else {
                    remainingHeight -= spec.titleParagraphSpacing
                }

                var currentParagraph = paragraph.orEmpty()
                while (currentParagraph.isNotEmpty()) {
                    checkCancelled()
                    remainingHeight -= if (showTitle) {
                        spec.titleLineHeight
                    } else {
                        spec.bodyLineHeight
                    }
                    if (remainingHeight < 0 && lines.isNotEmpty()) {
                        enforcePageBudget(pages.size)
                        pages.add(page(chapter, pages.size, lines, titleLinesCount))
                        lines.clear()
                        remainingHeight = spec.visibleHeight
                        titleLinesCount = 0
                        continue
                    }

                    val measuredCount = if (showTitle) {
                        spec.breakTitle(currentParagraph)
                    } else {
                        spec.breakBody(currentParagraph)
                    }
                    val wordCount = ReaderTextSplitter.lineBreakCount(currentParagraph, measuredCount)
                    val line = currentParagraph.substring(0, wordCount)
                    if (line != "\n") {
                        lines.add(line)
                        if (showTitle) {
                            titleLinesCount += 1
                            remainingHeight -= spec.titleInterval
                        } else {
                            remainingHeight -= spec.bodyInterval
                        }
                    }
                    currentParagraph = currentParagraph.substring(wordCount)
                    paragraph = currentParagraph
                }

                if (!showTitle && lines.isNotEmpty()) {
                    remainingHeight =
                        remainingHeight - spec.bodyParagraphSpacing + spec.bodyInterval
                }
                if (showTitle) {
                    remainingHeight =
                        remainingHeight - spec.titleParagraphSpacing + spec.titleInterval
                    showTitle = false
                }
            }
        }

        checkCancelled()
        if (lines.isNotEmpty()) {
            enforcePageBudget(pages.size)
            pages.add(page(chapter, pages.size, lines, titleLinesCount))
        }
        return pages
    }

    private fun page(
        chapter: TxtChapter,
        position: Int,
        lines: List<String>,
        titleLinesCount: Int
    ): TxtPage {
        return TxtPage().apply {
            this.position = position
            title = chapter.getTitle()
            this.lines = ArrayList(lines)
            titleLines = titleLinesCount
        }
    }

    private fun enforceCharacterBudget(normalizedChars: Long) {
        if (normalizedChars > MAX_NORMALIZED_CHARS) {
            throw ReaderChapterPaginationLimitException(
                "TXT chapter exceeds the ${MAX_NORMALIZED_CHARS}-character pagination limit"
            )
        }
    }

    private fun enforcePageBudget(currentPageCount: Int) {
        if (currentPageCount >= MAX_PAGES) {
            throw ReaderChapterPaginationLimitException(
                "TXT chapter exceeds the $MAX_PAGES-page pagination limit"
            )
        }
    }

}

internal object ReaderBodyParagraphFormatter {
    private const val PARAGRAPH_INDENT = "\u3000\u3000"

    fun format(rawParagraph: String): String {
        val content = rawParagraph.trim()
        return if (content.isEmpty()) {
            "\n"
        } else {
            "$PARAGRAPH_INDENT$content\n"
        }
    }
}

internal class ReaderChapterPaginationLimitException(message: String) : IOException(message)

internal class LatestPageLoadGeneration {
    private val generation = AtomicLong(0L)

    fun next(): Long = generation.incrementAndGet()

    fun isLatest(candidate: Long): Boolean = generation.get() == candidate

    fun invalidate() {
        generation.incrementAndGet()
    }
}
