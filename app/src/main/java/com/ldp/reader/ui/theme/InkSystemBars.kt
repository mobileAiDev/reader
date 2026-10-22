package com.ldp.reader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ldp.reader.R

/**
 * Paints the status and navigation bars to match the page surface and picks icon contrast from
 * the current light/dark configuration, so no screen hard-codes light-only system bars.
 */
object InkSystemBars {
    fun apply(
        activity: Activity,
        @ColorRes statusColor: Int = R.color.ink_bg,
        @ColorRes navigationColor: Int = R.color.ink_bg
    ) {
        val window = activity.window
        val lightBars = !AppearanceMode.isNight(activity)
        // Dark navigation icons exist from API 26; older systems keep a dark bar so the
        // light icons stay visible.
        val lightNavigationSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        window.statusBarColor = ContextCompat.getColor(activity, statusColor)
        window.navigationBarColor = ContextCompat.getColor(
            activity,
            if (lightNavigationSupported) navigationColor else R.color.ink_system_nav
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars && lightNavigationSupported
        }
    }
}
