package com.ldp.reader.ui.motion

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.ldp.reader.R
import org.xmlpull.v1.XmlPullParser

/**
 * A filled shape with continuous corners, inflatable from XML:
 * `<drawable class="com.ldp.reader.ui.motion.SquircleDrawable" app:squircleColor=… />`.
 */
class SquircleDrawable() : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private var fillColors: ColorStateList? = null
    private var strokeColors: ColorStateList? = null

    /** Colour resources from XML, resolved again in [applyTheme]: they may read theme attributes. */
    private var fillColorRes = 0
    private var strokeColorRes = 0
    var radius = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    constructor(color: Int, radiusPx: Float) : this() {
        fillColors = ColorStateList.valueOf(color)
        radius = radiusPx
        updateColors(state)
    }

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        val array = if (theme != null) {
            theme.obtainStyledAttributes(attrs, R.styleable.SquircleDrawable, 0, 0)
        } else {
            r.obtainAttributes(attrs, R.styleable.SquircleDrawable)
        }
        fillColors = array.getColorStateList(R.styleable.SquircleDrawable_squircleColor)
        strokeColors = array.getColorStateList(R.styleable.SquircleDrawable_squircleStrokeColor)
        fillColorRes = array.getResourceId(R.styleable.SquircleDrawable_squircleColor, 0)
        strokeColorRes = array.getResourceId(R.styleable.SquircleDrawable_squircleStrokeColor, 0)
        stroke.strokeWidth = array.getDimension(R.styleable.SquircleDrawable_squircleStrokeWidth, 0f)
        radius = array.getDimension(R.styleable.SquircleDrawable_squircleRadius, 0f)
        array.recycle()
        updateColors(state)
    }

    // Resources inflate XML drawables without a theme, cache them, then hand each user the theme
    // here; colours that read the InkTheme only resolve at this point.
    override fun canApplyTheme(): Boolean = fillColorRes != 0 || strokeColorRes != 0

    override fun applyTheme(t: Resources.Theme) {
        super.applyTheme(t)
        if (fillColorRes != 0) fillColors = t.resources.getColorStateList(fillColorRes, t)
        if (strokeColorRes != 0) strokeColors = t.resources.getColorStateList(strokeColorRes, t)
        updateColors(state)
        invalidateSelf()
    }

    override fun isStateful(): Boolean {
        return fillColors?.isStateful == true || strokeColors?.isStateful == true
    }

    override fun onStateChange(state: IntArray): Boolean = updateColors(state)

    private fun updateColors(state: IntArray): Boolean {
        val fillColor = fillColors?.getColorForState(state, 0) ?: 0
        val strokeColor = strokeColors?.getColorForState(state, 0) ?: 0
        val changed = fillColor != fill.color || strokeColor != stroke.color
        fill.color = fillColor
        stroke.color = strokeColor
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        Squircle.path(path, rect, radius)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        Squircle.path(path, rect, radius)
        if (fill.color ushr 24 != 0) canvas.drawPath(path, fill)
        if (stroke.strokeWidth > 0f && stroke.color ushr 24 != 0) canvas.drawPath(path, stroke)
    }

    override fun getOutline(outline: Outline) {
        Squircle.outline(outline, rect, radius, Path())
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
