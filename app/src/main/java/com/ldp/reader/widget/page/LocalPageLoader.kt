package com.ldp.reader.widget.page

import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.local.ChapterCatalogWriteResult
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.utils.Charset as ReaderCharset
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.LogUtils
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.utils.StringUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

/**
 * Created by ldp on 17-7-1.
 * 问题:
 * 1. 异常处理没有做好
 */
class LocalPageLoader(pageView: PageView, collBook: CollBookBean) : PageLoader(pageView, collBook) {
    override fun shouldLoadCurrentChapterInBackground(): Boolean = true

    private val snapshotDirectory = LocalTxtSnapshotDirectoryStore.forCacheDirectory(
        pageView.context.cacheDir
    )

    // 获取书本的文件
    private lateinit var mBookFile: File

    private var mBookSnapshot: LocalTxtFileSnapshot? = null

    // 编码类型
    private lateinit var mCharset: ReaderCharset

    private val chapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mChapterJob: Job? = null
    private val chapterRefreshRequests = LocalTxtChapterRefreshRequests()

    init {
        mStatus = STATUS_PARING
    }

    private fun convertTxtChapter(bookChapters: List<BookChapterBean>): MutableList<TxtChapter> {
        return LocalTxtChapterMapper.fromCached(bookChapters)
    }

    /**
     * 未完成的部分:
     * 1. 序章的添加
     * 2. 章节存在的书本的虚拟分章效果
     */
    @Throws(IOException::class)
    private fun loadChapters(
        snapshot: LocalTxtFileSnapshot,
        charset: ReaderCharset,
        cancellationCheck: () -> Unit
    ): LocalTxtChapterSnapshot {
        return LocalTxtChapterSnapshotLoader.load(
            snapshot = snapshot,
            charset = Charset.forName(charset.getName()),
            bufferSize = BUFFER_SIZE,
            virtualChapterBytes = MAX_LENGTH_WITH_NO_CHAPTER,
            cancellationCheck = cancellationCheck
        )
    }

    override fun saveRecord() {
        super.saveRecord()
        // 修改当前COllBook记录
        if (mCollBook != null && isChapterListPrepare) {
            // 表示当前CollBook已经阅读
            mCollBook.setIsUpdate(false)
            mCollBook.lastChapter = mChapterList[mCurChapterPos].getTitle()
            mCollBook.lastRead = StringUtils.dateConvert(
                System.currentTimeMillis(),
                Constant.FORMAT_BOOK_DATE
            )
            val progressTenths = calculateProgressTenths(
                mChapterList.size,
                mCurChapterPos,
                getCurrentPagePosition(),
                getCurrentPageCount()
            )
            BookshelfLocalProgressStore.saveProgressTenths(mCollBook.get_id(), progressTenths)
            // 直接更新
            BookRepository.getInstance().saveCollBook(mCollBook)
        }
    }

    override fun closeBook() {
        chapterRefreshRequests.invalidate()
        mChapterJob?.cancel()
        mChapterJob = null
        chapterScope.cancel()
        super.closeBook()
        mBookSnapshot?.close()
        mBookSnapshot = null
    }

    internal val textEncodingPreference: LocalTxtEncodingPreference
        get() = LocalTxtEncodingPreferences.load(mCollBook.get_id())

    internal fun setTextEncodingPreference(preference: LocalTxtEncodingPreference): Boolean {
        val bookId = mCollBook.get_id()?.takeIf { it.isNotBlank() } ?: return false
        if (LocalTxtEncodingPreferences.load(bookId) == preference) return false
        val liveAnchor = LocalTxtReadingAnchorResolver.capture(
            chapters = mChapterList,
            currentChapterOrdinal = mCurChapterPos,
            currentPageIndex = getCurrentPagePosition(),
            currentPageCount = getCurrentPageCount()
        )
        val request = chapterRefreshRequests.beginEncodingReload(
            liveAnchor = liveAnchor,
            currentPageReadable = mStatus == STATUS_FINISH && getCurrentPageCount() > 0
        )
        LocalTxtEncodingPreferences.save(bookId, preference)
        showCatalogLoading()
        refreshChapterList(request)
        return true
    }

    override fun refreshChapterList() {
        refreshChapterList(chapterRefreshRequests.beginRegularRefresh())
    }

