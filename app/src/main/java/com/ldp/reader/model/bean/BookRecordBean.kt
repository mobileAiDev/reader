package com.ldp.reader.model.bean

class BookRecordBean() {
    var bookId: String? = null
    var chapter: Int = 0
    var pagePos: Int = 0
    var chapterLink: String? = null
    var chapterTitle: String? = null

    constructor(bookId: String?, chapter: Int, pagePos: Int) : this() {
        this.bookId = bookId
        this.chapter = chapter
        this.pagePos = pagePos
    }

    constructor(
        bookId: String?,
        chapter: Int,
        pagePos: Int,
        chapterLink: String?,
        chapterTitle: String?
    ) : this(bookId, chapter, pagePos) {
        this.chapterLink = chapterLink
        this.chapterTitle = chapterTitle
    }
}
