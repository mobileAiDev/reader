package com.ldp.reader.widget.page

import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.source.SourceEngineContentCachePolicy
import com.ldp.reader.source.SourceEngineMetadataCleaner
import com.ldp.reader.source.hasHiddenSourceIntegrityMark
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.utils.BookManager
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.StringUtils
import java.io.BufferedReader
import java.io.FileReader
import java.io.StringReader
import java.nio.charset.Charset

/**
 * Created by ldp on 17-5-29.
 * 网络页面加载器
 */
class NetPageLoader(pageView: PageView, collBook: CollBookBean) : PageLoader(pageView, collBook) {
    private var firstTime = true
    private val markedCurrentRefreshKeys = HashSet<String>()

    private fun convertTxtChapter(bookChapters: List<BookChapterBean>): MutableList<TxtChapter> {
        val txtChapters = ArrayList<TxtChapter>(bookChapters.size)
        for (bean in bookChapters) {
            val chapter = TxtChapter()
            chapter.bookId = bean.bookId
            chapter.title = bean.title
            chapter.link = bean.link
            chapter.catalogIndex = bean.start.toInt()
            chapter.sourceIntegrityState = bean.sourceIntegrityState
            chapter.sourceIntegrityConfidence = bean.sourceIntegrityConfidence
            chapter.sourceIntegrityReason = bean.sourceIntegrityReason
            chapter.start = bean.start
            txtChapters.add(chapter)
        }
        return txtChapters
    }

    override fun refreshChapterList() {
        Log.e(TAG, "refreshChapterList")
        val startedAt = System.currentTimeMillis()
        val bookChapters = mCollBook.getBookChapters()
        AiBridgeTrace.event(
            "source_read_page_catalog_refresh_started",
            mCollBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "rawChapters" to (bookChapters?.size ?: 0),
                "chapterPos" to mCurChapterPos,
                "status" to mStatus
            )
        )
        if (bookChapters == null) {
            AiBridgeTrace.event(
                "source_read_page_catalog_refresh_skipped",
                mCollBook.title.orEmpty(),
                AiBridgeTrace.fields("reason" to "null_catalog", "durationMs" to (System.currentTimeMillis() - startedAt))
            )
            return
        }
        SourceEngineContentCachePolicy.ensureFresh(mCollBook)

        // 将 BookChapter 转换成当前可用的 Chapter
        mChapterList = convertTxtChapter(bookChapters)
        mCollBook.chaptersCount = mChapterList.size
        isChapterListPrepare = true

        // 目录加载完成，执行回调操作。
        if (mPageChangeListener != null) {
            mPageChangeListener!!.onCategoryFinish(mChapterList)
        }

        val clampedChapter = clampCurrentChapterToAvailableCatalog()
        if (clampedChapter) {
            firstTime = true
        }

        // 如果章节未打开
        if (!isChapterOpen() || clampedChapter) {
            // 打开章节
            Log.e(TAG, "+refreshChapterList")
            openChapter()
        }
        AiBridgeTrace.state(
            "source_read_page_catalog_ready",
            mCollBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapters" to mChapterList.size,
                "chapterPos" to mCurChapterPos,
                "current" to currentChapterTitle.orEmpty(),
                "clamped" to clampedChapter,
                "status" to mStatus,
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
    }

    override fun getChapterReader(chapter: TxtChapter): BufferedReader? {
        val file = BookManager.findBookFile(mCollBook.get_id(), chapter.title)
        if (!file.exists()) return null

        if (ReaderFeatureSwitches.isCleanContentEnabled()) {
            return BufferedReader(
                StringReader(SourceEngineMetadataCleaner.cleanContent(file.readText(Charset.defaultCharset())))
            )
        }
        return BufferedReader(FileReader(file))
    }

    override fun hasChapterData(chapter: TxtChapter): Boolean {
        return BookManager.isChapterCached(mCollBook.get_id(), chapter.title)
    }

    // 装载上一章节的内容
    override fun parsePrevChapter(): Boolean {
        val isRight = super.parsePrevChapter()

        if (mStatus == STATUS_FINISH) {
            loadPrevChapter()
        } else if (mStatus == STATUS_LOADING) {
            loadCurrentChapter()
        }
        return isRight
    }

    // 装载当前章内容。
    @Synchronized
    override fun parseCurChapter(): Boolean {
        Log.d("+打开章节NetPage", "parseCurChapter")

        val isRight = super.parseCurChapter()

        if (mStatus == STATUS_LOADING) {
            firstTime = false
            Log.d(TAG, "STATUS_LOADING")
            loadCurrentChapter()
        } else if (mStatus == STATUS_FINISH) {
            loadNextChapter()
        }
        return isRight
    }

    // 装载下一章节的内容
    override fun parseNextChapter(): Boolean {
        val isRight = super.parseNextChapter()

        if (mStatus == STATUS_FINISH) {
            loadNextChapter()
        } else if (mStatus == STATUS_LOADING) {
            loadCurrentChapter()
        }

        return isRight
    }

    /**
     * 加载当前页的前面两个章节
     */
    private fun loadPrevChapter() {
        if (mPageChangeListener != null) {
            val end = mCurChapterPos
            var begin = end - 2
            if (begin < 0) {
                begin = 0
            }
            Log.e(TAG, "+loadPrevChapter" + "begin:" + begin + "   end:" + end)

            requestChapters(begin, end)
        }
    }

