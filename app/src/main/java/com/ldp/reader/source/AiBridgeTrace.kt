package com.ldp.reader.source

import com.ldp.reader.BuildConfig

internal object AiBridgeTrace {
    fun state(name: String, key: String, value: String) {
        record("recordState", name, key, value)
    }

    fun event(name: String, key: String, value: String) {
        record("recordEvent", name, key, value)
    }

    private fun record(methodName: String, first: String, second: String, third: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val bridge = Class.forName("io.github.mobileaidev.aiappbridge.android.AiAppBridge")
            bridge
                .getMethod(methodName, String::class.java, String::class.java, String::class.java)
                .invoke(null, first, second, third)
        }
    }
}