    private fun refreshChapterList(request: LocalTxtChapterRefreshRequest) {
        // 对于文件是否存在，或者为空的判断，不作处理。 ==> 在文件打开前处理过了。
        invalidateCurrentChapterLoad()
        val bookFile = File(mCollBook.cover!!)
        mBookFile = bookFile
        val cachedChapters = mCollBook.getBookChapters()?.toList().orEmpty()
        val cacheAllowed = !mCollBook.isUpdate()
        val cachedUpdated = mCollBook.updated
        val bookId = mCollBook.get_id()?.takeIf { it.isNotBlank() }
        if (bookId == null) {
            showCatalogError()
            return
        }
        val encodingPreference = LocalTxtEncodingPreferences.load(bookId)
        val repository = BookRepository.getInstance()
        mChapterJob?.cancel()
        mChapterJob = chapterScope.launch {
            try {
                val catalogWriteRequest = LocalTxtChapterCatalogTransactions.onIo {
                    repository.beginChapterCatalogWriteRequest(bookId)
                }
                if (!chapterRefreshRequests.isLatest(request)) {
                    return@launch
                }
                val prepared = prepareChapterRefresh(
                    bookFile = bookFile,
                    cachedChapters = cachedChapters,
                    cacheAllowed = cacheAllowed,
                    cachedUpdated = cachedUpdated,
                    bookId = bookId,
                    encodingPreference = encodingPreference
                )
                if (!chapterRefreshRequests.isLatest(request)) {
                    prepared.close()
                    return@launch
                }
                val previousSnapshot = mBookSnapshot
                mBookSnapshot = prepared.snapshot
                previousSnapshot?.close()
                mCharset = prepared.charset
                mChapterList = prepared.chapters
                isChapterListPrepare = true
                val readingAnchor = request.readingAnchor
                val resolvedAnchor = readingAnchor?.let { readingAnchor ->
                    LocalTxtReadingAnchorResolver.resolve(readingAnchor, mChapterList)
                }
                if (resolvedAnchor == null) {
                    restoreCurrentChapterFromAnchor()
                }

                // 提示目录加载完成
                if (mPageChangeListener != null) {
                    mPageChangeListener!!.onCategoryFinish(mChapterList)
                }

                // 存储章节到数据库
                val bookChapterBeanList = ArrayList<BookChapterBean>()
                for (i in 0 until mChapterList.size) {
                    val chapter = mChapterList[i]
                    val bean = BookChapterBean()
                    bean.id = MD5Utils.strToMd5By16(
                        bookFile.absolutePath + File.separator + chapter.title
                    )
                    bean.title = chapter.getTitle()
                    bean.start = chapter.getStart()
                    bean.setUnreadble(false)
                    bean.end = chapter.getEnd()
                    bookChapterBeanList.add(bean)
                }
                mCollBook.setBookChapters(bookChapterBeanList)
                mCollBook.chaptersCount = mChapterList.size
                mCollBook.updated = prepared.lastModified

                val writeToken = LocalTxtChapterCatalogTransactions.onIo {
                    repository.bindChapterCatalogWrite(
                        catalogWriteRequest,
                        prepared.fingerprint
                    )
                }
                if (!chapterRefreshRequests.isLatest(request) || writeToken == null) {
                    return@launch
                }
                LocalTxtChapterCatalogTransactions.onIo {
                    repository.saveCollBook(mCollBook)
                }
                if (!chapterRefreshRequests.isLatest(request)) {
                    return@launch
                }
                repository.saveBookChaptersWithAsync(
                    bookChapterBeanList,
                    writeToken
                ) { result ->
                    if (result != ChapterCatalogWriteResult.APPLIED) {
                        return@saveBookChaptersWithAsync
                    }
                    chapterScope.launch(Dispatchers.IO) {
                        if (!chapterRefreshRequests.isLatest(request)) return@launch
                        val fingerprintContext = currentCoroutineContext()
                        val currentFingerprint = try {
                            LocalTxtParserCacheVersionStore.fingerprint(bookFile) {
                                fingerprintContext.ensureActive()
                            }
                        } catch (_: IOException) {
                            return@launch
                        }
                        if (currentFingerprint != writeToken.fingerprint) return@launch
                        repository.runIfChapterCatalogWriteLatest(writeToken) {
                            if (chapterRefreshRequests.isLatest(request)) {
                                LocalTxtParserCacheVersionStore.markCurrent(
                                    writeToken.bookId,
                                    writeToken.fingerprint,
                                    encodingPreference
                                )
                            }
                        }
                    }
                }

                // 加载并显示当前章节
                Log.e(TAG, "+refreshChapterList")
                if (resolvedAnchor == null) {
                    openChapter()
                } else {
                    reopenChapterFromCatalog(resolvedAnchor.chapterOrdinal) { pageCount ->
                        resolvedAnchor.pageProgress.resolvePageIndex(pageCount)
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                val userMessage = (e as? LocalTxtParseException)
                    ?.let(LocalTxtCatalogFailurePolicy::after)
                    ?.userMessage
                if (userMessage == null) {
                    showCatalogError()
                } else {
                    showDocumentError(userMessage)
                }
                LogUtils.d(TAG, "file load error:$e")
            } finally {
                if (chapterRefreshRequests.isLatest(request)) {
                    mChapterJob = null
                }
            }
        }
    }

    override fun getChapterReader(chapter: TxtChapter): BufferedReader {
        val snapshot = mBookSnapshot
            ?: throw IOException("Local TXT file snapshot is not available")
        val input = snapshot.openRange(chapter.start, chapter.end)
        return BufferedReader(InputStreamReader(input, mCharset.getName()))
    }

    override fun hasChapterData(chapter: TxtChapter): Boolean {
        return true
    }

    override fun onReadableEndReached() {
        saveRecord()
    }

    private suspend fun prepareChapterRefresh(
        bookFile: File,
        cachedChapters: List<BookChapterBean>,
        cacheAllowed: Boolean,
        cachedUpdated: String?,
        bookId: String?,
        encodingPreference: LocalTxtEncodingPreference
    ): PreparedChapterRefresh {
        val preparationContext = currentCoroutineContext()
        val cancellationCheck = { preparationContext.ensureActive() }
        return LocalTxtSnapshotOwnershipTransfer.prepare {
            var lastChangeError: LocalTxtParseException? = null
            repeat(MAX_PREPARE_ATTEMPTS) {
                cancellationCheck()
                val snapshot = LocalTxtFileSnapshot.createImmutableCopy(
                    bookFile,
                    snapshotDirectory,
                    cancellationCheck
                )
                var keepSnapshot = false
                try {
                    cancellationCheck()
                    val fingerprintBefore = LocalTxtParserCacheVersionStore.fingerprint(
                        snapshot,
                        cancellationCheck
                    )
                    cancellationCheck()
                    val charset = LocalTxtCharsetResolver.resolve(
                        snapshot,
                        encodingPreference,
                        cancellationCheck
                    )
                    cancellationCheck()
                    val fingerprintAfterCharset = LocalTxtParserCacheVersionStore.fingerprint(
                        snapshot,
                        cancellationCheck
                    )
                    if (fingerprintBefore != fingerprintAfterCharset) {
                        return@repeat
                    }

                    val lastModified = StringUtils.dateConvert(
                        snapshot.lastModified,
                        Constant.FORMAT_BOOK_DATE
                    )
                    if (
                        cacheAllowed &&
                        cachedUpdated == lastModified &&
                        cachedChapters.isNotEmpty() &&
                        LocalTxtParserCacheVersionStore.isCurrent(
                            bookId,
                            fingerprintAfterCharset,
                            encodingPreference
                        )
                    ) {
                        keepSnapshot = true
                        return@prepare PreparedChapterRefresh(
                            snapshot = snapshot,
                            charset = charset,
                            fingerprint = fingerprintAfterCharset,
                            lastModified = lastModified,
                            chapters = convertTxtChapter(cachedChapters)
                        )
                    }

                    val chapterSnapshot = loadChapters(snapshot, charset, cancellationCheck)
                    if (chapterSnapshot.fingerprint == fingerprintAfterCharset) {
                        keepSnapshot = true
                        return@prepare PreparedChapterRefresh(
                            snapshot = snapshot,
                            charset = charset,
                            fingerprint = chapterSnapshot.fingerprint,
                            lastModified = lastModified,
                            chapters = chapterSnapshot.chapters
                        )
                    }
                } catch (error: LocalTxtParseException) {
                    val decision = LocalTxtCatalogFailurePolicy.after(error)
                    if (!decision.retryPreparation) {
                        throw error
                    }
                    lastChangeError = error
                } finally {
                    if (!keepSnapshot) {
                        snapshot.close()
                    }
                }
            }
            throw lastChangeError
                ?: LocalTxtParseException("TXT file changed while its charset and chapters were inspected")
        }
    }

    private data class PreparedChapterRefresh(
        val snapshot: LocalTxtFileSnapshot,
        val charset: ReaderCharset,
        val fingerprint: String,
        val lastModified: String,
        val chapters: MutableList<TxtChapter>
    ) : Closeable {
        override fun close() {
            snapshot.close()
        }
    }

    companion object {
        private const val TAG = "LocalPageLoader"

        // 默认从文件中获取数据的长度
        private const val BUFFER_SIZE = 512 * 1024

        // 没有标题的时候，每个章节的最大长度
        private const val MAX_LENGTH_WITH_NO_CHAPTER = 10 * 1024

        private const val MAX_PREPARE_ATTEMPTS = 2

    }
}

/** Serializes the tiny durable request fences away from the UI and ObjectBox callback threads. */
internal object LocalTxtChapterCatalogTransactions {
    private val transactionMutex = Mutex()

    suspend fun <T> onIo(action: () -> T): T = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            action()
        }
    }
}

/**
 * Transfers a closeable prepared on IO to the caller without the prompt-cancellation leak window
 * of a bare `withContext(IO)`: cancellation before delivery closes the prepared value here.
 */
internal object LocalTxtSnapshotOwnershipTransfer {
    suspend fun <T : Closeable> prepare(block: suspend () -> T): T {
        val pendingOwnership = AtomicReference<T?>(null)
        try {
            val prepared = withContext(Dispatchers.IO) {
                block().also { pendingOwnership.set(it) }
            }
            pendingOwnership.compareAndSet(prepared, null)
            return prepared
        } finally {
            pendingOwnership.getAndSet(null)?.close()
        }
    }
}
