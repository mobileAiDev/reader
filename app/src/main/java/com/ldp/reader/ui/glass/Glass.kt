package com.ldp.reader.ui.glass

import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.view.View
import android.view.ViewGroup

/**
 * Liquid glass for floating chrome. Android 13+ bends light at the rim with one shader; Android
 * 12 blurs what lies behind and draws the rim; earlier releases show a frosted tint with the
 * same rim. Main thread only. Adapted from the duo host UI.
 */
object Glass {
    /** What lies behind on screen can be recorded and blurred live. */
    val LIVE = Build.VERSION.SDK_INT >= 31

    /** The rim bends what shows through it. */
    val LENS = Build.VERSION.SDK_INT >= 33

    /**
     * One glass surface: a rectangle with continuous corners (a superellipse of `exponent` over
     * `extent` at each corner). Inside the `bezel` the backdrop is sampled further in from the
     * rim, so what lies under the glass stretches out to its edge as a thick convex rim bends it;
     * colour channels part slightly there. A thin light line follows the rim, brightest where it
     * faces the light from the top left. Deep inside, pixels take one sample and skip the normal.
     */
    const val SHADER = """
uniform shader backdrop;
uniform float2 size;
uniform float2 origin;
uniform float extent;
uniform float exponent;
uniform float bezel;
uniform float depth;
uniform float dispersion;
uniform float saturation;
uniform float4 tint;
uniform float highlight;
float3 colour(float3 col) {
  float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
  col = clamp(mix(float3(luma), col, saturation), 0.0, 1.0);
  return mix(col, tint.rgb, tint.a);
}
half4 main(float2 coord) {
  float2 p = coord - origin - size * 0.5;
  float2 q = abs(p) - size * 0.5 + extent;
  float2 c = max(q, 0.0);
  float d = pow(pow(c.x, exponent) + pow(c.y, exponent), 1.0 / exponent)
      + min(max(q.x, q.y), 0.0) - extent;
  if (d > 1.0) { return half4(0.0); }
  if (-d >= max(bezel, 1.6)) { return half4(half3(colour(backdrop.eval(coord).rgb)), 1.0); }
  float2 g = c.x + c.y > 0.0 ? pow(c, float2(exponent - 1.0))
      : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
  float2 n = sign(p) * g / max(length(g), 0.0001);
  float rim = 1.0 - clamp(-d / bezel, 0.0, 1.0);
  float lens = 1.0 - sqrt(max(1.0 - rim * rim, 0.0));
  float2 shift = n * depth * lens;
  float2 at = coord - shift;
  float2 split = shift * dispersion * 0.5;
  float3 col = colour(float3(backdrop.eval(at + split).r, backdrop.eval(at).g, backdrop.eval(at - split).b));
  float facing = dot(n, float2(-0.48, -0.88));
  float lit = max(facing, 0.0);
  float back = max(-facing, 0.0);
  float spec = lit * lit + 0.5 * back * back;
  float line = 1.0 - smoothstep(0.0, 1.6, -d);
  float sheen = 1.0 - smoothstep(0.0, bezel * 0.75, -d);
  col += highlight * (line * (0.14 + 0.5 * spec) + sheen * spec * 0.07);
  col -= highlight * sheen * max(n.y, 0.0) * 0.04;
  col = clamp(col, 0.0, 1.0);
  float alpha = clamp(0.5 - d, 0.0, 1.0);
  return half4(half3(col) * half(alpha), half(alpha));
}
"""

    /**
     * Draws everything behind [view], in its root's coordinates: the window background, then for
     * each ancestor its background and the children drawn before the branch holding [view].
     * Nothing in front of the view, and not the view itself, so glass never shows itself.
     */
    fun drawBehind(view: View, canvas: Canvas) {
        val group = view.parent as? ViewGroup
        if (group == null) {
            view.background?.draw(canvas)
            return
        }
        drawBehind(group, canvas)
        canvas.save()
        val matrix = Matrix()
        localToRoot(group, matrix)
        canvas.concat(matrix)
        if (group.parent is ViewGroup) group.background?.draw(canvas)
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child === view) break
            if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            canvas.save()
            canvas.translate((child.left - group.scrollX).toFloat(), (child.top - group.scrollY).toFloat())
            canvas.concat(child.matrix)
            if (child.alpha < 1f) {
                canvas.saveLayerAlpha(0f, 0f, child.width.toFloat(), child.height.toFloat(), Math.round(255 * child.alpha))
            }
            child.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    /** [out] maps [view]'s local coordinates to its root's, with every transform on the way. */
    fun localToRoot(view: View, out: Matrix) {
        out.reset()
        var node = view
        while (node.parent is View) {
            val parent = node.parent as View
            out.postConcat(node.matrix)
            out.postTranslate((node.left - parent.scrollX).toFloat(), (node.top - parent.scrollY).toFloat())
            node = parent
        }
    }
}
