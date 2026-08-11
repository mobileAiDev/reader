package com.ldp.reader.ui.activity

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ldp.reader.R
import com.ldp.reader.audio.AudioBoundCatalogRecoveryPolicy
import com.ldp.reader.audio.AudioChapterOrder
import com.ldp.reader.audio.AudioBoundSessionRecoveryPolicy
import com.ldp.reader.audio.AudioEpisodeNavigation
import com.ldp.reader.audio.AudioNowPlaying
import com.ldp.reader.audio.AudioPlayerControlDecision
import com.ldp.reader.audio.AudioPlayerControlPolicy
import com.ldp.reader.audio.AudioPlayerPrimaryAction
import com.ldp.reader.audio.AudioPlayerUiReadinessPolicy
import com.ldp.reader.audio.AudioPlaybackLoadIntent
import com.ldp.reader.audio.AudioPlaybackLoadIntentPolicy
import com.ldp.reader.audio.AudioPlaybackFailure
import com.ldp.reader.audio.AudioPlaybackFailureCopy
import com.ldp.reader.audio.AudioPlaybackFailureStore
import com.ldp.reader.audio.AudioPlaybackProgressStore
import com.ldp.reader.audio.AudioPlaybackRetryPolicy
import com.ldp.reader.audio.AudioPlaybackService
import com.ldp.reader.audio.AudioPlaybackSpeed
import com.ldp.reader.audio.AudioPlaybackStateStore
import com.ldp.reader.audio.AudioRepositoryCallResult
import com.ldp.reader.audio.AudioRepositoryCallRunner
import com.ldp.reader.audio.AudioSleepTimer
import com.ldp.reader.databinding.ActivityAudioPlayerBinding
import com.ldp.reader.media.MediaDetailLoadProgress
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.ui.adapter.MediaChapterAdapter
import com.ldp.reader.ui.audio.AudioCoverChrome
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.utils.BookCoverUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException as FutureCancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AudioPlayerActivity : BaseActivity<ActivityAudioPlayerBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            updatePlayerUi()
            progressHandler.postDelayed(this, 1_000L)
        }
    }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var chapterRouteId: String
    private var bookRouteId: String? = null
    private var currentBookTitle: String = ""
    private var currentEpisodeTitle: String = ""
    private var currentEpisodeIndex = -1
    private var episodes: List<MediaChapterItem> = emptyList()
    private val episodeAdapter = MediaChapterAdapter()
    private var userSeeking = false
    private var showingCatalog = false
    private var loadToken = 0
    private var currentCoverUrl: String = ""
    private var coverAnimator: ObjectAnimator? = null
    private var coverBackgroundTarget: CustomTarget<Bitmap>? = null
    private var lastCoverRotationTrace = ""
    private var forceStartPlayback = false
    private var autoPlayPlayback = false
    private var resolveFreshPlayback = false
    private var playerRetryEnabled = false
    private var catalogRetryEnabled = false
    private var catalogStateText = MediaDetailLoadProgress.Step.CATALOG.label
    private var controllerConnectGeneration = 0L
    private var playerLoadJob: Job? = null
    private var playbackCommandConsumed = false
    private var bindExistingSessionOnly = false
    private var boundSessionRecoveryAction: AudioBoundSessionRecoveryPolicy.Action? = null
    private var boundSessionRecoveryState: AudioNowPlaying? = null
    private var boundSessionRecoveryDispatchPending = false

    override fun getViewBinding(): ActivityAudioPlayerBinding {
        return ActivityAudioPlayerBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.audioPlayerToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_audio_player_close_32)
        toolbar?.setNavigationContentDescription(R.string.audio_player_close_page)
        MediaUiChrome.darkReader(this)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        BookContentProviderRouter.stopLowPriorityV8Maintenance("audio-player")
        playbackCommandConsumed =
            savedInstanceState?.getBoolean(STATE_PLAYBACK_COMMAND_CONSUMED, false) == true
        readIntent(intent, readPlaybackCommand = !playbackCommandConsumed)
    }

    override fun initWidget() {
        super.initWidget()
        renderTitleHeader()
        renderPlayerStatus("加载中...")
        renderPlayPauseIcon(false)
        binding.audioPlayerElapsed.text = "00:00"
        binding.audioPlayerDuration.text = "00:00"
        renderSleepTimer()
        renderPlaybackSpeed(AudioPlaybackStateStore.current(this)?.playbackSpeed)
        binding.audioPlayerCatalogList.layoutManager = LinearLayoutManager(this)
        binding.audioPlayerCatalogList.adapter = episodeAdapter
        AudioCoverChrome.configureCircularCover(binding.audioPlayerCover)
        episodeAdapter.accentColorRes = R.color.media_audio_accent
        episodeAdapter.metaPrefix = "剧集"
        episodeAdapter.selectedIndex = currentEpisodeIndex
        renderCatalogState(MediaDetailLoadProgress.Step.CATALOG.label, retryEnabled = false)
        renderTab()
        updateEpisodeButtons()
    }

    override fun initClick() {
        super.initClick()
        binding.audioPlayerPlayPause.setOnClickListener {
            if (retryFailedPlayback()) return@setOnClickListener
            val player = controller ?: return@setOnClickListener
            if (handleEmptyBoundSessionPrimaryAction()) {
                return@setOnClickListener
            }
            when (playerControlDecision(player).action) {
                AudioPlayerPrimaryAction.PAUSE -> player.pause()
                AudioPlayerPrimaryAction.PLAY -> player.play()
                AudioPlayerPrimaryAction.REPLAY_FROM_START -> {
                    player.seekTo(0L)
                    player.play()
                }
            }
            updatePlayerUi()
        }
        binding.audioPlayerRewind.setOnClickListener {
            controller?.let { it.seekTo((it.currentPosition - 15_000L).coerceAtLeast(0L)) }
        }
        binding.audioPlayerForward.setOnClickListener {
            controller?.let { player ->
                val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + 15_000L).coerceAtMost(duration))
            }
        }
        binding.audioPlayerPrevious.setOnClickListener { openSiblingEpisode(-1) }
        binding.audioPlayerNext.setOnClickListener { openSiblingEpisode(1) }
        binding.audioPlayerTabNow.setOnClickListener {
            showingCatalog = false
            renderTab()
        }
        binding.audioPlayerTabCatalog.setOnClickListener {
            showingCatalog = true
            renderTab()
        }
        binding.audioPlayerCatalogState.setOnClickListener {
            if (catalogRetryEnabled) loadPlayer()
        }
        episodeAdapter.setOnItemClickListener { _, pos ->
            val episode = episodeAdapter.getItem(pos)
            transitionToEpisode(episode, position = pos)
        }
        binding.audioPlayerSpeed.setOnClickListener {
            val speed = AudioPlaybackSpeed.next(
                controller?.playbackParameters?.speed
                    ?: AudioPlaybackStateStore.current(this)?.playbackSpeed
            )
            AudioPlaybackStateStore.setPlaybackSpeed(this, speed)
            controller?.setPlaybackSpeed(speed)
            renderPlaybackSpeed(speed)
        }
        binding.audioPlayerSleep.setOnClickListener {
            val current = activeSleepTimerMinutes()
            val minutes = AudioSleepTimer.nextMinutes(current)
            setSleepTimer(minutes)
        }
        binding.audioPlayerState.setOnClickListener {
            retryFailedPlayback()
        }
        binding.audioPlayerProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val player = controller ?: return
                val duration = player.duration
                if (duration > 0) {
                    val target = duration * progress / SEEK_BAR_MAX
                    binding.audioPlayerElapsed.text = formatMillis(target)
                    persistAudioProgress(target, duration)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = controller ?: return
                val duration = player.duration
                if (duration > 0) {
                    val target = duration * (seekBar?.progress ?: 0) / SEEK_BAR_MAX
                    player.seekTo(target)
                    persistAudioProgress(target, duration)
                }
                userSeeking = false
            }
        })
    }

    override fun processLogic() {
        super.processLogic()
        loadPlayer()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        playbackCommandConsumed = false
        readIntent(intent, readPlaybackCommand = true)
        loadPlayer()
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        outState.putBoolean(STATE_PLAYBACK_COMMAND_CONSUMED, playbackCommandConsumed)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        persistControllerProgress()
        super.onPause()
    }

    private fun readIntent(intent: Intent, readPlaybackCommand: Boolean) {
        boundSessionRecoveryAction = null
        boundSessionRecoveryState = null
        boundSessionRecoveryDispatchPending = false
        chapterRouteId = intent.getStringExtra(EXTRA_CHAPTER_ROUTE_ID).orEmpty()
        val nowPlaying = AudioPlaybackStateStore.current(this)
            ?.takeIf { it.chapterRouteId == chapterRouteId }
        MediaShelfStore.restoreForChapter(this, chapterRouteId)
        bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId)
            ?: nowPlaying?.bookRouteId?.takeIf { it.isNotBlank() }
        currentEpisodeIndex = MediaRouteRegistry.chapter(chapterRouteId)?.index ?: -1
        currentEpisodeTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifBlank { nowPlaying?.title.orEmpty() }
            .ifBlank { MediaRouteRegistry.chapter(chapterRouteId)?.name.orEmpty() }
        currentBookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
            .ifBlank { nowPlaying?.bookTitle.orEmpty() }
            .ifBlank { bookRouteId?.let { MediaRouteRegistry.detail(it)?.name.orEmpty() }.orEmpty() }
        forceStartPlayback = readPlaybackCommand && intent.getBooleanExtra(EXTRA_FORCE_START, false)
        autoPlayPlayback = readPlaybackCommand && intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)
        resolveFreshPlayback = false
        bindExistingSessionOnly = intent.getBooleanExtra(EXTRA_BIND_EXISTING_SESSION_ONLY, false)
    }

    private fun loadPlayer() {
        playerLoadJob?.cancel()
        val token = ++loadToken
        val loadIntent = AudioPlaybackLoadIntent(
            forceStart = forceStartPlayback,
            resolveFresh = resolveFreshPlayback,
            autoPlay = autoPlayPlayback
        )
        AudioPlaybackFailureStore.clear()
        progressHandler.removeCallbacks(progressTicker)
        releaseControllerConnection()
        if (!bindExistingSessionOnly) {
            boundSessionRecoveryAction = null
            boundSessionRecoveryState = null
            boundSessionRecoveryDispatchPending = false
        }
        renderTitleHeader()
        renderPlayerStatus("加载中...")
        renderPlayPauseIcon(false)
        binding.audioPlayerElapsed.text = "00:00"
        binding.audioPlayerDuration.text = "--:--"
        binding.audioPlayerProgress.progress = 0
        renderSavedProgress()
        updateCoverRotation(false)
        episodes = emptyList()
        episodeAdapter.refreshItems(emptyList())
        episodeAdapter.selectedIndex = currentEpisodeIndex
        renderCatalogState(MediaDetailLoadProgress.Step.CATALOG.label, retryEnabled = false)
        updateEpisodeButtons()
        if (bindExistingSessionOnly) {
            playerLoadJob = null
            bindExistingPlaybackSession()
            return
        }
        playerLoadJob = scope.launch {
            val resolution = withTimeoutOrNull(PLAYER_LOAD_TIMEOUT_MS) {
                val bookRoute = bookRouteId
                if (bookRoute != null) {
                    val detail = loadRepositoryValue { MediaSourceRepository.detail(bookRoute) }
                    if (token != loadToken) return@withTimeoutOrNull null
                    currentBookTitle = detail?.title.orEmpty().ifBlank { currentBookTitle }
                    renderTitleHeader()
                    currentCoverUrl = detail?.coverUrl.orEmpty().ifBlank { currentCoverUrl }
                    renderCover(currentCoverUrl)
                    episodes = AudioChapterOrder.chronological(
                        loadRepositoryValue { MediaSourceRepository.chapters(bookRoute) }.orEmpty()
                    )
                    if (token != loadToken) return@withTimeoutOrNull null
                    episodeAdapter.useChronologicalDisplayOrder(episodes)
                    episodeAdapter.refreshItems(episodes)
                    episodeAdapter.selectedIndex = currentEpisodeIndex
                    renderCatalogState(
                        if (episodes.isEmpty()) MediaDetailLoadProgress.Step.FAILED.label else "",
                        retryEnabled = episodes.isEmpty()
                    )
                    updateEpisodeButtons()
                } else {
                    val nowPlaying = AudioPlaybackStateStore.current(this@AudioPlayerActivity)
                    currentBookTitle = currentBookTitle.ifBlank { nowPlaying?.bookTitle.orEmpty() }
                    currentCoverUrl = nowPlaying?.coverUrl.orEmpty()
                    renderTitleHeader()
                    renderCover(currentCoverUrl)
                    renderCatalogState(MediaDetailLoadProgress.Step.FAILED.label, retryEnabled = true)
                }
                val restoredRequest = AudioPlaybackStateStore.current(this@AudioPlayerActivity)
                    ?.takeIf {
                        it.chapterRouteId == chapterRouteId &&
                            it.audioUrl.isNotBlank() &&
                            !loadIntent.forceStart &&
                            !loadIntent.resolveFresh
                    }
                    ?.let { MediaRequest(it.audioUrl, it.headers) }
                restoredRequest?.let { AudioRequestResolution(it, failureMessage = null) }
                    ?: resolveFreshAudioRequest()
            }
            if (token != loadToken) return@launch
            val request = resolution?.request
            if (request == null || request.url.isBlank()) {
                MediaSourceRepository.recordResolvedContent(chapterRouteId, 0)
                AiBridgeTrace.event(
                    "media_audio_request_resolved",
                    chapterRouteId,
                    AiBridgeTrace.fields("title" to currentEpisodeTitle, "url" to "blank")
                )
                renderPlayerFailure(
                    resolution?.failureMessage
                        ?: if (resolution == null) {
                            AudioPlaybackFailureCopy.unknownFailureMessage()
                        } else {
                            AudioPlaybackFailureCopy.missingOrRejectedRequestMessage()
                        }
                )
                if (episodes.isEmpty()) {
                    renderCatalogState(MediaDetailLoadProgress.Step.FAILED.label, retryEnabled = true)
                }
                return@launch
            }
            MediaSourceRepository.recordResolvedContent(chapterRouteId, 1)
            val title = currentEpisodeTitle.ifBlank { binding.audioPlayerEpisode.text?.toString().orEmpty() }
            AiBridgeTrace.event(
                "media_audio_request_resolved",
                chapterRouteId,
                AiBridgeTrace.fields("title" to title, "url" to request.url.audioTraceToken())
            )
            MediaShelfStore.addOrUpdateForChapter(
                this@AudioPlayerActivity,
                ReaderMediaKind.AUDIO,
                chapterRouteId,
                title
            )?.let { item ->
                bookRouteId = item.bookRouteId
                currentBookTitle = currentBookTitle.ifBlank { item.title }
                currentCoverUrl = currentCoverUrl.ifBlank { item.coverUrl }
                renderTitleHeader()
                if (currentCoverUrl.isNotBlank()) {
                    renderCover(currentCoverUrl)
                }
            }
            AudioPlaybackStateStore.setNowPlaying(
                this@AudioPlayerActivity,
                chapterRouteId,
                title,
                bookRouteId.orEmpty(),
                currentCoverUrl,
                bookTitle = currentBookTitle,
                audioUrl = request.url,
                headers = request.headers
            )
            MediaShelfStore.updateAudioChapter(this@AudioPlayerActivity, chapterRouteId, title)
            startService(
                Intent(this@AudioPlayerActivity, AudioPlaybackService::class.java)
                    .putExtra(AudioPlaybackService.EXTRA_URL, request.url)
                    .putExtra(AudioPlaybackService.EXTRA_TITLE, title)
                    .putExtra(AudioPlaybackService.EXTRA_CHAPTER_ROUTE_ID, chapterRouteId)
                    .putExtra(AudioPlaybackService.EXTRA_BOOK_ROUTE_ID, bookRouteId.orEmpty())
                    .putExtra(AudioPlaybackService.EXTRA_BOOK_TITLE, currentBookTitle)
                    .putExtra(AudioPlaybackService.EXTRA_COVER_URL, currentCoverUrl)
                    .putExtra(AudioPlaybackService.EXTRA_HEADERS_JSON, AudioPlaybackService.encodeHeaders(request.headers))
                    .putExtra(AudioPlaybackService.EXTRA_FORCE_START, loadIntent.forceStart)
                    .putExtra(AudioPlaybackService.EXTRA_AUTO_PLAY, loadIntent.autoPlay)
            )
            val consumedLoadIntent = AudioPlaybackLoadIntentPolicy.afterServiceDispatch(loadIntent)
            forceStartPlayback = consumedLoadIntent.forceStart
            resolveFreshPlayback = consumedLoadIntent.resolveFresh
            autoPlayPlayback = consumedLoadIntent.autoPlay
            consumePlaybackCommand()
            connectController()
            val playing = loadIntent.autoPlay ||
                (AudioPlaybackStateStore.current(this@AudioPlayerActivity)
                    ?.takeIf { it.chapterRouteId == chapterRouteId }
                    ?.isPlaying == true)
            renderPlayerStatus(if (playing) "正在播放" else "已暂停")
            renderPlayPauseIcon(playing)
            updateCoverRotation(playing)
        }
    }

    private fun bindExistingPlaybackSession() {
        val nowPlaying = AudioPlaybackStateStore.current(this)
            ?.takeIf { it.chapterRouteId == chapterRouteId }
        currentBookTitle = currentBookTitle.ifBlank { nowPlaying?.bookTitle.orEmpty() }
        currentCoverUrl = nowPlaying?.coverUrl.orEmpty().ifBlank { currentCoverUrl }
        renderTitleHeader()
        renderCover(currentCoverUrl)
        episodes = AudioChapterOrder.chronological(
            bookRouteId
                ?.let { MediaRouteRegistry.chaptersForBookRoute(it) }
                .orEmpty()
                .map { snapshot ->
                    MediaChapterItem(
                        routeId = snapshot.routeId,
                        title = snapshot.chapter.name,
                        index = snapshot.chapter.index
                    )
                }
        )
        episodeAdapter.useChronologicalDisplayOrder(episodes)
        episodeAdapter.refreshItems(episodes)
        episodeAdapter.selectedIndex = currentEpisodeIndex
        renderCatalogState(
            if (episodes.isEmpty()) MediaDetailLoadProgress.Step.FAILED.label else "",
            retryEnabled = false
        )
        updateEpisodeButtons()
        consumePlaybackCommand()
        connectController()
        val playing = nowPlaying?.isPlaying == true
        renderPlayerStatus(if (playing) "正在播放" else "已暂停")
        renderPlayPauseIcon(playing)
        updateCoverRotation(playing)
        recoverBoundCatalogIfNeeded(loadToken)
    }

    private fun recoverBoundCatalogIfNeeded(token: Int) {
        val recoveryBookRouteId = bookRouteId.orEmpty()
        when (
            AudioBoundCatalogRecoveryPolicy.decide(
                recoveryBookRouteId,
                registeredEpisodeCount = episodes.size
            )
        ) {
            AudioBoundCatalogRecoveryPolicy.KEEP_REGISTERED -> return
            AudioBoundCatalogRecoveryPolicy.UNAVAILABLE -> return
            AudioBoundCatalogRecoveryPolicy.LOAD_FULL_CATALOG -> Unit
        }
        playerLoadJob = scope.launch {
            val recoveredEpisodes = withTimeoutOrNull(PLAYER_LOAD_TIMEOUT_MS) {
                loadRepositoryValue {
                    MediaSourceRepository.chapters(recoveryBookRouteId)
                }
            }
            if (token != loadToken) return@launch
            if (recoveredEpisodes.isNullOrEmpty()) {
                renderCatalogState(MediaDetailLoadProgress.Step.FAILED.label, retryEnabled = true)
                return@launch
            }
            episodes = AudioChapterOrder.chronological(recoveredEpisodes)
            episodeAdapter.useChronologicalDisplayOrder(episodes)
            episodeAdapter.refreshItems(episodes)
            episodeAdapter.selectedIndex = currentEpisodeIndex
            renderCatalogState("", retryEnabled = false)
            updateEpisodeButtons()
        }
    }

    private fun consumePlaybackCommand() {
        playbackCommandConsumed = true
        intent.removeExtra(EXTRA_FORCE_START)
        intent.removeExtra(EXTRA_AUTO_PLAY)
    }

    private fun renderSavedProgress() {
        val saved = AudioPlaybackStateStore.current(this)?.takeIf { it.chapterRouteId == chapterRouteId } ?: return
        val position = saved.positionMs.coerceAtLeast(0L)
        val duration = saved.durationMs
        if (position <= 0L && duration <= 0L) return
        binding.audioPlayerElapsed.text = formatMillis(position)
        if (duration > 0L) {
            binding.audioPlayerDuration.text = formatMillis(duration)
            binding.audioPlayerProgress.progress = (position * SEEK_BAR_MAX / duration).toInt().coerceIn(0, SEEK_BAR_MAX)
        }
    }

    private suspend fun <T> loadRepositoryValue(block: () -> T): T? {
        return AudioRepositoryCallRunner.runForegroundPlayer(block)
    }

    private suspend fun resolveFreshAudioRequest(): AudioRequestResolution {
        return when (
            val result = AudioRepositoryCallRunner.runForegroundPlayerResult {
                MediaSourceRepository.audioRequest(chapterRouteId)
            }
        ) {
            is AudioRepositoryCallResult.Success -> AudioRequestResolution(
                request = result.value,
                failureMessage = null
            )
            is AudioRepositoryCallResult.Failure -> AudioRequestResolution(
                request = null,
                failureMessage = AudioPlaybackFailureCopy.messageForRepositoryFailure(result.error)
            )
            AudioRepositoryCallResult.Busy -> AudioRequestResolution(
                request = null,
                failureMessage = AudioPlaybackFailureCopy.unknownFailureMessage()
            )
        }
    }

    private fun renderTitleHeader() {
        binding.audioPlayerTitle.text = currentBookTitle.ifBlank { "听书" }
        binding.audioPlayerEpisode.text = currentEpisodeTitle.ifBlank { "未知章节" }
    }

    private fun persistControllerProgress() {
        val player = controller ?: return
        val duration = player.duration
        if (duration <= 0L) return
        persistAudioProgress(player.currentPosition.coerceAtLeast(0L), duration)
    }

    private fun persistAudioProgress(position: Long, duration: Long) {
        if (chapterRouteId.isBlank() || position <= 0L || duration <= 0L) return
        AudioPlaybackProgressStore.save(this, chapterRouteId, position, duration)
        AudioPlaybackStateStore.setProgress(this, chapterRouteId, position, duration)
    }

    override fun onDestroy() {
        playerLoadJob?.cancel()
        playerLoadJob = null
        persistControllerProgress()
        progressHandler.removeCallbacks(progressTicker)
        coverAnimator?.cancel()
        coverAnimator = null
        releaseCoverBackgroundTarget()
        releaseControllerConnection()
        scope.cancel()
        super.onDestroy()
    }

    private fun connectController() {
        progressHandler.removeCallbacks(progressTicker)
        releaseControllerConnection()
        val generation = controllerConnectGeneration
        val token = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val result = runCatching { future.get() }
                if (!isCurrentControllerConnection(generation, future)) {
                    result.getOrNull()?.release()
                    return@addListener
                }
                val connectedController = result.getOrElse { error ->
                    if (!error.isFutureCancellation()) {
                        progressHandler.post {
                            if (isCurrentControllerConnection(generation, future)) {
                                renderPlayerFailure(AudioPlaybackFailureCopy.unknownFailureMessage())
                            }
                        }
                    }
                    return@addListener
                }
                controller = connectedController
                connectedController.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        postForController(generation, connectedController) {
                            boundSessionRecoveryDispatchPending = false
                            syncRouteFromMediaItem(mediaItem)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        postForController(generation, connectedController) {
                            if (playbackState == Player.STATE_READY) {
                                boundSessionRecoveryDispatchPending = false
                                syncRouteFromMediaItem(connectedController.currentMediaItem)
                            }
                            updatePlayerUi()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        postForController(generation, connectedController) { updatePlayerUi() }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        postForController(generation, connectedController) {
                            renderPlaybackSpeed(playbackParameters.speed)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        postForController(generation, connectedController) {
                            boundSessionRecoveryAction = AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH
                            boundSessionRecoveryDispatchPending = false
                            renderPlayerFailure(
                                AudioPlaybackFailureCopy.messageForPlayerError(error.errorCode)
                            )
                            renderPlayPauseIcon(false)
                        }
                    }
                })
                val connectedSpeed = connectedController.playbackParameters.speed
                AudioPlaybackStateStore.setPlaybackSpeed(this, connectedSpeed)
                progressHandler.removeCallbacks(progressTicker)
                progressHandler.post {
                    if (!isActiveController(generation, connectedController)) return@post
                    if (connectedController.playbackState == Player.STATE_READY) {
                        syncRouteFromMediaItem(connectedController.currentMediaItem)
                    }
                    recoverBoundSessionIfNeeded(connectedController)
                    renderPlaybackSpeed(connectedSpeed)
                    progressTicker.run()
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun releaseControllerConnection() {
        controllerConnectGeneration += 1L
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    private fun recoverBoundSessionIfNeeded(player: MediaController) {
        if (!bindExistingSessionOnly) return
        val persisted = AudioPlaybackStateStore.current(this)
            ?.takeIf { it.chapterRouteId == chapterRouteId }
        val action = AudioBoundSessionRecoveryPolicy.decide(
            player.mediaItemCount > 0,
            player.playerError != null,
            chapterRouteId,
            persisted?.chapterRouteId.orEmpty(),
            persisted?.audioUrl.orEmpty(),
            persisted?.playbackReady == true,
            persisted?.playbackEnded == true
        )
        boundSessionRecoveryAction = action
        boundSessionRecoveryState = persisted
        when (action) {
            AudioBoundSessionRecoveryPolicy.Action.KEEP_LIVE -> Unit
            AudioBoundSessionRecoveryPolicy.Action.RESTORE_PAUSED -> {
                persisted ?: return
                boundSessionRecoveryDispatchPending = true
                dispatchPersistedPlayback(persisted, forceStart = false, autoPlay = false)
                renderPlayerStatus("正在恢复...")
            }
            AudioBoundSessionRecoveryPolicy.Action.SHOW_ENDED -> {
                renderPlayerStatus("播放完成")
                renderPlayPauseIcon(false)
            }
            AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH -> {
                renderPlayerStatus("点击播放恢复")
                renderPlayPauseIcon(false)
            }
        }
        renderControlReadiness()
    }

    private fun handleEmptyBoundSessionPrimaryAction(): Boolean {
        if (!bindExistingSessionOnly) return false
        if (boundSessionRecoveryDispatchPending) return true
        if (AudioPlaybackFailureStore.current()?.chapterRouteId == chapterRouteId) {
            boundSessionRecoveryAction = AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH
        }
        return when (boundSessionRecoveryAction) {
            AudioBoundSessionRecoveryPolicy.Action.RESTORE_PAUSED -> {
                val persisted = boundSessionRecoveryState ?: return false
                boundSessionRecoveryDispatchPending = true
                dispatchPersistedPlayback(persisted, forceStart = false, autoPlay = true)
                renderPlayerStatus("正在播放")
                renderControlReadiness()
                true
            }
            AudioBoundSessionRecoveryPolicy.Action.SHOW_ENDED -> {
                val persisted = boundSessionRecoveryState ?: return false
                boundSessionRecoveryDispatchPending = true
                dispatchPersistedPlayback(persisted, forceStart = true, autoPlay = true)
                renderPlayerStatus("正在播放")
                renderControlReadiness()
                true
            }
            AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH -> {
                bindExistingSessionOnly = false
                resolveFreshPlayback = true
                forceStartPlayback = false
                autoPlayPlayback = true
                loadPlayer()
                true
            }
            AudioBoundSessionRecoveryPolicy.Action.KEEP_LIVE,
            null -> false
        }
    }

    private fun dispatchPersistedPlayback(
        persisted: AudioNowPlaying,
        forceStart: Boolean,
        autoPlay: Boolean
    ) {
        if (
            persisted.chapterRouteId != chapterRouteId ||
            !persisted.playbackReady ||
            persisted.audioUrl.isBlank()
        ) {
            return
        }
        AudioPlaybackFailureStore.clear()
        startService(
            Intent(this, AudioPlaybackService::class.java)
                .putExtra(AudioPlaybackService.EXTRA_URL, persisted.audioUrl)
                .putExtra(AudioPlaybackService.EXTRA_TITLE, persisted.title)
                .putExtra(AudioPlaybackService.EXTRA_CHAPTER_ROUTE_ID, persisted.chapterRouteId)
                .putExtra(AudioPlaybackService.EXTRA_BOOK_ROUTE_ID, persisted.bookRouteId)
                .putExtra(AudioPlaybackService.EXTRA_BOOK_TITLE, persisted.bookTitle)
                .putExtra(AudioPlaybackService.EXTRA_COVER_URL, persisted.coverUrl)
                .putExtra(
                    AudioPlaybackService.EXTRA_HEADERS_JSON,
                    AudioPlaybackService.encodeHeaders(persisted.headers)
                )
                .putExtra(AudioPlaybackService.EXTRA_FORCE_START, forceStart)
                .putExtra(AudioPlaybackService.EXTRA_AUTO_PLAY, autoPlay)
        )
    }

    private fun isCurrentControllerConnection(
        generation: Long,
        future: ListenableFuture<MediaController>
    ): Boolean {
        return generation == controllerConnectGeneration &&
            controllerFuture === future &&
            isActivityAlive()
    }

    private fun isActiveController(generation: Long, expected: MediaController): Boolean {
        return generation == controllerConnectGeneration && controller === expected && isActivityAlive()
    }

    private fun postForController(
        generation: Long,
        expected: MediaController,
        block: () -> Unit
    ) {
        progressHandler.post {
            if (isActiveController(generation, expected)) {
                block()
            }
        }
    }

    private fun Throwable.isFutureCancellation(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is FutureCancellationException) return true
            current = current.cause
        }
        return false
    }

    private fun syncRouteFromMediaItem(mediaItem: MediaItem?) {
        syncPlaybackRoute(mediaItem?.mediaId.orEmpty())
    }

    private fun syncRouteFromPlaybackFailure(failure: AudioPlaybackFailure) {
        syncPlaybackRoute(failure.chapterRouteId)
    }

    private fun syncPlaybackRoute(nextRouteId: String) {
        if (nextRouteId.isBlank() || nextRouteId == chapterRouteId) return
        if (!MediaShelfStore.restoreForChapter(this, nextRouteId)) return
        val nextChapter = MediaRouteRegistry.chapter(nextRouteId) ?: return
        val nextBookRouteId = MediaRouteRegistry.bookRouteForChapter(nextRouteId) ?: return
        chapterRouteId = nextRouteId
        bookRouteId = nextBookRouteId
        currentEpisodeIndex = nextChapter.index
        currentEpisodeTitle = nextChapter.name
        AudioPlaybackStateStore.current(this)
            ?.takeIf { it.chapterRouteId == nextRouteId }
            ?.let { nowPlaying ->
                currentBookTitle = nowPlaying.bookTitle
                currentCoverUrl = nowPlaying.coverUrl
            }
        renderTitleHeader()
        binding.audioPlayerElapsed.text = "00:00"
        binding.audioPlayerDuration.text = "--:--"
        binding.audioPlayerProgress.progress = 0
        updatePlayerUi()
        episodeAdapter.selectedIndex = currentEpisodeIndex
        updateEpisodeButtons()
    }

    private fun updatePlayerUi() {
        AudioPlaybackFailureStore.current()?.let { failure ->
            syncRouteFromPlaybackFailure(failure)
            renderPlayerFailure(AudioPlaybackFailureCopy.messageForRecordedFailure(failure))
            renderPlayPauseIcon(false)
            updateCoverRotation(false)
            return
        }
        val player = controller ?: return
        val controlDecision = playerControlDecision(player)
        renderPlayPauseIcon(controlDecision)
        updateCoverRotation(controlDecision.showsPause)
        when {
            player.playbackState == Player.STATE_BUFFERING -> renderPlayerStatus("正在缓冲")
            player.playbackState == Player.STATE_READY && player.isPlaying -> renderPlayerStatus("正在播放")
            player.playbackState == Player.STATE_READY -> renderPlayerStatus("已暂停")
            player.playbackState == Player.STATE_ENDED -> renderPlayerStatus("播放完成")
        }
        renderPlaybackSpeed(player.playbackParameters.speed)
        renderSleepTimer()
        val duration = player.duration
        val position = player.currentPosition.coerceAtLeast(0L)
        binding.audioPlayerElapsed.text = formatMillis(position)
        if (duration > 0) {
            binding.audioPlayerDuration.text = formatMillis(duration)
            if (!userSeeking) {
                binding.audioPlayerProgress.progress = (position * SEEK_BAR_MAX / duration).toInt().coerceIn(0, SEEK_BAR_MAX)
            }
        } else {
            binding.audioPlayerDuration.text = "--:--"
        }
    }

    private fun playerControlDecision(player: Player): AudioPlayerControlDecision {
        return AudioPlayerControlPolicy.resolve(
            playbackEnded = player.playbackState == Player.STATE_ENDED,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady
        )
    }

    private fun renderTab() {
        binding.audioPlayerNowPanel.visibility = if (showingCatalog) View.GONE else View.VISIBLE
        binding.audioPlayerCatalogList.visibility =
            if (showingCatalog && episodes.isNotEmpty() && !catalogRetryEnabled) View.VISIBLE else View.GONE
        binding.audioPlayerCatalogState.visibility =
            if (showingCatalog && (episodes.isEmpty() || catalogRetryEnabled)) View.VISIBLE else View.GONE
        binding.audioPlayerTabNow.setTextColor(
            resources.getColor(if (showingCatalog) R.color.media_audio_player_text_secondary else R.color.media_audio_player_text_primary)
        )
        binding.audioPlayerTabCatalog.setTextColor(
            resources.getColor(if (showingCatalog) R.color.media_audio_player_text_primary else R.color.media_audio_player_text_secondary)
        )
        binding.audioPlayerTabNowLine.visibility = if (showingCatalog) View.INVISIBLE else View.VISIBLE
        binding.audioPlayerTabCatalogLine.visibility = if (showingCatalog) View.VISIBLE else View.INVISIBLE
        binding.audioPlayerTabNow.setTypeface(null, android.graphics.Typeface.NORMAL)
        binding.audioPlayerTabCatalog.setTypeface(null, android.graphics.Typeface.NORMAL)
    }

    private fun openSiblingEpisode(offset: Int) {
        val target = AudioEpisodeNavigation.sibling(episodes, chapterRouteId, offset) ?: return
        transitionToEpisode(
            target,
            position = AudioEpisodeNavigation.positionOf(episodes, target.routeId)
        )
    }

    private fun transitionToEpisode(episode: MediaChapterItem, position: Int) {
        val forceStart = episode.routeId != chapterRouteId
        AiBridgeTrace.event(
            "media_audio_episode_selected",
            episode.routeId,
            AiBridgeTrace.fields("position" to position, "title" to episode.title)
        )
        if (forceStart) {
            persistControllerProgress()
            controller?.pause()
            progressHandler.removeCallbacks(progressTicker)
            releaseControllerConnection()
            renderPlayerStatus("切换中...")
            currentEpisodeTitle = episode.title
            renderTitleHeader()
            renderPlayPauseIcon(false)
            episodeAdapter.selectedIndex = episode.index
            updateCoverRotation(false)
        }
        startManualSelection(
            this,
            episode.routeId,
            episode.title,
            bookTitle = currentBookTitle,
            forceStart = forceStart,
            autoPlay = true
        )
    }

    private fun renderCover(coverUrl: String) {
        BookCoverLoader.load(
            listOfNotNull(coverUrl.takeIf { it.isNotBlank() }),
            binding.audioPlayerCover,
            R.drawable.ic_book_cover_placeholder,
            circle = true
        )
        renderCoverBackground(coverUrl)
    }

    private fun renderCoverBackground(coverUrl: String) {
        clearCoverBackgroundTarget()
        val cleanCoverUrl = BookCoverUrl.clean(coverUrl).takeIf { BookCoverUrl.isUsable(it) }
        if (cleanCoverUrl == null) {
            applyPlayerBackground(DEFAULT_AUDIO_BACKGROUND_COLOR)
            return
        }
        val target = object : CustomTarget<Bitmap>(96, 96) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (!isActivityAlive() || coverBackgroundTarget !== this) return
                applyPlayerBackground(extractPlayerBackgroundColor(resource))
            }

            override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit

            override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                if (!isActivityAlive() || coverBackgroundTarget !== this) return
                applyPlayerBackground(DEFAULT_AUDIO_BACKGROUND_COLOR)
            }
        }
        coverBackgroundTarget = target
        Glide.with(applicationContext)
            .asBitmap()
            .load(coverGlideModel(cleanCoverUrl))
            .dontAnimate()
            .into(target)
    }

    private fun clearCoverBackgroundTarget() {
        val target = coverBackgroundTarget ?: return
        coverBackgroundTarget = null
        Glide.with(applicationContext).clear(target)
    }

    private fun releaseCoverBackgroundTarget() {
        coverBackgroundTarget = null
    }

    private fun isActivityAlive(): Boolean {
        return !isFinishing && !isDestroyed
    }

    private fun applyPlayerBackground(seedColor: Int) {
        val top = toneColor(seedColor, saturationFloor = 0.45f, value = 0.32f)
        val middle = toneColor(seedColor, saturationFloor = 0.42f, value = 0.20f)
        val bottom = toneColor(seedColor, saturationFloor = 0.38f, value = 0.07f)
        binding.root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, middle, bottom)
        )
        window.statusBarColor = top
        window.navigationBarColor = bottom
    }

    private fun extractPlayerBackgroundColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return DEFAULT_AUDIO_BACKGROUND_COLOR
        val bins = HashMap<Int, ColorBucket>()
        val step = max(1, min(width, height) / 32)
        val hsv = FloatArray(3)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) >= 160) {
                    Color.colorToHSV(color, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    if (saturation >= 0.16f && value in 0.10f..0.92f) {
                        val key = ((Color.red(color) / 16) shl 8) or
                            ((Color.green(color) / 16) shl 4) or
                            (Color.blue(color) / 16)
                        bins.getOrPut(key) { ColorBucket() }.add(color, saturation, value)
                    }
                }
                x += step
            }
            y += step
        }
        return bins.values
            .maxByOrNull { it.score() }
            ?.averageColor()
            ?: DEFAULT_AUDIO_BACKGROUND_COLOR
    }

    private fun toneColor(color: Int, saturationFloor: Float, value: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = max(hsv[1], saturationFloor).coerceAtMost(0.78f)
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    private fun coverGlideModel(url: String): Any {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return url
        }
        val uri = Uri.parse(url)
        val referer = uri.host?.let { host ->
            "${uri.scheme ?: "https"}://$host/"
        } ?: url
        return GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("User-Agent", COVER_USER_AGENT)
                .addHeader("Referer", referer)
                .build()
        )
    }

    private fun updateCoverRotation(playing: Boolean) {
        coverAnimator = AudioCoverChrome.updateRotation(binding.audioPlayerCover, playing, coverAnimator)
        val trace = "playing_${playing}_started_${coverAnimator?.isStarted == true}_running_${coverAnimator?.isRunning == true}"
        if (trace == lastCoverRotationTrace) return
        lastCoverRotationTrace = trace
        AiBridgeTrace.state(
            "media_audio_cover_rotation",
            chapterRouteId,
            AiBridgeTrace.fields(
                "playing" to playing,
                "started" to (coverAnimator?.isStarted == true),
                "running" to (coverAnimator?.isRunning == true),
                "rotation" to binding.audioPlayerCover.rotation.toInt()
            )
        )
    }

    private fun updateEpisodeButtons() {
        val hasPrevious = AudioEpisodeNavigation.sibling(episodes, chapterRouteId, -1) != null
        val hasNext = AudioEpisodeNavigation.sibling(episodes, chapterRouteId, 1) != null
        binding.audioPlayerPrevious.isEnabled = hasPrevious
        binding.audioPlayerNext.isEnabled = hasNext
        binding.audioPlayerPrevious.alpha = if (hasPrevious) 1f else 0.38f
        binding.audioPlayerNext.alpha = if (hasNext) 1f else 0.38f
    }

    private fun setSleepTimer(minutes: Int) {
        startService(
            Intent(this, AudioPlaybackService::class.java)
                .setAction(AudioPlaybackService.ACTION_SET_SLEEP_TIMER)
                .putExtra(AudioPlaybackService.EXTRA_SLEEP_TIMER_MINUTES, minutes)
        )
        binding.audioPlayerSleep.text = AudioSleepTimer.label(minutes)
        AiBridgeTrace.event(
            "media_audio_sleep_timer_selected",
            chapterRouteId,
            AiBridgeTrace.fields("minutes" to minutes)
        )
    }

    private fun renderSleepTimer() {
        binding.audioPlayerSleep.text = AudioSleepTimer.label(activeSleepTimerMinutes())
    }

    private fun renderPlaybackSpeed(speed: Float?) {
        binding.audioPlayerSpeed.text = AudioPlaybackSpeed.label(speed)
    }

    private fun renderCatalogState(text: String, retryEnabled: Boolean) {
        catalogStateText = text
        catalogRetryEnabled = retryEnabled
        binding.audioPlayerCatalogState.text = catalogStateText
        binding.audioPlayerCatalogState.isClickable = retryEnabled
        binding.audioPlayerCatalogState.contentDescription = catalogStateText
        binding.audioPlayerCatalogState.visibility =
            if (showingCatalog && (episodes.isEmpty() || retryEnabled)) View.VISIBLE else View.GONE
        binding.audioPlayerCatalogList.visibility =
            if (showingCatalog && episodes.isNotEmpty() && !retryEnabled) View.VISIBLE else View.GONE
    }

    private fun renderPlayerStatus(text: String) {
        playerRetryEnabled = false
        binding.audioPlayerState.isClickable = false
        binding.audioPlayerState.contentDescription = text
        binding.audioPlayerState.text = text
        binding.audioPlayerState.setTextColor(resources.getColor(R.color.media_audio_player_text_secondary))
    }

    private fun renderPlayerFailure(message: String) {
        playerRetryEnabled = true
        binding.audioPlayerState.isClickable = true
        binding.audioPlayerState.contentDescription = message
        binding.audioPlayerState.text = message
        binding.audioPlayerState.setTextColor(resources.getColor(R.color.media_audio_player_text_primary))
        renderControlReadiness()
    }

    private fun activeSleepTimerMinutes(): Int {
        val nowPlaying = AudioPlaybackStateStore.current(this) ?: return 0
        return if (AudioSleepTimer.isActive(
                nowPlaying.sleepTimerMinutes,
                nowPlaying.sleepTimerEndAtMs
            )
        ) {
            nowPlaying.sleepTimerMinutes
        } else {
            0
        }
    }

    private fun renderPlayPauseIcon(playing: Boolean) {
        binding.audioPlayerPlayPause.setImageResource(
            if (playing) R.drawable.ic_audio_player_pause_round else R.drawable.ic_audio_player_play_round
        )
        binding.audioPlayerPlayPause.contentDescription = if (playing) "暂停" else "播放"
        renderControlReadiness()
    }

    private fun renderPlayPauseIcon(decision: AudioPlayerControlDecision) {
        binding.audioPlayerPlayPause.setImageResource(
            if (decision.showsPause) {
                R.drawable.ic_audio_player_pause_round
            } else {
                R.drawable.ic_audio_player_play_round
            }
        )
        binding.audioPlayerPlayPause.contentDescription = decision.contentDescription
        renderControlReadiness()
    }

    private fun retryFailedPlayback(): Boolean {
        if (!playerRetryEnabled) return false
        persistControllerProgress()
        val retry = AudioPlaybackRetryPolicy.afterPlaybackFailure()
        bindExistingSessionOnly = false
        resolveFreshPlayback = retry.resolveFreshUrl
        forceStartPlayback = retry.restartFromBeginning
        autoPlayPlayback = retry.autoPlay
        loadPlayer()
        return true
    }

    private fun renderControlReadiness() {
        val recoveryActionAvailable = when (boundSessionRecoveryAction) {
            AudioBoundSessionRecoveryPolicy.Action.RESTORE_PAUSED,
            AudioBoundSessionRecoveryPolicy.Action.SHOW_ENDED,
            AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH -> true
            AudioBoundSessionRecoveryPolicy.Action.KEEP_LIVE,
            null -> false
        }
        val readiness = AudioPlayerUiReadinessPolicy.decide(
            controllerConnected = controller != null,
            hasLiveMediaItem = (controller?.mediaItemCount ?: 0) > 0 &&
                controller?.playerError == null &&
                boundSessionRecoveryAction != AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH &&
                !playerRetryEnabled,
            recoveryActionAvailable = recoveryActionAvailable,
            recoveryDispatchPending = boundSessionRecoveryDispatchPending,
            retryAvailable = playerRetryEnabled
        )
        binding.audioPlayerPlayPause.isEnabled = readiness.primaryEnabled
        binding.audioPlayerPlayPause.alpha = if (readiness.primaryEnabled) 1f else 0.38f
        binding.audioPlayerRewind.isEnabled = readiness.sessionControlsEnabled
        binding.audioPlayerForward.isEnabled = readiness.sessionControlsEnabled
        binding.audioPlayerProgress.isEnabled = readiness.sessionControlsEnabled
        binding.audioPlayerSpeed.isEnabled = readiness.sessionControlsEnabled
        binding.audioPlayerSleep.isEnabled = readiness.sessionControlsEnabled
        val sessionAlpha = if (readiness.sessionControlsEnabled) 1f else 0.38f
        binding.audioPlayerRewind.alpha = sessionAlpha
        binding.audioPlayerForward.alpha = sessionAlpha
        binding.audioPlayerProgress.alpha = sessionAlpha
        binding.audioPlayerSpeed.alpha = sessionAlpha
        binding.audioPlayerSleep.alpha = sessionAlpha
        binding.audioPlayerPlayPause.contentDescription = when {
            playerRetryEnabled -> "重试播放"
            !readiness.primaryEnabled -> "音频正在加载，暂不可播放"
            else -> binding.audioPlayerPlayPause.contentDescription
        }
    }

    private fun formatMillis(value: Long): String {
        val totalSeconds = (value / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun String.audioTraceToken(): String {
        val uri = Uri.parse(this)
        return listOf(uri.host.orEmpty(), uri.lastPathSegment.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString("|")
            .ifBlank { hashCode().toString() }
    }

    private class ColorBucket {
        private var count = 0
        private var red = 0L
        private var green = 0L
        private var blue = 0L
        private var saturationScore = 0f
        private var valueScore = 0f

        fun add(color: Int, saturation: Float, value: Float) {
            count += 1
            red += Color.red(color).toLong()
            green += Color.green(color).toLong()
            blue += Color.blue(color).toLong()
            saturationScore += saturation
            valueScore += 1f - abs(value - 0.48f)
        }

        fun score(): Float {
            if (count <= 0) return 0f
            return count * (saturationScore / count) * (valueScore / count)
        }

        fun averageColor(): Int {
            val safeCount = count.coerceAtLeast(1)
            return Color.rgb(
                (red / safeCount).toInt().coerceIn(0, 255),
                (green / safeCount).toInt().coerceIn(0, 255),
                (blue / safeCount).toInt().coerceIn(0, 255)
            )
        }
    }

    private data class AudioRequestResolution(
        val request: MediaRequest?,
        val failureMessage: String?
    )

    companion object {
        private const val SEEK_BAR_MAX = 1000
        private const val PLAYER_LOAD_TIMEOUT_MS = 45_000L
        private const val EXTRA_CHAPTER_ROUTE_ID = "chapter_route_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BOOK_TITLE = "book_title"
        private const val EXTRA_FORCE_START = "force_start"
        private const val EXTRA_AUTO_PLAY = "auto_play"
        private const val EXTRA_BIND_EXISTING_SESSION_ONLY = "bind_existing_session_only"
        private const val STATE_PLAYBACK_COMMAND_CONSUMED = "playback_command_consumed"
        private const val DEFAULT_AUDIO_BACKGROUND_COLOR = 0xFF0F3A2B.toInt()
        private const val COVER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"

        fun createIntent(
            context: Context,
            chapterRouteId: String,
            title: String,
            bookTitle: String = "",
            forceStart: Boolean = false,
            autoPlay: Boolean = false
        ): Intent {
            return Intent(context, AudioPlayerActivity::class.java)
                .putExtra(EXTRA_CHAPTER_ROUTE_ID, chapterRouteId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BOOK_TITLE, bookTitle)
                .putExtra(EXTRA_FORCE_START, forceStart)
                .putExtra(EXTRA_AUTO_PLAY, autoPlay)
        }

        fun startManualSelection(
            context: Context,
            chapterRouteId: String,
            title: String,
            bookTitle: String = "",
            forceStart: Boolean = false,
            autoPlay: Boolean = true
        ) {
            context.startService(
                Intent(context, AudioPlaybackService::class.java)
                    .setAction(AudioPlaybackService.ACTION_CANCEL_AUTO_ADVANCE)
            )
            start(context, chapterRouteId, title, bookTitle, forceStart, autoPlay)
        }

        fun openCurrent(
            context: Context,
            chapterRouteId: String,
            title: String,
            bookTitle: String = ""
        ) {
            context.startActivity(
                createIntent(context, chapterRouteId, title, bookTitle)
                    .putExtra(EXTRA_BIND_EXISTING_SESSION_ONLY, true)
            )
        }

        fun start(
            context: Context,
            chapterRouteId: String,
            title: String,
            bookTitle: String = "",
            forceStart: Boolean = false,
            autoPlay: Boolean = false
        ) {
            context.startActivity(
                createIntent(context, chapterRouteId, title, bookTitle, forceStart, autoPlay)
            )
        }
    }
}
