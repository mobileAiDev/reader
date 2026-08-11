package com.ldp.reader.widget.page

import com.ldp.reader.model.bean.BookChapterBean
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.regex.Pattern

internal data class ParsedLocalTxtChapter(
    val title: String,
    val start: Long,
    val end: Long
)

internal data class LocalTxtParseResult(
    val chapters: List<ParsedLocalTxtChapter>,
    val hasExplicitChapters: Boolean
)

internal data class LocalTxtChapterSnapshot(
    val fingerprint: String,
    val chapters: MutableList<TxtChapter>
)

internal enum class LocalTxtParseFailure {
    SOURCE_CHANGED,
    LINE_TOO_LONG
}

internal class LocalTxtParseException(
    message: String,
    val failure: LocalTxtParseFailure = LocalTxtParseFailure.SOURCE_CHANGED
) : IOException(message)

/**
 * Parses local TXT chapter boundaries as raw byte offsets.
 *
 * Lines are decoded only after a complete line delimiter has been observed, so a buffered read
 * boundary can never split a UTF-8, GBK, or UTF-16 character. Explicit title lines are stored as
 * chapter metadata and excluded from the chapter body consumed by [PageLoader].
 */
internal object LocalTxtChapterParser {
    private const val DEFAULT_BUFFER_SIZE = 512 * 1024
    private const val DEFAULT_VIRTUAL_CHAPTER_BYTES = 10 * 1024
    private const val DEFAULT_MAX_LINE_BYTES = 1024 * 1024

    private val chapterPatterns = listOf(
        Pattern.compile("^(\\s{0,8})(第)([0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,10})([章节回集卷])(.{0,30})$"),
        Pattern.compile("^(\\s{0,4})([\\(【《]?(卷)?)([0-9零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,10})([\\.:：\\u0020\\f\\t])(.{0,30})$"),
        Pattern.compile("^(\\s{0,4})([\\(（【《])(.{0,30})([\\)）】》])(\\s{0,2})$"),
        Pattern.compile("^(\\s{0,4})(正文)(.{0,20})$"),
        Pattern.compile("^(\\s{0,4})(Chapter|chapter)(\\s{0,4})([0-9]{1,4})(.{0,30})$")
    )

    fun parse(
        file: File,
        charset: Charset,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        virtualChapterBytes: Int = DEFAULT_VIRTUAL_CHAPTER_BYTES,
        maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
        cancellationCheck: () -> Unit = {}
    ): LocalTxtParseResult {
        cancellationCheck()
        return LocalTxtFileSnapshot.open(file).use { snapshot ->
            parse(
                snapshot,
                charset,
                bufferSize,
                virtualChapterBytes,
                maxLineBytes,
                cancellationCheck
            )
        }
    }

    fun parse(
        snapshot: LocalTxtFileSnapshot,
        charset: Charset,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        virtualChapterBytes: Int = DEFAULT_VIRTUAL_CHAPTER_BYTES,
        maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
        cancellationCheck: () -> Unit = {}
    ): LocalTxtParseResult {
        require(bufferSize > 0)
        require(virtualChapterBytes > 0)
        require(maxLineBytes > 0)
        cancellationCheck()
        if (snapshot.length == 0L) {
            return LocalTxtParseResult(emptyList(), hasExplicitChapters = false)
        }

        val matchingPatterns = BooleanArray(chapterPatterns.size)
        scanLines(snapshot, charset, bufferSize, maxLineBytes, cancellationCheck) { line ->
            chapterPatterns.forEachIndexed { index, pattern ->
                if (!matchingPatterns[index] && pattern.matcher(line.text).matches()) {
                    matchingPatterns[index] = true
                }
            }
        }
        val chapterPattern = chapterPatterns.indices
            .firstOrNull { matchingPatterns[it] }
            ?.let(chapterPatterns::get)

        return if (chapterPattern == null) {
            LocalTxtParseResult(
                chapters = parseVirtualChapters(
                    snapshot,
                    charset,
                    bufferSize,
                    virtualChapterBytes,
                    maxLineBytes,
                    cancellationCheck
                ),
                hasExplicitChapters = false
            )
        } else {
            LocalTxtParseResult(
                chapters = parseExplicitChapters(
                    snapshot,
                    charset,
                    bufferSize,
                    maxLineBytes,
                    chapterPattern,
                    cancellationCheck
                ),
                hasExplicitChapters = true
            )
        }
    }

