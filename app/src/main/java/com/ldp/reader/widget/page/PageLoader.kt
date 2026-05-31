package com.ldp.reader.widget.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import androidx.core.content.ContextCompat
import com.ldp.reader.R
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.local.ReadSettingManager
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.source.hasHiddenSourceIntegrityMark
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.IOUtils
import com.ldp.reader.utils.ScreenUtils
import com.ldp.reader.utils.StringUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Created by ldp on 17-7-1.
 */
abstract class PageLoader(pageView: PageView, collBook: CollBookBean) {
    protected var isInit = false

    // 当前章节列表
    protected var mChapterList: MutableList<TxtChapter> = ArrayList(1)

    // 书本对象
    protected var mCollBook: CollBookBean = collBook

    // 监听器
    protected var mPageChangeListener: OnPageChangeListener? = null

    private var mContext: Context = pageView.context

    // 页面显示类
    private var mPageView: PageView? = pageView

    // 当前显示的页
    private var mCurPage: TxtPage? = null

    // 上一章的页面列表缓存
    private var mPrePageList: MutableList<TxtPage>? = null

    // 当前章节的页面列表
    private var mCurPageList: MutableList<TxtPage>? = null

    // 下一章的页面列表缓存
    private var mNextPageList: MutableList<TxtPage>? = null

    // 绘制电池的画笔
    private lateinit var mBatteryPaint: Paint

    // 绘制提示的画笔
    private lateinit var mTipPaint: Paint

    // 绘制标题的画笔
    private lateinit var mTitlePaint: Paint

    // 绘制背景颜色的画笔(用来擦除需要重绘的部分)
    private lateinit var mBgPaint: Paint

    // 绘制小说内容的画笔
    private lateinit var mTextPaint: TextPaint

    // 阅读器的配置选项
    private lateinit var mSettingManager: ReadSettingManager

    // 被遮盖的页，或者认为被取消显示的页
    private var mCancelPage: TxtPage? = null

    // 存储阅读记录类
    private lateinit var mBookRecord: BookRecordBean

    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mPreLoadJob: Job? = null

    // 当前的状态
    protected var mStatus = STATUS_LOADING

    // 判断章节列表是否加载完成
    protected var isChapterListPrepare = false

    // 是否打开过章节
    private var isChapterOpen = false
    private var isFirstOpen = true
    private var isClose = false

    // 页面的翻页效果模式
    private lateinit var mPageMode: PageMode

    // 加载器的颜色主题
    private lateinit var mPageStyle: PageStyle

    // 当前是否是夜间模式
    private var isNightMode = false
    private var showWrongChapters = true

    // 书籍绘制区域的宽高
    private var mVisibleWidth = 0
    private var mVisibleHeight = 0

    // 应用的宽高
    private var mDisplayWidth = 0
    private var mDisplayHeight = 0

    // 间距
    private var mMarginWidth = 0
    private var mMarginTop = 0
    private var mMarginBottom = 0
    private var mFooterAreaHeight = 0

    // 字体的颜色
    private var mTextColor = 0

    // 标题的大小
    private var mTitleSize = 0

    // 字体的大小
    private var mTextSize = 0

    // 行间距
    private var mTextInterval = 0

    // 标题的行间距
    private var mTitleInterval = 0

    // 段落距离(基于行间距的额外距离)
    private var mTextPara = 0
    private var mTitlePara = 0

    // 电池的百分比
    private var mBatteryLevel = 0

    // 当前页面的背景
    private var mBgColor = 0

    // 当前章
    protected var mCurChapterPos = 0

    // 上一章的记录
    private var mLastChapterPos = 0

    val pageStatus: Int
        get() = mStatus

    val collBook: CollBookBean
        get() = mCollBook

    val chapterCategory: MutableList<TxtChapter>
        get() = mChapterList

    val pagePos: Int
        get() = mCurPage!!.position

    protected fun getCurrentPageCount(): Int {
        return mCurPageList?.size ?: 0
    }

    protected fun getCurrentPagePosition(): Int {
        return mCurPage?.position ?: 0
    }

    val chapterPos: Int
        get() = mCurChapterPos

    val currentChapterTitle: String?
        get() = mChapterList.getOrNull(mCurChapterPos)?.getTitle()

    val marginHeight: Int
        get() = mMarginTop

    fun setShowWrongChapters(showWrongChapters: Boolean) {
        if (this.showWrongChapters == showWrongChapters) {
            return
        }
        this.showWrongChapters = showWrongChapters
        refreshSourceIntegrityMarks()
    }

    fun refreshSourceIntegrityMarks() {
        val pageView = mPageView ?: return
        if (pageView.isPrepare() && !pageView.isRunning()) {
            pageView.drawCurPage(false)
        }
    }

    init {
        Log.d(TAG, "+PageLoader")
        isInit = false

        // 初始化数据
        initData()
        // 初始化画笔
        initPaint()
        // 初始化PageView
        initPageView()
        // 初始化书籍
        prepareBook()
    }

    private fun initData() {
        Log.d(TAG, "+initData")
        isInit = false

        // 获取配置管理器
        mSettingManager = ReadSettingManager.getInstance()
        // 获取配置参数
        mPageMode = mSettingManager.pageMode
        mPageStyle = mSettingManager.pageStyle
        // 初始化参数
        mMarginWidth = ScreenUtils.dpToPx(DEFAULT_MARGIN_WIDTH)
        val verticalPadding = ScreenUtils.dpToPx(DEFAULT_CONTENT_PADDING_VERTICAL)
        mMarginTop = calculateContentTopMargin(ScreenUtils.getStatusBarHeight(), verticalPadding)
        mMarginBottom = calculateContentBottomMargin(verticalPadding)
        mFooterAreaHeight = ScreenUtils.dpToPx(DEFAULT_FOOTER_AREA_HEIGHT)
        // 配置文字有关的参数
        setUpTextParams(mSettingManager.textSize)
    }

    /**
     * 作用：设置与文字相关的参数
     */
    private fun setUpTextParams(textSize: Int) {
        // 文字大小
        mTextSize = textSize
        mTitleSize = mTextSize + ScreenUtils.spToPx(EXTRA_TITLE_SIZE)
        // 行间距(大小为字体的一半)
        mTextInterval = mTextSize / 2
        mTitleInterval = mTitleSize / 2
        // 段落间距(大小为字体的高度)
        mTextPara = mTextSize
        mTitlePara = mTitleSize
    }

