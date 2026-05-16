package com.ldp.reader.model.objectbox

import com.ldp.reader.model.bean.CollBookBean
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
class ObjectBoxCollBookEntity {
    @Id
    var objectBoxId: Long = 0

    @Index
    var bookId: String? = null

    var title: String? = null
    var author: String? = null
    var shortIntro: String? = null
    var cover: String? = null
    var bookStatus: String? = null
    var updated: String? = null
    var lastRead: String? = null
    var chaptersCount: Int = 0
    var lastChapter: String? = null
    var isUpdate: Boolean = false
    var isLocal: Boolean = false
    var bookIdInBiquge: String? = null

    fun toCollBook(): CollBookBean {
        val book = CollBookBean()
        book.set_id(bookId)
        book.title = title
        book.author = author
        book.shortIntro = shortIntro
        book.cover = cover
        book.bookStatus = bookStatus
        book.updated = updated
        book.lastRead = lastRead
        book.chaptersCount = chaptersCount
        book.lastChapter = lastChapter
        book.setIsUpdate(isUpdate)
        book.setIsLocal(isLocal)
        book.bookIdInBiquge = bookIdInBiquge
        return book
    }

    companion object {
        @JvmStatic
        fun from(book: CollBookBean): ObjectBoxCollBookEntity {
            val entity = ObjectBoxCollBookEntity()
            entity.bookId = book.get_id()
            entity.title = book.title
            entity.author = book.author
            entity.shortIntro = book.shortIntro
            entity.cover = book.cover
            entity.bookStatus = book.bookStatus
            entity.updated = book.updated
            entity.lastRead = book.lastRead
            entity.chaptersCount = book.chaptersCount
            entity.lastChapter = book.lastChapter
            entity.isUpdate = book.getIsUpdate()
            entity.isLocal = book.getIsLocal()
            entity.bookIdInBiquge = book.bookIdInBiquge
            return entity
        }
    }
}