    private fun parseExplicitChapters(
        snapshot: LocalTxtFileSnapshot,
        charset: Charset,
        bufferSize: Int,
        maxLineBytes: Int,
        chapterPattern: Pattern,
        cancellationCheck: () -> Unit
    ): List<ParsedLocalTxtChapter> {
        val chapters = ArrayList<ParsedLocalTxtChapter>()
        cancellationCheck()
        val dataStart = bomLength(snapshot, charset, cancellationCheck).toLong()
        var currentTitle: String? = null
        var currentBodyStart = 0L

        scanLines(snapshot, charset, bufferSize, maxLineBytes, cancellationCheck) { line ->
            val matcher = chapterPattern.matcher(line.text)
            if (!matcher.matches()) return@scanLines

            if (currentTitle == null) {
                if (line.start - dataStart > MIN_PROLOGUE_BYTES) {
                    chapters.add(
                        ParsedLocalTxtChapter(
                            title = PROLOGUE_TITLE,
                            start = dataStart,
                            end = line.start
                        )
                    )
                }
            } else {
                chapters.add(
                    ParsedLocalTxtChapter(
                        title = currentTitle!!,
                        start = currentBodyStart,
                        end = line.start
                    )
                )
            }

            currentTitle = matcher.group().trim()
            currentBodyStart = line.end
        }

        currentTitle?.let { title ->
            chapters.add(
                ParsedLocalTxtChapter(
                    title = title,
                    start = currentBodyStart,
                    end = snapshot.length
                )
            )
        }
        return chapters
    }

    private fun parseVirtualChapters(
        snapshot: LocalTxtFileSnapshot,
        charset: Charset,
        bufferSize: Int,
        virtualChapterBytes: Int,
        maxLineBytes: Int,
        cancellationCheck: () -> Unit
    ): List<ParsedLocalTxtChapter> {
        val chapters = ArrayList<ParsedLocalTxtChapter>()
        cancellationCheck()
        var chapterStart = bomLength(snapshot, charset, cancellationCheck).toLong()
        var lastLineEnd = chapterStart

        scanLines(snapshot, charset, bufferSize, maxLineBytes, cancellationCheck) { line ->
            lastLineEnd = line.end
            if (line.end - chapterStart >= virtualChapterBytes) {
                chapters.add(
                    ParsedLocalTxtChapter(
                        title = virtualTitle(chapters.size),
                        start = chapterStart,
                        end = line.end
                    )
                )
                chapterStart = line.end
            }
        }

        val fileEnd = snapshot.length
        if (chapterStart < fileEnd) {
            chapters.add(
                ParsedLocalTxtChapter(
                    title = virtualTitle(chapters.size),
                    start = chapterStart,
                    end = fileEnd
                )
            )
        } else if (chapters.isEmpty() && lastLineEnd > chapterStart) {
            chapters.add(
                ParsedLocalTxtChapter(
                    title = virtualTitle(0),
                    start = chapterStart,
                    end = lastLineEnd
                )
            )
        }
        return chapters
    }

    private fun virtualTitle(index: Int): String = "第 ${index + 1} 节"

    private fun scanLines(
        snapshot: LocalTxtFileSnapshot,
        charset: Charset,
        bufferSize: Int,
        maxLineBytes: Int,
        cancellationCheck: () -> Unit,
        action: (ByteLine) -> Unit
    ) {
        cancellationCheck()
        if (isUtf16(charset)) {
            scanUtf16Lines(
                snapshot,
                charset,
                bufferSize,
                maxLineBytes,
                cancellationCheck,
                action
            )
        } else {
            scanSingleByteDelimitedLines(
                snapshot,
                charset,
                bufferSize,
                maxLineBytes,
                cancellationCheck,
                action
            )
        }
    }

