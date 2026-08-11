package com.ldp.reader.model.objectbox

import com.ldp.reader.model.bean.BookRecordBean
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
class ObjectBoxBookRecordEntity() {
    @Id
    var id: Long = 0

    @Index
    var bookId: String? = null

    var chapter: Int = 0
    var pagePos: Int = 0
    var chapterLink: String? = null
    var chapterTitle: String? = null

    constructor(
        bookId: String?,
        chapter: Int,
        pagePos: Int,
        chapterLink: String?,
        chapterTitle: String?
    ) : this() {
        this.bookId = bookId
        this.chapter = chapter
        this.pagePos = pagePos
        this.chapterLink = chapterLink
        this.chapterTitle = chapterTitle
    }

    fun toBookRecord(): BookRecordBean {
        return BookRecordBean(bookId, chapter, pagePos, chapterLink, chapterTitle)
    }

    companion object {
        @JvmStatic
        fun from(record: BookRecordBean): ObjectBoxBookRecordEntity {
            return ObjectBoxBookRecordEntity(
                record.bookId,
                record.chapter,
                record.pagePos,
                record.chapterLink,
                record.chapterTitle
            )
        }
    }
}
