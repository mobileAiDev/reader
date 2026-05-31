package com.ldp.reader.source

import com.google.gson.Gson
import com.ldp.reader.utils.BookIdentity
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import java.util.Base64

object SourceEngineBookRoute {
    private const val BOOK_PREFIX = "source_engine_book_"
    private const val CHAPTER_PREFIX = "source_engine_chapter_"
    private val gson = Gson()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun isBookId(id: String?): Boolean {
        return id?.startsWith(BOOK_PREFIX) == true
    }

    fun isChapterId(id: String?): Boolean {
        return id?.startsWith(CHAPTER_PREFIX) == true
    }

    fun isShelfBookId(id: String?): Boolean {
        return BookIdentity.isSourceEngineShelfId(id)
    }

    fun bookId(book: SourceBook, coverCandidates: List<String> = emptyList()): String {
        return BOOK_PREFIX + encode(
            BookPayload(
                sourceUrl = book.source.sourceUrl,
                bookUrl = book.bookUrl,
                name = book.name,
                author = book.author,
                coverUrl = book.coverUrl,
                intro = book.intro,
                kind = book.kind,
                lastChapter = book.lastChapter,
                coverCandidates = coverCandidates(book.coverUrl, coverCandidates)
            )
        )
    }

    fun shelfBookId(book: SourceBook): String {
        return BookIdentity.sourceEngineShelfId(book.name, book.author)
    }

    fun chapterId(chapter: SourceChapter): String {
        return CHAPTER_PREFIX + encode(
            ChapterPayload(
                sourceUrl = chapter.source.sourceUrl,
                bookUrl = chapter.book.bookUrl,
                bookName = chapter.book.name,
                author = chapter.book.author,
                coverUrl = chapter.book.coverUrl,
                intro = chapter.book.intro,
                kind = chapter.book.kind,
                lastChapter = chapter.book.lastChapter,
                index = chapter.index,
                chapterName = chapter.name,
                chapterUrl = chapter.chapterUrl,
                coverCandidates = coverCandidates(chapter.book.coverUrl, emptyList())
            )
        )
    }

    fun decodeBookId(id: String): BookPayload {
        require(isBookId(id)) { "Not a source-engine book id." }
        return decode(id.removePrefix(BOOK_PREFIX), BookPayload::class.java)
    }

    fun decodeChapterId(id: String): ChapterPayload {
        require(isChapterId(id)) { "Not a source-engine chapter id." }
        return decode(id.removePrefix(CHAPTER_PREFIX), ChapterPayload::class.java)
    }

    fun toSourceBook(source: BookSource, payload: BookPayload): SourceBook {
        return SourceBook(
            source = source,
            name = payload.name,
            author = payload.author,
            bookUrl = payload.bookUrl,
            coverUrl = payload.coverUrl,
            intro = payload.intro,
            kind = payload.kind,
            lastChapter = payload.lastChapter
        )
    }

    fun toSourceChapter(source: BookSource, payload: ChapterPayload): SourceChapter {
        val book = SourceBook(
            source = source,
            name = payload.bookName,
            author = payload.author,
            bookUrl = payload.bookUrl,
            coverUrl = payload.coverUrl,
            intro = payload.intro,
            kind = payload.kind,
            lastChapter = payload.lastChapter
        )
        return SourceChapter(
            source = source,
            book = book,
            index = payload.index,
            name = payload.chapterName,
            chapterUrl = payload.chapterUrl
        )
    }

    fun coverCandidates(payload: BookPayload): List<String> {
        return coverCandidates(payload.coverUrl, payload.coverCandidates.orEmpty())
    }

    fun coverCandidates(payload: ChapterPayload): List<String> {
        return coverCandidates(payload.coverUrl, payload.coverCandidates.orEmpty())
    }

    private fun encode(value: Any): String {
        return encoder.encodeToString(gson.toJson(value).toByteArray(Charsets.UTF_8))
    }

    private fun <T> decode(value: String, type: Class<T>): T {
        val json = String(decoder.decode(value), Charsets.UTF_8)
        return gson.fromJson(json, type)
    }

    private fun coverCandidates(primary: String?, candidates: List<String>): List<String> {
        return (listOf(primary) + candidates)
            .map { value -> value?.trim().orEmpty() }
            .filter { value -> value.isNotBlank() }
            .distinct()
    }

    data class BookPayload(
        val sourceUrl: String,
        val bookUrl: String,
        val name: String,
        val author: String,
        val coverUrl: String,
        val intro: String,
        val kind: String,
        val lastChapter: String,
        val coverCandidates: List<String>? = null
    )

    data class ChapterPayload(
        val sourceUrl: String,
        val bookUrl: String,
        val bookName: String,
        val author: String,
        val coverUrl: String,
        val intro: String,
        val kind: String,
        val lastChapter: String,
        val index: Int,
        val chapterName: String,
        val chapterUrl: String,
        val coverCandidates: List<String>? = null
    )
}
