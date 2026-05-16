package com.ldp.reader.ui.fragment

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.BookIdBean
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.ChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.bean.DirectSycBookShelfBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.remote.RemoteRepository
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.LogUtils
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.utils.RxUtils
import com.ldp.reader.utils.SharedPreUtils
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Created by ldp on 17-5-8.
 */
class BookShelfViewModel : ViewModel() {
    private val disposables = CompositeDisposable()
    private val _collBooks = MutableLiveData<List<CollBookBean>>()
    private val _updateFinishedEvents = MutableLiveData<Int>()
    private val _syncFinishedEvents = MutableLiveData<Int>()
    private val _completeEvents = MutableLiveData<Int>()
    private val _errorTips = MutableLiveData<String?>()
    private var updateFinishedVersion = 0
    private var syncFinishedVersion = 0
    private var completeVersion = 0

    val collBooks: LiveData<List<CollBookBean>> = _collBooks
    val updateFinishedEvents: LiveData<Int> = _updateFinishedEvents
    val syncFinishedEvents: LiveData<Int> = _syncFinishedEvents
    val completeEvents: LiveData<Int> = _completeEvents
    val errorTips: LiveData<String?> = _errorTips

    enum class FilterKey {
        ALL,
        UPDATED_3_DAYS,
        UPDATED_7_DAYS,
        UNREAD,
        READING,
        FINISHED,
        LOCAL
    }

    private enum class ProgressState {
        UNREAD,
        READING,
        FINISHED
    }

    fun refreshCollBooks() {
        val collBooks = BookRepository.getInstance().collBooks
        for (bookBean in collBooks) {
            Log.d("+书名", bookBean.title!!)
        }
        _collBooks.value = collBooks
    }

