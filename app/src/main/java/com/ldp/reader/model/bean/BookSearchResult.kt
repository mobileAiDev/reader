package com.ldp.reader.model.bean

import java.util.Objects

class BookSearchResult {
    var cover: String? = null
    var title: String? = null
    var author: String? = null
    var desc: String? = null
    var sources: List<SourcesBean>? = null
    var id: String? = null
        get() {
            field = Objects.hash(title, author).toString()
            return field
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as BookSearchResult
        return title!! == that.title && author!! == that.author
    }

    override fun hashCode(): Int {
        return Objects.hash(title, author)
    }

    class SourcesBean {
        var link: String? = null
        var source: SourceBean? = null

        class SourceBean {
            var id: Int = 0
            var name: String? = null
            var searchURL: String? = null
            var minKeywords: Int = 0
        }
    }
}
