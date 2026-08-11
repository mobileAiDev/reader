package com.ldp.reader.widget.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.widget.animation.CoverPageAnim
import com.ldp.reader.widget.animation.HorizonPageAnim
import com.ldp.reader.widget.animation.NonePageAnim
import com.ldp.reader.widget.animation.PageAnimation
import com.ldp.reader.widget.animation.ScrollPageAnim
import com.ldp.reader.widget.animation.SimulationPageAnim
import com.ldp.reader.widget.animation.SlidePageAnim

/**
 * Created by Administrator on 2016/8/29 0029.
 * 原作者的GitHub Project Path:(https://github.com/PeachBlossom/treader)
 * 绘制页面显示内容的类
 */
class PageView : View {
    private var mViewWidth = 0 // 当前View的宽
    private var mViewHeight = 0 // 当前View的高

    private var mStartX = 0
    private var mStartY = 0
    private var isMove = false

    // 初始化参数
    private var mBgColor = -0x313d64
    private var mPageMode = PageMode.SIMULATION

    // 是否允许点击
    private var canTouch = true

    // 唤醒菜单的区域
    private var mCenterRect: RectF? = null
    private var prepared = false

    // 动画类
    private var mPageAnim: PageAnimation? = null

    // 动画监听类
    private val mPageAnimListener = object : PageAnimation.OnPageChangeListener {
        override fun hasPrev(): Boolean {
            return this@PageView.hasPrevPage()
        }

        override fun hasNext(): Boolean {
            return this@PageView.hasNextPage()
        }

        override fun pageCancel() {
            this@PageView.pageCancel()
        }
    }

    // 点击监听
    private var mTouchListener: TouchListener? = null

    // 内容加载器
    private var mPageLoader: PageLoader? = null
    private var readerSafeInsets = ReaderPageSafeInsets.NONE
    private var readerSafeInsetBindingEnabled = false
    private var readerSafeInsetsResolved = false

