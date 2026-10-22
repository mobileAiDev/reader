package com.ldp.reader.ui.glass

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import android.view.ViewTreeObserver
import com.ldp.reader.ui.motion.Squircle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Paints the glass behind one view's content. Live glass shows what lies behind it on screen,
 * recorded each frame something below changes. The host forwards attach, detach and draw.
 * Adapted from the duo host UI.
 */
@SuppressLint("NewApi")
class GlassPainter(private val host: View, style: GlassStyle, live: Boolean) {
    var style: GlassStyle = style
        set(value) {
            field = value
            host.invalidate()
        }

    /** Continuous corner radius in px; negative for a capsule. */
    var cornerRadius = -1f
        set(value) {
            field = value
            host.invalidateOutline()
            host.invalidate()
        }

    private val density = host.resources.displayMetrics.density
    private val bounds = RectF()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private val lens: RuntimeShader? = if (Glass.LENS) RuntimeShader(Glass.SHADER) else null
    private val node: RenderNode? = if (live && Glass.LIVE) RenderNode("glass") else null
    private var margin = 0
    private var observer: ViewTreeObserver? = null
    private val capture = ViewTreeObserver.OnPreDrawListener {
        capture()
        true
    }

    fun attach() {
        observer = host.viewTreeObserver.also { tree ->
            if (node != null) tree.addOnPreDrawListener(capture)
        }
    }

    fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(capture)
        observer = null
        node?.discardDisplayList()
    }

    fun radius(width: Float, height: Float): Float {
        return if (cornerRadius < 0f) min(width, height) / 2f else cornerRadius
    }

    fun draw(canvas: Canvas) {
        bounds.set(0f, 0f, host.width.toFloat(), host.height.toFloat())
        if (bounds.width() < 1f || bounds.height() < 1f) return
        val radius = radius(bounds.width(), bounds.height())
        val liveNode = node
        if (liveNode != null && canvas.isHardwareAccelerated && liveNode.hasDisplayList()) {
            drawLive(canvas, liveNode, radius)
            return
        }
        // Before Android 12, or where the host opts out, there is no live backdrop: a frosted fill with the same rim.
        Squircle.path(path, bounds, radius)
        tintPaint.color = style.fallback
        canvas.drawPath(path, tintPaint)
        drawRim(canvas, radius)
    }

    private fun drawLive(canvas: Canvas, liveNode: RenderNode, radius: Float) {
        applyEffect(liveNode, radius)
        canvas.save()
        if (lens == null) {
            Squircle.path(path, bounds, radius)
            canvas.clipPath(path)
        }
        canvas.drawRenderNode(liveNode)
        canvas.restore()
        if (lens == null) {
            tintPaint.color = style.tint
            canvas.drawPath(path, tintPaint)
            drawRim(canvas, radius)
        }
    }

    /** The rim light as a stroke lit from the top left (the lens shader draws its own). */
    private fun drawRim(canvas: Canvas, radius: Float) {
        Squircle.path(path, bounds, radius)
        val light = style.highlight
        rimPaint.strokeWidth = max(1f, density * 0.8f)
        rimPaint.shader = LinearGradient(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            intArrayOf(white(0.55f * light), white(0.08f * light), white(0.28f * light)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, rimPaint)
    }

    /**
     * Records what lies behind the host into the glass node, in host coordinates, with a margin
     * the blur and the rim can reach into.
     */
    private fun capture() {
        val liveNode = node ?: return
        if (!host.isShown || host.width == 0) return
        margin = (style.depth * density + style.blur * density * 2f + 2f).roundToInt()
        val width = host.width + margin * 2
        val height = host.height + margin * 2
        liveNode.setPosition(-margin, -margin, width - margin, height - margin)
        Glass.localToRoot(host, matrix)
        matrix.invert(matrix)
        val recording = liveNode.beginRecording(width, height)
        recording.translate(margin.toFloat(), margin.toFloat())
        recording.concat(matrix)
        Glass.drawBehind(host, recording)
        liveNode.endRecording()
    }

    private fun applyEffect(liveNode: RenderNode, radius: Float) {
        val blur = style.blur * density
        val frost = if (blur >= 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null
        val shader = lens
        if (shader == null) {
            liveNode.setRenderEffect(frost)
            return
        }
        setUniforms(shader, radius)
        val bend = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        liveNode.setRenderEffect(if (frost == null) bend else RenderEffect.createChainEffect(bend, frost))
    }

    private fun setUniforms(shader: RuntimeShader, radius: Float) {
        val glass = style
        shader.setFloatUniform("size", bounds.width(), bounds.height())
        shader.setFloatUniform("origin", margin.toFloat(), margin.toFloat())
        shader.setFloatUniform("extent", Squircle.extent(bounds, radius))
        shader.setFloatUniform("exponent", Squircle.exponent(bounds, radius))
        shader.setFloatUniform(
            "bezel",
            max(1f, min(glass.bezel * density, min(bounds.width(), bounds.height()) / 2f))
        )
        shader.setFloatUniform("depth", glass.depth * density)
        shader.setFloatUniform("dispersion", glass.dispersion)
        shader.setFloatUniform("saturation", glass.saturation)
        val tint = glass.tint
        shader.setFloatUniform(
            "tint",
            ((tint shr 16) and 0xFF) / 255f,
            ((tint shr 8) and 0xFF) / 255f,
            (tint and 0xFF) / 255f,
            (tint ushr 24) / 255f
        )
        shader.setFloatUniform("highlight", glass.highlight)
    }

    private fun white(alpha: Float): Int = (Math.round(alpha.coerceIn(0f, 1f) * 255) shl 24) or 0xFFFFFF
}
