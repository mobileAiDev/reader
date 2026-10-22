package com.ldp.reader.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.ldp.reader.utils.SharedPreUtils

/** App-wide light/dark appearance. The reader's own day/night page style is independent. */
enum class AppearanceMode(val label: String, val nightMode: Int) {
    FOLLOW_SYSTEM("跟随系统", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("浅色", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("深色", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        private const val KEY = "ink_appearance_mode"

        fun current(): AppearanceMode {
            val stored = SharedPreUtils.getInstance().getString(KEY)
            return entries.firstOrNull { it.name == stored } ?: FOLLOW_SYSTEM
        }

        fun select(mode: AppearanceMode) {
            SharedPreUtils.getInstance().putString(KEY, mode.name)
            AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        }

        fun applySaved() {
            AppCompatDelegate.setDefaultNightMode(current().nightMode)
        }

        /**
         * Application context carrying the app's chosen light/dark mode. AppCompat only applies
         * the default night mode to activities, so app-context UI (toasts) must opt in here.
         */
        fun appContext(app: Context): Context {
            val systemNight = isNight(app)
            val night = when (AppCompatDelegate.getDefaultNightMode()) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> systemNight
            }
            val config = Configuration(app.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            return app.createConfigurationContext(config)
        }

        fun isNight(context: Context): Boolean {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
