package com.ldp.reader.ui.theme

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ldp.reader.R

/**
 * Content drawn under transparent system bars, with each view keeping itself clear of them.
 * Initial padding and margins are remembered so repeated inset dispatches never accumulate.
 */
object EdgeToEdge {
    private const val LEGACY_LIGHT_NAV_SCRIM = 0x66000000

    fun apply(activity: Activity) {
        val window = activity.window
        val lightBars = !AppearanceMode.isNight(activity)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        // Dark navigation icons exist from API 26; before that a scrim keeps light icons visible.
        window.navigationBarColor = if (lightBars && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            LEGACY_LIGHT_NAV_SCRIM
        } else {
            Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    /** Top padding grows by the status bar (and cutout) height. */
    fun padTop(view: View) {
        val initial = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            target.setPadding(target.paddingLeft, initial + top, target.paddingRight, target.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** Bottom padding grows by the navigation bar height plus [extraPx] for floating chrome. */
    fun padBottom(view: View, extraPx: Int) {
        val initial = view.getTag(R.id.ink_initial_padding_bottom) as? Int ?: view.paddingBottom
        view.setTag(R.id.ink_initial_padding_bottom, initial)
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, initial + bottom + extraPx)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** Bottom margin grows by the navigation bar height: floating bars sit above the gesture area. */
    fun liftAboveNavigation(view: View) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        val initial = params.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val lp = target.layoutParams as ViewGroup.MarginLayoutParams
            if (lp.bottomMargin != initial + bottom) {
                lp.bottomMargin = initial + bottom
                target.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
