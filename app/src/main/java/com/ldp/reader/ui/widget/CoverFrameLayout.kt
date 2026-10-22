package com.ldp.reader.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.ldp.reader.R
import com.ldp.reader.ui.theme.CoverStyle
import com.ldp.reader.ui.theme.InkThemeTraits
import kotlin.math.roundToInt

/**
 * Book-cover frame that always keeps the classic 3:4 portrait ratio of its width, dressed as the
 * theme draws covers:
 *  - FLAT: the picture as it is, with softly rounded corners.
 *  - BOOK: lifted with a soft shadow, lit from above, the hinge of a bound book shaded over the
 *    left edge.
 *  - VOLUME: a hardcover standing on a shelf. Square boards lit from above, a dark binding, a
 *    crease of light and a groove at the spine, a sheen across the board, the page block showing
 *    at the fore-edge, and a height that varies a little from book to book while every bottom
 *    stays on the shelf. The book darkens the shelf where it stands; that contact shadow falls
 *    just below the frame, so it shows where the parent does not clip children (a shelf item).
 *
 * The frame owns the whole dressing: shape, light and hairline edge. Its children are plain
 * rectangles (a downloaded picture, a cloth cover), so online and local books look the same and
 * nothing underneath can show at a corner.
 */
class CoverFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val style = InkThemeTraits.of(context).coverStyle
    private val density = resources.displayMetrics.density
    private val cornerPx = density * when (style) {
        CoverStyle.FLAT -> FLAT_CORNER_DP
        CoverStyle.BOOK -> BOOK_CORNER_DP
        CoverStyle.VOLUME -> 0f
    }
    private val pageShade = context.getColor(R.color.book_page_shade)
    private val pageLight = context.getColor(R.color.book_page_light)
    /** Solid fills, each setting its own colour. */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Gradient fills, always opaque so the gradient's own alpha is all that shows. */
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = context.getColor(R.color.ink_cover_stroke)
    }
    private val contactShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, UNIT, CONTACT_SHADOW, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val board = RectF()
    private var light: LinearGradient? = null
    private var hinge: LinearGradient? = null
    private var groove: LinearGradient? = null
    private var sheen: LinearGradient? = null
    private var pages: LinearGradient? = null

    /** VOLUME: fraction of the height this book is shorter than the tallest, 0 to [MAX_SHORTER]. */
    private var shorter = 0f

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, paddingTop, width - paddingRight, height, cornerPx)
            }
        }
        when (style) {
            CoverStyle.FLAT -> clipToOutline = true
            CoverStyle.BOOK -> {
                elevation = BOOK_ELEVATION_DP * density
                clipToOutline = true
            }
            CoverStyle.VOLUME -> {
                // The page block lies outside the board, so the frame draws itself and is not clipped.
                setWillNotDraw(false)
                elevation = VOLUME_ELEVATION_DP * density
            }
        }
    }

    /** VOLUME: gives this book its own height, the same for the same [seed] (its title). */
    fun setVolumeSeed(seed: Int) {
        if (style != CoverStyle.VOLUME) return
        val next = Math.floorMod(seed, HEIGHT_STEPS) * MAX_SHORTER / (HEIGHT_STEPS - 1)
        if (next == shorter) return
        shorter = next
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = width * 4 / 3
        if (style == CoverStyle.VOLUME) {
            // The board fills the frame but for the page block at the fore-edge and the room
            // left above a shorter book.
            setPadding(0, (height * shorter).roundToInt(), (PAGE_BLOCK_DP * density).roundToInt(), 0)
        }
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A volume's board moves with the padding its height sets, not only with the frame size.
        layoutBoard(width, height)
        invalidateOutline()
    }

    private fun layoutBoard(w: Int, h: Int) {
        val top = paddingTop.toFloat()
        val coverRight = (w - paddingRight).toFloat()
        board.set(0f, top, coverRight, h.toFloat())
        light = LinearGradient(
            0f, top, 0f, h.toFloat(),
            intArrayOf(LIGHT_TOP, 0, SHADE_BOTTOM), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        hinge = LinearGradient(
            0f, 0f, coverRight * HINGE_WIDTH, 0f,
            intArrayOf(HINGE_DARK, HINGE_MID, 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        val grooveStart = coverRight * BINDING_WIDTH + density
        groove = LinearGradient(
            grooveStart, 0f, grooveStart + coverRight * GROOVE_WIDTH, 0f,
            GROOVE_DARK, 0, Shader.TileMode.CLAMP
        )
        sheen = LinearGradient(
            0f, top, coverRight, top + (h - top) * 0.7f,
            intArrayOf(0, SHEEN, 0), floatArrayOf(0.15f, 0.42f, 0.7f), Shader.TileMode.CLAMP
        )
        pages = LinearGradient(
            coverRight, 0f, w.toFloat(), 0f,
            pageShade, pageLight, Shader.TileMode.CLAMP
        )
    }

    /** VOLUME: the contact shadow under the book and the page block behind the board, visible at the fore-edge. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawContactShadow(canvas)
        val coverRight = board.right
        val pagesTop = board.top + PAGE_INSET_DP * density
        val pagesBottom = height - PAGE_INSET_DP * density * 0.6f
        shaderPaint.shader = pages
        canvas.drawRect(coverRight - density, pagesTop, width.toFloat(), pagesBottom, shaderPaint)
        // The edges of the leaves as fine lines, and the outer edge of the block.
        paint.color = PAGE_LINE
        paint.strokeWidth = density * 0.5f
        var x = coverRight + density
        while (x < width) {
            canvas.drawLine(x, pagesTop, x, pagesBottom, paint)
            x += density * 0.9f
        }
        paint.color = PAGE_EDGE
        canvas.drawRect(width - density * 0.75f, pagesTop, width.toFloat(), pagesBottom, paint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        when (style) {
            CoverStyle.FLAT -> Unit
            CoverStyle.BOOK -> {
                drawLight(canvas)
                shaderPaint.shader = hinge
                canvas.drawRect(0f, 0f, width * HINGE_WIDTH, height.toFloat(), shaderPaint)
                paint.color = CREASE_LIGHT
                val creaseX = width * CREASE_AT
                canvas.drawRect(creaseX, 0f, creaseX + density, height.toFloat(), paint)
            }
            CoverStyle.VOLUME -> {
                drawLight(canvas)
                drawVolume(canvas)
            }
        }
        canvas.drawRoundRect(
            board.left + 0.5f, board.top + 0.5f, board.right - 0.5f, board.bottom - 0.5f,
            cornerPx, cornerPx, hairline
        )
    }

    /** A soft dark ellipse on the shelf, centred a little above the foot of the board. */
    private fun drawContactShadow(canvas: Canvas) {
        canvas.save()
        canvas.translate(board.centerX(), board.bottom - CONTACT_LIFT_DP * density)
        canvas.scale(board.width() * SHADOW_SPREAD / UNIT, board.width() * SHADOW_DEPTH / UNIT)
        canvas.drawCircle(0f, 0f, UNIT, contactShadow)
        canvas.restore()
    }

    /** Light falls on the top of the book; the board darkens towards the bottom. */
    private fun drawLight(canvas: Canvas) {
        shaderPaint.shader = light
        canvas.drawRect(board, shaderPaint)
    }

    private fun drawVolume(canvas: Canvas) {
        val top = board.top
        val bottom = board.bottom
        val coverRight = board.right
        // The binding: a darker band where the board wraps the spine.
        paint.color = BINDING
        canvas.drawRect(0f, top, coverRight * BINDING_WIDTH, bottom, paint)
        shaderPaint.shader = hinge
        canvas.drawRect(0f, top, coverRight * HINGE_WIDTH, bottom, shaderPaint)
        // The hinge crease catches the light; the groove after it falls into shadow.
        paint.color = CREASE_LIGHT
        val creaseX = coverRight * BINDING_WIDTH
        canvas.drawRect(creaseX, top, creaseX + density, bottom, paint)
        shaderPaint.shader = groove
        canvas.drawRect(creaseX + density, top, creaseX + density + coverRight * GROOVE_WIDTH, bottom, shaderPaint)
        // A sheen across the board, and its edges: lit on top, darker at the foot, and the cut
        // edge of the board in shadow against the pages.
        shaderPaint.shader = sheen
        canvas.drawRect(board, shaderPaint)
        paint.color = TOP_EDGE
        canvas.drawRect(0f, top, coverRight, top + density, paint)
        paint.color = BOTTOM_EDGE
        canvas.drawRect(0f, bottom - density, coverRight, bottom, paint)
        paint.color = FORE_EDGE
        canvas.drawRect(coverRight - density * 1.25f, top, coverRight, bottom, paint)
    }

    private companion object {
        const val FLAT_CORNER_DP = 4f
        const val BOOK_CORNER_DP = 3f
        const val BOOK_ELEVATION_DP = 5f
        const val VOLUME_ELEVATION_DP = 7f
        const val PAGE_BLOCK_DP = 4.5f
        const val PAGE_INSET_DP = 2.5f
        const val MAX_SHORTER = 0.07f
        const val UNIT = 64f
        const val CONTACT_SHADOW = 0x80000000.toInt()
        const val CONTACT_LIFT_DP = 1.5f
        const val SHADOW_SPREAD = 0.64f
        const val SHADOW_DEPTH = 0.08f
        const val HEIGHT_STEPS = 5
        const val HINGE_WIDTH = 0.1f
        const val CREASE_AT = 0.04f
        const val BINDING_WIDTH = 0.07f
        const val GROOVE_WIDTH = 0.05f
        const val LIGHT_TOP = 0x14FFFFFF
        const val SHADE_BOTTOM = 0x29000000
        const val HINGE_DARK = 0x38000000
        const val HINGE_MID = 0x12000000
        const val BINDING = 0x2E000000
        const val GROOVE_DARK = 0x33000000
        const val CREASE_LIGHT = 0x3DFFFFFF
        const val SHEEN = 0x1FFFFFFF
        const val TOP_EDGE = 0x33FFFFFF
        const val BOTTOM_EDGE = 0x40000000
        const val FORE_EDGE = 0x33000000
        const val PAGE_LINE = 0x33785F3C
        const val PAGE_EDGE = 0x4D3A2A1C
    }
}
