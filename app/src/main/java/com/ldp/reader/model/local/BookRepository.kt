package com.ldp.reader.model.local

import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.objectbox.ObjectBoxBookRecordStore
import com.ldp.reader.model.objectbox.ObjectBoxBookStore
import com.ldp.reader.model.objectbox.ObjectBoxDbHelper
import com.ldp.reader.model.objectbox.ChapterCatalogWriteRequest
import com.ldp.reader.model.objectbox.ChapterCatalogWriteToken
import com.ldp.reader.model.objectbox.StaleChapterCatalogWriteException
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.source.SourceEngineCachedCatalogMigration
import com.ldp.reader.source.SourceEngineCatalogPersistencePolicy
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
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
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ChapterCatalogWriteResult {
    APPLIED,
    STALE,
    FAILED
}

/**
 * Created by ldp on 17-5-8.
 * 存储关于书籍内容的信息(CollBook(收藏书籍),BookChapter(书籍列表),ChapterInfo(书籍章节),BookRecord(记录))
 */
class BookRepository internal constructor(
    boxStore: BoxStore,
    private val cachedBookDeleter: (String?) -> Unit,
    private val readingProgressClearer: (String?) -> Unit
) {
    private val mBookStore = ObjectBoxBookStore(boxStore)
    private val mBookRecordStore = ObjectBoxBookRecordStore(boxStore)

    private constructor() : this(
        ObjectBoxDbHelper.getInstance().store,
        cachedBookDeleter = { bookId ->
            FileUtils.deleteFile(BookManager.cacheFolderPath(bookId))
        },
        readingProgressClearer = BookshelfLocalProgressStore::clear
    )

    // 存储已收藏书籍
    fun saveCollBookWithAsync(
        bean: CollBookBean,
        shouldApply: () -> Boolean = { true },
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val applied = AtomicBoolean(false)
        // 启动异步存储
        mBookStore.runInTxAsync(
            Runnable {
                if (!shouldApply()) {
                    return@Runnable
                }
                val prepared = prepareCollBookForSave(bean)
                if (prepared.getBookChapters() != null) {
                    val catalogApplied = replaceBookChaptersInTx(
                        prepared.get_id(),
                        prepared.getBookChapters()
                    )
                    if (!catalogApplied && isSourceEngineBook(prepared)) {
                        restoreStoredCatalogMetadata(prepared)
                    }
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
                applied.set(true)
                Log.d(
                    TAG,
                    "saveCollBookWithAsync: " + "存储完成" + prepared.author + prepared.title + prepared.shortIntro
                )
                val collBooksTest = mBookStore.getCollBooks()
                for (collBookBean in collBooksTest) {
                    Log.d(TAG, "+存储后: " + "进行存储" + collBookBean.title)
                }
            },
            onComplete = { error ->
                onComplete?.invoke(error == null && applied.get())
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
                        val catalogApplied = replaceBookChaptersInTx(
                            bean.get_id(),
                            bean.getBookChapters()
                        )
                        if (!catalogApplied && isSourceEngineBook(bean)) {
                            restoreStoredCatalogMetadata(bean)
                        }
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
    fun saveBookChaptersWithAsync(
        beans: List<BookChapterBean>?,
        onSuccess: (() -> Unit)? = null
    ) {
        val applied = AtomicBoolean(false)
        mBookStore.runInTxAsync(
            Runnable {
                if (beans == null || beans.isEmpty()) {
                    return@Runnable
                }
                applied.set(replaceBookChaptersInTx(beans[0].bookId, beans))
                for (bookChapterBean in beans) {
                    Log.d("+存储", "saveBookChaptersWithAsync: " + bookChapterBean.title)
                }
                Log.d(TAG, "saveBookChaptersWithAsync: " + "进行存储")
            },
            onComplete = { error ->
                if (error == null && applied.get()) {
                    onSuccess?.invoke()
                }
            }
        )
    }

    internal fun beginChapterCatalogWriteRequest(bookId: String): ChapterCatalogWriteRequest {
        return mBookStore.beginChapterCatalogWriteRequest(bookId)
    }

    internal fun bindChapterCatalogWrite(
        request: ChapterCatalogWriteRequest,
        fingerprint: String
    ): ChapterCatalogWriteToken? {
        return mBookStore.bindChapterCatalogWrite(request, fingerprint)
    }

    internal fun saveBookChaptersWithAsync(
        beans: List<BookChapterBean>,
        token: ChapterCatalogWriteToken,
        onComplete: (ChapterCatalogWriteResult) -> Unit
    ) {
        val applied = AtomicBoolean(false)
        mBookStore.runInTxAsync(
            Runnable {
                applied.set(mBookStore.replaceBookChaptersIfLatest(token, beans))
            },
            onComplete = { error ->
                val result = when {
                    error is StaleChapterCatalogWriteException -> ChapterCatalogWriteResult.STALE
                    error != null -> ChapterCatalogWriteResult.FAILED
                    !applied.get() -> ChapterCatalogWriteResult.STALE
                    else -> try {
                        if (mBookStore.finalizeChapterCatalogWriteAfterCommit(token)) {
                            ChapterCatalogWriteResult.APPLIED
                        } else {
                            ChapterCatalogWriteResult.STALE
                        }
                    } catch (cleanupError: Throwable) {
                        Log.e(TAG, "Could not clean up stale chapter catalog write", cleanupError)
                        ChapterCatalogWriteResult.FAILED
                    }
                }
                onComplete(result)
            }
        )
    }

    internal fun runIfChapterCatalogWriteLatest(
        token: ChapterCatalogWriteToken,
        action: () -> Unit
    ): Boolean {
        return mBookStore.runIfChapterCatalogWriteLatest(token, action)
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

    fun persistSourceEngineCatalogMigration(
        chapters: List<BookChapterBean>,
        record: BookRecordBean?
    ) {
        if (chapters.isEmpty()) return
        val bookId = chapters.firstOrNull()?.bookId ?: record?.bookId ?: return
        mBookStore.runInTx {
            replaceBookChaptersInTx(bookId, chapters)
            if (record != null) {
                if (record.bookId.isNullOrBlank()) {
                    record.bookId = bookId
                }
                mBookRecordStore.saveBookRecord(record)
            }
        }
    }

    /*****************************get************************************************/
    fun getCollBook(bookId: String?): CollBookBean? {
        return mBookStore.getCollBook(bookId)
    }

    val collBooks: List<CollBookBean>
        get() = mergeDuplicateSourceEngineBooks(mBookStore.getCollBooks())

    fun getExistingCollBookIds(bookIds: Collection<String?>): Set<String> {
        return mBookStore.getExistingCollBookIds(bookIds)
    }

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

    fun getBookRecords(bookIds: Collection<String?>): Map<String, BookRecordBean> {
        return mBookRecordStore.getBookRecords(bookIds)
    }

    /************************************************************/
    fun deleteCollBookWithFiles(bean: CollBookBean) {
        val bookId = bean.get_id()
        // 查看文本中是否存在删除的数据
        deleteBook(bookId)
        // 删除目录
        deleteBookChapter(bookId)
        // 放弃追更后再加入应当从头开始，不应恢复旧章节与书架进度。
        mBookRecordStore.deleteBookRecord(bookId)
        readingProgressClearer(bookId)
        // 删除CollBook
        mBookStore.deleteCollBook(bean)
    }

    fun deleteBookChapter(bookId: String?) {
        mBookStore.deleteBookChapters(bookId)
    }

    private fun replaceBookChaptersInTx(bookId: String?, beans: List<BookChapterBean>?): Boolean {
        if (bookId == null || beans == null) {
            return false
        }
        val sourceEngineCatalog = SourceEngineBookRoute.isBookId(bookId) ||
            SourceEngineBookRoute.isShelfBookId(bookId)
        val preparedChapters = if (sourceEngineCatalog) {
            SourceEngineCachedCatalogMigration.sanitize(beans).chapters.also { chapters ->
                chapters.forEachIndexed { index, chapter -> chapter.start = index.toLong() }
            }
        } else {
            beans
        }
        if (sourceEngineCatalog) {
            val persistedChapters = SourceEngineCachedCatalogMigration
                .sanitize(mBookStore.getBookChapters(bookId))
                .chapters
            if (!SourceEngineCatalogPersistencePolicy.canReplacePersistedCatalog(
                    persistedChapters,
                    preparedChapters
                )
            ) {
                Log.w(TAG, "Rejected an incompatible source catalog replacement for $bookId")
                return false
            }
        }
        mBookStore.replaceBookChapters(bookId, preparedChapters)
        if (sourceEngineCatalog) {
            mBookStore.getCollBook(bookId)?.let { storedBook ->
                storedBook.chaptersCount = preparedChapters.size
                storedBook.lastChapter = preparedChapters.lastOrNull()?.title ?: storedBook.lastChapter
                mBookStore.saveCollBook(storedBook)
            }
        }
        return true
    }

    private fun savePreparedCollBook(bean: CollBookBean) {
        mBookStore.saveCollBook(bean)
    }

    private fun prepareCollBookForSave(bean: CollBookBean): CollBookBean {
        normalizeSourceEngineShelfIdentity(bean)
        sanitizeSourceEngineCatalogForSave(bean)
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
        rejectUnbackedSourceCatalogMetadata(bean)
        return bean
    }

    private fun rejectUnbackedSourceCatalogMetadata(book: CollBookBean) {
        if (!isSourceEngineBook(book)) return
        val candidate = book.getBookChapters() ?: return
        val persisted = SourceEngineCachedCatalogMigration
            .sanitize(mBookStore.getBookChapters(book.get_id()))
            .chapters
        if (!SourceEngineCatalogPersistencePolicy.canReplacePersistedCatalog(persisted, candidate)) {
            restoreStoredCatalogMetadata(book)
        }
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
        sanitizeSourceEngineCatalogForSave(stored)
        sanitizeSourceEngineCatalogForSave(incoming)
        val keepStoredSourceEngineCatalog = shouldKeepStoredSourceEngineCatalog(stored, incoming)
        if ((keepStoredSourceEngineCatalog ||
                !SourceEngineBookRoute.isBookId(incoming.bookIdInBiquge)) &&
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
        if (keepStoredSourceEngineCatalog) {
            incoming.chaptersCount = stored.chaptersCount
            incoming.lastChapter = stored.lastChapter
            incoming.setBookChapters(stored.getBookChapters())
        }
        if (incoming.chaptersCount <= 0) {
            incoming.chaptersCount = stored.chaptersCount
        }
        if (incoming.getBookChapters().isNullOrEmpty() && !stored.getBookChapters().isNullOrEmpty()) {
            incoming.setBookChapters(stored.getBookChapters())
        }
    }

    private fun sanitizeSourceEngineCatalogForSave(book: CollBookBean): Boolean {
        if (!isSourceEngineBook(book)) return false
        val chapters = book.getBookChapters()
        if (chapters.isNullOrEmpty()) return false
        val migration = SourceEngineCachedCatalogMigration.sanitize(chapters)
        if (!migration.changed) return false
        book.setBookChapters(migration.chapters)
        book.chaptersCount = migration.chapters.size
        book.lastChapter = migration.chapters.lastOrNull()?.title ?: book.lastChapter
        return true
    }

    private fun shouldKeepStoredSourceEngineCatalog(
        stored: CollBookBean,
        incoming: CollBookBean
    ): Boolean {
        if (!isSourceEngineBook(stored) || !isSourceEngineBook(incoming)) return false
        val incomingChapters = incoming.getBookChapters() ?: return false
        return !SourceEngineCatalogPersistencePolicy.canReplacePersistedCatalog(
            stored.getBookChapters().orEmpty(),
            incomingChapters
        )
    }

    private fun restoreStoredCatalogMetadata(book: CollBookBean) {
        val stored = mBookStore.getCollBook(book.get_id())
        book.setBookChapters(stored?.getBookChapters().orEmpty())
        book.chaptersCount = stored?.chaptersCount ?: 0
        book.lastChapter = stored?.lastChapter
        if (SourceEngineBookRoute.isBookId(stored?.bookIdInBiquge)) {
            book.bookIdInBiquge = stored?.bookIdInBiquge
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
        if (sourceEngineIdentityKey(book).isBlank()) {
            return null
        }
        return candidates.firstOrNull { candidate ->
            !candidate.isLocal() &&
                isSourceEngineBook(candidate) &&
                candidate.get_id() != book.get_id() &&
                sameSourceEngineShelfBook(candidate, book)
        }
    }

    private fun mergeDuplicateSourceEngineBooks(books: List<CollBookBean>): List<CollBookBean> {
        val result = ArrayList<CollBookBean>()
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
            val existing = result.firstOrNull { candidate ->
                !candidate.isLocal() &&
                    isSourceEngineBook(candidate) &&
                    sameSourceEngineShelfBook(candidate, book)
            }
            if (existing == null) {
                result.add(book)
            } else {
                val merged = mergeVisibleSourceEngineBook(existing, book)
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
        preferred.author = BookIdentity.preferredDisplayAuthor(preferred.author, fallback.author)
        if (SourceEngineBookRoute.isShelfBookId(preferred.get_id()) || SourceEngineBookRoute.isShelfBookId(fallback.get_id())) {
            preferred.set_id(BookIdentity.sourceEngineShelfId(preferred.title, preferred.author))
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

    private fun sameSourceEngineShelfBook(first: CollBookBean, second: CollBookBean): Boolean {
        val firstTitle = BookIdentity.canonicalTitleKey(first.title, first.author)
        val secondTitle = BookIdentity.canonicalTitleKey(second.title, second.author)
        if (firstTitle.isBlank() || firstTitle != secondTitle) return false
        return BookIdentity.authorsCompatible(first.author, second.author)
    }

    fun deleteCollBook(collBook: CollBookBean) {
        mBookStore.deleteCollBook(collBook)
    }

    // 删除书籍
    fun deleteBook(bookId: String?) {
        cachedBookDeleter(bookId)
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
