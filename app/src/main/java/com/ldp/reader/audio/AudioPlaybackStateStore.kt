package com.ldp.reader.audio

import android.content.Context
import com.google.gson.Gson

object AudioPlaybackStateStore {
    private const val STORE_NAME = "reader_audio_now_playing"
    private const val KEY_NOW_PLAYING = "now_playing_json"
    private val gson = Gson()

    @Volatile
    private var nowPlaying: AudioNowPlaying? = null

    fun setNowPlaying(
        chapterRouteId: String,
        title: String,
        bookRouteId: String = "",
        coverUrl: String = "",
        bookTitle: String = ""
    ) {
        if (chapterRouteId.isBlank()) return
        val playing = nowPlaying?.isPlaying ?: false
        nowPlaying = AudioNowPlaying(
            chapterRouteId = chapterRouteId,
            bookRouteId = bookRouteId.ifBlank { nowPlaying?.bookRouteId.orEmpty() },
            bookTitle = bookTitle.ifBlank { nowPlaying?.bookTitle.orEmpty() },
            title = title.ifBlank { "听书" },
            coverUrl = coverUrl.ifBlank { nowPlaying?.coverUrl.orEmpty() },
            isPlaying = playing,
            audioUrl = nowPlaying?.audioUrl.orEmpty(),
            headers = nowPlaying?.headers.orEmpty(),
            positionMs = nowPlaying?.positionMs ?: 0L,
            durationMs = nowPlaying?.durationMs ?: 0L,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun setNowPlaying(
        context: Context,
        chapterRouteId: String,
        title: String,
        bookRouteId: String = "",
        coverUrl: String = "",
        bookTitle: String = "",
        audioUrl: String = "",
        headers: Map<String, String> = emptyMap()
    ) {
        if (chapterRouteId.isBlank()) return
        val previous = nowPlaying ?: load(context)
        val sameChapter = previous?.chapterRouteId == chapterRouteId
        val playing = previous?.isPlaying ?: false
        nowPlaying = AudioNowPlaying(
            chapterRouteId = chapterRouteId,
            bookRouteId = bookRouteId.ifBlank { if (sameChapter) previous?.bookRouteId.orEmpty() else "" },
            bookTitle = bookTitle.ifBlank { if (sameChapter) previous?.bookTitle.orEmpty() else "" },
            title = title.ifBlank { previous?.title.orEmpty() }.ifBlank { "听书" },
            coverUrl = coverUrl.ifBlank { if (sameChapter) previous?.coverUrl.orEmpty() else "" },
            isPlaying = playing,
            audioUrl = audioUrl.ifBlank { if (sameChapter) previous?.audioUrl.orEmpty() else "" },
            headers = if (headers.isNotEmpty()) headers else if (sameChapter) previous?.headers.orEmpty() else emptyMap(),
            positionMs = if (sameChapter) previous?.positionMs ?: 0L else 0L,
            durationMs = if (sameChapter) previous?.durationMs ?: 0L else 0L,
            updatedAtMs = System.currentTimeMillis()
        )
        persist(context)
    }

    fun setPlaying(isPlaying: Boolean) {
        nowPlaying = nowPlaying?.copy(isPlaying = isPlaying)
    }

    fun setPlaying(context: Context, isPlaying: Boolean) {
        nowPlaying = (nowPlaying ?: load(context))?.copy(
            isPlaying = isPlaying,
            updatedAtMs = System.currentTimeMillis()
        )
        persist(context)
    }

    fun setProgress(context: Context, chapterRouteId: String, positionMs: Long, durationMs: Long) {
        if (chapterRouteId.isBlank()) return
        val current = nowPlaying ?: load(context)
        if (current?.chapterRouteId != chapterRouteId) return
        nowPlaying = current.copy(
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.takeIf { it > 0L } ?: current.durationMs,
            updatedAtMs = System.currentTimeMillis()
        )
        persist(context)
    }

    fun clear() {
        nowPlaying = null
    }

    fun clear(context: Context) {
        nowPlaying = null
        context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_NOW_PLAYING)
            .apply()
    }

    fun current(): AudioNowPlaying? = nowPlaying

    fun current(context: Context): AudioNowPlaying? {
        return nowPlaying ?: load(context).also { nowPlaying = it }
    }

    private fun load(context: Context): AudioNowPlaying? {
        val json = context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NOW_PLAYING, "")
            .orEmpty()
        if (json.isBlank()) return null
        return runCatching { gson.fromJson(json, AudioNowPlaying::class.java) }.getOrNull()
    }

    private fun persist(context: Context) {
        val state = nowPlaying ?: return
        context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOW_PLAYING, gson.toJson(state))
            .apply()
    }
}

data class AudioNowPlaying(
    val chapterRouteId: String,
    val bookRouteId: String = "",
    val bookTitle: String = "",
    val title: String,
    val coverUrl: String = "",
    val isPlaying: Boolean = false,
    val audioUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L
)