    private fun initPaint() {
        Log.d(TAG, "+initPaint")

        // 绘制提示的画笔
        mTipPaint = Paint()
        mTipPaint.color = mTextColor
        mTipPaint.textAlign = Paint.Align.LEFT
        mTipPaint.textSize = ScreenUtils.spToPx(DEFAULT_TIP_SIZE).toFloat()
        mTipPaint.isAntiAlias = true
        mTipPaint.isSubpixelText = true

        // 绘制页面内容的画笔
        mTextPaint = TextPaint()
        mTextPaint.color = mTextColor
        mTextPaint.textSize = mTextSize.toFloat()
        mTextPaint.isAntiAlias = true

        // 绘制标题的画笔
        mTitlePaint = TextPaint()
        mTitlePaint.color = mTextColor
        mTitlePaint.textSize = mTitleSize.toFloat()
        mTitlePaint.style = Paint.Style.FILL_AND_STROKE
        mTitlePaint.typeface = Typeface.DEFAULT_BOLD
        mTitlePaint.isAntiAlias = true

        // 绘制背景的画笔
        mBgPaint = Paint()
        mBgPaint.color = mBgColor

        // 绘制电池的画笔
        mBatteryPaint = Paint()
        mBatteryPaint.isAntiAlias = true
        mBatteryPaint.isDither = true

        // 初始化页面样式
        setNightMode(mSettingManager.isNightMode)
    }

    private fun initPageView() {
        Log.d(TAG, "+initPageView")

        // 配置参数
        mPageView!!.setPageMode(mPageMode)
        mPageView!!.setBgColor(mBgColor)
    }

    /**
     * 跳转到上一章
     */
    fun skipPreChapter(): Boolean {
        if (!hasPrevChapter()) {
            return false
        }

        // 载入上一章。
        mCurPage = if (parsePrevChapter()) {
            getCurPage(0)
        } else {
            TxtPage()
        }
        mPageView!!.drawCurPage(false)
        return true
    }

    /**
     * 跳转到下一章
     */
    fun skipNextChapter(): Boolean {
        if (!hasNextChapter()) {
            return false
        }

        // 判断是否达到章节的终止点
        mCurPage = if (parseNextChapter()) {
            getCurPage(0)
        } else {
            TxtPage()
        }
        mPageView!!.drawCurPage(false)
        return true
    }

    /**
     * 跳转到指定章节
     *
     * @param pos:从 0 开始。
     */
    fun skipToChapter(pos: Int) {
        Log.d("+跳转", "skipToChapter$pos")
        // 设置参数
        mCurChapterPos = pos

        // 将上一章的缓存设置为null
        mPrePageList = null
        // 如果当前下一章缓存正在执行，则取消
        mPreLoadJob?.cancel()
        // 将下一章缓存设置为null
        mNextPageList = null

        // 打开指定章节
        Log.e(TAG, "skipToChapter")
        openChapter()
    }

    /**
     * 跳转到指定的页
     */
    fun skipToPage(pos: Int): Boolean {
        Log.d(TAG, "skipToChapter$pos")

        if (!isChapterListPrepare) {
            return false
        }
        mCurPage = getCurPage(pos)
        mPageView!!.drawCurPage(false)
        return true
    }

    /**
     * 翻到上一页
     */
    fun skipToPrePage(): Boolean {
        return mPageView!!.autoPrevPage()
    }

    /**
     * 翻到下一页
     */
    fun skipToNextPage(): Boolean {
        return mPageView!!.autoNextPage()
    }

    /**
     * 更新时间
     */
    fun updateTime() {
        if (!mPageView!!.isRunning()) {
            mPageView!!.drawCurPage(true)
        }
    }

    /**
     * 更新电量
     */
    fun updateBattery(level: Int) {
        mBatteryLevel = level

        if (!mPageView!!.isRunning()) {
            mPageView!!.drawCurPage(true)
        }
    }

    /**
     * 设置提示的文字大小
     *
     * @param textSize:单位为 px。
     */
    fun setTipTextSize(textSize: Int) {
        mTipPaint.textSize = textSize.toFloat()

        // 如果屏幕大小加载完成
        mPageView!!.drawCurPage(false)
    }

    /**
     * 设置文字相关参数
     */
    fun setTextSize(textSize: Int) {
        Log.d(TAG, "setTextSize")

        // 设置文字相关参数
        setUpTextParams(textSize)

        // 设置画笔的字体大小
        mTextPaint.textSize = mTextSize.toFloat()
        // 设置标题的字体大小
        mTitlePaint.textSize = mTitleSize.toFloat()
        // 存储文字大小
        mSettingManager.textSize = mTextSize
        // 取消缓存
        mPrePageList = null
        mNextPageList = null

        // 如果当前已经显示数据
        if (isChapterListPrepare && mStatus == STATUS_FINISH) {
            // 重新计算当前页面
            dealLoadPageList(mCurChapterPos)

            // 防止在最后一页，通过修改字体，以至于页面数减少导致崩溃的问题
            if (mCurPage!!.position >= mCurPageList!!.size) {
                mCurPage!!.position = mCurPageList!!.size - 1
            }

            // 重新获取指定页面
            mCurPage = mCurPageList!![mCurPage!!.position]
        }

        mPageView!!.drawCurPage(false)
    }

    /**
     * 设置夜间模式
     */
    fun setNightMode(nightMode: Boolean) {
        mSettingManager.isNightMode = nightMode
        isNightMode = nightMode

        if (isNightMode) {
            mBatteryPaint.color = Color.WHITE
            setPageStyle(PageStyle.NIGHT)
        } else {
            mBatteryPaint.color = Color.BLACK
            setPageStyle(mPageStyle)
        }
    }

    /**
     * 设置页面样式
     *
     * @param pageStyle:页面样式
     */
    fun setPageStyle(pageStyle: PageStyle) {
        if (pageStyle != PageStyle.NIGHT) {
            mPageStyle = pageStyle
            mSettingManager.pageStyle = pageStyle
        }

        if (isNightMode && pageStyle != PageStyle.NIGHT) {
            return
        }

        // 设置当前颜色样式
        mTextColor = ContextCompat.getColor(mContext, pageStyle.fontColor)
        mBgColor = ContextCompat.getColor(mContext, pageStyle.bgColor)

        mTipPaint.color = mTextColor
        mTitlePaint.color = mTextColor
        mTextPaint.color = mTextColor

        mBgPaint.color = mBgColor

        mPageView!!.drawCurPage(false)
    }

