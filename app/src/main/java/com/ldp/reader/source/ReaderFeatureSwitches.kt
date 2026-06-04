package com.ldp.reader.source

import com.ldp.reader.utils.SharedPreUtils

object ReaderFeatureSwitches {
    private const val KEY_CLEAN_CONTENT = "reader_clean_content_enabled"
    private const val KEY_CLEAN_INTRO = "reader_clean_intro_enabled"
    private const val KEY_SMART_WRONG_CHAPTER = "reader_smart_wrong_chapter_enabled"

    fun isCleanContentEnabled(): Boolean {
        return getBoolean(KEY_CLEAN_CONTENT, true)
    }

    fun setCleanContentEnabled(enabled: Boolean) {
        putBoolean(KEY_CLEAN_CONTENT, enabled)
    }

    fun isCleanIntroEnabled(): Boolean {
        return getBoolean(KEY_CLEAN_INTRO, true)
    }

    fun setCleanIntroEnabled(enabled: Boolean) {
        putBoolean(KEY_CLEAN_INTRO, enabled)
    }

    fun isSmartWrongChapterAnalysisEnabled(): Boolean {
        return getBoolean(KEY_SMART_WRONG_CHAPTER, true, storageErrorDefault = false)
    }

    fun setSmartWrongChapterAnalysisEnabled(enabled: Boolean) {
        putBoolean(KEY_SMART_WRONG_CHAPTER, enabled)
    }

    private fun getBoolean(
        key: String,
        defaultValue: Boolean,
        storageErrorDefault: Boolean = defaultValue
    ): Boolean {
        return runCatching {
            SharedPreUtils.getInstance().getBoolean(key, defaultValue)
        }.getOrDefault(storageErrorDefault)
    }

    private fun putBoolean(key: String, value: Boolean) {
        runCatching {
            SharedPreUtils.getInstance().putBoolean(key, value)
        }
    }
}
