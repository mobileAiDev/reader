package com.ldp.reader.ui.image

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.ImageView
import com.ldp.reader.R

/**
 * A soft wash of a cover's own colours behind the top of a detail page: a heavily blurred copy
 * of the cover. RenderEffect blur exists from Android 12; earlier pages keep the plain paper.
 */
object AmbientCover {
    private const val BLUR_DP = 56f

    fun show(ambient: ImageView, covers: List<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = BLUR_DP * ambient.resources.displayMetrics.density
        ambient.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR))
        ambient.visibility = View.VISIBLE
        BookCoverLoader.load(covers, ambient, R.color.transparent)
    }
}
