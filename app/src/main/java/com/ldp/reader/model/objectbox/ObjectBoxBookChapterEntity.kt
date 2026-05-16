package com.ldp.reader.model.objectbox

import com.ldp.reader.model.bean.BookChapterBean
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
class ObjectBoxBookChapterEntity {
    @Id
    var objectBoxId: Long = 0

    @Index
    var chapterBusinessId: String? = null

    @Index
    var bookId: String? = null

    var link: String? = null
    var title: String? = null
    var taskName: String? = null
    var unreadble: Boolean = false
    var validInZhuishu: Boolean = false
    var start: Long = 0
    var end: Long = 0

    fun toBookChapter(): BookChapterBean {
        return BookChapterBean(
            chapterBusinessId,
            link,
            title,
            taskName,
            unreadble,
            validInZhuishu,
            bookId,
            start,
            end
        )
    }

    companion object {
        @JvmStatic
        fun from(chapter: BookChapterBean): ObjectBoxBookChapterEntity {
            val entity = ObjectBoxBookChapterEntity()
            entity.chapterBusinessId = chapter.id
            entity.bookId = chapter.bookId
            entity.link = chapter.link
            entity.title = chapter.title
            entity.taskName = chapter.taskName
            entity.unreadble = chapter.getUnreadble()
            entity.validInZhuishu = chapter.getValidInZhuishu()
            entity.start = chapter.start
            entity.end = chapter.end
            return entity
        }
    }
}
