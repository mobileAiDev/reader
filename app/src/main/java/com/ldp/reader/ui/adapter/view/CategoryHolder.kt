package com.ldp.reader.ui.adapter.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemCategoryBinding
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.ui.base.adapter.ViewHolderImpl
import com.ldp.reader.utils.BookManager
import com.ldp.reader.widget.page.TxtChapter
import kotlin.math.ceil
import kotlin.math.max

/**
 * Created by ldp on 17-5-16.
 */
class CategoryHolder : ViewHolderImpl<TxtChapter>() {
    private lateinit var mTvChapter: TextView

    override fun initView() {
        val binding = ItemCategoryBinding.bind(getItemView())
        mTvChapter = binding.categoryTvChapter
    }

    override fun onBind(value: TxtChapter, pos: Int) {
        val drawable: Drawable? = if (value.link == null) {
            ContextCompat.getDrawable(getContext(), R.drawable.selector_category_load)
        } else if (value.bookId != null && BookManager.isChapterCached(value.bookId, value.title)) {
            ContextCompat.getDrawable(getContext(), R.drawable.selector_category_load)
        } else {
            ContextCompat.getDrawable(getContext(), R.drawable.selector_category_unload)
        }

        mTvChapter.isSelected = false
        mTvChapter.setTextColor(ContextCompat.getColor(getContext(), R.color.nb_text_default))
        mTvChapter.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
        mTvChapter.text = integrityDisplayTitle(value)
    }

    override fun getItemLayoutId(): Int {
        return R.layout.item_category
    }

    fun setSelectedChapter() {
        mTvChapter.setTextColor(ContextCompat.getColor(getContext(), R.color.light_red))
        mTvChapter.isSelected = true
    }

    private fun integrityDisplayTitle(value: TxtChapter): CharSequence {
        val title = value.title.orEmpty()
        val badge = integrityBadge(value.sourceIntegrityState) ?: return title
        return SpannableStringBuilder(title).apply {
            append(" ")
            val start = length
            append(badge.label)
            setSpan(
                IntegrityBadgeSpan(
                    textColor = badge.textColor,
                    backgroundColor = badge.backgroundColor,
                    horizontalPadding = dp(6f),
                    verticalPadding = dp(2f),
                    minHeight = dp(18f),
                    cornerRadius = dp(9f)
                ),
                start,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun integrityBadge(markState: String?): IntegrityBadge? {
        if (!ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) return null
        return when (markState) {
            V8ChapterMarkState.WRONG.name,
            V8ChapterMarkState.NON_STORY.name,
            V8ChapterMarkState.BAD_EXTRACTION.name -> IntegrityBadge(
                label = "错章",
                textColor = color(R.color.chapter_mark_wrong_text),
                backgroundColor = color(R.color.chapter_mark_wrong_bg)
            )
            else -> null
        }
    }

    private fun color(colorRes: Int): Int {
        return ContextCompat.getColor(getContext(), colorRes)
    }

    private fun dp(value: Float): Float {
        return value * getContext().resources.displayMetrics.density
    }

    private data class IntegrityBadge(
        val label: String,
        val textColor: Int,
        val backgroundColor: Int
    )

    private class IntegrityBadgeSpan(
        private val textColor: Int,
        private val backgroundColor: Int,
        private val horizontalPadding: Float,
        private val verticalPadding: Float,
        private val minHeight: Float,
        private val cornerRadius: Float
    ) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val oldTextSize = paint.textSize
            paint.textSize = oldTextSize * TEXT_SCALE
            val width = paint.measureText(text, start, end) + horizontalPadding * 2
            paint.textSize = oldTextSize
            return ceil(width).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val oldColor = paint.color
            val oldStyle = paint.style
            val oldTextSize = paint.textSize
            val oldFakeBold = paint.isFakeBoldText

            paint.textSize = oldTextSize * TEXT_SCALE
            paint.isFakeBoldText = true
            val metrics = paint.fontMetrics
            val textWidth = paint.measureText(text, start, end)
            val badgeWidth = textWidth + horizontalPadding * 2
            val badgeHeight = max(minHeight, metrics.descent - metrics.ascent + verticalPadding * 2)
            val centerY = (top + bottom) / 2f
            val rect = RectF(x, centerY - badgeHeight / 2, x + badgeWidth, centerY + badgeHeight / 2)

            paint.style = Paint.Style.FILL
            paint.color = backgroundColor
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            paint.color = textColor
            val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2
            canvas.drawText(text, start, end, x + horizontalPadding, baseline, paint)

            paint.color = oldColor
            paint.style = oldStyle
            paint.textSize = oldTextSize
            paint.isFakeBoldText = oldFakeBold
        }

        companion object {
            private const val TEXT_SCALE = 0.72f
        }
    }
}
