package com.ldp.reader.audio

import android.content.Context

object AudioPlaybackProgressStore {
    private const val STORE_NAME = "reader_audio_progress"
    private const val POSITION_SUFFIX = ":position"
    private const val DURATION_SUFFIX = ":duration"

    fun save(context: Context, chapterRouteId: String, positionMs: Long, durationMs: Long) {
        if (chapterRouteId.isBlank()) return
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.takeIf { it > 0L } ?: 0L
        context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(chapterRouteId + POSITION_SUFFIX, position)
            .putLong(chapterRouteId + DURATION_SUFFIX, duration)
            .apply()
    }

    fun position(context: Context, chapterRouteId: String): Long {
        if (chapterRouteId.isBlank()) return 0L
        return context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .getLong(chapterRouteId + POSITION_SUFFIX, 0L)
            .coerceAtLeast(0L)
    }

    fun clear(context: Context, chapterRouteId: String) {
        if (chapterRouteId.isBlank()) return
        context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(chapterRouteId + POSITION_SUFFIX)
            .remove(chapterRouteId + DURATION_SUFFIX)
            .apply()
    }
}