    /**
     * 加载前一页，当前页，后一页。
     */
    @Synchronized
    private fun loadCurrentChapter() {
        Log.e(TAG, "+NetloadCurrentChapter")
        if (mPageChangeListener != null) {
            val begin = mCurChapterPos
            val end = (mCurChapterPos + FORWARD_PREFETCH_CHAPTERS).coerceAtMost(mChapterList.size - 1)
            Log.e(TAG, "+loadCurrentChapter" + "begin:" + begin + "   end:" + end)

            requestChapters(begin, end)
        }
    }

    /**
     * 加载当前页的后两个章节
     */
    private fun loadNextChapter() {
        if (mPageChangeListener != null) {
            // 当前章可读后，继续向后预取低优先级章节。
            val begin = if (hasPendingMarkedCurrentRefresh()) {
                mCurChapterPos
            } else {
                mCurChapterPos + 1
            }
            var end = mCurChapterPos + FORWARD_PREFETCH_CHAPTERS

            // 判断是否大于最后一章
            if (begin >= mChapterList.size) {
                // 如果下一章超出目录了，就没有必要加载了
                return
            }

            if (end > mChapterList.size) {
                end = mChapterList.size - 1
            }
            Log.e(TAG, "+loadNextChapter" + "begin:" + begin + "   end:" + end)

            requestChapters(begin, end)
        }
    }

    private fun hasPendingMarkedCurrentRefresh(): Boolean {
        val chapter = mChapterList.getOrNull(mCurChapterPos) ?: return false
        val key = markedCurrentRefreshKey(chapter) ?: return false
        return hasChapterData(chapter) &&
            ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled() &&
            chapter.hasHiddenSourceIntegrityMark() &&
            key !in markedCurrentRefreshKeys
    }

    @Synchronized
    private fun requestChapters(start: Int, end: Int) {
        val startedAt = System.currentTimeMillis()
        var requestStart = start
        var requestEnd = end
        Log.e(TAG, "+requestChapters  start:$requestStart   end:$requestEnd")
        // 检验输入值
        if (requestStart < 0) {
            requestStart = 0
        }

        if (requestEnd >= mChapterList.size) {
            requestEnd = mChapterList.size - 1
        }

        if (requestStart > requestEnd || requestStart >= mChapterList.size) {
            return
        }

        val chapters = ArrayList<TxtChapter>()

        val requestOrder = ArrayList<Int>(requestEnd - requestStart + 1)
        if (mCurChapterPos in requestStart..requestEnd) {
            requestOrder.add(mCurChapterPos)
        }
        for (i in requestStart..requestEnd) {
            if (i != mCurChapterPos) requestOrder.add(i)
        }

        // 过滤，哪些数据已经加载了。当前阅读章节优先，避免预取章节失败阻断正文显示。
        var integrityRefreshes = 0
        for (i in requestOrder) {
            val txtChapter = mChapterList[i]
            val cached = hasChapterData(txtChapter)
            val refreshMarkedCurrent = cached && i == mCurChapterPos &&
                ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled() &&
                txtChapter.hasHiddenSourceIntegrityMark() &&
                markedCurrentRefreshKey(txtChapter)?.let { key -> markedCurrentRefreshKeys.add(key) } == true
            if (!cached || refreshMarkedCurrent) {
                chapters.add(txtChapter)
                if (refreshMarkedCurrent) integrityRefreshes += 1
                Log.e(TAG, "+requestChapters  " + txtChapter.getTitle())
            }
        }

        AiBridgeTrace.event(
            "source_read_page_request_plan",
            mCollBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "inputStart" to start,
                "inputEnd" to end,
                "start" to requestStart,
                "end" to requestEnd,
                "chapterPos" to mCurChapterPos,
                "current" to currentChapterTitle.orEmpty(),
                "currentFirst" to (requestOrder.firstOrNull() == mCurChapterPos),
                "order" to requestOrder.take(8).joinToString("|"),
                "cacheHits" to (requestOrder.size - chapters.size),
                "cacheMisses" to chapters.size,
                "integrityRefreshes" to integrityRefreshes,
                "firstMiss" to chapters.firstOrNull()?.title.orEmpty(),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        if (chapters.isNotEmpty()) {
            AiBridgeTrace.event(
                "source_read_page_request_dispatched",
                mCollBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "requested" to chapters.size,
                    "first" to chapters.firstOrNull()?.title.orEmpty(),
                    "current" to currentChapterTitle.orEmpty(),
                    "chapterPos" to mCurChapterPos
                )
            )
            mPageChangeListener!!.requestChapters(chapters)
        } else {
            AiBridgeTrace.event(
                "source_read_page_request_skipped",
                mCollBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "reason" to "all_cached",
                    "current" to currentChapterTitle.orEmpty(),
                    "chapterPos" to mCurChapterPos
                )
            )
        }
    }

    private fun markedCurrentRefreshKey(chapter: TxtChapter): String? {
        return chapter.link ?: chapter.title
    }

    override fun saveRecord() {
        super.saveRecord()
        if (mCollBook != null && isChapterListPrepare) {
            // 表示当前CollBook已经阅读
            mCollBook.setIsUpdate(false)
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

    override fun onReadableEndReached() {
        saveRecord()
    }

    companion object {
        private const val TAG = "PageFactory"
        private const val FORWARD_PREFETCH_CHAPTERS = 5
    }
}
