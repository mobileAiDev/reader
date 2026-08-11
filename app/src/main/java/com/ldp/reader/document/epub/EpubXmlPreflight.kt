package com.ldp.reader.document.epub

/**
 * Performs a bounded, allocation-light markup scan before Jsoup builds an XML DOM.
 *
 * This is intentionally not a second XML parser. Jsoup remains responsible for parsing and
 * validation; the preflight only puts a hard ceiling on the number and nesting depth of elements
 * that Jsoup may allocate, while providing cooperative cancellation during the byte scan.
 */
internal class EpubXmlPreflight(
    private val limits: EpubReadLimits
) {
    fun validate(bytes: ByteArray, path: String, checkCancelled: () -> Unit = {}) {
        var index = 0
        var nodeCount = 0
        val openTags = ArrayDeque<TagName>()
        checkCancelled()

        while (index < bytes.size) {
            checkCancelled()
            index = findNextMarkup(bytes, index, checkCancelled)
            if (index >= bytes.size) break

            when {
                bytes.startsWithAscii(index, COMMENT_START) -> {
                    index = findTerminator(
                        bytes = bytes,
                        start = index + COMMENT_START.length,
                        terminator = COMMENT_END,
                        path = path,
                        checkCancelled = checkCancelled
                    )
                }

                bytes.startsWithAscii(index, CDATA_START) -> {
                    index = findTerminator(
                        bytes = bytes,
                        start = index + CDATA_START.length,
                        terminator = CDATA_END,
                        path = path,
                        checkCancelled = checkCancelled
                    )
                }

                bytes.startsWithAscii(index, PROCESSING_INSTRUCTION_START) -> {
                    index = findTerminator(
                        bytes = bytes,
                        start = index + PROCESSING_INSTRUCTION_START.length,
                        terminator = PROCESSING_INSTRUCTION_END,
                        path = path,
                        checkCancelled = checkCancelled
                    )
                }

                bytes.startsWithAscii(index, CLOSING_TAG_START) -> {
                    val tagEnd = findTagEnd(bytes, index + CLOSING_TAG_START.length, path, checkCancelled)
                    val closingTag = readClosingTagName(
                        bytes,
                        index + CLOSING_TAG_START.length,
                        tagEnd - 1,
                        path
                    )
                    val openingTag = openTags.lastOrNull() ?: throw malformed(path)
                    if (!bytes.tagNamesEqual(openingTag, closingTag)) throw malformed(path)
                    openTags.removeLast()
                    index = tagEnd
                }

                bytes.startsWithAscii(index, DECLARATION_START) -> {
                    index = findDeclarationEnd(bytes, index + DECLARATION_START.length, path, checkCancelled)
                }

                else -> {
                    val tagEnd = findTagEnd(bytes, index + 1, path, checkCancelled)
                    val openingTag = readOpeningTagName(bytes, index + 1, tagEnd - 1, path)
                    nodeCount += 1
                    if (nodeCount > limits.maxXmlNodes) {
                        throw EpubParseException(
                            EpubParseError.XML_NODE_LIMIT_EXCEEDED,
                            "EPUB XML 节点数超过安全上限: $path"
                        )
                    }
                    if (!isSelfClosing(bytes, tagEnd - 2)) {
                        openTags.addLast(openingTag)
                        if (openTags.size > limits.maxXmlDepth) {
                            throw EpubParseException(
                                EpubParseError.XML_DEPTH_LIMIT_EXCEEDED,
                                "EPUB XML 嵌套层级超过安全上限: $path"
                            )
                        }
                    }
                    index = tagEnd
                }
            }
        }
        if (openTags.isNotEmpty()) throw malformed(path)
        checkCancelled()
    }

    private fun readOpeningTagName(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        path: String
    ): TagName {
        val name = readTagName(bytes, start, endExclusive, path)
        if (name.start != start) throw malformed(path)
        return name
    }

    private fun readClosingTagName(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        path: String
    ): TagName {
        val name = readTagName(bytes, start, endExclusive, path)
        var index = name.endExclusive
        while (index < endExclusive && bytes[index].isXmlWhitespace()) index += 1
        if (index != endExclusive) throw malformed(path)
        return name
    }

    private fun readTagName(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        path: String
    ): TagName {
        if (start >= endExclusive || bytes[start].isXmlWhitespace()) throw malformed(path)
        var index = start
        while (
            index < endExclusive &&
            !bytes[index].isXmlWhitespace() &&
            bytes[index] != SELF_CLOSING_MARKER
        ) {
            index += 1
        }
        if (index == start) throw malformed(path)
        return TagName(start = start, endExclusive = index)
    }

    private fun findNextMarkup(
        bytes: ByteArray,
        start: Int,
        checkCancelled: () -> Unit
    ): Int {
        var index = start
        while (index < bytes.size && bytes[index] != MARKUP_OPEN) {
            index += 1
            if (index % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        }
        return index
    }

    /** Returns the first byte index after the closing delimiter. */
    private fun findTerminator(
        bytes: ByteArray,
        start: Int,
        terminator: String,
        path: String,
        checkCancelled: () -> Unit
    ): Int {
        var index = start
        while (index <= bytes.size - terminator.length) {
            if (bytes.startsWithAscii(index, terminator)) return index + terminator.length
            index += 1
            if (index % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        }
        throw malformed(path)
    }

    /** Returns the first byte index after `>`, respecting quoted attribute values. */
    private fun findTagEnd(
        bytes: ByteArray,
        start: Int,
        path: String,
        checkCancelled: () -> Unit
    ): Int {
        var quote: Byte? = null
        var index = start
        while (index < bytes.size) {
            val value = bytes[index]
            when {
                quote != null && value == quote -> quote = null
                quote == null && (value == SINGLE_QUOTE || value == DOUBLE_QUOTE) -> quote = value
                quote == null && value == MARKUP_CLOSE -> return index + 1
            }
            index += 1
            if (index % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        }
        throw malformed(path)
    }

    /**
     * Skips a declaration, including a possible DOCTYPE internal subset. Bracket tracking keeps
     * `>` characters inside `[ ... ]` from being mistaken for the declaration terminator.
     */
    private fun findDeclarationEnd(
        bytes: ByteArray,
        start: Int,
        path: String,
        checkCancelled: () -> Unit
    ): Int {
        var quote: Byte? = null
        var subsetDepth = 0
        var index = start
        while (index < bytes.size) {
            val value = bytes[index]
            when {
                quote != null && value == quote -> quote = null
                quote == null && (value == SINGLE_QUOTE || value == DOUBLE_QUOTE) -> quote = value
                quote == null && value == INTERNAL_SUBSET_OPEN -> subsetDepth += 1
                quote == null && value == INTERNAL_SUBSET_CLOSE && subsetDepth > 0 -> subsetDepth -= 1
                quote == null && value == MARKUP_CLOSE && subsetDepth == 0 -> return index + 1
            }
            index += 1
            if (index % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        }
        throw malformed(path)
    }

    private fun isSelfClosing(bytes: ByteArray, byteBeforeTagEnd: Int): Boolean {
        return byteBeforeTagEnd >= 0 && bytes[byteBeforeTagEnd] == SELF_CLOSING_MARKER
    }

    private fun ByteArray.startsWithAscii(offset: Int, value: String): Boolean {
        if (offset < 0 || offset + value.length > size) return false
        for (index in value.indices) {
            if (this[offset + index] != value[index].code.toByte()) return false
        }
        return true
    }

    private fun ByteArray.tagNamesEqual(first: TagName, second: TagName): Boolean {
        val firstLength = first.endExclusive - first.start
        if (firstLength != second.endExclusive - second.start) return false
        for (offset in 0 until firstLength) {
            if (this[first.start + offset] != this[second.start + offset]) return false
        }
        return true
    }

    private fun Byte.isXmlWhitespace(): Boolean {
        return this == SPACE || this == TAB || this == LINE_FEED || this == CARRIAGE_RETURN
    }

    private fun malformed(path: String): EpubParseException {
        return EpubParseException(EpubParseError.PACKAGE_INVALID, "EPUB XML 无法解析: $path")
    }

    private data class TagName(
        val start: Int,
        val endExclusive: Int
    )

    private companion object {
        const val CANCELLATION_CHECK_INTERVAL = 4 * 1024
        const val COMMENT_START = "<!--"
        const val COMMENT_END = "-->"
        const val CDATA_START = "<![CDATA["
        const val CDATA_END = "]]>"
        const val PROCESSING_INSTRUCTION_START = "<?"
        const val PROCESSING_INSTRUCTION_END = "?>"
        const val CLOSING_TAG_START = "</"
        const val DECLARATION_START = "<!"
        const val MARKUP_OPEN: Byte = 60
        const val MARKUP_CLOSE: Byte = 62
        const val SINGLE_QUOTE: Byte = 39
        const val DOUBLE_QUOTE: Byte = 34
        const val INTERNAL_SUBSET_OPEN: Byte = 91
        const val INTERNAL_SUBSET_CLOSE: Byte = 93
        const val SELF_CLOSING_MARKER: Byte = 47
        const val SPACE: Byte = 32
        const val TAB: Byte = 9
        const val LINE_FEED: Byte = 10
        const val CARRIAGE_RETURN: Byte = 13
    }
}
