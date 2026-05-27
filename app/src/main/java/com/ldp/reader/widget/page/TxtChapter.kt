package com.ldp.reader.widget.page

class TxtChapter {
    @JvmField
    var bookId: String? = null

    @JvmField
    var link: String? = null

    @JvmField
    var chapterIdInBiquge = 0

    @JvmField
    var title: String? = null

    @JvmField
    var catalogIndex = -1

    @JvmField
    var sourceIntegrityState: String? = null

    @JvmField
    var sourceIntegrityConfidence: Double = 0.0

    @JvmField
    var sourceIntegrityReason: String? = null

    @JvmField
    var sourceEngineCurrentReadRequest: Boolean = false

    @JvmField
    var start = 0L

    @JvmField
    var end = 0L

    fun getChapterIdInBiquge(): Int {
        return chapterIdInBiquge
    }

    fun setChapterIdInBiquge(chapterIdInBiquge: Int) {
        this.chapterIdInBiquge = chapterIdInBiquge
    }

    fun getBookId(): String? {
        return bookId
    }

    fun setBookId(id: String?) {
        bookId = id
    }

    fun getLink(): String? {
        return link
    }

    fun setLink(link: String?) {
        this.link = link
    }

    fun getTitle(): String? {
        return title
    }

    fun setTitle(title: String?) {
        this.title = title
    }

    fun getStart(): Long {
        return start
    }

    fun setStart(start: Long) {
        this.start = start
    }

    fun getEnd(): Long {
        return end
    }

    fun setEnd(end: Long) {
        this.end = end
    }

    override fun toString(): String {
        return "TxtChapter{" +
            "title='" + title + '\'' +
            ", start=" + start +
            ", end=" + end +
            '}'
    }
}
