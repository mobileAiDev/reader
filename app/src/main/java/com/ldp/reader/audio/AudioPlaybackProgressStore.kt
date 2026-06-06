package com.ldp.reader.audio

import android.content.Context
import com.tencent.mmkv.MMKV

object AudioPlaybackProgressStore {
    private const val STORE_NAME = "reader_audio_progress"
    private const val POSITION_SUFFIX = ":position"
    private const val DURATION_SUFFIX = ":duration"

    fun save(context: Context, chapterRouteId: String, positionMs: Long, durationMs: Long) {
        if (chapterRouteId.isBlank()) return
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.takeIf { it > 0L } ?: 0L
        val mmkv = store(context)
        mmkv.encode(chapterRouteId + POSITION_SUFFIX, position)
        mmkv.encode(chapterRouteId + DURATION_SUFFIX, duration)
    }

    fun position(context: Context, chapterRouteId: String): Long {
        if (chapterRouteId.isBlank()) return 0L
        return store(context)
            .decodeLong(chapterRouteId + POSITION_SUFFIX, 0L)
            .coerceAtLeast(0L)
    }

    fun duration(context: Context, chapterRouteId: String): Long {
        if (chapterRouteId.isBlank()) return 0L
        return store(context)
            .decodeLong(chapterRouteId + DURATION_SUFFIX, 0L)
            .coerceAtLeast(0L)
    }

    fun clear(context: Context, chapterRouteId: String) {
        if (chapterRouteId.isBlank()) return
        val mmkv = store(context)
        mmkv.removeValueForKey(chapterRouteId + POSITION_SUFFIX)
        mmkv.removeValueForKey(chapterRouteId + DURATION_SUFFIX)
    }

    private fun store(context: Context): MMKV {
        return MMKV.mmkvWithID(STORE_NAME)
    }
}
