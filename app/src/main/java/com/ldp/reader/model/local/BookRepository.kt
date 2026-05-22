package com.ldp.reader.model.local

import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.objectbox.ObjectBoxBookRecordStore
import com.ldp.reader.model.objectbox.ObjectBoxBookStore
import com.ldp.reader.model.objectbox.ObjectBoxDbHelper
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.utils.BookCoverUrl
import com.ldp.reader.utils.BookIdentity
import com.ldp.reader.utils.BookManager
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.FileUtils
import com.ldp.reader.utils.IOUtils
import com.ldp.reader.utils.StringUtils
import io.objectbox.BoxStore
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.IOException
import java.io.Writer

/**
 * Created by ldp on 17-5-8.
 * 存储关于书籍内容的信息(CollBook(收藏书籍),BookChapter(书籍列表),ChapterInfo(书籍章节),BookRecord(记录))
 */
class BookRepository private constructor() {
    private val mBookStore: ObjectBoxBookStore
    private val mBookRecordStore: ObjectBoxBookRecordStore

    init {
        val boxStore: BoxStore = ObjectBoxDbHelper.getInstance().store
        mBookStore = ObjectBoxBookStore(boxStore)
        mBookRecordStore = ObjectBoxBookRecordStore(boxStore)
    }

    // 存储已收藏书籍
    fun saveCollBookWithAsync(bean: CollBookBean) {
        // 启动异步存储
        mBookStore.runInTxAsync(
            Runnable {
                val prepared = prepareCollBookForSave(bean)
                if (prepared.getBookChapters() != null) {
                    replaceBookChaptersInTx(prepared.get_id(), prepared.getBookChapters())
                    Log.d(TAG, "saveCollBookWithAsync: " + "进行存储" + prepared.getBookChapters())
                }
                Log.d(
                    TAG,
                    "saveCollBookWithAsync: " + "进行存储" + prepared.author + prepared.title + prepared.shortIntro
                )
                // 存储CollBook (确保先后顺序，否则出错)
                // 表示当前CollBook已经阅读
                prepared.setIsUpdate(false)
                prepared.lastRead = StringUtils.dateConvert(
                    System.currentTimeMillis(),
                    Constant.FORMAT_BOOK_DATE
                )
                savePreparedCollBook(prepared)
                Log.d(
                    TAG,
                    "saveCollBookWithAsync: " + "存储完成" + prepared.author + prepared.title + prepared.shortIntro
                )
                val collBooksTest = mBookStore.getCollBooks()
                for (collBookBean in collBooksTest) {
                    Log.d(TAG, "+存储后: " + "进行存储" + collBookBean.title)
                }
            }
        )
    }

    /**
     * 异步存储。
     * 同时保存BookChapter
     */
    fun saveCollBooksWithAsync(beans: List<CollBookBean>) {
        mBookStore.runInTxAsync(
            Runnable {
                Log.d(TAG, "111saveCollBookWithAsync : " + "进行存储" + beans.toString())
                val preparedBooks = beans.map { prepareCollBookForSave(it) }
                for (bean in preparedBooks) {
                    if (bean.getBookChapters() != null) {
                        replaceBookChaptersInTx(bean.get_id(), bean.getBookChapters())
                    }
                }
                mBookStore.saveCollBooks(preparedBooks)
            }
        )
    }

    fun saveCollBook(bean: CollBookBean) {
        Log.d(TAG, "22saveCollBookWithAsync : " + "进行存储" + bean.toString())
        savePreparedCollBook(prepareCollBookForSave(bean))
    }

    fun saveCollBooks(beans: List<CollBookBean>) {
        Log.d(TAG, "33saveCollBookWithAsync : " + "进行存储" + beans.toString())
        mBookStore.saveCollBooks(beans.map { prepareCollBookForSave(it) })
    }

    /**
     * 异步存储BookChapter
     */
    fun saveBookChaptersWithAsync(beans: List<BookChapterBean>?) {
        mBookStore.runInTxAsync(
            Runnable {
                if (beans == null || beans.isEmpty()) {
                    return@Runnable
                }
                replaceBookChaptersInTx(beans[0].bookId, beans)
                for (bookChapterBean in beans) {
                    Log.d("+存储", "saveBookChaptersWithAsync: " + bookChapterBean.title)
                }
                Log.d(TAG, "saveBookChaptersWithAsync: " + "进行存储")
            }
        )
    }

    /**
     * 存储章节
     */
    fun saveChapterInfo(folderName: String?, fileName: String?, content: String?) {
        if (content == null) {
            return
        }
        val file = BookManager.getBookFile(folderName, fileName)
        // 获取流并存储
        var writer: Writer? = null
        try {
            writer = BufferedWriter(FileWriter(file))
            writer.write(content)
            writer.flush()
        } catch (e: IOException) {
            e.printStackTrace()
            IOUtils.close(writer)
        }
    }

