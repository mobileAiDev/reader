package com.ldp.reader.widget.animation

import android.animation.TypeEvaluator
import android.graphics.PointF

/**
 * Created by D on 2016/11/8.
 */
class BezierEvaluator(
    private val mPointF1: PointF,
    private val mPointF2: PointF
) : TypeEvaluator<PointF> {
    override fun evaluate(time: Float, startValue: PointF, endValue: PointF): PointF {
        val timeLeft = 1.0f - time
        val point = PointF()

        point.x = timeLeft * timeLeft * timeLeft * startValue.x +
            3 * timeLeft * timeLeft * time * mPointF1.x +
            3 * timeLeft * time * time * mPointF2.x +
            time * time * time * endValue.x

        point.y = timeLeft * timeLeft * timeLeft * startValue.y +
            3 * timeLeft * timeLeft * time * mPointF1.y +
            3 * timeLeft * time * time * mPointF2.y +
            time * time * time * endValue.y
        return point
    }
}
