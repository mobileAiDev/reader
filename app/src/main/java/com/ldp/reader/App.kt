package com.ldp.reader

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.ldp.reader.source.BookContentProviderRouter
import com.mob.MobSDK
import com.tencent.bugly.crashreport.CrashReport
import com.tencent.mmkv.MMKV

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        sInstance = this
        MMKV.initialize(this)
        CrashReport.initCrashReport(applicationContext, "ab86f05cf4", true)

        MobSDK.submitPolicyGrantResult(true)
        BookContentProviderRouter.startLowPriorityV5Maintenance()
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