    private fun scanSingleByteDelimitedLines(
        snapshot: LocalTxtFileSnapshot,
        charset: Charset,
        bufferSize: Int,
        maxLineBytes: Int,
        cancellationCheck: () -> Unit,
        action: (ByteLine) -> Unit
    ) {
        BufferedInputStream(snapshot.openStream(), bufferSize).use { input ->
            val bom = skipBom(input, charset)
            cancellationCheck()
            var offset = bom.toLong()
            var lineStart = offset
            val lineBytes = ByteArrayOutputStream()
            var pendingCarriageReturn = false
            var uncheckedBytes = 0

            fun emitLine(lineEnd: Long) {
                action(ByteLine(lineStart, lineEnd, String(lineBytes.toByteArray(), charset)))
                lineBytes.reset()
                lineStart = lineEnd
            }

            while (true) {
                val value = input.read()
                if (value < 0) break
                offset += 1
                uncheckedBytes += 1
                if (uncheckedBytes >= CANCELLATION_CHECK_BYTES) {
                    cancellationCheck()
                    uncheckedBytes = 0
                }

                if (pendingCarriageReturn) {
                    pendingCarriageReturn = false
                    if (value == LF) {
                        emitLine(offset)
                        continue
                    }
                    emitLine(offset - 1)
                }

                when (value) {
                    CR -> pendingCarriageReturn = true
                    LF -> emitLine(offset)
                    else -> {
                        lineBytes.write(value)
                        ensureLineWithinLimit(lineBytes.size(), maxLineBytes)
                    }
                }
            }

            if (pendingCarriageReturn) {
                emitLine(offset)
            } else if (lineBytes.size() > 0) {
                emitLine(offset)
            }
            cancellationCheck()
        }
    }

    private fun scanUtf16Lines(
        snapshot: LocalTxtFileSnapshot,
        charset: Charset,
        bufferSize: Int,
        maxLineBytes: Int,
        cancellationCheck: () -> Unit,
        action: (ByteLine) -> Unit
    ) {
        val littleEndian = charset.name().equals("UTF-16LE", ignoreCase = true)
        BufferedInputStream(snapshot.openStream(), bufferSize).use { input ->
            val bom = skipBom(input, charset)
            cancellationCheck()
            var offset = bom.toLong()
            var lineStart = offset
            val lineBytes = ByteArrayOutputStream()
            var pendingCarriageReturn = false
            var uncheckedBytes = 0

            fun emitLine(lineEnd: Long) {
                action(ByteLine(lineStart, lineEnd, String(lineBytes.toByteArray(), charset)))
                lineBytes.reset()
                lineStart = lineEnd
            }

            while (true) {
                val first = input.read()
                if (first < 0) break
                val second = input.read()
                offset += if (second < 0) 1 else 2
                uncheckedBytes += if (second < 0) 1 else 2
                if (uncheckedBytes >= CANCELLATION_CHECK_BYTES) {
                    cancellationCheck()
                    uncheckedBytes = 0
                }
                if (second < 0) {
                    lineBytes.write(first)
                    break
                }

                val codeUnit = if (littleEndian) {
                    first or (second shl 8)
                } else {
                    (first shl 8) or second
                }

                if (pendingCarriageReturn) {
                    pendingCarriageReturn = false
                    if (codeUnit == LF) {
                        emitLine(offset)
                        continue
                    }
                    emitLine(offset - 2)
                }

                when (codeUnit) {
                    CR -> pendingCarriageReturn = true
                    LF -> emitLine(offset)
                    else -> {
                        lineBytes.write(first)
                        lineBytes.write(second)
                        ensureLineWithinLimit(lineBytes.size(), maxLineBytes)
                    }
                }
            }

            if (pendingCarriageReturn) {
                emitLine(offset)
            } else if (lineBytes.size() > 0) {
                emitLine(offset)
            }
            cancellationCheck()
        }
    }

    private fun ensureLineWithinLimit(lineBytes: Int, maxLineBytes: Int) {
        if (lineBytes > maxLineBytes) {
            throw LocalTxtParseException(
                message = "TXT line exceeds $maxLineBytes bytes",
                failure = LocalTxtParseFailure.LINE_TOO_LONG
            )
        }
    }

    private fun skipBom(input: BufferedInputStream, charset: Charset): Int {
        val expected = when {
            charset.name().equals("UTF-8", ignoreCase = true) -> UTF8_BOM
            charset.name().equals("UTF-16LE", ignoreCase = true) -> UTF16_LE_BOM
            charset.name().equals("UTF-16BE", ignoreCase = true) -> UTF16_BE_BOM
            else -> return 0
        }
        input.mark(expected.size)
        val actual = ByteArray(expected.size)
        val count = input.read(actual)
        if (count == expected.size && actual.contentEquals(expected)) {
            return expected.size
        }
        input.reset()
        return 0
    }

    private fun bomLength(
        snapshot: LocalTxtFileSnapshot,
        charset: Charset,
        cancellationCheck: () -> Unit
    ): Int {
        cancellationCheck()
        BufferedInputStream(snapshot.openStream()).use { input ->
            return skipBom(input, charset).also { cancellationCheck() }
        }
    }

