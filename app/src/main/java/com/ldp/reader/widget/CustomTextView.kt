package com.ldp.reader.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import androidx.appcompat.widget.AppCompatTextView

/**
 * Created by ldp on 17-4-29.
 * 1. 找到改写TextView内容的方法:是哪个 0 0，个人猜测是setText()
 * 2. 找到文章中存在《》的位置:
 * 3. 设置ForeSpan
 * 4. 添加点击事件
 */
class CustomTextView : AppCompatTextView {
    private var paint1: Paint? = null
    private var paint2: Paint? = null

    private var mWidth = 0
    private var gradient: LinearGradient? = null
    private var matrix: Matrix? = null

    //渐变的速度
    private var deltaX = 0

    constructor(context: Context) : super(context, null)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initView(context, attrs)
    }

    private fun initView(context: Context, attrs: AttributeSet?) {
        paint1 = Paint()
        paint1!!.color = resources.getColor(android.R.color.holo_blue_dark)
        paint1!!.style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.e(TAG, "*********************")

        if (mWidth == 0) {
            Log.e(TAG, "*********************")
            mWidth = measuredWidth
            paint2 = paint
            //颜色渐变器
            gradient = LinearGradient(
                0f,
                0f,
                mWidth.toFloat(),
                0f,
                intArrayOf(Color.BLUE, Color.RED, Color.YELLOW),
                floatArrayOf(0.3f, 0.5f, 1.0f),
                Shader.TileMode.CLAMP
            )
            paint2!!.shader = gradient

            matrix = Matrix()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d(TAG, "onDraw: ")
        if (matrix != null) {
            deltaX += mWidth / 5
            if (deltaX > 2 * mWidth) {
                deltaX = -mWidth
            }
        }
        //关键代码通过矩阵的平移实现
        matrix!!.setTranslate(deltaX.toFloat(), 0f)
        gradient!!.setLocalMatrix(matrix)
//        postInvalidateDelayed(100);
    }

    companion object {
        private val TAG = CustomTextView::class.java.simpleName
    }
}