    /**
     * 翻页动画
     *
     * @see PageMode
     */
    fun setPageMode(pageMode: PageMode) {
        mPageMode = pageMode

        mPageView!!.setPageMode(mPageMode)
        mSettingManager.pageMode = mPageMode

        // 重新绘制当前页
        mPageView!!.drawCurPage(false)
    }

    /**
     * 设置内容与屏幕的间距
     *
     * @param marginWidth  :单位为 px
     * @param marginHeight :单位为 px
     */
    fun setMargin(marginWidth: Int, marginHeight: Int) {
        mMarginWidth = marginWidth
        mMarginTop = marginHeight
        mMarginBottom = marginHeight

        // 如果是滑动动画，则需要重新创建了
        if (mPageMode == PageMode.SCROLL) {
            mPageView!!.setPageMode(PageMode.SCROLL)
        }

        mPageView!!.drawCurPage(false)
    }

    /**
     * 设置页面切换监听
     */
    fun setOnPageChangeListener(listener: OnPageChangeListener?) {
        Log.d(TAG, "setOnPageChangeListener")

        mPageChangeListener = listener

        // 如果目录加载完之后才设置监听器，那么会默认回调
        if (isChapterListPrepare) {
            mPageChangeListener!!.onCategoryFinish(mChapterList)
        }
    }

    /**
     * 保存阅读记录
     */
    open fun saveRecord() {
        if (mChapterList.isEmpty()) {
            return
        }

        mBookRecord.bookId = mCollBook.get_id()
        mBookRecord.chapter = mCurChapterPos

        if (mCurPage != null) {
            mBookRecord.pagePos = mCurPage!!.position
        } else {
            mBookRecord.pagePos = 0
        }

        // 存储到数据库
        BookRepository.getInstance().saveBookRecord(mBookRecord)
    }

    protected fun clampCurrentChapterToAvailableCatalog(): Boolean {
        if (mChapterList.isEmpty()) {
            return false
        }
        val clampedChapter = mCurChapterPos.coerceIn(0, mChapterList.lastIndex)
        if (clampedChapter == mCurChapterPos) {
            return false
        }
        Log.w(
            TAG,
            "operation=chapterPositionClamped from=$mCurChapterPos to=$clampedChapter " +
                "chapterCount=${mChapterList.size} book=${mCollBook.title}"
        )
        mCurChapterPos = clampedChapter
        mLastChapterPos = clampedChapter
        mBookRecord.bookId = mCollBook.get_id()
        mBookRecord.chapter = clampedChapter
        mBookRecord.pagePos = 0
        mPrePageList = null
        mCurPageList = null
        mNextPageList = null
        mCurPage = null
        BookRepository.getInstance().saveBookRecord(mBookRecord)
        return true
    }

    /**
     * 初始化书籍
     */
    private fun prepareBook() {
        Log.d(TAG, "prepareBook")

        var bookRecord = BookRepository.getInstance().getBookRecord(mCollBook.get_id())

        if (bookRecord == null) {
            bookRecord = BookRecordBean()
        }
        mBookRecord = bookRecord

        mCurChapterPos = mBookRecord.chapter
        mLastChapterPos = mCurChapterPos
    }

