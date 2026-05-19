package com.ldp.reader.model.bean

import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.StringUtils

class BookDetailBeanInOwn {
    var bookId: Int = 0
    var routeId: String? = null
    var shelfBookId: String? = null
    var cover: String? = null
    var title: String? = null
    var author: String? = null
    var lastChapter: String? = null
    var chaptersCount: Int = 0
    var updateTime: Long = 0
    var desc: String? = null
    var sources: List<SourcesBean>? = null
    private var cachedCollBookBean: CollBookBean? = null

    val collBookBean: CollBookBean
        get() {
            if (cachedCollBookBean == null) {
                cachedCollBookBean = createCollBookBean()
            }
            return cachedCollBookBean!!
        }

    class SourcesBean {
        var bookId: Int = 0
        var link: String? = null
        var source: SourceBean? = null

        class SourceBean {
            var id: Int = 0
            var name: String? = null
            var searchURL: String? = null
            var minKeywords: Int = 0
        }
    }

    fun createCollBookBean(): CollBookBean {
        val bean = CollBookBean()
        bean.set_id(shelfBookId ?: routeId ?: bookId.toString())
        if (!routeId.isNullOrBlank() && routeId != bean.get_id()) {
            bean.bookIdInBiquge = routeId
        }
        bean.title = title
        bean.author = author
        bean.shortIntro = desc
        bean.cover = cover
        bean.bookStatus = "连载中"
        bean.updated = StringUtils.dateConvert(updateTime, Constant.FORMAT_BOOK_DATE)
        bean.chaptersCount = chaptersCount.takeIf { it > 0 } ?: 100
        bean.lastChapter = lastChapter
        return bean
    }
}
