package com.ldp.reader.widget.tab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ldp.reader.R
import com.ldp.reader.utils.ScreenUtils

class TabTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), TabView {
    private var mWidth = 0
    private var mHeight = 0

    private lateinit var mPaint: Paint
    private var mText: String? = "title"
    private var mTextHeight = 0f

    private var mTextSize = 0
    private var mTextColor = 0
    private var mTextColorFocus = 0
    private var mPadding = 0

    init {
        init(context)
    }

    private fun init(context: Context) {
        mTextSize = ScreenUtils.dpToPx(15)
        mTextColor = ContextCompat.getColor(context, R.color.ink_text_tertiary)
        mTextColorFocus = ContextCompat.getColor(context, R.color.ink_primary)

        mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mPaint.textAlign = Paint.Align.CENTER
        mPaint.textSize = mTextSize.toFloat()
        mPaint.color = mTextColor

        mTextHeight = ScreenUtils.getTextHeight(mPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = mWidth / 2f
        val y = mHeight / 2f + mTextHeight / 2f
        canvas.drawText(mText!!, x, y, mPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        mWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            ScreenUtils.getTextWidth(mText, mPaint) + mPadding * 2
        }
        mHeight = getDefaultSize(suggestedMinimumWidth, heightMeasureSpec)
        setMeasuredDimension(mWidth, mHeight)
    }

    override fun setText(text: String?) {
        mText = text
        requestLayout()
    }

    override fun setPadding(padding: Int) {
        mPadding = padding
        requestLayout()
    }

    override fun setNumber(text: String?, visibility: Int) {
    }

    override fun notifyData(focus: Boolean) {
        mPaint.color = if (focus) mTextColorFocus else mTextColor
        invalidate()
    }

    override fun onScroll(factor: Float) {
    }
}