    private fun isUtf16(charset: Charset): Boolean {
        return charset.name().equals("UTF-16LE", ignoreCase = true) ||
            charset.name().equals("UTF-16BE", ignoreCase = true)
    }

    private data class ByteLine(
        val start: Long,
        val end: Long,
        val text: String
    )

    private const val MIN_PROLOGUE_BYTES = 30
    private const val PROLOGUE_TITLE = "序章"
    private const val LF = 0x0a
    private const val CR = 0x0d
    private const val CANCELLATION_CHECK_BYTES = 4 * 1024
    private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xff.toByte(), 0xfe.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xfe.toByte(), 0xff.toByte())
}

internal object LocalTxtChapterMapper {
    fun fromCached(bookChapters: List<BookChapterBean>): MutableList<TxtChapter> {
        return bookChapters.mapIndexedTo(ArrayList(bookChapters.size)) { index, bean ->
            TxtChapter().apply {
                title = bean.title
                catalogIndex = index
                start = bean.start
                end = bean.end
            }
        }
    }
}

/** Publishes chapter offsets only when the source file stayed unchanged for the whole parse. */
internal object LocalTxtChapterSnapshotLoader {
    private const val MAX_PARSE_ATTEMPTS = 2

    fun load(
        file: File,
        charset: Charset,
        bufferSize: Int,
        virtualChapterBytes: Int,
        cancellationCheck: () -> Unit = {}
    ): LocalTxtChapterSnapshot {
        return loadStable(file, cancellationCheck = cancellationCheck) {
            LocalTxtChapterParser.parse(
                file = file,
                charset = charset,
                bufferSize = bufferSize,
                virtualChapterBytes = virtualChapterBytes,
                cancellationCheck = cancellationCheck
            )
        }
    }

    fun load(
        snapshot: LocalTxtFileSnapshot,
        charset: Charset,
        bufferSize: Int,
        virtualChapterBytes: Int,
        cancellationCheck: () -> Unit = {}
    ): LocalTxtChapterSnapshot {
        repeat(MAX_PARSE_ATTEMPTS) {
            cancellationCheck()
            val fingerprintBefore = LocalTxtParserCacheVersionStore.fingerprint(
                snapshot,
                cancellationCheck
            )
            cancellationCheck()
            val parsed = LocalTxtChapterParser.parse(
                snapshot = snapshot,
                charset = charset,
                bufferSize = bufferSize,
                virtualChapterBytes = virtualChapterBytes,
                cancellationCheck = cancellationCheck
            )
            cancellationCheck()
            val fingerprintAfter = LocalTxtParserCacheVersionStore.fingerprint(
                snapshot,
                cancellationCheck
            )
            cancellationCheck()
            if (fingerprintBefore == fingerprintAfter) {
                return LocalTxtChapterSnapshot(
                    fingerprintAfter,
                    mapChapters(parsed)
                )
            }
        }
        throw LocalTxtParseException("TXT file changed while chapters were being parsed")
    }

    internal fun loadStable(
        file: File,
        maxAttempts: Int = MAX_PARSE_ATTEMPTS,
        cancellationCheck: () -> Unit = {},
        parse: () -> LocalTxtParseResult
    ): LocalTxtChapterSnapshot {
        require(maxAttempts > 0)
        repeat(maxAttempts) {
            cancellationCheck()
            val fingerprintBefore = LocalTxtParserCacheVersionStore.fingerprint(
                file,
                cancellationCheck
            )
            cancellationCheck()
            val parsed = parse()
            cancellationCheck()
            val fingerprintAfter = LocalTxtParserCacheVersionStore.fingerprint(
                file,
                cancellationCheck
            )
            cancellationCheck()
            if (fingerprintBefore == fingerprintAfter) {
                return LocalTxtChapterSnapshot(fingerprintAfter, mapChapters(parsed))
            }
        }
        throw LocalTxtParseException("TXT file changed while chapters were being parsed")
    }

    private fun mapChapters(parsed: LocalTxtParseResult): MutableList<TxtChapter> {
        return parsed.chapters.mapIndexedTo(ArrayList(parsed.chapters.size)) {
                index,
                parsedChapter ->
            TxtChapter().apply {
                title = parsedChapter.title
                catalogIndex = index
                start = parsedChapter.start
                end = parsedChapter.end
            }
        }
    }
}