    fun saveBookRecord(bean: BookRecordBean) {
        mBookRecordStore.saveBookRecord(bean)
    }

    /*****************************get************************************************/
    fun getCollBook(bookId: String?): CollBookBean? {
        return mBookStore.getCollBook(bookId)
    }

    val collBooks: List<CollBookBean>
        get() = mergeDuplicateSourceEngineBooks(mBookStore.getCollBooks())

    fun findSameOnlineBook(book: CollBookBean?): CollBookBean? {
        return findSameOnlineBook(book, mBookStore.getCollBooks())
    }

    // 获取书籍列表
    fun getBookChapters(bookId: String?): List<BookChapterBean> {
        return mBookStore.getBookChapters(bookId)
    }

    // 获取阅读记录
    fun getBookRecord(bookId: String?): BookRecordBean? {
        return mBookRecordStore.getBookRecord(bookId)
    }

    /************************************************************/
    fun deleteCollBookWithFiles(bean: CollBookBean) {
        // 查看文本中是否存在删除的数据
        deleteBook(bean.get_id())
        // 删除目录
        deleteBookChapter(bean.get_id())
        // 删除CollBook
        mBookStore.deleteCollBook(bean)
    }

    fun deleteBookChapter(bookId: String?) {
        mBookStore.deleteBookChapters(bookId)
    }

    private fun replaceBookChaptersInTx(bookId: String?, beans: List<BookChapterBean>?) {
        if (bookId == null || beans == null) {
            return
        }
        mBookStore.replaceBookChapters(bookId, beans)
    }

    private fun savePreparedCollBook(bean: CollBookBean) {
        mBookStore.saveCollBook(bean)
    }

    private fun prepareCollBookForSave(bean: CollBookBean): CollBookBean {
        normalizeSourceEngineShelfIdentity(bean)
        val duplicate = findSameOnlineBook(bean, mBookStore.getCollBooks())
        if (duplicate != null && duplicate.get_id() != bean.get_id()) {
            val hydratedDuplicate = mBookStore.getCollBook(duplicate.get_id()) ?: duplicate
            mergeStoredBookIntoIncoming(hydratedDuplicate, bean)
            moveBookRecordIfNeeded(duplicate.get_id(), bean.get_id())
            mBookStore.deleteBookChapters(duplicate.get_id())
            mBookStore.deleteCollBook(duplicate)
        }
        val existing = mBookStore.getCollBook(bean.get_id())
        if (existing != null) {
            mergeStoredBookIntoIncoming(existing, bean)
        }
        return bean
    }

    private fun normalizeSourceEngineShelfIdentity(bean: CollBookBean) {
        val currentId = bean.get_id()
        val routeId = when {
            SourceEngineBookRoute.isBookId(bean.bookIdInBiquge) -> bean.bookIdInBiquge
            SourceEngineBookRoute.isBookId(currentId) -> currentId
            else -> null
        }
        if (routeId != null || SourceEngineBookRoute.isShelfBookId(currentId)) {
            bean.set_id(BookIdentity.sourceEngineShelfId(bean.title, bean.author))
            if (routeId != null) {
                bean.bookIdInBiquge = routeId
            }
        }
    }

    private fun mergeStoredBookIntoIncoming(stored: CollBookBean, incoming: CollBookBean) {
        if (!SourceEngineBookRoute.isBookId(incoming.bookIdInBiquge) &&
            SourceEngineBookRoute.isBookId(stored.bookIdInBiquge)
        ) {
            incoming.bookIdInBiquge = stored.bookIdInBiquge
        }
        if (!BookCoverUrl.isLikelyImage(incoming.cover) && BookCoverUrl.isLikelyImage(stored.cover)) {
            incoming.cover = stored.cover
        }
        if (incoming.shortIntro.isNullOrBlank()) {
            incoming.shortIntro = stored.shortIntro
        }
        if (incoming.author.isNullOrBlank()) {
            incoming.author = stored.author
        }
        if (incoming.lastChapter.isNullOrBlank()) {
            incoming.lastChapter = stored.lastChapter
        }
        if (incoming.updated.isNullOrBlank()) {
            incoming.updated = stored.updated
        }
        if (incoming.lastRead.isNullOrBlank()) {
            incoming.lastRead = stored.lastRead
        }
        if (incoming.chaptersCount <= 0) {
            incoming.chaptersCount = stored.chaptersCount
        }
        if (incoming.getBookChapters().isNullOrEmpty() && !stored.getBookChapters().isNullOrEmpty()) {
            incoming.setBookChapters(stored.getBookChapters())
        }
    }

