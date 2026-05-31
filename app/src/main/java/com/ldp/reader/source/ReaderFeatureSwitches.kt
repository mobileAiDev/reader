package com.ldp.reader.source

import com.ldp.reader.utils.SharedPreUtils

object ReaderFeatureSwitches {
    private const val KEY_CLEAN_CONTENT = "reader_clean_content_enabled"
    private const val KEY_CLEAN_INTRO = "reader_clean_intro_enabled"
    private const val KEY_SMART_WRONG_CHAPTER = "reader_smart_wrong_chapter_enabled"

    fun isCleanContentEnabled(): Boolean {
        return SharedPreUtils.getInstance().getBoolean(KEY_CLEAN_CONTENT, true)
    }

    fun setCleanContentEnabled(enabled: Boolean) {
        SharedPreUtils.getInstance().putBoolean(KEY_CLEAN_CONTENT, enabled)
    }

    fun isCleanIntroEnabled(): Boolean {
        return SharedPreUtils.getInstance().getBoolean(KEY_CLEAN_INTRO, true)
    }

    fun setCleanIntroEnabled(enabled: Boolean) {
        SharedPreUtils.getInstance().putBoolean(KEY_CLEAN_INTRO, enabled)
    }

    fun isSmartWrongChapterAnalysisEnabled(): Boolean {
        return SharedPreUtils.getInstance().getBoolean(KEY_SMART_WRONG_CHAPTER, true)
    }

    fun setSmartWrongChapterAnalysisEnabled(enabled: Boolean) {
        SharedPreUtils.getInstance().putBoolean(KEY_SMART_WRONG_CHAPTER, enabled)
    }
}
