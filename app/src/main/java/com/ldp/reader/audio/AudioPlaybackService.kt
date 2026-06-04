package com.ldp.reader.audio

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ldp.reader.media.MediaPlaybackHeaders
import com.ldp.reader.media.MediaPlaybackTlsPolicy
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.source.AiBridgeTrace
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import okhttp3.OkHttpClient
import org.json.JSONObject

class AudioPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentChapterRouteId: String = ""
    private var currentBookRouteId: String = ""
    private var currentBookTitle: String = ""
    private var currentCoverUrl: String = ""
    private var currentHeaders: Map<String, String> = emptyMap()
    private var forceStartCurrent: Boolean = false
    private var autoPlayCurrent: Boolean = true
    private var retriedWithPlaybackHeaders: Boolean = false
    private var retriedWithoutReferer: Boolean = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            saveCurrentProgress()
            progressHandler.postDelayed(this, 1_500L)
        }
    }
    private val audioHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .hostnameVerifier { host, session -> verifyAudioHostname(host, session) }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this)
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(
                            TAG,
                            "Audio playback error code=${error.errorCodeName} response=${error.responseCode()} url=${currentUrl.safeUrlForLog()} headers=${currentHeaders.keysForLog()}",
                            error
                        )
                        retryAfterHttpError(error)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        AudioPlaybackStateStore.setPlaying(this@AudioPlaybackService, isPlaying)
                        tracePlaybackState("playing_$isPlaying")
                        if (isPlaying) {
                            startProgressTicker()
                        } else {
                            saveCurrentProgress()
                            stopProgressTicker()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        tracePlaybackState("state_$playbackState")
                        if (playbackState == Player.STATE_ENDED) {
                            AudioPlaybackStateStore.setPlaying(this@AudioPlaybackService, false)
                            AudioPlaybackProgressStore.clear(this@AudioPlaybackService, currentChapterRouteId)
                            stopProgressTicker()
                        }
                    }
                })
            }
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setId("reader_audio_session")
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                player?.let { player ->
                    if (player.isPlaying) player.pause() else player.play()
                }
                return super.onStartCommand(intent, flags, startId)
            }
            ACTION_STOP -> {
                saveCurrentProgress()
                player?.pause()
                AudioPlaybackStateStore.clear(this)
                clearLegacyNotification()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isNotBlank()) {
            val chapterRouteId = intent?.getStringExtra(EXTRA_CHAPTER_ROUTE_ID).orEmpty()
            val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
            val bookRouteId = intent?.getStringExtra(EXTRA_BOOK_ROUTE_ID).orEmpty()
            val bookTitle = intent?.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
            val coverUrl = intent?.getStringExtra(EXTRA_COVER_URL).orEmpty()
            val forceStart = intent?.getBooleanExtra(EXTRA_FORCE_START, false) == true
            val autoPlay = intent?.getBooleanExtra(EXTRA_AUTO_PLAY, true) != false
            if (
                chapterRouteId.isNotBlank() &&
                chapterRouteId == currentChapterRouteId &&
                url == currentUrl &&
                (player?.mediaItemCount ?: 0) > 0
            ) {
                if (forceStart) {
                    AudioPlaybackProgressStore.clear(this, chapterRouteId)
                    player?.seekTo(0L)
                }
                AiBridgeTrace.event(
                    "media_audio_service_reuse",
                    chapterRouteId,
                    AiBridgeTrace.fields("title" to title, "url" to url.audioTraceToken(), "forceStart" to forceStart)
                )
                currentTitle = title.ifBlank { currentTitle }
                currentBookRouteId = bookRouteId.ifBlank { currentBookRouteId }
                currentBookTitle = bookTitle.ifBlank { currentBookTitle }
                currentCoverUrl = coverUrl.ifBlank { currentCoverUrl }
                AudioPlaybackStateStore.setNowPlaying(
                    this,
                    currentChapterRouteId,
                    currentTitle,
                    currentBookRouteId,
                    currentCoverUrl,
                    bookTitle = currentBookTitle,
                    audioUrl = currentUrl,
                    headers = currentHeaders
                )
                MediaShelfStore.updateAudioChapter(this, currentChapterRouteId, currentTitle)
                if (autoPlay) {
                    player?.play()
                }
                return super.onStartCommand(intent, flags, startId)
            }
            saveCurrentProgress()
            stopProgressTicker()
            player?.apply {
                stop()
                clearMediaItems()
            }
            currentUrl = url
            currentTitle = title
            currentChapterRouteId = chapterRouteId
            currentBookRouteId = bookRouteId
            currentBookTitle = bookTitle
            currentCoverUrl = coverUrl
            forceStartCurrent = forceStart
            autoPlayCurrent = autoPlay
            currentHeaders = MediaPlaybackHeaders.audio(
                decodeHeaders(intent?.getStringExtra(EXTRA_HEADERS_JSON).orEmpty())
            )
            AiBridgeTrace.event(
                "media_audio_service_start",
                currentChapterRouteId,
                AiBridgeTrace.fields("title" to currentTitle, "url" to currentUrl.audioTraceToken(), "forceStart" to forceStart)
            )
            retriedWithPlaybackHeaders = false
            retriedWithoutReferer = false
            AudioPlaybackStateStore.setNowPlaying(
                this,
                currentChapterRouteId,
                currentTitle,
                currentBookRouteId,
                currentCoverUrl,
                bookTitle = currentBookTitle,
                audioUrl = currentUrl,
                headers = currentHeaders
            )
            MediaShelfStore.updateAudioChapter(this, currentChapterRouteId, currentTitle)
            if (!autoPlayCurrent) {
                AudioPlaybackStateStore.setPlaying(this, false)
            }
            clearLegacyNotification()
            playCurrent(currentHeaders)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun playCurrent(headers: Map<String, String>) {
        val upstreamDataSourceFactory = OkHttpDataSource.Factory(audioHttpClient)
            .setUserAgent(MediaPlaybackHeaders.userAgent(headers))
            .setDefaultRequestProperties(MediaPlaybackHeaders.defaultRequestProperties(headers))
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(audioCache())
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
        val mediaItem = MediaItem.Builder()
            .setUri(currentUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(currentTitle.ifBlank { "听书" })
                    .build()
            )
            .build()
        player?.apply {
            val resumePosition = if (forceStartCurrent) {
                AudioPlaybackProgressStore.clear(this@AudioPlaybackService, currentChapterRouteId)
                0L
            } else {
                val savedNowPlaying = AudioPlaybackStateStore.current(this@AudioPlaybackService)
                    ?.takeIf { it.chapterRouteId == currentChapterRouteId }
                savedNowPlaying?.positionMs
                    ?.takeIf { it > 0L }
                    ?: AudioPlaybackProgressStore.position(this@AudioPlaybackService, currentChapterRouteId)
            }
            setMediaSource(mediaSourceFactory.createMediaSource(mediaItem), resumePosition)
            prepare()
            AiBridgeTrace.state(
                "media_audio_now_playing",
                currentChapterRouteId,
                AiBridgeTrace.fields(
                    "title" to currentTitle,
                    "url" to currentUrl.audioTraceToken(),
                    "resume" to resumePosition,
                    "forceStart" to forceStartCurrent,
                    "cache" to "media_audio_cache"
                )
            )
            playWhenReady = autoPlayCurrent
        }
    }

    private fun audioCache(): SimpleCache {
        return sharedAudioCache ?: synchronized(AudioPlaybackService::class.java) {
            sharedAudioCache ?: SimpleCache(
                File(cacheDir, AUDIO_CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(this)
            ).also {
                sharedAudioCache = it
            }
        }
    }

    private fun saveCurrentProgress() {
        val routeId = currentChapterRouteId
        val exoPlayer = player
        if (routeId.isBlank() || exoPlayer == null) return
        val position = exoPlayer.currentPosition
        if (position <= 0L) return
        AudioPlaybackProgressStore.save(this, routeId, position, exoPlayer.duration)
        AudioPlaybackStateStore.setProgress(this, routeId, position, exoPlayer.duration)
        MediaShelfStore.updateAudioProgress(this, routeId, position, exoPlayer.duration)
        AiBridgeTrace.state(
            "media_audio_progress",
            routeId,
            AiBridgeTrace.fields(
                "title" to currentTitle,
                "position" to position,
                "duration" to exoPlayer.duration,
                "playing" to exoPlayer.isPlaying
            )
        )
    }

    private fun tracePlaybackState(state: String) {
        val routeId = currentChapterRouteId
        if (routeId.isBlank()) return
        val exoPlayer = player
        AiBridgeTrace.event(
            "media_audio_player_state",
            routeId,
            AiBridgeTrace.fields(
                "state" to state,
                "title" to currentTitle,
                "position" to (exoPlayer?.currentPosition ?: -1L),
                "duration" to (exoPlayer?.duration ?: -1L)
            )
        )
    }

    private fun String.audioTraceToken(): String {
        val uri = Uri.parse(this)
        return listOf(uri.host.orEmpty(), uri.lastPathSegment.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString("|")
            .ifBlank { hashCode().toString() }
    }

    private fun startProgressTicker() {
        progressHandler.removeCallbacks(progressTicker)
        progressHandler.post(progressTicker)
    }

    private fun stopProgressTicker() {
        progressHandler.removeCallbacks(progressTicker)
    }

    private fun verifyAudioHostname(host: String, session: SSLSession): Boolean {
        if (DEFAULT_HOSTNAME_VERIFIER.verify(host, session)) return true
        val dnsNames = runCatching {
            session.peerCertificates
                .filterIsInstance<X509Certificate>()
                .flatMap { MediaPlaybackTlsPolicy.dnsSubjectAlternativeNames(it) }
        }.getOrDefault(emptyList())
        val accepted = MediaPlaybackTlsPolicy.acceptsKnownAudioCdnAlias(host, dnsNames)
        if (accepted) {
            Log.i(TAG, "Accepted known audio CDN certificate alias host=$host names=${dnsNames.joinToString(",")}")
        }
        return accepted
    }

    private fun retryAfterHttpError(error: PlaybackException) {
        val responseCode = error.responseCode() ?: return
        if (responseCode != 401 && responseCode != 403) return
        if (!retriedWithPlaybackHeaders && currentUrl.isNotBlank()) {
            retriedWithPlaybackHeaders = true
            val retryHeaders = MediaPlaybackHeaders.audio(currentHeaders)
            if (retryHeaders != currentHeaders) {
                currentHeaders = retryHeaders
                Log.i(TAG, "Retrying audio playback with normalized playback headers after HTTP $responseCode")
                playCurrent(retryHeaders)
                return
            }
        }
        if (retriedWithoutReferer || currentUrl.isBlank() || !currentHeaders.hasHostBoundHeaders()) return
        retriedWithoutReferer = true
        val retryHeaders = currentHeaders.withoutHostBoundHeaders()
        currentHeaders = retryHeaders
                Log.i(TAG, "Retrying audio playback without host-bound headers after HTTP $responseCode")
                playCurrent(retryHeaders)
    }

    private fun clearLegacyNotification() {
        NotificationManagerCompat.from(this).cancel(LEGACY_NOTIFICATION_ID)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player?.isPlaying != true) {
            saveCurrentProgress()
            clearLegacyNotification()
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        saveCurrentProgress()
        stopProgressTicker()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "audio_url"
        const val EXTRA_TITLE = "audio_title"
        const val EXTRA_CHAPTER_ROUTE_ID = "audio_chapter_route_id"
        const val EXTRA_BOOK_ROUTE_ID = "audio_book_route_id"
        const val EXTRA_BOOK_TITLE = "audio_book_title"
        const val EXTRA_COVER_URL = "audio_cover_url"
        const val EXTRA_HEADERS_JSON = "audio_headers_json"
        const val EXTRA_FORCE_START = "audio_force_start"
        const val EXTRA_AUTO_PLAY = "audio_auto_play"
        const val ACTION_TOGGLE = "com.ldp.reader.audio.TOGGLE"
        const val ACTION_STOP = "com.ldp.reader.audio.STOP"
        private const val TAG = "AudioPlaybackService"
        private const val LEGACY_NOTIFICATION_ID = 2306
        private const val AUDIO_CACHE_DIR = "media_audio_cache"
        private const val AUDIO_CACHE_MAX_BYTES = 256L * 1024L * 1024L
        @Volatile
        private var sharedAudioCache: SimpleCache? = null
        private val DEFAULT_HOSTNAME_VERIFIER = HttpsURLConnection.getDefaultHostnameVerifier()
        fun encodeHeaders(headers: Map<String, String>): String {
            val json = JSONObject()
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) {
                    json.put(name, value)
                }
            }
            return json.toString()
        }

        private fun decodeHeaders(headersJson: String): Map<String, String> {
            if (headersJson.isBlank()) return emptyMap()
            return runCatching {
                val json = JSONObject(headersJson)
                json.keys().asSequence()
                    .associateWith { key -> json.optString(key) }
                    .filterValues { it.isNotBlank() }
            }.getOrDefault(emptyMap())
        }

        private fun Map<String, String>.hasHostBoundHeaders(): Boolean {
            return keys.any { it.equals("Referer", ignoreCase = true) || it.equals("Origin", ignoreCase = true) }
        }

        private fun Map<String, String>.withoutHostBoundHeaders(): Map<String, String> {
            return filterKeys {
                !it.equals("Referer", ignoreCase = true) &&
                    !it.equals("Origin", ignoreCase = true)
            }
        }

        private fun PlaybackException.responseCode(): Int? {
            var current: Throwable? = cause
            while (current != null) {
                if (current is HttpDataSource.InvalidResponseCodeException) {
                    return current.responseCode
                }
                current = current.cause
            }
            return null
        }

        private fun String.safeUrlForLog(): String {
            return substringBefore('?').take(180)
        }

        private fun Map<String, String>.keysForLog(): String {
            return keys.joinToString(",")
        }
    }
}