    private var accessibilitySnapshot = ReaderPageSemanticSnapshot.EMPTY
    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            node.isClickable = false
            node.isFocusable = false
            node.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            return accessibilitySnapshot.nodes.firstOrNull { node ->
                virtualBounds(node.virtualId).contains(x.toInt(), y.toInt())
            }?.virtualId ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            accessibilitySnapshot.nodes.mapTo(virtualViewIds) { it.virtualId }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            val semanticNode = accessibilitySnapshot.nodes.firstOrNull {
                it.virtualId == virtualViewId
            } ?: return
            node.className = TextView::class.java.name
            node.text = semanticNode.text
            node.isFocusable = true
            node.isHeading = semanticNode.role == ReaderPageSemanticRole.HEADING
            node.setBoundsInParent(virtualBounds(virtualViewId))
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean = false
    }

    init {
        isFocusable = true
        ViewCompat.setImportantForAccessibility(this, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES)
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    constructor(context: Context) : this(context, null) {
        Log.d(TAG, "+PageView")
    }

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0) {
        Log.d(TAG, "+PageView1")
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        Log.d(TAG, "+PageView2")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mViewWidth = w
        mViewHeight = h

        prepared = false
        prepareDisplayIfReady()
    }

    // 设置翻页的模式
    fun setPageMode(pageMode: PageMode) {
        mPageMode = pageMode
        // 视图未初始化的时候，禁止调用
        if (mViewWidth == 0 || mViewHeight == 0) return

        mPageAnim = when (mPageMode) {
            PageMode.SIMULATION -> {
                SimulationPageAnim(mViewWidth, mViewHeight, this, mPageAnimListener)
            }

            PageMode.COVER -> {
                CoverPageAnim(mViewWidth, mViewHeight, this, mPageAnimListener)
            }

            PageMode.SLIDE -> {
                SlidePageAnim(mViewWidth, mViewHeight, this, mPageAnimListener)
            }

            PageMode.NONE -> {
                NonePageAnim(mViewWidth, mViewHeight, this, mPageAnimListener)
            }

            PageMode.SCROLL -> {
                ScrollPageAnim(
                    mViewWidth,
                    mViewHeight,
                    contentTop = mPageLoader!!.contentTop,
                    contentHeight = mPageLoader!!.visibleContentHeight,
                    view = this,
                    listener = mPageAnimListener
                )
            }
        }
    }

    /**
     * Observes only persistent gesture and cutout insets. Transient status/navigation bars are
     * intentionally excluded so opening the reader menu never changes pagination geometry.
     */
    fun bindReaderSafeInsets() {
        readerSafeInsetBindingEnabled = true
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
            val mandatoryGestures = windowInsets.getInsets(
                WindowInsetsCompat.Type.mandatorySystemGestures()
            )
            val tappableElements = windowInsets.getInsets(
                WindowInsetsCompat.Type.tappableElement()
            )
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val resolved = ReaderPageGeometry.resolveSafeInsets(
                mandatoryGestureBottomPx = mandatoryGestures.bottom,
                tappableElementBottomPx = tappableElements.bottom,
                cutoutLeftPx = cutout.left,
                cutoutRightPx = cutout.right,
                cutoutBottomPx = cutout.bottom
            )
            val firstResolution = !readerSafeInsetsResolved
            val changed = readerSafeInsets != resolved
            readerSafeInsetsResolved = true
            if (changed) {
                readerSafeInsets = resolved
            }
            mPageLoader?.setReaderSafeInsets(readerSafeInsets)
            if (firstResolution || changed) {
                prepareDisplayIfReady()
            }
            windowInsets
        }
        requestReaderSafeInsetsWhenAttached()
    }

    private fun requestReaderSafeInsetsWhenAttached() {
        if (ViewCompat.isAttachedToWindow(this)) {
            ViewCompat.requestApplyInsets(this)
            return
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attachedView: View) {
                attachedView.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(attachedView)
            }

            override fun onViewDetachedFromWindow(detachedView: View) = Unit
        })
    }

    fun getNextBitmap(): Bitmap? {
        if (mPageAnim == null) return null
        return mPageAnim!!.getNextBitmap()
    }

    fun getBgBitmap(): Bitmap? {
        if (mPageAnim == null) return null
        return mPageAnim!!.getBgBitmap()
    }

    fun autoPrevPage(): Boolean {
        // 滚动暂时不支持自动翻页
        return if (mPageAnim is ScrollPageAnim) {
            false
        } else {
            startPageAnim(PageAnimation.Direction.PRE)
            true
        }
    }

    fun autoNextPage(): Boolean {
        return if (mPageAnim is ScrollPageAnim) {
            false
        } else {
            startPageAnim(PageAnimation.Direction.NEXT)
            true
        }
    }

    private fun startPageAnim(direction: PageAnimation.Direction) {
        Log.d(TAG, "+startPageAnim")

        if (mTouchListener == null) return
        // 是否正在执行动画
        abortAnimation()
        if (direction == PageAnimation.Direction.NEXT) {
            val x = mViewWidth
            val y = mViewHeight
            // 初始化动画
            mPageAnim!!.setStartPoint(x.toFloat(), y.toFloat())
            // 设置点击点
            mPageAnim!!.setTouchPoint(x.toFloat(), y.toFloat())
            // 设置方向
            val hasNext = hasNextPage()
            Log.d(TAG, x.toString() + "" + y)
            mPageAnim!!.setDirection(direction)
            if (!hasNext) {
                return
            }
        } else {
            val x = 0
            val y = mViewHeight
            // 初始化动画
            mPageAnim!!.setStartPoint(x.toFloat(), y.toFloat())
            // 设置点击点
            mPageAnim!!.setTouchPoint(x.toFloat(), y.toFloat())
            mPageAnim!!.setDirection(direction)
            // 设置方向方向
            val hashPrev = hasPrevPage()
            if (!hashPrev) {
                return
            }
        }
        mPageAnim!!.startAnim()
        this.postInvalidate()
    }

    fun setBgColor(color: Int) {
        mBgColor = color
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        // 绘制动画
        mPageAnim?.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d(TAG, "+onTouchEvent")

        super.onTouchEvent(event)
        if (!prepared || mPageAnim == null || mTouchListener == null) return true

        if (!canTouch && event.action != MotionEvent.ACTION_DOWN) return true

        val x = event.x.toInt()
        val y = event.y.toInt()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mStartX = x
                mStartY = y
                isMove = false
                canTouch = mTouchListener!!.onTouch()
                mPageAnim!!.onTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                // 判断是否大于最小滑动值。
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (!isMove) {
                    isMove = kotlin.math.abs(mStartX - event.x) > slop ||
                        kotlin.math.abs(mStartY - event.y) > slop
                }

                // 如果滑动了，则进行翻页。
                if (isMove) {
                    mPageAnim!!.onTouchEvent(event)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isMove) {
                    // 设置中间区域范围
                    if (mCenterRect == null) {
                        mCenterRect = RectF(
                            (mViewWidth / 5).toFloat(),
                            (mViewHeight / 3).toFloat(),
                            (mViewWidth * 4 / 5).toFloat(),
                            (mViewHeight * 2 / 3).toFloat()
                        )
                    }

                    // 是否点击了中间
                    if (mCenterRect!!.contains(x.toFloat(), y.toFloat())) {
                        if (mTouchListener != null) {
                            mTouchListener!!.center()
                        }
                        return true
                    }
                }
                mPageAnim!!.onTouchEvent(event)
            }
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return accessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    internal fun publishAccessibilitySnapshot(snapshot: ReaderPageSemanticSnapshot) {
        if (accessibilitySnapshot == snapshot) return
        accessibilitySnapshot = snapshot
        accessibilityHelper.invalidateRoot()
    }

    private fun virtualBounds(virtualViewId: Int): Rect {
        val nodes = accessibilitySnapshot.nodes
        val nodeIndex = nodes.indexOfFirst { it.virtualId == virtualViewId }
        if (nodeIndex < 0) return Rect(0, 0, 1, 1)
        val totalWeight = nodes.sumOf { it.visualLineCount.coerceAtLeast(1) }.coerceAtLeast(1)
        val precedingWeight = nodes.take(nodeIndex)
            .sumOf { it.visualLineCount.coerceAtLeast(1) }
        val nodeWeight = nodes[nodeIndex].visualLineCount.coerceAtLeast(1)
        val availableWidth = width.coerceAtLeast(1)
        val availableHeight = height.coerceAtLeast(1)
        val top = (availableHeight.toLong() * precedingWeight / totalWeight).toInt()
        val bottom = (
            availableHeight.toLong() * (precedingWeight + nodeWeight) / totalWeight
            ).toInt().coerceAtLeast(top + 1).coerceAtMost(availableHeight)
        return Rect(0, top, availableWidth, bottom)
    }

    /**
     * 判断是否存在上一页
     */
    private fun hasPrevPage(): Boolean {
        mTouchListener!!.prePage()
        return mPageLoader!!.prev()
    }

    /**
     * 判断是否下一页存在
     */
    private fun hasNextPage(): Boolean {
        mTouchListener!!.nextPage()
        return mPageLoader!!.next()
    }

    private fun pageCancel() {
        mTouchListener!!.cancel()
        mPageLoader!!.pageCancel()
    }

    override fun computeScroll() {
        // 进行滑动
        mPageAnim?.scrollAnim()
        super.computeScroll()
    }

    // 如果滑动状态没有停止就取消状态，重新设置Anim的触碰点
    fun abortAnimation() {
        mPageAnim?.abortAnim()
    }

    fun isRunning(): Boolean {
        if (mPageAnim == null) {
            return false
        }
        return mPageAnim!!.isRunning()
    }

    fun isPrepare(): Boolean {
        return prepared
    }

    fun setTouchListener(mTouchListener: TouchListener?) {
        Log.d(TAG, "+setTouchListener")
        this.mTouchListener = mTouchListener
    }

    fun drawNextPage() {
        Log.d(TAG, "+drawNextPage")
        if (!prepared) return

        if (mPageAnim is HorizonPageAnim) {
            (mPageAnim as HorizonPageAnim).changePage()
        }
        mPageLoader!!.drawPage(getNextBitmap(), false)
    }

    /**
     * 绘制当前页。
     */
    fun drawCurPage(isUpdate: Boolean) {
        Log.d(TAG, "+drawCurPage")
        if (!prepared) return

        if (!isUpdate) {
            if (mPageAnim is ScrollPageAnim) {
                (mPageAnim as ScrollPageAnim).resetBitmap()
            }
        }

        mPageLoader!!.drawPage(getNextBitmap(), isUpdate)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mPageAnim?.abortAnim()
        mPageAnim?.clear()

        mPageLoader = null
        mPageAnim = null
    }

    /**
     * 获取 PageLoader
     */
    fun getPageLoader(collBook: CollBookBean): PageLoader {
        Log.d(TAG, "+getPageLoader")
        // 判是否已经存在
        if (mPageLoader != null) {
            return mPageLoader!!
        }
        // 根据书籍类型，获取具体的加载器
        mPageLoader = if (collBook.isLocal()) {
            LocalPageLoader(this, collBook)
        } else {
            NetPageLoader(this, collBook)
        }
        mPageLoader!!.setReaderSafeInsets(readerSafeInsets)
        // 判断是否 PageView 已经初始化完成
        prepareDisplayIfReady()

        return mPageLoader!!
    }

    private fun prepareDisplayIfReady() {
        val pageLoader = mPageLoader ?: return
        if (mViewWidth <= 0 || mViewHeight <= 0) return
        if (
            !ReaderPageGeometry.canPrepareDisplay(
                safeInsetBindingEnabled = readerSafeInsetBindingEnabled,
                safeInsetsResolved = readerSafeInsetsResolved
            )
        ) {
            return
        }
        prepared = true
        pageLoader.prepareDisplay(mViewWidth, mViewHeight)
    }

    interface TouchListener {
        fun onTouch(): Boolean

        fun center()

        fun prePage()

        fun nextPage()

        fun cancel()
    }

    companion object {
        private const val TAG = "BookPageWidget"
    }
}
