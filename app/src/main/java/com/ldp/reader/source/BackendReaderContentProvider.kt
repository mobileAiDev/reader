package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.remote.RemoteRepository
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.widget.page.TxtChapter

class BackendReaderContentProvider : ReaderContentProvider {
    override val providerName: String = "backend"

    override suspend fun searchHotWords(): List<String> {
        return RemoteRepository.getInstance().getHotWords()
    }

    override suspend fun searchKeyWords(query: String?): List<String> {
        return RemoteRepository.getInstance().getKeyWords(query)
    }

    override suspend fun searchBooks(query: String?): List<BookSearchResult> {
        return RemoteRepository.getInstance().getSearchResult(query)
    }

    override suspend fun getBookInfo(bookId: String?): BookDetailBeanInOwn {
        return RemoteRepository.getInstance().getBookInfo(bookId)
    }

    override suspend fun getBookFolder(bookId: String?, collBookBean: CollBookBean): List<BookChapterBean> {
        return RemoteRepository.getInstance().getBookFolder(bookId).mapIndexed { index, chapter ->
            BookChapterBean().apply {
                link = chapter.chapterId.toString()
                title = chapter.title
                id = MD5Utils.strToMd5By16(link!!)
                this.bookId = collBookBean.get_id()
                start = index.toLong()
            }
        }
    }

    override suspend fun getBookContent(
        bookId: String?,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        sourceIndex: Int
    ): String {
        return requireNotNull(RemoteRepository.getInstance()
            .getBookContent(sourceBook.get_id(), bookChapter.link, sourceIndex)
            .content) {
            "Backend content is null."
        }
    }
}
