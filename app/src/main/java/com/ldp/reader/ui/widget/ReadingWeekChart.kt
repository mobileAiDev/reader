package com.ldp.reader.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ldp.reader.R
import com.ldp.reader.ui.motion.SpringValue
import com.ldp.reader.utils.ReadingStatsUtils
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * The last week of reading as seven quiet bars: today in ink with its time above it, the other
 * days in a light wash, an empty day as a short dash. A day of only a few minutes is a small
 * pill. The bars grow in on a spring each time new days are set.
 */
class ReadingWeekChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val todayColor = ContextCompat.getColor(context, R.color.ink_primary)
    private val dayColor = ContextCompat.getColor(context, R.color.ink_outline)
    private val emptyColor = ContextCompat.getColor(context, R.color.ink_divider)
    private val todayLabelColor = ContextCompat.getColor(context, R.color.ink_text_primary)
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
        color = todayLabelColor
    }
    private val dayLabelColor = ContextCompat.getColor(context, R.color.ink_text_tertiary)
    private val bar = RectF()
    private val grow = SpringValue(context, response = 0.7f, dampingRatio = 0.85f, restDelta = 0.002f).apply {
        listener = SpringValue.Listener { invalidate() }
    }
    private var days: List<Long> = emptyList()
    private var labels: List<String> = emptyList()

    /** [dailyMillis] holds one entry per day, oldest first, the last one being the day of [nowMs]. */
    fun setDays(dailyMillis: List<Long>, nowMs: Long) {
        val weekdays = resources.getStringArray(R.array.reading_stats_weekdays)
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_MONTH, 1 - dailyMillis.size)
        }
        labels = dailyMillis.indices.map { index ->
            val label = if (index == dailyMillis.lastIndex) {
                context.getString(R.string.reading_stats_today_short)
            } else {
                weekdays[calendar[Calendar.DAY_OF_WEEK] - 1]
            }
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            label
        }
        days = dailyMillis
        contentDescription = context.getString(R.string.reading_stats_recent) + "：" +
            labels.indices.joinToString("，") { labels[it] + " " + ReadingStatsUtils.formatDurationLabel(days[it]) }
        grow.jumpTo(0f)
        grow.animateTo(1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resolveSize((DEFAULT_HEIGHT_DP * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    override fun onDetachedFromWindow() {
        grow.stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val labelArea = LABEL_AREA_DP * density
        val chartTop = paddingTop.toFloat()
        val chartBottom = height - paddingBottom - labelArea
        val chartHeight = chartBottom - chartTop
        val slot = (width - paddingLeft - paddingRight) / days.size.toFloat()
        val barWidth = min(slot * 0.42f, MAX_BAR_WIDTH_DP * density)
        // A short day must not fill the chart: half an hour is the smallest full-height scale.
        val scaleMs = max(days.max(), MIN_SCALE_MS).toFloat()
        val labelBaseline = height - paddingBottom - (labelArea - labelPaint.textSize) / 2f
        days.forEachIndexed { index, dayMs ->
            val centerX = paddingLeft + slot * (index + 0.5f)
            val isToday = index == days.lastIndex
            val barHeight = if (dayMs <= 0L) {
                EMPTY_BAR_DP * density
            } else {
                max(MIN_BAR_DP * density, chartHeight * (dayMs / scaleMs) * grow.value)
            }
            bar.set(centerX - barWidth / 2f, chartBottom - barHeight, centerX + barWidth / 2f, chartBottom)
            barPaint.color = when {
                dayMs <= 0L -> emptyColor
                isToday -> todayColor
                else -> dayColor
            }
            val corner = min(barWidth, barHeight) / 2f
            canvas.drawRoundRect(bar, corner, corner, barPaint)
            if (isToday && dayMs > 0L) {
                valuePaint.alpha = (255 * grow.value.coerceIn(0f, 1f)).toInt()
                canvas.drawText(ReadingStatsUtils.formatDurationLabel(dayMs), centerX, bar.top - VALUE_GAP_DP * density, valuePaint)
            }
            labelPaint.color = if (isToday) todayLabelColor else dayLabelColor
            labelPaint.isFakeBoldText = isToday
            canvas.drawText(labels[index], centerX, labelBaseline, labelPaint)
        }
    }

    private companion object {
        const val DEFAULT_HEIGHT_DP = 140
        const val LABEL_AREA_DP = 24f
        const val MAX_BAR_WIDTH_DP = 16f
        const val EMPTY_BAR_DP = 4f
        const val MIN_BAR_DP = 8f
        const val VALUE_GAP_DP = 6f
        const val MIN_SCALE_MS = 30L * 60_000L
    }
}
