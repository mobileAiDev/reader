package com.ldp.reader.widget.page

import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.source.SourceEngineContentCachePolicy
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.utils.BookManager
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.StringUtils
import java.io.BufferedReader
import java.io.FileReader

/**
 * Created by ldp on 17-5-29.
 * 网络页面加载器
 */
class NetPageLoader(pageView: PageView, collBook: CollBookBean) : PageLoader(pageView, collBook) {
    private var firstTime = true

    private fun convertTxtChapter(bookChapters: List<BookChapterBean>): MutableList<TxtChapter> {
        val txtChapters = ArrayList<TxtChapter>(bookChapters.size)
        for (bean in bookChapters) {
            val chapter = TxtChapter()
            chapter.bookId = bean.bookId
            chapter.title = bean.title
            chapter.link = bean.link
            txtChapters.add(chapter)
        }
        return txtChapters
    }

    override fun refreshChapterList() {
        Log.e(TAG, "refreshChapterList")
        if (mCollBook.getBookChapters() == null) return
        SourceEngineContentCachePolicy.ensureFresh(mCollBook)

        // 将 BookChapter 转换成当前可用的 Chapter
        mChapterList = convertTxtChapter(mCollBook.getBookChapters()!!)
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
    }

    override fun getChapterReader(chapter: TxtChapter): BufferedReader? {
        val file = BookManager.findBookFile(mCollBook.get_id(), chapter.title)
        if (!file.exists()) return null

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

        if (mStatus == STATUS_LOADING && (firstTime || isInit)) {
            firstTime = false
            Log.d(TAG, "STATUS_LOADING")
            loadCurrentChapter()
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
            var begin = mCurChapterPos
            var end = mCurChapterPos

            // 是否当前不是最后一章
            if (end < mChapterList.size) {
                end += 1
                if (end >= mChapterList.size) {
                    end = mChapterList.size - 1
                }
            }

            // 如果当前不是第一章
            if (begin != 0) {
                begin -= 1
                if (begin < 0) {
                    begin = 0
                }
            }
            Log.e(TAG, "+loadCurrentChapter" + "begin:" + begin + "   end:" + end)

            requestChapters(begin, end)
        }
    }

    /**
     * 加载当前页的后两个章节
     */
    private fun loadNextChapter() {
        if (mPageChangeListener != null) {
            // 提示加载后两章
            val begin = mCurChapterPos + 1
            var end = begin + 1

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

    @Synchronized
    private fun requestChapters(start: Int, end: Int) {
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
        for (i in requestOrder) {
            val txtChapter = mChapterList[i]
            if (!hasChapterData(txtChapter)) {
                chapters.add(txtChapter)
                Log.e(TAG, "+requestChapters  " + txtChapter.getTitle())
            }
        }

        if (chapters.isNotEmpty()) {
            mPageChangeListener!!.requestChapters(chapters)
        }
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
    }
}