    /**
     * 打开指定章节
     */
    @Synchronized
    fun openChapter() {
        Log.e(TAG, "+openChapter")
        val startedAt = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_read_page_open_started",
            collBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapterPos" to mCurChapterPos,
                "current" to currentChapterTitle.orEmpty(),
                "catalogReady" to isChapterListPrepare,
                "chapterCount" to mChapterList.size,
                "status" to mStatus
            )
        )
        isFirstOpen = false

        if (!mPageView!!.isPrepare()) {
            AiBridgeTrace.event(
                "source_read_page_open_skipped",
                collBook.title.orEmpty(),
                AiBridgeTrace.fields("reason" to "page_not_prepare", "durationMs" to (System.currentTimeMillis() - startedAt))
            )
            return
        }

        // 如果章节目录没有准备好
        if (!isChapterListPrepare) {
            mStatus = STATUS_LOADING
            mPageView!!.drawCurPage(false)
            Log.e("+章节目录没有准备好", "parseCurChapter")
            AiBridgeTrace.state(
                "source_read_page_status",
                collBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "status" to "loading",
                    "reason" to "catalog_not_ready",
                    "chapterPos" to mCurChapterPos,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return
        }

        // 如果获取到的章节目录为空
        if (mChapterList.isEmpty()) {
            mStatus = STATUS_CATEGORY_EMPTY
            mPageView!!.drawCurPage(false)
            isInit = true
            Log.e("+如果获取到的章节目录为空", "parseCurChapter")
            AiBridgeTrace.state(
                "source_read_page_status",
                collBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "status" to "category_empty",
                    "reason" to "empty_catalog",
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )

            return
        }
        Log.e("+打开章节调用前", "parseCurChapter")
        clampCurrentChapterToAvailableCatalog()

        val parsed = parseCurChapter()
        if (parsed) {
            Log.e("+打开章节", "openChapter")

            // 如果章节从未打开
            if (!isChapterOpen) {
                var position = mBookRecord.pagePos

                // 防止记录页的页号，大于当前最大页号
                if (position >= mCurPageList!!.size) {
                    position = mCurPageList!!.size - 1
                }
                mCurPage = getCurPage(position)
                mCancelPage = mCurPage
                // 切换状态
                isChapterOpen = true
            } else {
                mCurPage = getCurPage(0)
            }
        } else {
            mCurPage = TxtPage()
        }

        mPageView!!.drawCurPage(false)
        AiBridgeTrace.state(
            "source_read_page_open_finished",
            collBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "parsed" to parsed,
                "status" to mStatus,
                "chapterPos" to mCurChapterPos,
                "current" to currentChapterTitle.orEmpty(),
                "pages" to (mCurPageList?.size ?: 0),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        Log.e("+绘制页面", "drawCurPage")
    }

    fun chapterError() {
        // 加载错误
        mStatus = STATUS_ERROR
        AiBridgeTrace.state(
            "source_read_page_status",
            collBook.title.orEmpty(),
            "status_error_chapter_${currentChapterTitle.orEmpty().replace(Regex("""[\s=:/\\#]+"""), "_").take(100)}"
        )
        mPageView!!.drawCurPage(false)
    }

    /**
     * 关闭书本
     */
    open fun closeBook() {
        isChapterListPrepare = false
        isClose = true

        mPreLoadJob?.cancel()
        loaderScope.cancel()

        clearList(mChapterList)
        clearList(mCurPageList)
        clearList(mNextPageList)

        mChapterList = ArrayList(0)
        mCurPageList = null
        mNextPageList = null
        mPageView = null
        mCurPage = null
    }

    private fun clearList(list: MutableList<*>?) {
        list?.clear()
    }

    fun isClose(): Boolean {
        return isClose
    }

    fun isChapterOpen(): Boolean {
        return isChapterOpen
    }

    /**
     * 加载页面列表
     *
     * @param chapterPos:章节序号
     */
    @Throws(Exception::class)
    fun loadPageList(chapterPos: Int): MutableList<TxtPage>? {
        Log.d(TAG, "loadPageList")
        val startedAt = System.currentTimeMillis()

        // 获取章节
        val chapter = mChapterList[chapterPos]
        // 判断章节是否存在
        if (!hasChapterData(chapter)) {
            Log.d(TAG, "chapterNull")
            AiBridgeTrace.event(
                "source_read_page_cache_miss",
                collBook.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapterPos" to chapterPos,
                    "chapter" to chapter.getTitle().orEmpty(),
                    "status" to mStatus,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            return null
        }
        // 获取章节的文本流
        val reader = getChapterReader(chapter)
        val pages = loadPages(chapter, reader)
        AiBridgeTrace.event(
            "source_read_page_cache_hit",
            collBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapterPos" to chapterPos,
                "chapter" to chapter.getTitle().orEmpty(),
                "pages" to pages.size,
                "reader" to (reader != null),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )
        return pages
    }

    /**
     * 刷新章节列表
     */
    abstract fun refreshChapterList()

    /**
     * 获取章节的文本流
     */
    @Throws(Exception::class)
    protected abstract fun getChapterReader(chapter: TxtChapter): BufferedReader?

    /**
     * 章节数据是否存在
     */
    protected abstract fun hasChapterData(chapter: TxtChapter): Boolean

    fun drawPage(bitmap: Bitmap?, isUpdate: Boolean) {
        drawBackground(mPageView!!.getBgBitmap(), isUpdate)
        if (!isUpdate) {
            drawContent(bitmap)
        }
        // 更新绘制
        mPageView!!.invalidate()
    }

    private fun drawBackground(bitmap: Bitmap?, isUpdate: Boolean) {
        Log.d(TAG, "drawBackground")

        val canvas = Canvas(bitmap!!)
        val tipMarginHeight = ScreenUtils.dpToPx(3)
        if (!isUpdate) {
            canvas.drawColor(mBgColor)

            if (mChapterList.isNotEmpty()) {
                val y = mDisplayHeight - mTipPaint.fontMetrics.bottom - tipMarginHeight
                var percent = ""
                // 只有finish的时候采用页码
                if (mStatus == STATUS_FINISH) {
                    percent = (mCurPage!!.position + 1).toString() + "/" + mCurPageList!!.size
                    canvas.drawText(percent, mMarginWidth.toFloat(), y, mTipPaint)
                }
                val tipTop = tipMarginHeight - mTipPaint.fontMetrics.top
                if (mStatus != STATUS_FINISH) {
                    if (isChapterListPrepare) {
                        if (mCurChapterPos < mChapterList.size && mChapterList[mCurChapterPos].getTitle() != null) {
                            canvas.drawText(
                                mChapterList[mCurChapterPos].getTitle()!!,
                                mMarginWidth.toFloat(),
                                tipTop,
                                mTipPaint
                            )
                        }
                    }
                } else {
                    Log.d("+绘制标题", "" + mCurPage!!.title + "" + mTipPaint)

                    val percentTextWidth = mTipPaint.measureText(percent)
                    drawFooterChapterTitle(
                        canvas,
                        mCurPage!!.title!!,
                        mMarginWidth + percentTextWidth + mMarginWidth,
                        y,
                        footerTitleRightLimit()
                    )
                }
            }
        } else {
            // 擦除区域
            mBgPaint.color = mBgColor
            canvas.drawRect(
                (mDisplayWidth / 2).toFloat(),
                (mDisplayHeight - mFooterAreaHeight + ScreenUtils.dpToPx(2)).toFloat(),
                mDisplayWidth.toFloat(),
                mDisplayHeight.toFloat(),
                mBgPaint
            )
        }

        val visibleRight = mDisplayWidth - mMarginWidth
        val visibleBottom = mDisplayHeight - tipMarginHeight

        val outFrameWidth = mTipPaint.measureText("xxx").toInt()
        val outFrameHeight = mTipPaint.textSize.toInt()

        val polarHeight = ScreenUtils.dpToPx(6)
        val polarWidth = ScreenUtils.dpToPx(2)
        val border = 1
        val innerMargin = 1

        // 电极的制作
        val polarLeft = visibleRight - polarWidth
        val polarTop = visibleBottom - (outFrameHeight + polarHeight) / 2
        val polar = Rect(polarLeft, polarTop, visibleRight, polarTop + polarHeight - ScreenUtils.dpToPx(2))

        mBatteryPaint.style = Paint.Style.FILL
        canvas.drawRect(polar, mBatteryPaint)

        // 外框的制作
        val outFrameLeft = polarLeft - outFrameWidth
        val outFrameTop = visibleBottom - outFrameHeight
        val outFrameBottom = visibleBottom - ScreenUtils.dpToPx(2)
        val outFrame = Rect(outFrameLeft, outFrameTop, polarLeft, outFrameBottom)

        mBatteryPaint.style = Paint.Style.STROKE
        mBatteryPaint.strokeWidth = border.toFloat()
        canvas.drawRect(outFrame, mBatteryPaint)

        // 内框的制作
        val innerWidth = (outFrame.width() - innerMargin * 2 - border) * (mBatteryLevel / 100.0f)
        val innerFrame = RectF(
            (outFrameLeft + border + innerMargin).toFloat(),
            (outFrameTop + border + innerMargin).toFloat(),
            outFrameLeft + border + innerMargin + innerWidth,
            (outFrameBottom - border - innerMargin).toFloat()
        )

        mBatteryPaint.style = Paint.Style.FILL
        canvas.drawRect(innerFrame, mBatteryPaint)

        val y = mDisplayHeight - mTipPaint.fontMetrics.bottom - tipMarginHeight
        val time = StringUtils.dateConvert(System.currentTimeMillis(), Constant.FORMAT_TIME)
        val x = outFrameLeft - mTipPaint.measureText(time) - ScreenUtils.dpToPx(4)
        canvas.drawText(time, x, y, mTipPaint)
    }

    private fun drawFooterChapterTitle(canvas: Canvas, title: String, x: Float, y: Float, rightLimit: Float) {
        val chapter = mChapterList.getOrNull(mCurChapterPos) ?: return
        val showBadge = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled() &&
            showWrongChapters &&
            chapter.hasHiddenSourceIntegrityMark()
        val badgeGap = if (showBadge) ScreenUtils.dpToPx(FOOTER_BADGE_GAP_DP).toFloat() else 0f
        val badgeWidth = if (showBadge) footerIntegrityBadgeWidth() else 0f
        val displayTitle = fitFooterTitle(title, rightLimit - x - badgeGap - badgeWidth)
        canvas.drawText(displayTitle, x, y, mTipPaint)
        if (!showBadge) {
            return
        }
        drawFooterIntegrityBadge(canvas, x + mTipPaint.measureText(displayTitle) + badgeGap, y)
    }

    private fun footerTitleRightLimit(): Float {
        val visibleRight = mDisplayWidth - mMarginWidth
        val outFrameWidth = mTipPaint.measureText("xxx")
        val polarWidth = ScreenUtils.dpToPx(2)
        val outFrameLeft = visibleRight - polarWidth - outFrameWidth
        val time = StringUtils.dateConvert(System.currentTimeMillis(), Constant.FORMAT_TIME)
        return outFrameLeft - mTipPaint.measureText(time) - ScreenUtils.dpToPx(12)
    }

    private fun fitFooterTitle(title: String, maxWidth: Float): String {
        if (maxWidth <= 0f) {
            return ""
        }
        if (mTipPaint.measureText(title) <= maxWidth) {
            return title
        }
        val ellipsisWidth = mTipPaint.measureText(FOOTER_TITLE_ELLIPSIS)
        val textWidth = maxWidth - ellipsisWidth
        if (textWidth <= 0f) {
            return FOOTER_TITLE_ELLIPSIS
        }
        val count = mTipPaint.breakText(title, true, textWidth, null)
        return title.substring(0, count) + FOOTER_TITLE_ELLIPSIS
    }

    private fun footerIntegrityBadgeWidth(): Float {
        val oldTextSize = mTipPaint.textSize
        val oldFakeBold = mTipPaint.isFakeBoldText
        mTipPaint.textSize = oldTextSize * FOOTER_BADGE_TEXT_SCALE
        mTipPaint.isFakeBoldText = true
        val horizontalPadding = ScreenUtils.dpToPx(FOOTER_BADGE_HORIZONTAL_PADDING_DP).toFloat()
        val width = mTipPaint.measureText(FOOTER_BADGE_TEXT) + horizontalPadding * 2
        mTipPaint.textSize = oldTextSize
        mTipPaint.isFakeBoldText = oldFakeBold
        return width
    }

    private fun drawFooterIntegrityBadge(canvas: Canvas, x: Float, y: Float) {
        val oldTextColor = mTipPaint.color
        val oldTextSize = mTipPaint.textSize
        val oldFakeBold = mTipPaint.isFakeBoldText
        val oldBgColor = mBgPaint.color
        val oldBgStyle = mBgPaint.style

        val normalMetrics = mTipPaint.fontMetrics
        mTipPaint.textSize = oldTextSize * FOOTER_BADGE_TEXT_SCALE
        mTipPaint.isFakeBoldText = true
        val badgeMetrics = mTipPaint.fontMetrics
        val horizontalPadding = ScreenUtils.dpToPx(FOOTER_BADGE_HORIZONTAL_PADDING_DP).toFloat()
        val verticalPadding = ScreenUtils.dpToPx(FOOTER_BADGE_VERTICAL_PADDING_DP).toFloat()
        val badgeWidth = mTipPaint.measureText(FOOTER_BADGE_TEXT) + horizontalPadding * 2
        val badgeHeight = max(
            ScreenUtils.dpToPx(FOOTER_BADGE_MIN_HEIGHT_DP).toFloat(),
            badgeMetrics.descent - badgeMetrics.ascent + verticalPadding * 2
        )
        val centerY = y + (normalMetrics.ascent + normalMetrics.descent) / 2
        val badgeRect = RectF(x, centerY - badgeHeight / 2, x + badgeWidth, centerY + badgeHeight / 2)

        mBgPaint.style = Paint.Style.FILL
        mBgPaint.color = ContextCompat.getColor(mContext, R.color.chapter_mark_wrong_bg)
        val cornerRadius = badgeHeight / 2
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, mBgPaint)

        mTipPaint.color = ContextCompat.getColor(mContext, R.color.chapter_mark_wrong_text)
        val badgeBaseline = badgeRect.centerY() - (badgeMetrics.ascent + badgeMetrics.descent) / 2
        canvas.drawText(FOOTER_BADGE_TEXT, x + horizontalPadding, badgeBaseline, mTipPaint)

        mTipPaint.color = oldTextColor
        mTipPaint.textSize = oldTextSize
        mTipPaint.isFakeBoldText = oldFakeBold
        mBgPaint.color = oldBgColor
        mBgPaint.style = oldBgStyle
    }

    private fun drawContent(bitmap: Bitmap?) {
        Log.d(TAG, "drawContent")

        val canvas = Canvas(bitmap!!)

        if (mPageMode == PageMode.SCROLL) {
            canvas.drawColor(mBgColor)
        }

        if (mStatus != STATUS_FINISH) {
            val tip = when (mStatus) {
                STATUS_LOADING -> "正在拼命加载中..."
                STATUS_ERROR -> "加载失败(点击边缘重试)"
                STATUS_EMPTY -> "文章内容为空"
                STATUS_PARING -> "正在排版请等待..."
                STATUS_PARSE_ERROR -> "文件解析错误"
                STATUS_CATEGORY_EMPTY -> "目录列表为空"
                else -> ""
            }

            val fontMetrics = mTextPaint.fontMetrics
            val textHeight = fontMetrics.top - fontMetrics.bottom
            val textWidth = mTextPaint.measureText(tip)
            val pivotX = (mDisplayWidth - textWidth) / 2
            val pivotY = (mDisplayHeight - textHeight) / 2
            canvas.drawText(tip, pivotX, pivotY, mTextPaint)
        } else {
            var top = if (mPageMode == PageMode.SCROLL) {
                -mTextPaint.fontMetrics.top
            } else {
                mMarginTop - mTextPaint.fontMetrics.top
            }

            val interval = mTextInterval + mTextPaint.textSize.toInt()
            val para = mTextPara + mTextPaint.textSize.toInt()
            val titleInterval = mTitleInterval + mTitlePaint.textSize.toInt()
            val titlePara = mTitlePara + mTextPaint.textSize.toInt()
            var str: String

            // 对标题进行绘制
            for (i in 0 until mCurPage!!.titleLines) {
                str = mCurPage!!.lines!![i]

                if (i == 0) {
                    top += mTitlePara
                }

                val start = ((mDisplayWidth - mTitlePaint.measureText(str)) / 2).toInt()
                canvas.drawText(str, start.toFloat(), top, mTitlePaint)
                top += if (i == mCurPage!!.titleLines - 1) {
                    titlePara
                } else {
                    titleInterval
                }
            }

            // 对内容进行绘制
            for (i in mCurPage!!.titleLines until mCurPage!!.lines!!.size) {
                str = mCurPage!!.lines!![i]
                Log.d(TAG + "+绘制的文本", str + "   " + mMarginWidth + "  " + top)
                canvas.drawText(str.replace("\n", ""), mMarginWidth.toFloat(), top, mTextPaint)
                top += if (str.endsWith("\n")) {
                    para
                } else {
                    interval
                }
            }
        }
    }

    fun prepareDisplay(w: Int, h: Int) {
        Log.d(TAG, "prepareDisplay")

        // 获取PageView的宽高
        mDisplayWidth = w
        mDisplayHeight = h

        // 获取内容显示位置的大小
        mVisibleWidth = mDisplayWidth - mMarginWidth * 2
        mVisibleHeight = calculateVisibleContentHeight(mDisplayHeight, mMarginTop, mMarginBottom)

        // 重置 PageMode
        mPageView!!.setPageMode(mPageMode)

        if (!isChapterOpen) {
            if (isChapterListPrepare) {
                openChapter()
                return
            }
            // 展示加载界面
            mPageView!!.drawCurPage(false)
            if (!isFirstOpen) {
                Log.e(TAG, "+prepareDisplay")
            }
        } else {
            // 如果章节已显示，那么就重新计算页面
            if (mStatus == STATUS_FINISH) {
                isInit = true
                dealLoadPageList(mCurChapterPos)
                // 重新设置文章指针的位置
                mCurPage = getCurPage(mCurPage!!.position)
            }
            mPageView!!.drawCurPage(false)
        }
    }

    /**
     * 翻阅上一页
     */
    fun prev(): Boolean {
        if (retryCurrentChapter()) {
            return false
        }
        if (!canTurnPage()) {
            return false
        }

        if (mStatus == STATUS_FINISH) {
            val prevPage = getPrevPage()
            if (prevPage != null) {
                mCancelPage = mCurPage
                mCurPage = prevPage
                mPageView!!.drawNextPage()
                return true
            }
        }

        if (!hasPrevChapter()) {
            return false
        }

        mCancelPage = mCurPage
        mCurPage = if (parsePrevChapter()) {
            getPrevLastPage()
        } else {
            TxtPage()
        }
        mPageView!!.drawNextPage()
        return true
    }

    /**
     * 解析上一章数据
     *
     * @return:数据是否解析成功
     */
    open fun parsePrevChapter(): Boolean {
        // 加载上一章数据
        val prevChapter = mCurChapterPos - 1

        mLastChapterPos = mCurChapterPos
        mCurChapterPos = prevChapter

        // 当前章缓存为下一章
        mNextPageList = mCurPageList

        // 判断是否具有上一章缓存
        if (mPrePageList != null) {
            mCurPageList = mPrePageList
            mPrePageList = null

            // 回调
            chapterChangeCallback()
        } else {
            dealLoadPageList(prevChapter)
        }
        return mCurPageList != null
    }

    private fun hasPrevChapter(): Boolean {
        if (mCurChapterPos - 1 < 0) {
            return false
        }
        return true
    }

    /**
     * 翻到下一页
     */
    fun next(): Boolean {
        if (retryCurrentChapter()) {
            return false
        }
        if (!canTurnPage()) {
            return false
        }

        if (mStatus == STATUS_FINISH) {
            val nextPage = getNextPage()
            if (nextPage != null) {
                mCancelPage = mCurPage
                mCurPage = nextPage
                mPageView!!.drawNextPage()
                return true
            }
        }

        if (!hasNextChapter()) {
            onReadableEndReached()
            return false
        }

        mCancelPage = mCurPage
        mCurPage = if (parseNextChapter()) {
            mCurPageList!![0]
        } else {
            TxtPage()
        }
        mPageView!!.drawNextPage()
        return true
    }

    private fun hasNextChapter(): Boolean {
        if (mCurChapterPos + 1 >= mChapterList.size) {
            return false
        }
        return true
    }

    protected open fun onReadableEndReached() {
    }

    @Synchronized
    open fun parseCurChapter(): Boolean {
        Log.e(TAG, "parseCurChapter")

        // 解析数据
        dealLoadPageList(mCurChapterPos)
        // 预加载下一页面
        preLoadNextChapter()

        return mCurPageList != null
    }

    /**
     * 解析下一章数据
     *
     * @return:返回解析成功还是失败
     */
    open fun parseNextChapter(): Boolean {
        val nextChapter = mCurChapterPos + 1

        mLastChapterPos = mCurChapterPos
        mCurChapterPos = nextChapter

        // 将当前章的页面列表，作为上一章缓存
        mPrePageList = mCurPageList

        // 是否下一章数据已经预加载了
        if (mNextPageList != null) {
            mCurPageList = mNextPageList
            mNextPageList = null
            // 回调
            chapterChangeCallback()
        } else {
            // 处理页面解析
            dealLoadPageList(nextChapter)
        }
        // 预加载下一页面
        preLoadNextChapter()
        return mCurPageList != null
    }

    private fun dealLoadPageList(chapterPos: Int) {
        val startedAt = System.currentTimeMillis()
        var outcome = "unknown"
        try {
            mCurPageList = loadPageList(chapterPos)
            if (mCurPageList != null) {
                if (mCurPageList!!.isEmpty()) {
                    mStatus = STATUS_EMPTY
                    outcome = "empty"

                    // 添加一个空数据
                    val page = TxtPage()
                    page.lines = ArrayList(1)
                    mCurPageList!!.add(page)
                } else {
                    Log.e("+完成", "STATUS_FINISH")
                    mStatus = STATUS_FINISH
                    outcome = "finish"
                    isInit = true
                }
            } else {
                mStatus = STATUS_LOADING
                outcome = "loading"
                Log.e("+加载中", "STATUS_LOADING")
            }
        } catch (e: Exception) {
            e.printStackTrace()

            mCurPageList = null
            mStatus = STATUS_ERROR
            outcome = "error_${e.javaClass.simpleName}"
        }
        AiBridgeTrace.state(
            "source_read_page_parse_result",
            collBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "outcome" to outcome,
                "status" to mStatus,
                "chapterPos" to chapterPos,
                "chapter" to mChapterList.getOrNull(chapterPos)?.getTitle().orEmpty(),
                "pages" to (mCurPageList?.size ?: 0),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        )

        // 回调
        Log.e("+回调", "STATUS_FINISH")
        chapterChangeCallback()
    }

    private fun chapterChangeCallback() {
        if (mPageChangeListener != null) {
            Log.d("+回调", "chapterChangeCallback")
            mPageChangeListener!!.onChapterChange(mCurChapterPos)
            mPageChangeListener!!.onPageCountChange(if (mCurPageList != null) mCurPageList!!.size else 0)
        }
    }

    // 预加载下一章
    @Synchronized
    private fun preLoadNextChapter() {
        Log.d("+加载下一章", "preLoadNextChapter")
        val nextChapter = mCurChapterPos + 1

        // 如果不存在下一章，且下一章没有数据，则不进行加载。
        if (!hasNextChapter() || !hasChapterData(mChapterList[nextChapter])) {
            return
        }

        // 如果之前正在加载则取消
        mPreLoadJob?.cancel()

        // 调用异步进行预加载加载
        mPreLoadJob = loaderScope.launch {
            try {
                mNextPageList = withContext(Dispatchers.IO) {
                    loadPageList(nextChapter)!!
                }
            } catch (e: Throwable) {
                // 无视错误
            }
        }
    }

    // 取消翻页
    fun pageCancel() {
        if (mCurPage!!.position == 0 && mCurChapterPos > mLastChapterPos) {
            if (mPrePageList != null) {
                cancelNextChapter()
            } else {
                mCurPage = if (parsePrevChapter()) {
                    getPrevLastPage()
                } else {
                    TxtPage()
                }
            }
        } else if (
            mCurPageList == null ||
            mCurPage!!.position == mCurPageList!!.size - 1 && mCurChapterPos < mLastChapterPos
        ) {
            if (mNextPageList != null) {
                cancelPreChapter()
            } else {
                mCurPage = if (parseNextChapter()) {
                    mCurPageList!![0]
                } else {
                    TxtPage()
                }
            }
        } else {
            // 假设加载到下一页，又取消了。那么需要重新装载。
            mCurPage = mCancelPage
        }
    }

    private fun cancelNextChapter() {
        val temp = mLastChapterPos
        mLastChapterPos = mCurChapterPos
        mCurChapterPos = temp

        mNextPageList = mCurPageList
        mCurPageList = mPrePageList
        mPrePageList = null

        chapterChangeCallback()

        mCurPage = getPrevLastPage()
        mCancelPage = null
    }

    private fun cancelPreChapter() {
        val temp = mLastChapterPos
        mLastChapterPos = mCurChapterPos
        mCurChapterPos = temp
        // 重置页面列表
        mPrePageList = mCurPageList
        mCurPageList = mNextPageList
        mNextPageList = null

        chapterChangeCallback()

        mCurPage = getCurPage(0)
        mCancelPage = null
    }

    /**
     * 将章节数据，解析成页面列表
     *
     * @param chapter：章节信息
     * @param br：章节的文本流
     */
    private fun loadPages(chapter: TxtChapter, br: BufferedReader?): MutableList<TxtPage> {
        Log.d(TAG, "+loadPages")

        // 生成的页面
        val pages = ArrayList<TxtPage>()
        // 使用流的方式加载
        val lines = ArrayList<String>()
        var rHeight = mVisibleHeight
        var titleLinesCount = 0
        var showTitle = true
        var paragraph: String? = chapter.getTitle()
        try {
            while (showTitle || br!!.readLine().also { paragraph = it } != null) {
                if (!showTitle) {
                    paragraph = paragraph!!.replace(Regex("\\s"), "")
                    if (paragraph == "") continue
                    paragraph = StringUtils.halfToFull("  " + paragraph + "\n")
                } else {
                    // 设置 title 的顶部间距
                    rHeight -= mTitlePara
                }
                var currentParagraph = paragraph!!
                while (currentParagraph.isNotEmpty()) {
                    // 当前空间，是否容得下一行文字
                    rHeight -= if (showTitle) {
                        mTitlePaint.textSize.toInt()
                    } else {
                        mTextPaint.textSize.toInt()
                    }
                    // 一页已经填充满了，创建 TextPage
                    if (rHeight <= 0) {
                        val page = TxtPage()
                        page.position = pages.size
                        page.title = chapter.getTitle()
                        page.lines = ArrayList(lines)
                        page.titleLines = titleLinesCount
                        pages.add(page)
                        lines.clear()
                        rHeight = mVisibleHeight
                        titleLinesCount = 0

                        continue
                    }

                    val wordCount = if (showTitle) {
                        mTitlePaint.breakText(currentParagraph, true, mVisibleWidth.toFloat(), null)
                    } else {
                        mTextPaint.breakText(currentParagraph, true, mVisibleWidth.toFloat(), null)
                    }

                    val subStr = currentParagraph.substring(0, wordCount)
                    if (subStr != "\n") {
                        lines.add(subStr)

                        if (showTitle) {
                            titleLinesCount += 1
                            rHeight -= mTitleInterval
                        } else {
                            rHeight -= mTextInterval
                        }
                    }
                    // 裁剪
                    currentParagraph = currentParagraph.substring(wordCount)
                    paragraph = currentParagraph
                }

                // 增加段落的间距
                if (!showTitle && lines.size != 0) {
                    rHeight = rHeight - mTextPara + mTextInterval
                }

                if (showTitle) {
                    rHeight = rHeight - mTitlePara + mTitleInterval
                    showTitle = false
                }
            }

            if (lines.size != 0) {
                val page = TxtPage()
                page.position = pages.size
                page.title = chapter.getTitle()
                page.lines = ArrayList(lines)
                page.titleLines = titleLinesCount
                pages.add(page)
                lines.clear()
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            IOUtils.close(br)
        }
        return pages
    }

    /**
     * @return:获取初始显示的页面
     */
    private fun getCurPage(pos: Int): TxtPage? {
        if (mPageChangeListener != null) {
            mPageChangeListener!!.onPageChange(pos)
        }
        if (mCurPageList == null) {
            return null
        }
        if (pos == mCurPageList!!.size) {
            return mCurPageList!![pos - 1]
        }
        return mCurPageList!![pos]
    }

    /**
     * @return:获取上一个页面
     */
    private fun getPrevPage(): TxtPage? {
        val pos = mCurPage!!.position - 1
        if (pos < 0) {
            return null
        }
        if (mPageChangeListener != null) {
            mPageChangeListener!!.onPageChange(pos)
        }
        return mCurPageList!![pos]
    }

    /**
     * @return:获取下一的页面
     */
    private fun getNextPage(): TxtPage? {
        val pos = mCurPage!!.position + 1
        if (pos >= mCurPageList!!.size) {
            return null
        }
        if (mPageChangeListener != null) {
            mPageChangeListener!!.onPageChange(pos)
        }
        return mCurPageList!![pos]
    }

    /**
     * @return:获取上一个章节的最后一页
     */
    private fun getPrevLastPage(): TxtPage {
        val pos = mCurPageList!!.size - 1

        if (mPageChangeListener != null) {
            mPageChangeListener!!.onPageChange(pos)
        }

        return mCurPageList!![pos]
    }

    /**
     * 根据当前状态，决定是否能够翻页
     */
    private fun canTurnPage(): Boolean {
        if (!isChapterListPrepare) {
            return false
        }

        if (mStatus == STATUS_PARSE_ERROR || mStatus == STATUS_PARING) {
            return false
        }
        return true
    }

    private fun retryCurrentChapter(): Boolean {
        if (mStatus != STATUS_ERROR) {
            return false
        }
        AiBridgeTrace.event(
            "source_read_page_retry",
            collBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapterPos" to mCurChapterPos,
                "chapter" to currentChapterTitle.orEmpty()
            )
        )
        mStatus = STATUS_LOADING
        openChapter()
        return true
    }

    interface OnPageChangeListener {
        /**
         * 作用：章节切换的时候进行回调
         *
         * @param pos:切换章节的序号
         */
        fun onChapterChange(pos: Int)

        /**
         * 作用：请求加载章节内容
         *
         * @param requestChapters:需要下载的章节列表
         */
        fun requestChapters(requestChapters: List<TxtChapter>)

        /**
         * 作用：章节目录加载完成时候回调
         *
         * @param chapters：返回章节目录
         */
        fun onCategoryFinish(chapters: List<TxtChapter>)

        /**
         * 作用：章节页码数量改变之后的回调。==> 字体大小的调整，或者是否关闭虚拟按钮功能都会改变页面的数量。
         *
         * @param count:页面的数量
         */
        fun onPageCountChange(count: Int)

        /**
         * 作用：当页面改变的时候回调
         *
         * @param pos:当前的页面的序号
         */
        fun onPageChange(pos: Int)
    }

    companion object {
        private const val TAG = "PageLoader"

        // 当前页面的状态
        const val STATUS_LOADING = 1
        const val STATUS_FINISH = 2
        const val STATUS_ERROR = 3
        const val STATUS_EMPTY = 4
        const val STATUS_PARING = 5
        const val STATUS_PARSE_ERROR = 6
        const val STATUS_CATEGORY_EMPTY = 7

        private const val DEFAULT_CONTENT_PADDING_VERTICAL = 8
        private const val DEFAULT_FOOTER_AREA_HEIGHT = 28
        private const val DEFAULT_MARGIN_WIDTH = 15
        private const val DEFAULT_TIP_SIZE = 12
        private const val EXTRA_TITLE_SIZE = 4
        private const val FOOTER_TITLE_ELLIPSIS = "…"
        private const val FOOTER_BADGE_TEXT = "错章"
        private const val FOOTER_BADGE_TEXT_SCALE = 0.62f
        private const val FOOTER_BADGE_GAP_DP = 2
        private const val FOOTER_BADGE_HORIZONTAL_PADDING_DP = 3
        private const val FOOTER_BADGE_VERTICAL_PADDING_DP = 1
        private const val FOOTER_BADGE_MIN_HEIGHT_DP = 12

        @JvmStatic
        fun calculateProgressTenths(
            chapterCount: Int,
            chapterIndex: Int,
            pageIndex: Int,
            pageCount: Int
        ): Int {
            val safeChapterCount = max(1, chapterCount)
            val safeChapterIndex = max(0, min(chapterIndex, safeChapterCount - 1))
            val safePageCount = max(0, pageCount)
            val safePageIndex = max(0, pageIndex)

            if (
                safeChapterIndex >= safeChapterCount - 1 &&
                (safePageCount <= 0 || safePageIndex >= max(0, safePageCount - 2))
            ) {
                return 999
            }

            val pageFraction = if (safePageCount > 0) {
                min(safePageIndex + 1, safePageCount) / safePageCount.toFloat()
            } else {
                if (safePageIndex > 0) 0.01f else 0f
            }
            val tenths = (((safeChapterIndex + pageFraction) / safeChapterCount) * 1000f).roundToInt()
            return max(1, min(tenths, 998))
        }

        @JvmStatic
        fun calculateContentTopMargin(statusBarHeightPx: Int, contentPaddingPx: Int): Int {
            return max(0, statusBarHeightPx) + max(0, contentPaddingPx)
        }

        @JvmStatic
        fun calculateContentBottomMargin(contentPaddingPx: Int): Int {
            return max(0, contentPaddingPx)
        }

        @JvmStatic
        fun calculateVisibleContentHeight(displayHeightPx: Int, topMarginPx: Int, bottomMarginPx: Int): Int {
            return max(0, displayHeightPx - max(0, topMarginPx) - max(0, bottomMarginPx))
        }
    }
}
