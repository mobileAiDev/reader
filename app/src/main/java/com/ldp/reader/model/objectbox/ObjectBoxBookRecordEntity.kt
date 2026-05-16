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

    constructor(bookId: String?, chapter: Int, pagePos: Int) : this() {
        this.bookId = bookId
        this.chapter = chapter
        this.pagePos = pagePos
    }

    fun toBookRecord(): BookRecordBean {
        return BookRecordBean(bookId, chapter, pagePos)
    }

    companion object {
        @JvmStatic
        fun from(record: BookRecordBean): ObjectBoxBookRecordEntity {
            return ObjectBoxBookRecordEntity(record.bookId, record.chapter, record.pagePos)
        }
    }
}
