package com.ldp.reader.audio

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ldp.reader.media.AudioPlaybackRedirectInterceptor
import com.ldp.reader.media.MediaPlaybackHeaders
import com.ldp.reader.media.MediaPlaybackTlsPolicy
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.source.AiBridgeTrace
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject

class AudioPlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
    private var endedRouteInProgress: String = ""
    private val transitionGate = AudioPlaybackTransitionGate()
    private val autoAdvanceStateMachine = AudioAutoAdvanceCandidateStateMachine(
        maxCandidateAttempts = MAX_AUTO_ADVANCE_CANDIDATE_ATTEMPTS
    )
    private var autoAdvanceJob: Job? = null
    private val sleepHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            saveCurrentProgress()
            progressHandler.postDelayed(this, 1_500L)
        }
    }
    private val audioHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(AudioPlaybackRedirectInterceptor())
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
                        if (!retryAfterHttpError(error)) {
                            AudioPlaybackStateStore.markFailed(
                                this@AudioPlaybackService,
                                currentChapterRouteId
                            )
                            continueAutoAdvanceAfterAsyncFailure(error)
                        }
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

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        AudioPlaybackStateStore.setPlaybackSpeed(
                            this@AudioPlaybackService,
                            playbackParameters.speed
                        )
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        tracePlaybackState("state_$playbackState")
                        when (playbackState) {
                            Player.STATE_READY -> {
                                finalizeStagedAutoAdvanceCandidate()
                                AudioPlaybackStateStore.markReady(
                                    this@AudioPlaybackService,
                                    currentChapterRouteId
                                )
                            }
                            Player.STATE_ENDED -> {
                                AudioPlaybackStateStore.markEnded(
                                    this@AudioPlaybackService,
                                    currentChapterRouteId
                                )
                                when (val result = AudioPlaybackCommandBoundary.execute { handlePlaybackEnded() }) {
                                    AudioPlaybackCommandResult.Success -> Unit
                                    is AudioPlaybackCommandResult.Failure -> {
                                        handlePlaybackCommandFailure(
                                            reason = "auto_advance_start_failed",
                                            error = result.error
                                        )
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                })
            }
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setId("reader_audio_session")
            .build()
        exoPlayer.setPlaybackSpeed(
            AudioPlaybackSpeed.normalize(AudioPlaybackStateStore.current(this)?.playbackSpeed)
        )
        restoreSleepTimerFromState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_AUTO_ADVANCE -> {
                cancelPendingAutoAdvance("manual_selection")
                return super.onStartCommand(intent, flags, startId)
            }
            ACTION_TOGGLE -> {
                player?.let { player ->
                    if (player.isPlaying) player.pause() else player.play()
                }
                return super.onStartCommand(intent, flags, startId)
            }
            ACTION_STOP -> {
                cancelPendingAutoAdvance("stop")
                saveCurrentProgress()
                sleepHandler.removeCallbacksAndMessages(null)
                player?.pause()
                AudioPlaybackStateStore.clear(this)
                clearLegacyNotification()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_SLEEP_TIMER -> {
                scheduleSleepTimer(intent.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0))
                return super.onStartCommand(intent, flags, startId)
            }
        }
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isNotBlank()) {
            cancelPendingAutoAdvance("external_start")
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
                (player?.mediaItemCount ?: 0) > 0 &&
                player?.playerError == null
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
                restoreSleepTimerFromState()
                MediaShelfStore.addOrUpdateForChapter(this, ReaderMediaKind.AUDIO, currentChapterRouteId, currentTitle)
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
            endedRouteInProgress = ""
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
            restoreSleepTimerFromState()
            MediaShelfStore.addOrUpdateForChapter(this, ReaderMediaKind.AUDIO, currentChapterRouteId, currentTitle)
            MediaShelfStore.updateAudioChapter(this, currentChapterRouteId, currentTitle)
            if (!autoPlayCurrent) {
                AudioPlaybackStateStore.setPlaying(this, false)
            }
            clearLegacyNotification()
            playCurrent(currentHeaders)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @OptIn(UnstableApi::class)
    private fun playCurrent(headers: Map<String, String>) {
        AudioPlaybackFailureStore.clear()
        AudioPlaybackStateStore.markPreparing(this, currentChapterRouteId)
        when (val result = AudioPlaybackCommandBoundary.execute {
            val mediaSource = createAudioMediaSource(
                routeId = currentChapterRouteId,
                title = currentTitle,
                url = currentUrl,
                headers = headers
            )
            player?.apply {
                val forceStart = forceStartCurrent
                forceStartCurrent = false
                val resumePosition = if (forceStart) {
                    AudioPlaybackProgressStore.clear(this@AudioPlaybackService, currentChapterRouteId)
                    0L
                } else {
                    val savedNowPlaying = AudioPlaybackStateStore.current(this@AudioPlaybackService)
                        ?.takeIf { it.chapterRouteId == currentChapterRouteId }
                    savedNowPlaying?.positionMs
                        ?.takeIf { it > 0L }
                        ?: AudioPlaybackProgressStore.position(this@AudioPlaybackService, currentChapterRouteId)
                }
                setMediaSource(mediaSource, resumePosition)
                setPlaybackSpeed(
                    AudioPlaybackSpeed.normalize(
                        AudioPlaybackStateStore.current(this@AudioPlaybackService)?.playbackSpeed
                    )
                )
                prepare()
                AiBridgeTrace.state(
                    "media_audio_now_playing",
                    currentChapterRouteId,
                    AiBridgeTrace.fields(
                        "title" to currentTitle,
                        "url" to currentUrl.audioTraceToken(),
                        "resume" to resumePosition,
                        "forceStart" to forceStart,
                        "cache" to "media_audio_cache"
                    )
                )
                playWhenReady = autoPlayCurrent
            }
        }) {
            AudioPlaybackCommandResult.Success -> Unit
            is AudioPlaybackCommandResult.Failure -> {
                handlePlaybackCommandFailure(reason = "media_setup_failed", error = result.error)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun createAudioMediaSource(
        routeId: String,
        title: String,
        url: String,
        headers: Map<String, String>
    ): MediaSource {
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
            .setMediaId(routeId)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifBlank { "听书" })
                    .build()
            )
            .build()
        return mediaSourceFactory.createMediaSource(mediaItem)
    }

    private fun handlePlaybackCommandFailure(reason: String, error: Exception) {
        endedRouteInProgress = ""
        AudioPlaybackFailureStore.record(currentChapterRouteId, reason, error)
        AudioPlaybackStateStore.markFailed(this, currentChapterRouteId)
        AudioPlaybackCommandBoundary.execute {
            Log.w(
                TAG,
                "Audio playback setup failed reason=$reason url=${currentUrl.safeUrlForLog()}",
                error
            )
            stopProgressTicker()
            AudioPlaybackStateStore.setPlaying(this, false)
            player?.stop()
            player?.clearMediaItems()
        }
        AudioPlaybackCommandBoundary.execute {
            AiBridgeTrace.event(
                "media_audio_playback_setup_failed",
                currentChapterRouteId,
                AiBridgeTrace.fields(
                    "reason" to reason,
                    "errorType" to error.javaClass.simpleName,
                    "url" to currentUrl.audioTraceToken()
                )
            )
            tracePlaybackState("setup_failed")
        }
    }

    private fun handlePlaybackEnded() {
        val endedRouteId = currentChapterRouteId
        if (endedRouteId.isBlank() || endedRouteInProgress == endedRouteId) return
        transitionGate.clearStagedAutoAdvance()
        endedRouteInProgress = endedRouteId
        AudioPlaybackStateStore.setPlaying(this, false)
        AudioPlaybackProgressStore.clear(this, endedRouteId)
        stopProgressTicker()
        val bookRouteId = currentBookRouteId
        val ticket = transitionGate.captureAutoAdvance(endedRouteId)
        autoAdvanceJob?.cancel()
        autoAdvanceJob = serviceScope.launch {
            try {
                val result = withTimeoutOrNull(AUTO_ADVANCE_RESOLVE_TIMEOUT_MS) {
                    val candidatesResult = AudioRepositoryCallRunner.runBackgroundServiceResult {
                        AudioAutoAdvanceResolver.remainingEpisodes(
                            chapters = MediaRouteRegistry.chaptersForBookRoute(bookRouteId),
                            currentChapterRouteId = endedRouteId,
                            maxCandidates = MAX_AUTO_ADVANCE_CANDIDATE_ATTEMPTS
                        ) {
                            if (bookRouteId.isBlank()) {
                                emptyList()
                            } else {
                                MediaSourceRepository.chapters(bookRouteId)
                                MediaRouteRegistry.chaptersForBookRoute(bookRouteId)
                            }
                        }
                    }
                    when (candidatesResult) {
                        is AudioRepositoryCallResult.Failure -> {
                            val error = candidatesResult.error
                            if (error is CancellationException) throw error
                            AudioAutoAdvanceAttemptResult.Failed(
                                reason = "catalog_resolve_failed",
                                attemptedCount = 0,
                                error = error
                            )
                        }
                        AudioRepositoryCallResult.Busy -> {
                            AudioAutoAdvanceAttemptResult.Failed(
                                reason = "repository_busy",
                                attemptedCount = 0
                            )
                        }
                        is AudioRepositoryCallResult.Success -> {
                            val candidates = candidatesResult.value.map { episode ->
                                AudioAutoAdvanceCandidate(
                                    routeId = episode.routeId,
                                    title = episode.chapter.name
                                )
                            }
                            autoAdvanceStateMachine.advance(
                                candidates = candidates,
                                resolveRequest = { candidate ->
                                    ensureAutoAdvanceIsCurrent(ticket)
                                    AudioRepositoryCallRunner.runBackgroundServiceResult {
                                        MediaSourceRepository.audioRequest(candidate.routeId)
                                    }
                                },
                                prepare = { candidate, request ->
                                    ensureAutoAdvanceIsCurrent(ticket)
                                    prepareAutoAdvanceCandidate(candidate, request)
                                },
                                stage = { candidate, request ->
                                    ensureAutoAdvanceIsCurrent(ticket)
                                    stageAutoAdvanceCandidate(endedRouteId, candidate, request)
                                }
                            )
                        }
                    }
                }
                if (result == null) {
                    if (transitionGate.canApply(ticket, currentChapterRouteId)) {
                        endedRouteInProgress = ""
                        tracePlaybackState("ended_next_timeout")
                    }
                    return@launch
                }
                when (result) {
                    is AudioAutoAdvanceAttemptResult.Advanced -> {
                        transitionGate.rememberStagedAutoAdvance(ticket, result)
                    }
                    is AudioAutoAdvanceAttemptResult.Exhausted -> {
                        if (!transitionGate.canApply(ticket, currentChapterRouteId)) return@launch
                        endedRouteInProgress = ""
                        AiBridgeTrace.event(
                            "media_audio_auto_next_exhausted",
                            endedRouteId,
                            AiBridgeTrace.fields("attempted" to result.attemptedCount)
                        )
                        tracePlaybackState("ended_no_playable_next")
                    }
                    is AudioAutoAdvanceAttemptResult.Failed -> {
                        if (!transitionGate.canApply(ticket, currentChapterRouteId)) return@launch
                        endedRouteInProgress = ""
                        traceAutoAdvanceFailure(endedRouteId, result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (transitionGate.canApply(ticket, currentChapterRouteId)) {
                    handlePlaybackCommandFailure(reason = "auto_advance_failed", error = error)
                }
            }
        }
    }

    private fun ensureAutoAdvanceIsCurrent(ticket: AudioAutoAdvanceTicket) {
        ensureAutoAdvanceIsCurrent(
            ticket = ticket,
            expectedCurrentRouteId = ticket.endedChapterRouteId
        )
    }

    private fun ensureAutoAdvanceIsCurrent(
        ticket: AudioAutoAdvanceTicket,
        expectedCurrentRouteId: String
    ) {
        if (!transitionGate.canApply(ticket, expectedCurrentRouteId, currentChapterRouteId)) {
            throw CancellationException("stale audio auto-advance transition")
        }
    }

    private fun continueAutoAdvanceAfterAsyncFailure(error: PlaybackException): Boolean {
        val failedRouteId = currentChapterRouteId
        val continuation = transitionGate.consumeStagedAutoAdvanceFailure(failedRouteId)
            ?: return false
        endedRouteInProgress = failedRouteId
        AiBridgeTrace.event(
            "media_audio_auto_next_skipped",
            failedRouteId,
            AiBridgeTrace.fields(
                "reason" to "async_playback_error",
                "errorType" to error.javaClass.simpleName,
                "remaining" to continuation.remainingCandidates.size
            )
        )
        autoAdvanceJob?.cancel()
        autoAdvanceJob = serviceScope.launch {
            try {
                val result = withTimeoutOrNull(AUTO_ADVANCE_RESOLVE_TIMEOUT_MS) {
                    autoAdvanceStateMachine.advance(
                        candidates = continuation.remainingCandidates,
                        resolveRequest = { candidate ->
                            ensureAutoAdvanceIsCurrent(
                                ticket = continuation.ticket,
                                expectedCurrentRouteId = failedRouteId
                            )
                            AudioRepositoryCallRunner.runBackgroundServiceResult {
                                MediaSourceRepository.audioRequest(candidate.routeId)
                            }
                        },
                        prepare = { candidate, request ->
                            ensureAutoAdvanceIsCurrent(
                                ticket = continuation.ticket,
                                expectedCurrentRouteId = failedRouteId
                            )
                            prepareAutoAdvanceCandidate(candidate, request)
                        },
                        stage = { candidate, request ->
                            ensureAutoAdvanceIsCurrent(
                                ticket = continuation.ticket,
                                expectedCurrentRouteId = failedRouteId
                            )
                            stageAutoAdvanceCandidate(failedRouteId, candidate, request)
                        }
                    )
                }
                if (result == null) {
                    if (transitionGate.canApply(continuation.ticket, failedRouteId, currentChapterRouteId)) {
                        endedRouteInProgress = ""
                        tracePlaybackState("ended_next_timeout")
                    }
                    return@launch
                }
                when (result) {
                    is AudioAutoAdvanceAttemptResult.Advanced -> {
                        transitionGate.rememberStagedAutoAdvance(
                            continuation.ticket,
                            result,
                            transitionFromChapterRouteId = failedRouteId
                        )
                    }
                    is AudioAutoAdvanceAttemptResult.Exhausted -> {
                        if (!transitionGate.canApply(
                                continuation.ticket,
                                failedRouteId,
                                currentChapterRouteId
                            )
                        ) {
                            return@launch
                        }
                        endedRouteInProgress = ""
                        AiBridgeTrace.event(
                            "media_audio_auto_next_exhausted",
                            failedRouteId,
                            AiBridgeTrace.fields("attempted" to result.attemptedCount)
                        )
                        tracePlaybackState("ended_no_playable_next")
                    }
                    is AudioAutoAdvanceAttemptResult.Failed -> {
                        if (!transitionGate.canApply(
                                continuation.ticket,
                                failedRouteId,
                                currentChapterRouteId
                            )
                        ) {
                            return@launch
                        }
                        endedRouteInProgress = ""
                        traceAutoAdvanceFailure(failedRouteId, result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (continuationError: Exception) {
                if (transitionGate.canApply(
                        continuation.ticket,
                        failedRouteId,
                        currentChapterRouteId
                    )
                ) {
                    handlePlaybackCommandFailure(
                        reason = "auto_advance_after_async_error_failed",
                        error = continuationError
                    )
                }
            }
        }
        return true
    }

    @OptIn(UnstableApi::class)
    private fun prepareAutoAdvanceCandidate(
        candidate: AudioAutoAdvanceCandidate,
        request: MediaRequest
    ): AudioPlaybackCommandResult {
        val result = AudioPlaybackCommandBoundary.execute {
            val exoPlayer = checkNotNull(player) { "audio player is unavailable" }
            val headers = MediaPlaybackHeaders.audio(request.headers)
            val mediaSource = createAudioMediaSource(
                routeId = candidate.routeId,
                title = candidate.title,
                url = request.url,
                headers = headers
            )
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaSource(mediaSource, 0L)
            exoPlayer.setPlaybackSpeed(
                AudioPlaybackSpeed.normalize(
                    AudioPlaybackStateStore.current(this)?.playbackSpeed
                )
            )
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        if (result is AudioPlaybackCommandResult.Failure) {
            AudioPlaybackCommandBoundary.execute {
                player?.stop()
                player?.clearMediaItems()
            }
            AiBridgeTrace.event(
                "media_audio_auto_next_skipped",
                candidate.routeId,
                AiBridgeTrace.fields(
                    "reason" to "media_setup_failed",
                    "errorType" to result.error.javaClass.simpleName
                )
            )
        }
        return result
    }

    private fun stageAutoAdvanceCandidate(
        transitionFromRouteId: String,
        candidate: AudioAutoAdvanceCandidate,
        request: MediaRequest
    ) {
        currentUrl = request.url
        currentTitle = candidate.title
        currentChapterRouteId = candidate.routeId
        currentHeaders = MediaPlaybackHeaders.audio(request.headers)
        forceStartCurrent = false
        autoPlayCurrent = true
        retriedWithPlaybackHeaders = false
        retriedWithoutReferer = false
        endedRouteInProgress = ""
        AudioPlaybackFailureStore.clear()
        AiBridgeTrace.event(
            "media_audio_auto_next_staged",
            currentChapterRouteId,
            AiBridgeTrace.fields(
                "from" to transitionFromRouteId,
                "title" to currentTitle,
                "url" to currentUrl.audioTraceToken()
            )
        )
    }

    private fun finalizeStagedAutoAdvanceCandidate() {
        val routeId = currentChapterRouteId
        val exoPlayer = player ?: return
        if (routeId.isBlank() || exoPlayer.currentMediaItem?.mediaId != routeId) return
        val transition = transitionGate.markStagedAutoAdvanceReady(routeId) ?: return
        AudioPlaybackProgressStore.clear(this, currentChapterRouteId)
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
        AudioPlaybackStateStore.markReady(this, currentChapterRouteId)
        restoreSleepTimerFromState()
        MediaShelfStore.addOrUpdateForChapter(
            this,
            ReaderMediaKind.AUDIO,
            currentChapterRouteId,
            currentTitle
        )
        MediaShelfStore.updateAudioChapter(this, currentChapterRouteId, currentTitle)
        clearLegacyNotification()
        AiBridgeTrace.state(
            "media_audio_now_playing",
            currentChapterRouteId,
            AiBridgeTrace.fields(
                "title" to currentTitle,
                "url" to currentUrl.audioTraceToken(),
                "resume" to 0L,
                "forceStart" to true,
                "cache" to "media_audio_cache"
            )
        )
        AiBridgeTrace.event(
            "media_audio_auto_next",
            currentChapterRouteId,
            AiBridgeTrace.fields(
                "from" to transition.transitionFromChapterRouteId,
                "title" to currentTitle,
                "url" to currentUrl.audioTraceToken()
            )
        )
    }

    private fun traceAutoAdvanceFailure(
        endedRouteId: String,
        failure: AudioAutoAdvanceAttemptResult.Failed
    ) {
        AiBridgeTrace.event(
            "media_audio_auto_next_failed",
            endedRouteId,
            AiBridgeTrace.fields(
                "reason" to failure.reason,
                "errorType" to failure.error?.javaClass?.simpleName.orEmpty()
            )
        )
        tracePlaybackState("ended_next_resolve_failed")
    }

    private fun cancelPendingAutoAdvance(reason: String) {
        val invalidation = transitionGate.invalidatePendingTransitions()
        val pending = autoAdvanceJob
        autoAdvanceJob = null
        pending?.cancel()
        if (invalidation.shouldStopMedia(
                currentChapterRouteId = currentChapterRouteId,
                currentMediaItemRouteId = player?.currentMediaItem?.mediaId.orEmpty()
            )
        ) {
            AudioPlaybackCommandBoundary.execute {
                player?.playWhenReady = false
                player?.stop()
                player?.clearMediaItems()
            }
        }
        if (endedRouteInProgress.isNotBlank()) {
            AiBridgeTrace.event(
                "media_audio_auto_next_cancelled",
                endedRouteInProgress,
                AiBridgeTrace.fields("reason" to reason)
            )
        }
        endedRouteInProgress = ""
    }

    @OptIn(UnstableApi::class)
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

    private fun scheduleSleepTimer(minutes: Int) {
        if (!AudioSleepTimer.isSupported(minutes)) {
            traceSleepTimer("invalid", minutes, 0L)
            return
        }
        val deadlineMs = AudioSleepTimer.deadlineMs(minutes)
        scheduleSleepTimerAt(minutes, deadlineMs)
    }

    private fun restoreSleepTimerFromState() {
        val state = AudioPlaybackStateStore.current(this)
        if (state == null || !AudioSleepTimer.isActive(state.sleepTimerMinutes, state.sleepTimerEndAtMs)) {
            sleepHandler.removeCallbacksAndMessages(null)
            if (state != null && (state.sleepTimerMinutes != 0 || state.sleepTimerEndAtMs != 0L)) {
                AudioPlaybackStateStore.setSleepTimer(this, 0, 0L)
            }
            return
        }
        scheduleSleepTimerAt(state.sleepTimerMinutes, state.sleepTimerEndAtMs)
    }

    private fun scheduleSleepTimerAt(minutes: Int, deadlineMs: Long) {
        sleepHandler.removeCallbacksAndMessages(null)
        if (minutes <= 0) {
            AudioPlaybackStateStore.setSleepTimer(this, 0, 0L)
            traceSleepTimer("off", 0, 0L)
            return
        }
        if (!AudioSleepTimer.isActive(minutes, deadlineMs)) {
            AudioPlaybackStateStore.setSleepTimer(this, 0, 0L)
            traceSleepTimer("expired", minutes, deadlineMs)
            return
        }
        AudioPlaybackStateStore.setSleepTimer(this, minutes, deadlineMs)
        traceSleepTimer("scheduled", minutes, deadlineMs)
        sleepHandler.postDelayed(
            {
                cancelPendingAutoAdvance("sleep_timer_fired")
                saveCurrentProgress()
                player?.pause()
                AudioPlaybackStateStore.setSleepTimer(this, 0, 0L)
                traceSleepTimer("fired", minutes, deadlineMs)
            },
            AudioSleepTimer.delayMs(minutes, deadlineMs)
        )
    }

    private fun traceSleepTimer(state: String, minutes: Int, deadlineMs: Long) {
        AiBridgeTrace.state(
            "media_audio_sleep_timer",
            currentChapterRouteId.ifBlank { "none" },
            AiBridgeTrace.fields(
                "state" to state,
                "minutes" to minutes,
                "deadlineMs" to deadlineMs,
                "title" to currentTitle
            )
        )
        AiBridgeTrace.event(
            "media_audio_sleep_timer",
            currentChapterRouteId.ifBlank { "none" },
            AiBridgeTrace.fields(
                "state" to state,
                "minutes" to minutes,
                "deadlineMs" to deadlineMs,
                "title" to currentTitle
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

    private fun retryAfterHttpError(error: PlaybackException): Boolean {
        val responseCode = error.responseCode() ?: return false
        if (responseCode != 401 && responseCode != 403) return false
        if (!retriedWithPlaybackHeaders && currentUrl.isNotBlank()) {
            retriedWithPlaybackHeaders = true
            val retryHeaders = MediaPlaybackHeaders.audio(currentHeaders)
            if (retryHeaders != currentHeaders) {
                currentHeaders = retryHeaders
                Log.i(TAG, "Retrying audio playback with normalized playback headers after HTTP $responseCode")
                playCurrent(retryHeaders)
                return true
            }
        }
        if (retriedWithoutReferer || currentUrl.isBlank() || !currentHeaders.hasHostBoundHeaders()) return false
        retriedWithoutReferer = true
        val retryHeaders = currentHeaders.withoutHostBoundHeaders()
        currentHeaders = retryHeaders
        Log.i(TAG, "Retrying audio playback without host-bound headers after HTTP $responseCode")
        playCurrent(retryHeaders)
        return true
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
        cancelPendingAutoAdvance("service_destroyed")
        saveCurrentProgress()
        stopProgressTicker()
        sleepHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        AudioPlaybackStateStore.clearServiceTransientState(this)
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
        const val ACTION_CANCEL_AUTO_ADVANCE = "com.ldp.reader.audio.CANCEL_AUTO_ADVANCE"
        const val ACTION_SET_SLEEP_TIMER = "com.ldp.reader.audio.SET_SLEEP_TIMER"
        const val EXTRA_SLEEP_TIMER_MINUTES = "audio_sleep_timer_minutes"
        private const val TAG = "AudioPlaybackService"
        private const val LEGACY_NOTIFICATION_ID = 2306
        private const val AUDIO_CACHE_DIR = "media_audio_cache"
        private const val AUDIO_CACHE_MAX_BYTES = 256L * 1024L * 1024L
        private const val MAX_AUTO_ADVANCE_CANDIDATE_ATTEMPTS = 8
        private const val AUTO_ADVANCE_RESOLVE_TIMEOUT_MS = 45_000L
        @Volatile
        @OptIn(UnstableApi::class)
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
