package com.ldp.reader.source

import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.widget.page.TxtChapter

object BookContentProviderRouter {
    private const val TAG = "BookContentProvider"

    private val backendProvider = BackendReaderContentProvider()
    private val sourceEngineProvider = SourceEngineReaderContentProvider()

    suspend fun searchHotWords(): List<String> {
        val provider = if (SourceEngineSwitch.isEnabled()) sourceEngineProvider else backendProvider
        logRoute("hotWords", provider, null)
        return provider.searchHotWords()
    }

    suspend fun searchKeyWords(query: String?): List<String> {
        val provider = if (SourceEngineSwitch.isEnabled()) sourceEngineProvider else backendProvider
        logRoute("keyWords", provider, query)
        return provider.searchKeyWords(query)
    }

    suspend fun searchBooks(query: String?): List<BookSearchResult> {
        val provider = if (SourceEngineSwitch.isEnabled()) sourceEngineProvider else backendProvider
        logRoute("search", provider, query)
        return provider.searchBooks(query)
    }

    suspend fun getBookInfo(bookId: String?): BookDetailBeanInOwn {
        val routeBookId = routeBookIdFor(bookId)
        val provider = providerForBook(routeBookId)
        logRoute("detail", provider, routeBookId)
        return provider.getBookInfo(routeBookId)
    }

    suspend fun getBookFolder(bookId: String?, collBookBean: CollBookBean): List<BookChapterBean> {
        val routeBookId = routeBookIdFor(bookId, collBookBean)
        val provider = providerForBook(routeBookId)
        logRoute("catalog", provider, routeBookId)
        return provider.getBookFolder(routeBookId, collBookBean)
    }

    suspend fun getBookContent(
        bookId: String?,
        sourceBook: CollBookBean,
        bookChapter: TxtChapter,
        sourceIndex: Int
    ): String {
        val provider = if (SourceEngineBookRoute.isChapterId(bookChapter.link)) {
            sourceEngineProvider
        } else {
            backendProvider
        }
        logRoute("content", provider, bookChapter.link)
        return provider.getBookContent(bookId, sourceBook, bookChapter, sourceIndex)
    }

    private fun providerForBook(bookId: String?): ReaderContentProvider {
        return if (SourceEngineBookRoute.isBookId(bookId)) sourceEngineProvider else backendProvider
    }

    private fun routeBookIdFor(bookId: String?, collBookBean: CollBookBean): String? {
        return when {
            SourceEngineBookRoute.isBookId(bookId) -> bookId
            SourceEngineBookRoute.isBookId(collBookBean.bookIdInBiquge) -> collBookBean.bookIdInBiquge
            else -> bookId ?: collBookBean.get_id()
        }
    }

    private fun routeBookIdFor(bookId: String?): String? {
        return when {
            SourceEngineBookRoute.isBookId(bookId) -> bookId
            SourceEngineBookRoute.isShelfBookId(bookId) -> {
                BookRepository.getInstance().getCollBook(bookId)?.bookIdInBiquge ?: bookId
            }
            else -> bookId
        }
    }

    private fun logRoute(operation: String, provider: ReaderContentProvider, key: String?) {
        Log.i(TAG, "operation=$operation provider=${provider.providerName} sourceEngineEnabled=${SourceEngineSwitch.isEnabled()} key=$key")
    }
}
