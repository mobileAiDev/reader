package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.widget.page.TxtChapter

interface ReaderContentProvider {
    val providerName: String

    suspend fun searchHotWords(): List<String>

    suspend fun searchKeyWords(query: String?): List<String>

    suspend fun searchBooks(query: String?): List<BookSearchResult>

    suspend fun getBookInfo(bookId: String?): BookDetailBeanInOwn

    suspend fun getBookFolder(bookId: String?, collBookBean: CollBookBean): List<BookChapterBean>

    suspend fun getBookContent(
        bookId: String?,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        sourceIndex: Int
    ): String
}
