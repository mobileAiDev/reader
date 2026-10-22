package com.ldp.reader.widget.tab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.ViewPager
import com.ldp.reader.R
import com.ldp.reader.utils.ScreenUtils

class ScrollTab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr),
    View.OnClickListener,
    ViewPager.OnPageChangeListener {
    private val typeView = 0
    private val typeViewGroup = 1

    private val typeIndicatorTrend = 0
    private val typeIndicatorTranslation = 1
    private val typeIndicatorNone = 2

    private var mWidth = 0
    private var mHeight = 0

    private val mContext: Context
    private lateinit var mRectF: RectF
    private lateinit var mPaint: Paint
    private lateinit var mDotPaint: Paint

    private var mType = 0
    private var mIsAvag = false
    private var mPadding = 0f
    private var mStrTitles: String? = null
    private var mIndicatorType = 0
    private var mIndicatorColor = 0
    private var mIndicatorWidth = 0f
    private var mIndicatorWeight = 0f
    private var mIndicatorRadius = 0f
    private var mIndicatorPadding = 0f

    private lateinit var mItems: ArrayList<TabItem>
    private lateinit var mTabs: ArrayList<View>
    private var mCount = 0
    private var mPosition = 0
    private var mPositionOffset = 0f
    private var mIsFirst = true
    private var mViewPager: ViewPager? = null
    private var mListener: OnTabListener? = null

    init {
        initTypedArray(context, attrs)
        mContext = context
        init(context)
    }

    private fun initTypedArray(context: Context, attrs: AttributeSet?) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.lib_pub_ScrollTab)
        mType = typedArray.getInt(R.styleable.lib_pub_ScrollTab_lib_pub_stab_type, typeView)
        mIsAvag = typedArray.getBoolean(R.styleable.lib_pub_ScrollTab_lib_pub_stab_avag, false)
        mPadding = typedArray.getDimension(R.styleable.lib_pub_ScrollTab_lib_pub_stab_padding, ScreenUtils.dpToPx(12).toFloat())
        mStrTitles = typedArray.getString(R.styleable.lib_pub_ScrollTab_lib_pub_stab_titles)
        mIndicatorType = typedArray.getInt(R.styleable.lib_pub_ScrollTab_lib_pub_stab_indicatorType, typeIndicatorTrend)
        mIndicatorColor = typedArray.getColor(
            R.styleable.lib_pub_ScrollTab_lib_pub_stab_indicatorColor,
            ContextCompat.getColor(context, R.color.ink_primary)
        )
        mIndicatorWidth = typedArray.getDimension(R.styleable.lib_pub_ScrollTab_lib_pub_stab_indicatorWidth, ScreenUtils.dpToPx(30).toFloat())
        mIndicatorWeight = typedArray.getDimension(R.styleable.lib_pub_ScrollTab_lib_pub_stab_indicatorWeight, ScreenUtils.dpToPx(1).toFloat())
        mIndicatorRadius = typedArray.getDimension(R.styleable.lib_pub_ScrollTab_lib_pub_stab_indicatorRadius, ScreenUtils.dpToPx(1).toFloat())
        mIndicatorPadding = typedArray.getDimension(R.styleable.lib_pub_ScrollTab_lib_pub_stab_indicatorPadding, ScreenUtils.dpToPx(5).toFloat())
        typedArray.recycle()
    }

    private fun init(context: Context) {
        setWillNotDraw(false)
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = mIsAvag
        mRectF = RectF()
        mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val linearGradient = LinearGradient(0f, 0f, 200f, 0f, mIndicatorColor, Color.YELLOW, Shader.TileMode.MIRROR)
        mPaint.shader = linearGradient
        mDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mDotPaint.color = Color.YELLOW
        mDotPaint.style = Paint.Style.FILL
        mTabs = ArrayList()
        mItems = ArrayList()
        if (!TextUtils.isEmpty(mStrTitles)) {
            val strs = mStrTitles!!.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (t in strs) {
                mItems.add(TabItem(t, ""))
            }
        }
    }

    fun setTitles(ts: List<String>?) {
        if (ts != null) {
            mItems.clear()
            for (t in ts) {
                mItems.add(TabItem(t, ""))
            }
            if (!mIsFirst) {
                resetTab()
                invalidate()
            }
        }
    }

    private fun resetTab() {
        if (mItems.size <= 0 || mWidth <= 0) {
            return
        }
        mIsFirst = false
        mCount = mItems.size
        mTabs.clear()
        removeAllViews()
        val parent = LinearLayout(mContext)
        val lp = LayoutParams(if (mIsAvag) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        parent.orientation = LinearLayout.HORIZONTAL
        parent.layoutParams = lp
        for (i in 0 until mCount) {
            val child = getTabView(i)
            parent.addView(child)
            mTabs.add(child)
        }
        addView(parent)
    }

    private fun getTabView(i: Int): View {
        val child: View = if (mType == typeView) {
            TabTextView(mContext)
        } else {
            TabViewGroup(mContext)
        }
        (child as TabView).setText(mItems[i].title)
        child.setNumber(mItems[i].text, if (TextUtils.isEmpty(mItems[i].text)) GONE else VISIBLE)
        if (!mIsAvag) {
            child.setPadding(mPadding.toInt())
        }
        child.notifyData(i == mPosition)
        child.layoutParams = LinearLayout.LayoutParams(
            if (mIsAvag) mWidth / (if (mCount > 0) mCount else 1) else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        child.tag = i
        child.setOnClickListener(this)
        return child
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isInEditMode || mCount <= 0 || mPosition < 0 || mPosition > mCount - 1) {
            return
        }
        var left: Float
        var right: Float
        if (mIndicatorType == typeIndicatorTrend) {
            left = mTabs[mPosition].left + mIndicatorPadding
            right = mTabs[mPosition].right - mIndicatorPadding
            val oriLeft = left
            val dotRadius = mIndicatorWeight / 2
            if (mPosition < mCount - 1) {
                val nextLeft = mTabs[mPosition + 1].left + mIndicatorPadding
                val nextRight = mTabs[mPosition + 1].right - mIndicatorPadding
                if (mPositionOffset < 0.5) {
                    right += (nextRight - right) * mPositionOffset * 2
                } else {
                    left += (nextLeft - left) * (mPositionOffset - 0.5f) * 2
                    right = nextRight
                }
            }
            if (mPositionOffset < 0.5) {
                canvas.drawCircle(oriLeft, mHeight - dotRadius, dotRadius + 0.5f, mDotPaint)
            } else if (mPositionOffset < 0.75) {
                canvas.drawCircle(oriLeft, mHeight - dotRadius, dotRadius + 0.5f, mDotPaint)
            } else {
                canvas.drawCircle(
                    (left + dotRadius) * mPositionOffset - (left - oriLeft) * (1 - mPositionOffset * mPositionOffset * mPositionOffset),
                    mHeight - dotRadius,
                    dotRadius + 0.5f,
                    mDotPaint
                )
            }
            mRectF.set(left, mHeight - mIndicatorWeight, right, mHeight.toFloat())
        } else if (mIndicatorType == typeIndicatorTranslation) {
            left = mTabs[mPosition].left.toFloat()
            right = mTabs[mPosition].right.toFloat()
            var middle = left + (right - left) / 2
            if (mPosition < mCount - 1) {
                val nextLeft = mTabs[mPosition + 1].left.toFloat()
                val nextRight = mTabs[mPosition + 1].right.toFloat()
                val nextMiddle = nextLeft + (nextRight - nextLeft) / 2
                middle += (nextMiddle - middle) * mPositionOffset
            }
            left = middle - mIndicatorWidth / 2
            right = middle + mIndicatorWidth / 2
            mRectF.set(left, mHeight - mIndicatorWeight, right, mHeight.toFloat())
        } else {
            left = mTabs[mPosition].left.toFloat()
            right = mTabs[mPosition].right.toFloat()
            val middle = left + (right - left) / 2
            left = middle - mIndicatorWidth / 2
            right = middle + mIndicatorWidth / 2
            mRectF.set(left, mHeight - mIndicatorWeight, right, mHeight.toFloat())
        }
        canvas.drawRoundRect(mRectF, mIndicatorRadius, mIndicatorRadius, mPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        mWidth = MeasureSpec.getSize(widthMeasureSpec)
        mHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (mIsFirst) {
            resetTab()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onClick(v: View) {
        val index = v.tag as Int
        if (mViewPager == null) {
            mPosition = index
            mPositionOffset = 0f
            onChange(index)
            adjustScrollY(index)
            invalidate()
        }
        if (mListener != null) {
            mListener!!.onChange(index, v)
        }
    }

    private fun onChange(position: Int) {
        for (i in 0 until mCount) {
            val view = mTabs[i] as TabView
            view.notifyData(i == position)
        }
    }

    fun setViewPager(viewPager: ViewPager) {
        mViewPager = viewPager
        viewPager.addOnPageChangeListener(this)
    }

    fun setNumber(position: Int, text: String?, visibility: Int) {
        if (position < 0 || position > mItems.size - 1) {
            return
        }
        mItems[position].text = text
        if (position < 0 || position > mCount - 1) {
            return
        }
        val view = mTabs[position] as TabView
        view.setNumber(text, visibility)
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
        if (mIndicatorType != typeIndicatorNone) {
            mPosition = position
            mPositionOffset = positionOffset
            invalidate()
        }
    }

    override fun onPageSelected(position: Int) {
        onChange(position)
        adjustScrollY(position)
        if (mIndicatorType == typeIndicatorNone) {
            mPosition = position
            invalidate()
        }
    }

    override fun onPageScrollStateChanged(state: Int) {
    }

    private fun adjustScrollY(position: Int) {
        if (mIsAvag) {
            return
        }
        val v = mTabs[position]
        val dr = v.right - (mWidth + scrollX)
        val dl = scrollX - v.left
        if (dr > 0) {
            smoothScrollBy(dr, 0)
        } else if (dl > 0) {
            smoothScrollBy(-dl, 0)
        }
    }

    interface OnTabListener {
        fun onChange(position: Int, v: View?)
    }

    fun setOnTabListener(l: OnTabListener?) {
        mListener = l
    }
}
