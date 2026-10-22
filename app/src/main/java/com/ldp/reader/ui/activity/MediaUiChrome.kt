package com.ldp.reader.ui.activity

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ldp.reader.R
import com.ldp.reader.ui.theme.InkSystemBars
import java.util.WeakHashMap

internal object MediaUiChrome {
    private const val LEGACY_LIGHT_PAGE_NAV_SCRIM = 0x66000000
    private val initialPaddings = WeakHashMap<View, ViewPadding>()
    private val initialMargins = WeakHashMap<View, ViewMargins>()

    /** Regular (non-immersive) media pages: page-coloured bars that follow light/dark mode. */
    fun light(activity: Activity) {
        InkSystemBars.apply(activity)
    }

    fun darkReader(activity: Activity) {
        val surface = ContextCompat.getColor(activity, R.color.media_reader_dark)
        activity.window.statusBarColor = surface
        activity.window.navigationBarColor = surface
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /**
     * @param lightBars true when the visible chrome is light (e.g. a paper reading page), so
     * the status/navigation icons switch to dark for legibility.
     */
    fun immersiveReader(activity: Activity, controlsVisible: Boolean, lightBars: Boolean = false) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.applyReaderSystemUi(controlsVisible, lightBars)
    }

    fun prepareImmersiveReader(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.allowReaderDisplayCutout()
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /**
     * Applies the current system-bar and cutout safe area to a reader-owned panel on every
     * inset dispatch. The original XML padding is retained as the baseline, so redispatching
     * after rotation, navigation-mode changes, or transient-bar animation never accumulates.
     */
    fun applyReaderContentInsets(content: View) {
        val initial = content.initialPadding()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initial.left + safeInsets.left,
                initial.top + safeInsets.top,
                initial.right + safeInsets.right,
                initial.bottom + safeInsets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(content)
    }

    /**
     * Keeps readable content outside short-edge display cutouts in landscape while preserving
     * the immersive edge-to-edge background. Vertical padding is left unchanged so the WebView's
     * scroll range and reading-position restoration keep using the real viewport height.
     */
    fun applyReaderCutoutInsets(content: View) {
        val initial = content.initialPadding()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                initial.left + cutoutInsets.left,
                initial.top,
                initial.right + cutoutInsets.right,
                initial.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(content)
    }

    /**
     * Keeps the compact hidden-chrome progress indicator outside mandatory gesture and cutout
     * regions without reserving the larger transient system-bar area around reading content.
     */
    fun applyReaderProgressInsets(progress: View) {
        val initial = progress.initialMargins() ?: return
        val insetHost = progress.parent as? View ?: progress
        ViewCompat.setOnApplyWindowInsetsListener(insetHost) { _, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.mandatorySystemGestures() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            val params = progress.layoutParams as? ViewGroup.MarginLayoutParams
                ?: return@setOnApplyWindowInsetsListener windowInsets
            params.setMargins(
                initial.left + safeInsets.left,
                initial.top,
                initial.right + safeInsets.right,
                initial.bottom + safeInsets.bottom
            )
            progress.layoutParams = params
            windowInsets
        }
        ViewCompat.requestApplyInsets(insetHost)
    }

    private fun Window.applyReaderSystemUi(controlsVisible: Boolean, lightBars: Boolean) {
        allowReaderDisplayCutout()
        statusBarColor = android.graphics.Color.TRANSPARENT
        // Before API 26 navigation icons are always light; a scrim keeps them readable on paper.
        navigationBarColor = if (lightBars && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            LEGACY_LIGHT_PAGE_NAV_SCRIM
        } else {
            android.graphics.Color.TRANSPARENT
        }
        WindowCompat.getInsetsController(this, decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
            if (controlsVisible) {
                show(WindowInsetsCompat.Type.systemBars())
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        // API 25-29 still needs the layout flags so content remains edge-to-edge. Visibility
        // itself is controlled by WindowInsetsControllerCompat above.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    if (controlsVisible) {
                        0
                    } else {
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    }
        }
    }

    private data class ViewPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class ViewMargins(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun View.initialPadding(): ViewPadding {
        return initialPaddings.getOrPut(this) {
            ViewPadding(
                left = paddingLeft,
                top = paddingTop,
                right = paddingRight,
                bottom = paddingBottom
            )
        }
    }

    private fun View.initialMargins(): ViewMargins? {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return null
        return initialMargins.getOrPut(this) {
            ViewMargins(
                left = params.leftMargin,
                top = params.topMargin,
                right = params.rightMargin,
                bottom = params.bottomMargin
            )
        }
    }

    private fun Window.allowReaderDisplayCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        attributes = attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
