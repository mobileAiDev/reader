package com.ldp.reader

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.ldp.reader.diagnostics.ReaderProcessExitReporter
import com.ldp.reader.media.LegacyMediaShelfMigration
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.ui.theme.AppearanceMode
import com.ldp.reader.ui.theme.InkThemes
import com.ldp.reader.utils.LegacyXmlPrefsCleaner
import com.mob.MobSDK
import com.tencent.bugly.crashreport.CrashReport
import com.tencent.mmkv.MMKV

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        sInstance = this
        if (BuildConfig.DEBUG) {
            ReaderProcessExitReporter.recordRecent(this)
        }
        MMKV.initialize(this)
        AppearanceMode.applySaved()
        InkThemes.install(this)
        LegacyMediaShelfMigration.migrate(this)
        LegacyXmlPrefsCleaner.clearMediaPrefs(this)
        CrashReport.initCrashReport(applicationContext, "ab86f05cf4", true)

        MobSDK.submitPolicyGrantResult(true)
        BookContentProviderRouter.startLowPriorityV8Maintenance()
        if (BuildConfig.DEBUG) {
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    companion object {
        private var sInstance: Context? = null

        @JvmStatic
        fun getContext(): Context {
            return sInstance!!
        }
    }
}