    private fun moveBookRecordIfNeeded(fromId: String?, toId: String?) {
        if (fromId.isNullOrBlank() || toId.isNullOrBlank() || fromId == toId) {
            return
        }
        if (mBookRecordStore.getBookRecord(toId) != null) {
            return
        }
        val oldRecord = mBookRecordStore.getBookRecord(fromId) ?: return
        oldRecord.bookId = toId
        mBookRecordStore.saveBookRecord(oldRecord)
        mBookRecordStore.deleteBookRecord(fromId)
    }

    private fun findSameOnlineBook(
        book: CollBookBean?,
        candidates: List<CollBookBean>
    ): CollBookBean? {
        if (book == null || book.isLocal() || !isSourceEngineBook(book)) {
            return null
        }
        val targetKey = sourceEngineIdentityKey(book)
        if (targetKey.isBlank()) {
            return null
        }
        return candidates.firstOrNull { candidate ->
            !candidate.isLocal() &&
                isSourceEngineBook(candidate) &&
                candidate.get_id() != book.get_id() &&
                sourceEngineIdentityKey(candidate) == targetKey
        }
    }

    private fun mergeDuplicateSourceEngineBooks(books: List<CollBookBean>): List<CollBookBean> {
        val result = ArrayList<CollBookBean>()
        val sourceEngineBooksByIdentity = LinkedHashMap<String, CollBookBean>()
        for (book in books) {
            if (book.isLocal() || !isSourceEngineBook(book)) {
                result.add(book)
                continue
            }
            val key = sourceEngineIdentityKey(book)
            if (key.isBlank()) {
                result.add(book)
                continue
            }
            val existing = sourceEngineBooksByIdentity[key]
            if (existing == null) {
                sourceEngineBooksByIdentity[key] = book
                result.add(book)
            } else {
                val merged = mergeVisibleSourceEngineBook(existing, book)
                sourceEngineBooksByIdentity[key] = merged
                val index = result.indexOf(existing)
                if (index >= 0) {
                    result[index] = merged
                }
            }
        }
        return result
    }

    private fun mergeVisibleSourceEngineBook(
        first: CollBookBean,
        second: CollBookBean
    ): CollBookBean {
        val preferred = chooseVisibleSourceEngineBook(first, second)
        val fallback = if (preferred === first) second else first
        if (!BookCoverUrl.isLikelyImage(preferred.cover) && BookCoverUrl.isLikelyImage(fallback.cover)) {
            preferred.cover = fallback.cover
        }
        if (!SourceEngineBookRoute.isBookId(preferred.bookIdInBiquge) &&
            SourceEngineBookRoute.isBookId(fallback.bookIdInBiquge)
        ) {
            preferred.bookIdInBiquge = fallback.bookIdInBiquge
        }
        if (preferred.chaptersCount <= 0) {
            preferred.chaptersCount = fallback.chaptersCount
        }
        if (preferred.lastChapter.isNullOrBlank()) {
            preferred.lastChapter = fallback.lastChapter
        }
        return preferred
    }

    private fun chooseVisibleSourceEngineBook(
        first: CollBookBean,
        second: CollBookBean
    ): CollBookBean {
        return listOf(first, second).maxWith(
            compareBy<CollBookBean> { if (SourceEngineBookRoute.isShelfBookId(it.get_id())) 1 else 0 }
                .thenBy { if (BookCoverUrl.isLikelyImage(it.cover)) 1 else 0 }
                .thenBy { it.chaptersCount }
                .thenBy { it.lastRead.orEmpty() }
        )
    }

    private fun isSourceEngineBook(book: CollBookBean): Boolean {
        return SourceEngineBookRoute.isBookId(book.get_id()) ||
            SourceEngineBookRoute.isShelfBookId(book.get_id()) ||
            SourceEngineBookRoute.isBookId(book.bookIdInBiquge)
    }

    private fun sourceEngineIdentityKey(book: CollBookBean): String {
        return BookIdentity.sourceEngineIdentityKey(book.title, book.author)
    }

    fun deleteCollBook(collBook: CollBookBean) {
        mBookStore.deleteCollBook(collBook)
    }

    // 删除书籍
    fun deleteBook(bookId: String?) {
        FileUtils.deleteFile(BookManager.cacheFolderPath(bookId))
    }

    fun deleteBookRecord(id: String?) {
        mBookRecordStore.deleteBookRecord(id)
    }

    companion object {
        private const val TAG = "CollBookManager"

        @Volatile
        private var sInstance: BookRepository? = null

        @JvmStatic
        fun getInstance(): BookRepository {
            if (sInstance == null) {
                synchronized(BookRepository::class.java) {
                    if (sInstance == null) {
                        sInstance = BookRepository()
                    }
                }
            }
            return sInstance!!
        }
    }
}
