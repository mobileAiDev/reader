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

    fun startLowPriorityV8Maintenance() {
        if (!SourceEngineSwitch.isEnabled()) return
        if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) return
        sourceEngineProvider.startLowPriorityV8Maintenance {
            BookRepository.getInstance().collBooks
        }
    }

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

    suspend fun searchBooksProgressively(
        query: String?,
        onUpdate: suspend (List<BookSearchResult>) -> Unit
    ): List<BookSearchResult> {
        val provider = if (SourceEngineSwitch.isEnabled()) sourceEngineProvider else backendProvider
        logRoute("searchProgressive", provider, query)
        return provider.searchBooksProgressively(query, onUpdate)
    }

    suspend fun refreshSearchCovers(
        query: String?,
        books: List<BookSearchResult>
    ): List<BookSearchResult> {
        val provider = if (SourceEngineSwitch.isEnabled()) sourceEngineProvider else backendProvider
        logRoute("searchCoverRefresh", provider, query)
        return provider.refreshSearchCovers(query, books)
    }

    suspend fun getBookInfo(bookId: String?): BookDetailBeanInOwn {
        val routeBookId = routeBookIdFor(bookId)
        val provider = providerForBook(routeBookId)
        logRoute("detail", provider, routeBookId)
        return provider.getBookInfo(routeBookId).also { detail ->
            if (ReaderFeatureSwitches.isCleanIntroEnabled()) {
                detail.desc = SourceEngineMetadataCleaner.cleanIntro(detail.desc)
            }
        }
    }

    suspend fun getBookFolder(
        bookId: String?,
        collBookBean: CollBookBean,
        triggerV8ForReading: Boolean = false
    ): List<BookChapterBean> {
        val routeBookId = routeBookIdFor(bookId, collBookBean)
        val provider = providerForBook(routeBookId)
        logRoute("catalog", provider, routeBookId)
        if (
            triggerV8ForReading &&
            ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled() &&
            provider === sourceEngineProvider
        ) {
            return sourceEngineProvider.getBookFolder(routeBookId, collBookBean, triggerV8ForReading = true)
        }
        return provider.getBookFolder(routeBookId, collBookBean)
    }

    suspend fun getReadingBootstrapChapters(
        bookId: String?,
        collBookBean: CollBookBean,
        limit: Int
    ): List<BookChapterBean> {
        val routeBookId = routeBookIdFor(bookId, collBookBean)
        if (!SourceEngineBookRoute.isBookId(routeBookId)) return emptyList()
        logRoute("catalogBootstrap", sourceEngineProvider, routeBookId)
        return sourceEngineProvider.getReadingBootstrapChapters(routeBookId, collBookBean, limit)
    }

    suspend fun prepareBookContentTier(
        bookId: String?,
        collBookBean: CollBookBean? = null,
        persist: Boolean = false,
        triggerV8: Boolean = false,
        requestPriority: SourceRequestPriority = SourceRequestPriority.FOREGROUND
    ): Boolean {
        val routeBookId = if (collBookBean == null) {
            routeBookIdFor(bookId)
        } else {
            routeBookIdFor(bookId, collBookBean)
        }
        if (!SourceEngineBookRoute.isBookId(routeBookId)) return true
        logRoute("contentTier", sourceEngineProvider, routeBookId)
        return sourceEngineProvider.prepareBookContentTier(
            routeBookId,
            collBookBean,
            persist,
            triggerV8 && ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled(),
            requestPriority
        )
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
        val content = provider.getBookContent(bookId, sourceBook, bookChapter, sourceIndex)
        return if (ReaderFeatureSwitches.isCleanContentEnabled()) {
            SourceEngineMetadataCleaner.cleanContent(content)
        } else {
            content
        }
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