    @Deprecated("")
    fun getBookShelf(token: String?) {
        val disposable = RemoteRepository.getInstance()
            .getBookShelf(token)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { bookIdBeans: List<BookIdBean?>? ->
                    val bookIdList: MutableList<String> = ArrayList()
                    if (bookIdBeans != null) {
                        for (bookIdBean in bookIdBeans) {
                            if (bookIdBean != null) {
                                bookIdList.add(bookIdBean.bookId.toString())
                            }
                        }
                    }
                    getBookInfo(bookIdList)
                },
                { e: Throwable ->
                    LogUtils.e(e)
                    _errorTips.value = e.toString()
                    _completeEvents.value = ++completeVersion
                }
            )
        disposables.add(disposable)
    }

    fun getBookShelfByMobile(mobile: String?, token: String?) {
        val disposable = RemoteRepository.getInstance()
            .getBookShelfByMobile(mobile, token)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { bookIdBeans: List<BookIdBean?>? ->
                    val bookIdList: MutableList<Long> = ArrayList()
                    if (bookIdBeans != null) {
                        for (bookIdBean in bookIdBeans) {
                            if (bookIdBean != null && bookIdBean.bookId != 0) {
                                bookIdList.add(bookIdBean.bookId.toLong())
                            }
                        }
                    }
                    getBookInfoBatch(bookIdList)
                },
                { e: Throwable ->
                    LogUtils.e(e)
                    _errorTips.value = e.toString()
                    _completeEvents.value = ++completeVersion
                }
            )
        disposables.add(disposable)
    }

    @Deprecated("")
    fun getBookInfo(bookId: List<String>?) {
        if (bookId == null || bookId.isEmpty()) {
            val collBooks = BookRepository.getInstance().collBooks
            val bookIds = onlineBookIdsFrom(collBooks)
            if ("password" == SharedPreUtils.getInstance().getString("loginType")) {
                setBookShelf(bookIds)
            } else {
                val mobile = SharedPreUtils.getInstance().getString("userName")
                val mobileToken = SharedPreUtils.getInstance().getString("token")
                setBookShelfByMobile(bookIds, mobile, mobileToken)
            }
            return
        }
        val bookDetailSingleList: MutableList<Single<BookDetailBeanInOwn>> = ArrayList()
        for (bookIdItem in bookId) {
            val collBookBean = BookRepository.getInstance().getCollBook(bookIdItem)
            if (collBookBean == null) {
                bookDetailSingleList.add(RemoteRepository.getInstance().getBookInfo(bookIdItem))
            }
        }
        val disposable = Single.concat(bookDetailSingleList)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { bookDetailBeanInOwn: BookDetailBeanInOwn -> addToBookShelf(bookDetailBeanInOwn) },
                { throwable: Throwable -> _errorTips.value = throwable.message },
                {
                    val collBooks = BookRepository.getInstance().collBooks
                    val bookIds: MutableList<String> = ArrayList()
                    for (collBookBean in collBooks) {
                        bookIds.add(collBookBean.get_id()!!)
                    }
                    if ("password" == SharedPreUtils.getInstance().getString("loginType")) {
                        setBookShelf(bookIds)
                    } else {
                        val mobile = SharedPreUtils.getInstance().getString("userName")
                        val mobileToken = SharedPreUtils.getInstance().getString("token")
                        setBookShelfByMobile(bookIds, mobile, mobileToken)
                    }
                }
            )
        disposables.add(disposable)
    }

    private fun addToBookShelf(bookDetailBeanInOwn: BookDetailBeanInOwn) {
        val collBookBean = bookDetailBeanInOwn.collBookBean
        collBookBean.updated = bookDetailBeanInOwn.updateTime.toString()
        val bookChapterBeans: MutableList<BookChapterBean> = ArrayList()
        BookRepository.getInstance()
            .saveCollBookWithAsync(collBookBean)
        val disposable = RemoteRepository.getInstance()
            .getBookFolder(collBookBean.get_id())
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { chapterBeans: List<ChapterBean> ->
                    for (chapterBean in chapterBeans) {
                        val bookChapterBeanTemp = BookChapterBean()
                        bookChapterBeanTemp.link = chapterBean.chapterId.toString()
                        bookChapterBeanTemp.title = chapterBean.title
                        bookChapterBeanTemp.id = MD5Utils.strToMd5By16(bookChapterBeanTemp.link!!)
                        bookChapterBeanTemp.bookId = collBookBean.get_id()
                        bookChapterBeanTemp.start = bookChapterBeans.size.toLong()
                        bookChapterBeans.add(bookChapterBeanTemp)
                    }
                    collBookBean.bookChapters = bookChapterBeans
                    collBookBean.chaptersCount = bookChapterBeans.size
                    BookRepository.getInstance().saveCollBookWithAsync(collBookBean)
                    val collBookBeanResult = BookRepository.getInstance().getCollBook(collBookBean.get_id())
                    Log.d(TAG, "addToBookShelf:collBookBeanResult $collBookBeanResult")
                },
                { throwable: Throwable -> _errorTips.value = throwable.message }
            )

        disposables.add(disposable)
    }

    fun setBookShelf(bookIds: List<String>?) {
        val syncBookIds = normalizeServerBookIds(bookIds)
        val body = Gson().toJson(syncBookIds).toRequestBody(JSON)
        val token = SharedPreUtils.getInstance().getString("token")
        val disposable = RemoteRepository.getInstance().setBookShelf(token, body)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                {
                    _syncFinishedEvents.value = ++syncFinishedVersion
                    _updateFinishedEvents.value = ++updateFinishedVersion
                },
                { throwable: Throwable -> _errorTips.value = throwable.message }
            )
        disposables.add(disposable)
    }

    fun setBookShelfByMobile(bookIds: List<String>?, mobile: String?, mobileToken: String?) {
        val directSycBookShelfBean = DirectSycBookShelfBean()
        directSycBookShelfBean.bookIds = normalizeServerBookIds(bookIds)
        directSycBookShelfBean.mobile = mobile
        directSycBookShelfBean.mobileToken = mobileToken
        val body = Gson().toJson(directSycBookShelfBean).toRequestBody(JSON)
        val disposable = RemoteRepository.getInstance().setBookShelfByMobile(body)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                {
                    _syncFinishedEvents.value = ++syncFinishedVersion
                    _updateFinishedEvents.value = ++updateFinishedVersion
                },
                { throwable: Throwable -> _errorTips.value = throwable.message }
            )
        disposables.add(disposable)
    }

    fun updateCollBooks(collBookBeans: List<CollBookBean>?) {
        if (collBookBeans == null || collBookBeans.isEmpty()) {
            return
        }
        val bookIdList = onlineBookLongIdsFrom(collBookBeans)
        updateBookInfoBatch(bookIdList)
    }

    private fun updateBookInfoBatch(bookIdList: List<Long>?) {
        if (bookIdList == null || bookIdList.isEmpty()) {
            _completeEvents.value = ++completeVersion
            _updateFinishedEvents.value = ++updateFinishedVersion
            return
        }
        val newCollBooksMerge: MutableList<CollBookBean> = ArrayList()
        val body = Gson().toJson(bookIdList).toRequestBody(JSON)
        val disposable = RemoteRepository.getInstance().getBookInfoBatch(body)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { bookDetailBeanInOwns: List<BookDetailBeanInOwn> ->
                    for (bookDetailBeanInOwn in bookDetailBeanInOwns) {
                        val oldCollBook = BookRepository.getInstance()
                            .getCollBook(bookDetailBeanInOwn.bookId.toString())
                        if (oldCollBook == null) {
                            continue
                        }
                        val lastChapterChanged = oldCollBook.lastChapter == null ||
                            oldCollBook.lastChapter != bookDetailBeanInOwn.lastChapter
                        if (lastChapterChanged) {
                            updateBookInfo(bookDetailBeanInOwn, oldCollBook)
                            newCollBooksMerge.add(oldCollBook)
                        } else if (isReadableFolderStale(oldCollBook)) {
                            updateCategory(oldCollBook)
                        }
                        Log.d(TAG, "+检查更新")
                    }
                    BookRepository.getInstance().saveCollBooks(newCollBooksMerge)
                    _completeEvents.value = ++completeVersion
                    _updateFinishedEvents.value = ++updateFinishedVersion
                },
                { throwable: Throwable ->
                    _completeEvents.value = ++completeVersion
                    _errorTips.value = throwable.message
                }
            )
        disposables.add(disposable)
    }

    private fun getBookInfoBatch(bookIdList: List<Long>?) {
        if (bookIdList == null || bookIdList.isEmpty()) {
            updateShelf(ArrayList())
            _completeEvents.value = ++completeVersion
            return
        }
        val body = Gson().toJson(bookIdList).toRequestBody(JSON)
        val disposable = RemoteRepository.getInstance().getBookInfoBatch(body)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { bookDetailBeanInOwns: List<BookDetailBeanInOwn> ->
                    for (bookDetailBeanInOwn in bookDetailBeanInOwns) {
                        addToBookShelf(bookDetailBeanInOwn)
                    }

                    updateShelf(bookIdList)
                },
                { throwable: Throwable -> _errorTips.value = throwable.message }
            )
        disposables.add(disposable)
    }

    /**
     * 更新书架,将未登录时的添加书籍同步给服务器
     */
    private fun updateShelf(bookIdList: List<Long>) {
        val collBooks = BookRepository.getInstance().collBooks
        val bookIds = mergeServerAndLocalOnlineIds(bookIdList, collBooks)

        val mobile = SharedPreUtils.getInstance().getString("userName")
        val mobileToken = SharedPreUtils.getInstance().getString("token")
        setBookShelfByMobile(bookIds, mobile, mobileToken)
    }

    private fun updateBookInfo(bookDetailBeanInOwn: BookDetailBeanInOwn, oldCollBook: CollBookBean) {
        oldCollBook.lastChapter = bookDetailBeanInOwn.lastChapter
        Log.d(TAG, "+更新书籍 " + oldCollBook.title + oldCollBook.lastChapter)
        oldCollBook.setUpdate(true)
        updateCategory(oldCollBook)
        oldCollBook.updated = bookDetailBeanInOwn.updateTime.toString()
    }

    private fun isReadableFolderStale(collBookBean: CollBookBean?): Boolean {
        if (collBookBean == null || collBookBean.isLocal()) {
            return false
        }
        val record = BookRepository.getInstance().getBookRecord(collBookBean.get_id())
        if (record == null || record.chapter <= 0) {
            return false
        }
        val chaptersCount = collBookBean.chaptersCount
        val storedChapterCount = chaptersCount.coerceAtLeast(0)
        var relationChapterCount = 0
        try {
            if (collBookBean.bookChapters != null) {
                relationChapterCount = collBookBean.bookChapters!!.size
            }
        } catch (ignored: RuntimeException) {
        }
        return storedChapterCount <= 0 ||
            record.chapter >= storedChapterCount ||
            relationChapterCount > storedChapterCount
    }

    private fun updateCategory(collBookBean: CollBookBean) {
        val bookChapterBeans: MutableList<BookChapterBean> = ArrayList()
        Log.d(TAG, "loadCategory: " + collBookBean.get_id() + collBookBean)
        val disposable = RemoteRepository.getInstance()
            .getBookFolder(collBookBean.get_id())
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { chapterBeans: List<ChapterBean> ->
                    for (chapterBean in chapterBeans) {
                        val bookChapterBeanTemp = BookChapterBean()
                        bookChapterBeanTemp.link = chapterBean.chapterId.toString()
                        bookChapterBeanTemp.title = chapterBean.title
                        bookChapterBeanTemp.id = MD5Utils.strToMd5By16(bookChapterBeanTemp.link!!)
                        Log.d(TAG, "+章节名  " + chapterBean.title)
                        bookChapterBeanTemp.bookId = collBookBean.get_id()
                        bookChapterBeanTemp.start = bookChapterBeans.size.toLong()
                        bookChapterBeans.add(bookChapterBeanTemp)
                    }
                    collBookBean.bookChapters = bookChapterBeans
                    collBookBean.chaptersCount = bookChapterBeans.size
                    Log.d(TAG, "accept: $bookChapterBeans")
                    BookRepository.getInstance()
                        .saveCollBookWithAsync(collBookBean)
                },
                { throwable: Throwable -> throwable.printStackTrace() }
            )
        disposables.add(disposable)
    }

    override fun onCleared() {
        disposables.clear()
        super.onCleared()
    }

    companion object {
        private const val TAG = "BookShelfViewModel"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val FINISHED_PROGRESS_TENTHS = 999
        private const val STALE_RELATION_TAIL_TOLERANCE = 3
        private const val START_READING = "开始阅读"
        private val VISIBLE_FILTER_KEYS = arrayOf(
            FilterKey.ALL,
            FilterKey.UPDATED_3_DAYS,
            FilterKey.UPDATED_7_DAYS,
            FilterKey.UNREAD,
            FilterKey.READING,
            FilterKey.FINISHED,
            FilterKey.LOCAL
        )
        private val FILTER_SECTION_LABELS = arrayOf("全部书籍", "更新状态", "阅读进度", "本地文件")
        private val STATUS_OPTION_LABELS = arrayOf("3日内更新", "7日内更新")
        private val PROGRESS_OPTION_LABELS = arrayOf("尚未阅读", "正在阅读", "已读完")
        private val JSON: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

        @JvmStatic
        fun visibleFilterKeys(): Array<FilterKey> {
            return VISIBLE_FILTER_KEYS.clone()
        }

        @JvmStatic
        fun filterSectionLabels(): Array<String> {
            return FILTER_SECTION_LABELS.clone()
        }

        @JvmStatic
        fun statusOptionLabels(): Array<String> {
            return STATUS_OPTION_LABELS.clone()
        }

        @JvmStatic
        fun progressOptionLabels(): Array<String> {
            return PROGRESS_OPTION_LABELS.clone()
        }

        @JvmStatic
        fun filterToolbarLabel(key: FilterKey?): String {
            if (key == null || key == FilterKey.ALL) {
                return "筛选"
            }
            return filterOptionLabel(key)
        }

        @JvmStatic
        fun filterOptionLabel(key: FilterKey?): String {
            return when (key) {
                null -> "全部书籍"
                FilterKey.UPDATED_3_DAYS -> "3日内更新"
                FilterKey.UPDATED_7_DAYS -> "7日内更新"
                FilterKey.UNREAD -> "尚未阅读"
                FilterKey.READING -> "正在阅读"
                FilterKey.FINISHED -> "已读完"
                FilterKey.LOCAL -> "本地书"
                FilterKey.ALL -> "全部书籍"
            }
        }

        @JvmStatic
        fun localFilterSectionLabel(): String {
            return "本地文件"
        }

        @JvmStatic
        fun matchesFilter(
            key: FilterKey?,
            book: CollBookBean?,
            record: BookRecordBean?,
            storedProgressTenths: Int,
            nowMillis: Long
        ): Boolean {
            if (book == null) {
                return false
            }
            if (key == null || key == FilterKey.ALL) {
                return true
            }
            return when (key) {
                FilterKey.UPDATED_3_DAYS -> isUpdatedWithin(book, 3, nowMillis)
                FilterKey.UPDATED_7_DAYS -> isUpdatedWithin(book, 7, nowMillis)
                FilterKey.UNREAD -> progressState(book, record, storedProgressTenths) == ProgressState.UNREAD
                FilterKey.READING -> progressState(book, record, storedProgressTenths) == ProgressState.READING
                FilterKey.FINISHED -> progressState(book, record, storedProgressTenths) == ProgressState.FINISHED
                FilterKey.LOCAL -> book.isLocal()
                FilterKey.ALL -> true
            }
        }

        @JvmStatic
        fun shouldShowFilterEmpty(key: FilterKey?, visibleBookCount: Int): Boolean {
            return key != null && key != FilterKey.ALL && visibleBookCount == 0
        }

        @JvmStatic
        fun emptyShelfTitle(): String {
            return "重拾阅读习惯，从添加一本书开始"
        }

        @JvmStatic
        fun filterEmptyTitle(_key: FilterKey?): String {
            return emptyShelfTitle()
        }

        @JvmStatic
        fun filterEmptyResetText(): String {
            return "全部书籍"
        }

        @JvmStatic
        fun emptyImportText(): String {
            return "去导入"
        }

        @JvmStatic
        fun onlineBookIdsFrom(books: List<CollBookBean?>?): List<String> {
            if (books == null || books.isEmpty()) {
                return ArrayList()
            }
            val ids: MutableSet<String> = LinkedHashSet()
            for (book in books) {
                if (book == null || book.isLocal()) {
                    continue
                }
                val id = book.get_id()
                if (isServerBookId(id)) {
                    ids.add(id!!)
                }
            }
            return ArrayList(ids)
        }

        @JvmStatic
        fun onlineBookLongIdsFrom(books: List<CollBookBean?>?): List<Long> {
            val ids = onlineBookIdsFrom(books)
            val longIds: MutableList<Long> = ArrayList(ids.size)
            for (id in ids) {
                longIds.add(id.toLong())
            }
            return longIds
        }

        @JvmStatic
        fun mergeServerAndLocalOnlineIds(
            serverBookIds: Collection<Long?>?,
            localBooks: List<CollBookBean?>?
        ): List<String> {
            val ids: MutableSet<String> = LinkedHashSet()
            if (serverBookIds != null) {
                for (serverBookId in serverBookIds) {
                    if (serverBookId != null && serverBookId != 0L) {
                        ids.add(serverBookId.toString())
                    }
                }
            }
            ids.addAll(onlineBookIdsFrom(localBooks))
            return ArrayList(ids)
        }

        @JvmStatic
        fun normalizeServerBookIds(bookIds: Collection<String>?): List<String> {
            val ids: MutableSet<String> = LinkedHashSet()
            if (bookIds == null) {
                return ArrayList()
            }
            for (bookId in bookIds) {
                if (isServerBookId(bookId)) {
                    ids.add(bookId)
                }
            }
            return ArrayList(ids)
        }

        private fun isUpdatedWithin(book: CollBookBean, days: Int, nowMillis: Long): Boolean {
            val updatedMillis = parseBookDateMillis(book.updated)
            if (updatedMillis <= 0L) {
                return false
            }
            val diffMillis = (nowMillis - updatedMillis).coerceAtLeast(0L)
            return diffMillis <= days * DAY_MS
        }

        private fun progressState(
            book: CollBookBean,
            record: BookRecordBean?,
            storedProgressTenths: Int
        ): ProgressState {
            if (storedProgressTenths >= FINISHED_PROGRESS_TENTHS) {
                return ProgressState.FINISHED
            }
            if (storedProgressTenths > 0) {
                return ProgressState.READING
            }
            if (isFinishedByRecord(book, record)) {
                return ProgressState.FINISHED
            }
            if (record != null && (record.chapter > 0 || record.pagePos > 0)) {
                return ProgressState.READING
            }
            if (book.isLocal()) {
                val lastChapter = book.lastChapter
                if (
                    lastChapter != null &&
                    lastChapter.trim().isNotEmpty() &&
                    START_READING != lastChapter.trim()
                ) {
                    return ProgressState.READING
                }
            }
            return ProgressState.UNREAD
        }

        private fun isFinishedByRecord(book: CollBookBean?, record: BookRecordBean?): Boolean {
            if (book == null || record == null || record.pagePos <= 0) {
                return false
            }
            val chapterCount = resolveChapterCount(book, record)
            if (chapterCount <= 0) {
                return false
            }
            return record.chapter >= chapterCount - 1
        }

        private fun resolveChapterCount(book: CollBookBean, record: BookRecordBean?): Int {
            val storedChapterCount = book.chaptersCount.coerceAtLeast(0)
            var relationChapterCount = 0
            try {
                val chapters = book.bookChapters
                if (chapters != null) {
                    relationChapterCount = chapters.size
                }
            } catch (ignored: RuntimeException) {
            }
            if (
                storedChapterCount > 0 &&
                relationChapterCount > storedChapterCount &&
                relationChapterCount - storedChapterCount <= STALE_RELATION_TAIL_TOLERANCE &&
                record != null &&
                record.chapter >= storedChapterCount - 1
            ) {
                return storedChapterCount
            }
            return storedChapterCount.coerceAtLeast(relationChapterCount)
        }

        private fun parseBookDateMillis(source: String?): Long {
            if (source == null || source.trim().isEmpty()) {
                return 0L
            }
            val value = source.trim()
            try {
                val numeric = value.toLong()
                if (numeric > 0L && numeric < 10_000_000_000L) {
                    return numeric * 1000L
                }
                return numeric
            } catch (ignored: NumberFormatException) {
            }
            val standardMillis = parseWithFormat(
                value,
                SimpleDateFormat(Constant.FORMAT_BOOK_DATE, Locale.CHINA)
            )
            if (standardMillis > 0L) {
                return standardMillis
            }
            val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.CHINA)
            utcFormat.timeZone = TimeZone.getTimeZone("UTC")
            return parseWithFormat(value, utcFormat)
        }

        private fun parseWithFormat(value: String, format: DateFormat): Long {
            return try {
                val date: Date? = format.parse(value)
                date?.time ?: 0L
            } catch (ignored: ParseException) {
                0L
            }
        }

        private fun isServerBookId(id: String?): Boolean {
            if (id == null || id.isEmpty()) {
                return false
            }
            val start = if (id[0] == '-') 1 else 0
            if (start == id.length) {
                return false
            }
            for (index in start until id.length) {
                if (!Character.isDigit(id[index])) {
                    return false
                }
            }
            return try {
                id.toLong() != 0L
            } catch (e: NumberFormatException) {
                false
            }
        }
    }
}

