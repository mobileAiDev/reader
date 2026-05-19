package com.ldp.reader.source

import com.ldp.reader.utils.SharedPreUtils

object SourceEngineSwitch {
    private const val KEY_ENABLED = "source_engine_reader_enabled"

    fun isEnabled(): Boolean {
        return true
    }

    fun setEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        SharedPreUtils.getInstance().putBoolean(KEY_ENABLED, true)
    }
}
