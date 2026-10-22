package com.ldp.reader.ui.theme

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import com.ldp.reader.R
import com.ldp.reader.utils.SharedPreUtils
import java.util.WeakHashMap

/**
 * One app theme: a whole visual language (palette, page dressing, covers, title type) with a
 * light and a dark variant.
 *
 * Adding a theme takes three steps and no code elsewhere:
 *  1. Add the style `InkTheme.<Name>` to res/values/themes_ink.xml and res/values-night/themes_ink.xml,
 *     defining every attribute of res/values/attrs_ink_theme.xml (InkThemeContractTest checks).
 *  2. Add an [InkTheme] to [InkThemes.all].
 *  3. Add its name and summary strings.
 */
data class InkTheme(
    val id: String,
    @StringRes val name: Int,
    @StringRes val summary: Int,
    @StyleRes val overlay: Int
)

/** The catalogue of themes and the one place the chosen theme is stored and applied. */
object InkThemes {
    val INK_WASH = InkTheme("ink_wash", R.string.theme_ink_wash, R.string.theme_ink_wash_summary, R.style.InkTheme_InkWash)
    val MODERN = InkTheme("modern", R.string.theme_modern, R.string.theme_modern_summary, R.style.InkTheme_Modern)
    val BOOKSHELF = InkTheme("bookshelf", R.string.theme_bookshelf, R.string.theme_bookshelf_summary, R.style.InkTheme_Bookshelf)

    /** In the order the picker shows them; the first is the default. */
    val all = listOf(INK_WASH, MODERN, BOOKSHELF)

    private const val KEY = "ink_theme"

    /** The theme each live activity was created with, to recreate it after a change. */
    private val appliedThemes = WeakHashMap<Activity, InkTheme>()

    fun current(): InkTheme {
        val stored = SharedPreUtils.getInstance().getString(KEY)
        return all.firstOrNull { it.id == stored } ?: all.first()
    }

    /** Stores [theme]; the calling activity recreates itself, the others when they come back. */
    fun select(app: Application, theme: InkTheme) {
        SharedPreUtils.getInstance().putString(KEY, theme.id)
        app.theme.applyStyle(theme.overlay, true)
    }

    /**
     * Applies the stored theme to every activity as it is created, before any view inflates,
     * and recreates an activity that resumes under a theme other than the one it was built with.
     */
    fun install(app: Application) {
        app.theme.applyStyle(current().overlay, true)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val theme = current()
                activity.theme.applyStyle(theme.overlay, true)
                // The manifest theme could only give the window a fixed paper; repaint it in the theme.
                activity.window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(activity, R.color.ink_bg)))
                appliedThemes[activity] = theme
            }

            override fun onActivityResumed(activity: Activity) {
                if (appliedThemes[activity] != current()) activity.recreate()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                appliedThemes.remove(activity)
            }
        })
    }

    /** [context] dressed in the app theme and the chosen InkTheme, for UI built off an activity. */
    fun themed(context: Context): Context {
        return ContextThemeWrapper(context, R.style.AppTheme).apply {
            theme.applyStyle(current().overlay, true)
        }
    }
}
