package com.ldp.reader.widget.page

internal enum class ReaderPageSemanticRole {
    HEADING,
    PARAGRAPH,
    STATUS
}

internal data class ReaderPageSemanticNode(
    val virtualId: Int,
    val role: ReaderPageSemanticRole,
    val text: String,
    val visualLineCount: Int
)

internal data class ReaderPageSemanticSnapshot(
    val nodes: List<ReaderPageSemanticNode>
) {
    companion object {
        val EMPTY = ReaderPageSemanticSnapshot(emptyList())
    }
}

/**
 * Converts only the currently visible canvas page into screen-reader-sized semantic nodes.
 * A chapter heading and each visible paragraph stay independently navigable.
 */
internal object ReaderPageSemantics {

    private const val MAX_NODE_CODE_POINTS = 280

    fun fromPage(
        statusText: String?,
        chapterTitle: String?,
        page: TxtPage?
    ): ReaderPageSemanticSnapshot {
        val visibleStatus = statusText?.trim().orEmpty()
        if (visibleStatus.isNotEmpty()) {
            return ReaderPageSemanticSnapshot(
                listOf(
                    ReaderPageSemanticNode(
                        virtualId = 1,
                        role = ReaderPageSemanticRole.STATUS,
                        text = visibleStatus,
                        visualLineCount = 1
                    )
                )
            )
        }

        if (page == null) return ReaderPageSemanticSnapshot.EMPTY

        val lines = page.lines.orEmpty()
        val titleLineCount = page.titleLines.coerceIn(0, lines.size)
        val nodes = mutableListOf<ReaderPageSemanticNode>()
        val heading = chapterTitle?.trim().takeUnless { it.isNullOrEmpty() }
            ?: page.title?.trim().orEmpty()
        if (heading.isNotEmpty()) {
            nodes += ReaderPageSemanticNode(
                virtualId = nodes.size + 1,
                role = ReaderPageSemanticRole.HEADING,
                text = heading,
                visualLineCount = titleLineCount.coerceAtLeast(1)
            )
        }

        val paragraph = StringBuilder()
        var paragraphLineCount = 0
        var paragraphCodePointCount = 0
        fun publishParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) {
                nodes += ReaderPageSemanticNode(
                    virtualId = nodes.size + 1,
                    role = ReaderPageSemanticRole.PARAGRAPH,
                    text = text,
                    visualLineCount = paragraphLineCount.coerceAtLeast(1)
                )
            }
            paragraph.clear()
            paragraphLineCount = 0
            paragraphCodePointCount = 0
        }

        lines.drop(titleLineCount).forEach { line ->
            val visibleLine = line.trimEnd('\r', '\n')
            splitByCodePoints(visibleLine, MAX_NODE_CODE_POINTS).forEach { segment ->
                val segmentCodePoints = segment.codePointCount(0, segment.length)
                if (
                    paragraph.isNotEmpty() &&
                    paragraphCodePointCount + segmentCodePoints > MAX_NODE_CODE_POINTS
                ) {
                    publishParagraph()
                }
                paragraph.append(segment)
                paragraphLineCount += 1
                paragraphCodePointCount += segmentCodePoints
            }
            if (line.endsWith("\n") || line.endsWith("\r")) {
                publishParagraph()
            }
        }
        publishParagraph()

        return ReaderPageSemanticSnapshot(nodes)
    }

    private fun splitByCodePoints(text: String, maxCodePoints: Int): List<String> {
        if (text.codePointCount(0, text.length) <= maxCodePoints) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remainingCodePoints = text.codePointCount(start, text.length)
            val chunkCodePoints = minOf(maxCodePoints, remainingCodePoints)
            val end = text.offsetByCodePoints(start, chunkCodePoints)
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}
