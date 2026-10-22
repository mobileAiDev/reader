package com.ldp.reader.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ldp.reader.R
import com.ldp.reader.ui.motion.Squircle

/** The glass shape as the view outline, for its soft shadow and for clipping. */
private class GlassOutline(private val glass: GlassPainter) : ViewOutlineProvider() {
    private val rect = RectF()
    private val path = Path()

    override fun getOutline(view: View, outline: Outline) {
        rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        Squircle.outline(outline, rect, glass.radius(rect.width(), rect.height()), path)
        // Glass casts only a faint shadow; a stronger one reads as a dark rim on the lower edge.
        outline.alpha = 0.16f
    }
}

private fun inflateGlass(host: View, attrs: AttributeSet?): GlassPainter {
    val declared = host.context.obtainStyledAttributes(attrs, R.styleable.Glass)
    val style = when (declared.getInt(R.styleable.Glass_glassLook, LOOK_BAR)) {
        LOOK_POPUP -> GlassStyle.popup(host.context)
        else -> GlassStyle.bar(host.context)
    }
    val glass = GlassPainter(host, style, declared.getBoolean(R.styleable.Glass_glassLive, true))
    if (declared.hasValue(R.styleable.Glass_glassCorner)) {
        glass.cornerRadius = declared.getDimension(R.styleable.Glass_glassCorner, -1f)
    }
    declared.recycle()
    host.outlineProvider = GlassOutline(glass)
    return glass
}

private const val LOOK_BAR = 1
private const val LOOK_POPUP = 2

/** A frame whose background is glass. Configure it through [glass]. */
open class GlassFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    val glass: GlassPainter

    init {
        setWillNotDraw(false)
        glass = inflateGlass(this, attrs)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.attach()
    }

    override fun onDetachedFromWindow() {
        glass.detach()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        glass.draw(canvas)
        super.onDraw(canvas)
    }
}

/** A row or column whose background is glass. Configure it through [glass]. */
open class GlassLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    val glass: GlassPainter

    init {
        setWillNotDraw(false)
        glass = inflateGlass(this, attrs)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.attach()
    }

    override fun onDetachedFromWindow() {
        glass.detach()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        glass.draw(canvas)
        super.onDraw(canvas)
    }
}
