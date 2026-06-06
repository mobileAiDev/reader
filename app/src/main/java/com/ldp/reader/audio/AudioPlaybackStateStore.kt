package com.ldp.reader.audio

import android.content.Context
import com.google.gson.Gson
import com.ldp.reader.media.MediaLegadoRuleSet
import com.ldp.reader.media.MediaRouteChapterSnapshot
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaRouteSnapshot
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceBookDetail
import com.ldp.reader.media.MediaSourceChapter
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceType
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.source.AiBridgeTrace
import com.tencent.mmkv.MMKV

object AudioPlaybackStateStore {
    private const val STORE_NAME = "reader_audio_now_playing"
    private const val KEY_STATE = "state_json"
    private const val KEY_LEGACY_NOW_PLAYING = "now_playing_json"
    private const val MAX_STORE_ACTUAL_SIZE_BYTES = 256_000L
    private const val MAX_STATE_JSON_BYTES = 16_000
    private const val MAX_PERSISTED_INTRO_CHARS = 800
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
        val nextBookRouteId = bookRouteId
            .ifBlank { MediaRouteRegistry.bookRouteForChapter(chapterRouteId).orEmpty() }
            .ifBlank { nowPlaying?.bookRouteId.orEmpty() }
        nowPlaying = AudioNowPlaying(
            chapterRouteId = chapterRouteId,
            bookRouteId = nextBookRouteId,
            bookTitle = bookTitle.ifBlank { nowPlaying?.bookTitle.orEmpty() },
            title = title.ifBlank { "听书" },
            coverUrl = coverUrl.ifBlank { nowPlaying?.coverUrl.orEmpty() },
            isPlaying = playing,
            audioUrl = nowPlaying?.audioUrl.orEmpty(),
            headers = nowPlaying?.headers.orEmpty(),
            positionMs = nowPlaying?.positionMs ?: 0L,
            durationMs = nowPlaying?.durationMs ?: 0L,
            routeSnapshot = snapshotFor(chapterRouteId, nextBookRouteId)
                ?: compactSnapshot(nowPlaying?.routeSnapshot, chapterRouteId),
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
        val nextBookRouteId = bookRouteId
            .ifBlank { MediaRouteRegistry.bookRouteForChapter(chapterRouteId).orEmpty() }
            .ifBlank { if (sameChapter) previous?.bookRouteId.orEmpty() else "" }
        nowPlaying = AudioNowPlaying(
            chapterRouteId = chapterRouteId,
            bookRouteId = nextBookRouteId,
            bookTitle = bookTitle.ifBlank { if (sameChapter) previous?.bookTitle.orEmpty() else "" },
            title = title.ifBlank { previous?.title.orEmpty() }.ifBlank { "听书" },
            coverUrl = coverUrl.ifBlank { if (sameChapter) previous?.coverUrl.orEmpty() else "" },
            isPlaying = playing,
            audioUrl = audioUrl.ifBlank { if (sameChapter) previous?.audioUrl.orEmpty() else "" },
            headers = if (headers.isNotEmpty()) headers else if (sameChapter) previous?.headers.orEmpty() else emptyMap(),
            positionMs = if (sameChapter) previous?.positionMs ?: 0L else 0L,
            durationMs = if (sameChapter) previous?.durationMs ?: 0L else 0L,
            sleepTimerMinutes = if (sameChapter) previous?.sleepTimerMinutes ?: 0 else 0,
            sleepTimerEndAtMs = if (sameChapter) previous?.sleepTimerEndAtMs ?: 0L else 0L,
            routeSnapshot = snapshotFor(chapterRouteId, nextBookRouteId)
                ?: if (sameChapter) compactSnapshot(previous?.routeSnapshot, chapterRouteId) else null,
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

    fun setSleepTimer(context: Context, minutes: Int, endAtMs: Long) {
        nowPlaying = (nowPlaying ?: load(context))?.copy(
            sleepTimerMinutes = minutes,
            sleepTimerEndAtMs = endAtMs,
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
            routeSnapshot = snapshotFor(chapterRouteId, current.bookRouteId)
                ?: compactSnapshot(current.routeSnapshot, chapterRouteId),
            updatedAtMs = System.currentTimeMillis()
        )
        persist(context)
    }

    fun clear() {
        nowPlaying = null
    }

    fun clear(context: Context) {
        nowPlaying = null
        store().clearAll()
    }

    fun current(): AudioNowPlaying? = nowPlaying

    fun current(context: Context): AudioNowPlaying? {
        return (nowPlaying ?: load(context).also { nowPlaying = it })
            ?.also { restoreRouteSnapshot(it) }
    }

    fun restoreRouteSnapshot(playing: AudioNowPlaying): Boolean {
        val snapshot = playing.routeSnapshot ?: return false
        if (MediaRouteRegistry.chapter(playing.chapterRouteId) != null) return true
        MediaRouteRegistry.restore(snapshot)
        val restored = MediaRouteRegistry.chapter(playing.chapterRouteId) != null
        AiBridgeTrace.event(
            "media_audio_now_playing_route_restore",
            playing.chapterRouteId,
            AiBridgeTrace.fields("restored" to restored, "bookRoute" to snapshot.bookRouteId, "chapters" to snapshot.chapters.size)
        )
        return restored
    }

    private fun load(context: Context): AudioNowPlaying? {
        val mmkv = store()
        val sizeBefore = mmkv.actualSize()
        if (sizeBefore > MAX_STORE_ACTUAL_SIZE_BYTES) {
            clearStore(mmkv, "oversized", sizeBefore)
            return null
        }
        if (mmkv.decodeString(KEY_LEGACY_NOW_PLAYING, "").orEmpty().isNotBlank()) {
            clearStore(mmkv, "legacy_single_key_snapshot", sizeBefore)
            return null
        }
        val json = mmkv.decodeString(KEY_STATE, "").orEmpty()
        if (json.isBlank()) return null
        if (json.toByteArray(Charsets.UTF_8).size > MAX_STATE_JSON_BYTES) {
            clearStore(mmkv, "oversized_state", sizeBefore)
            return null
        }
        val persisted = runCatching { gson.fromJson(json, PersistedAudioNowPlaying::class.java) }
            .getOrElse {
                clearStore(mmkv, "decode_failed", sizeBefore)
                return null
            }
            ?: return null
        return persisted.toNowPlaying()
    }

    private fun persist(context: Context) {
        val state = compactNowPlaying(nowPlaying ?: return)
        val persisted = persistedFrom(state)
        val json = gson.toJson(persisted)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        val mmkv = store()
        if (bytes > MAX_STATE_JSON_BYTES) {
            clearStore(mmkv, "state_too_large", mmkv.actualSize())
            AiBridgeTrace.event(
                "media_audio_now_playing_write_rejected",
                state.chapterRouteId,
                AiBridgeTrace.fields("bytes" to bytes)
            )
            return
        }
        nowPlaying = state
        mmkv.removeValueForKey(KEY_LEGACY_NOW_PLAYING)
        mmkv.encode(KEY_STATE, json)
        mmkv.trim()
    }

    private fun store(): MMKV {
        return MMKV.mmkvWithID(STORE_NAME)
    }

    private fun clearStore(mmkv: MMKV, reason: String, beforeBytes: Long) {
        nowPlaying = null
        mmkv.clearAll()
        mmkv.trim()
        AiBridgeTrace.event(
            "media_audio_now_playing_store_cleared",
            STORE_NAME,
            AiBridgeTrace.fields(
                "reason" to reason,
                "beforeBytes" to beforeBytes,
                "afterBytes" to mmkv.actualSize()
            )
        )
    }

    private fun compactNowPlaying(state: AudioNowPlaying): AudioNowPlaying {
        val snapshot = snapshotFor(state.chapterRouteId, state.bookRouteId)
            ?: compactSnapshot(state.routeSnapshot, state.chapterRouteId)
        return state.copy(routeSnapshot = snapshot)
    }

    private fun snapshotFor(chapterRouteId: String, bookRouteId: String): MediaRouteSnapshot? {
        val resolvedBookRouteId = bookRouteId.ifBlank { MediaRouteRegistry.bookRouteForChapter(chapterRouteId).orEmpty() }
        if (resolvedBookRouteId.isBlank()) return null
        return MediaRouteRegistry.snapshotBookRoute(resolvedBookRouteId)
            ?.let { MediaRouteRegistry.compactSnapshot(it, chapterRouteId) }
    }

    private fun compactSnapshot(snapshot: MediaRouteSnapshot?, chapterRouteId: String): MediaRouteSnapshot? {
        return snapshot?.let { MediaRouteRegistry.compactSnapshot(it, chapterRouteId) }
    }

    private fun persistedFrom(state: AudioNowPlaying): PersistedAudioNowPlaying {
        val snapshot = state.routeSnapshot
        val chapter = snapshot?.chapters?.firstOrNull { it.routeId == state.chapterRouteId }
            ?: snapshot?.chapters?.firstOrNull()
        val book = snapshot?.book
        val source = book?.source
        return PersistedAudioNowPlaying(
            chapterRouteId = state.chapterRouteId,
            bookRouteId = state.bookRouteId.ifBlank { snapshot?.bookRouteId.orEmpty() },
            bookTitle = state.bookTitle.ifBlank { book?.name.orEmpty() },
            title = state.title,
            coverUrl = state.coverUrl.ifBlank { book?.coverUrl.orEmpty() },
            isPlaying = state.isPlaying,
            audioUrl = state.audioUrl,
            headers = state.headers,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            sleepTimerMinutes = state.sleepTimerMinutes,
            sleepTimerEndAtMs = state.sleepTimerEndAtMs,
            sourceName = source?.sourceName.orEmpty(),
            sourceUrl = source?.sourceUrl.orEmpty(),
            sourceType = source?.sourceType ?: MediaSourceType.AUDIO,
            sourceGroup = source?.sourceGroup,
            bookUrl = book?.bookUrl.orEmpty(),
            bookKind = book?.kind.orEmpty(),
            author = book?.author.orEmpty(),
            intro = book?.intro.orEmpty().take(MAX_PERSISTED_INTRO_CHARS),
            latest = book?.lastChapter.orEmpty(),
            chapterUrl = chapter?.chapter?.chapterUrl.orEmpty(),
            chapterIndex = chapter?.chapter?.index ?: -1,
            updatedAtMs = state.updatedAtMs
        )
    }

    private data class PersistedAudioNowPlaying(
        val chapterRouteId: String,
        val bookRouteId: String,
        val bookTitle: String,
        val title: String,
        val coverUrl: String,
        val isPlaying: Boolean,
        val audioUrl: String,
        val headers: Map<String, String>,
        val positionMs: Long,
        val durationMs: Long,
        val sleepTimerMinutes: Int,
        val sleepTimerEndAtMs: Long,
        val sourceName: String,
        val sourceUrl: String,
        val sourceType: Int,
        val sourceGroup: String?,
        val bookUrl: String,
        val bookKind: String,
        val author: String,
        val intro: String,
        val latest: String,
        val chapterUrl: String,
        val chapterIndex: Int,
        val updatedAtMs: Long
    ) {
        fun toNowPlaying(): AudioNowPlaying {
            val snapshot = toSnapshot()
            return AudioNowPlaying(
                chapterRouteId = chapterRouteId,
                bookRouteId = bookRouteId,
                bookTitle = bookTitle,
                title = title,
                coverUrl = coverUrl,
                isPlaying = isPlaying,
                audioUrl = audioUrl,
                headers = headers,
                positionMs = positionMs,
                durationMs = durationMs,
                sleepTimerMinutes = sleepTimerMinutes,
                sleepTimerEndAtMs = sleepTimerEndAtMs,
                routeSnapshot = snapshot,
                updatedAtMs = updatedAtMs
            )
        }

        private fun toSnapshot(): MediaRouteSnapshot? {
            if (bookRouteId.isBlank() || chapterRouteId.isBlank() || sourceUrl.isBlank() || bookUrl.isBlank()) return null
            val emptyRules = MediaLegadoRuleSet("", emptyMap())
            val source = MediaSourceDefinition(
                sourceName = sourceName,
                sourceUrl = sourceUrl,
                sourceType = sourceType,
                sourceGroup = sourceGroup,
                sourceComment = null,
                enabled = true,
                headers = emptyMap(),
                searchUrl = null,
                ruleSearch = emptyRules,
                ruleBookInfo = emptyRules,
                ruleToc = emptyRules,
                ruleContent = emptyRules,
                diagnostics = emptyList()
            )
            val book = MediaSourceBook(
                source = source,
                name = bookTitle,
                author = author,
                bookUrl = bookUrl,
                coverUrl = coverUrl,
                intro = intro.take(MAX_PERSISTED_INTRO_CHARS),
                kind = bookKind.ifBlank { ReaderMediaKind.AUDIO.seedKey },
                lastChapter = latest
            )
            val chapter = MediaSourceChapter(
                source = source,
                book = book,
                index = chapterIndex,
                name = title,
                chapterUrl = chapterUrl
            )
            return MediaRouteSnapshot(
                kind = ReaderMediaKind.AUDIO,
                bookRouteId = bookRouteId,
                book = book,
                detail = MediaSourceBookDetail(
                    book = book,
                    name = book.name,
                    author = book.author,
                    coverUrl = book.coverUrl,
                    intro = book.intro,
                    kind = book.kind,
                    lastChapter = book.lastChapter,
                    tocUrl = book.bookUrl
                ),
                chapters = listOf(MediaRouteChapterSnapshot(routeId = chapterRouteId, chapter = chapter))
            )
        }
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
    val sleepTimerMinutes: Int = 0,
    val sleepTimerEndAtMs: Long = 0L,
    val routeSnapshot: MediaRouteSnapshot? = null,
    val updatedAtMs: Long = 0L
)
