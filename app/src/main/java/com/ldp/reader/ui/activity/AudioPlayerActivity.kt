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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ldp.reader.R
import com.ldp.reader.audio.AudioPlaybackProgressStore
import com.ldp.reader.audio.AudioPlaybackService
import com.ldp.reader.audio.AudioSleepTimer
import com.ldp.reader.audio.AudioPlaybackStateStore
import com.ldp.reader.databinding.ActivityAudioPlayerBinding
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var speedIndex = 0
    private var showingCatalog = false
    private var loadToken = 0
    private var currentCoverUrl: String = ""
    private var coverAnimator: ObjectAnimator? = null
    private var coverBackgroundTarget: CustomTarget<Bitmap>? = null
    private var lastCoverRotationTrace = ""
    private var forceStartPlayback = false
    private var autoPlayPlayback = false
    private val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.8f)

    override fun getViewBinding(): ActivityAudioPlayerBinding {
        return ActivityAudioPlayerBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.audioPlayerToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_audio_player_close_32)
        MediaUiChrome.darkReader(this)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        BookContentProviderRouter.stopLowPriorityV8Maintenance("audio-player")
        readIntent(intent)
    }

    override fun initWidget() {
        super.initWidget()
        renderTitleHeader()
        binding.audioPlayerState.text = "加载中..."
        renderPlayPauseIcon(false)
        binding.audioPlayerElapsed.text = "00:00"
        binding.audioPlayerDuration.text = "00:00"
        renderSleepTimer()
        binding.audioPlayerCatalogList.layoutManager = LinearLayoutManager(this)
        binding.audioPlayerCatalogList.adapter = episodeAdapter
        AudioCoverChrome.configureCircularCover(binding.audioPlayerCover)
        episodeAdapter.accentColorRes = R.color.media_audio_accent
        episodeAdapter.metaPrefix = "剧集"
        episodeAdapter.selectedIndex = currentEpisodeIndex
        renderTab()
        updateEpisodeButtons()
    }

    override fun initClick() {
        super.initClick()
        binding.audioPlayerPlayPause.setOnClickListener {
            val player = controller ?: return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
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
        episodeAdapter.setOnItemClickListener { _, pos ->
            val episode = episodeAdapter.getItem(pos)
            val forceStart = episode.routeId != chapterRouteId
            AiBridgeTrace.event(
                "media_audio_episode_selected",
                episode.routeId,
                AiBridgeTrace.fields("position" to pos, "title" to episode.title)
            )
            if (forceStart) {
                controller?.pause()
                binding.audioPlayerState.text = "切换中..."
                currentEpisodeTitle = episode.title
                renderTitleHeader()
                renderPlayPauseIcon(false)
                episodeAdapter.selectedIndex = MediaRouteRegistry.chapter(episode.routeId)?.index ?: pos
                updateCoverRotation(false)
            }
            start(this, episode.routeId, episode.title, bookTitle = currentBookTitle, forceStart = forceStart, autoPlay = true)
        }
        binding.audioPlayerSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val speed = speeds[speedIndex]
            controller?.setPlaybackSpeed(speed)
            binding.audioPlayerSpeed.text = if (speed == 1.0f) "倍速" else "${speed.formatSpeed()}x"
        }
        binding.audioPlayerSleep.setOnClickListener {
            val current = activeSleepTimerMinutes()
            val minutes = AudioSleepTimer.nextMinutes(current)
            setSleepTimer(minutes)
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
        readIntent(intent)
        loadPlayer()
    }

    override fun onPause() {
        persistControllerProgress()
        super.onPause()
    }

    private fun readIntent(intent: Intent) {
        chapterRouteId = intent.getStringExtra(EXTRA_CHAPTER_ROUTE_ID).orEmpty()
        MediaShelfStore.restoreForChapter(this, chapterRouteId)
        bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId)
        currentEpisodeIndex = MediaRouteRegistry.chapter(chapterRouteId)?.index ?: -1
        val nowPlaying = AudioPlaybackStateStore.current(this)?.takeIf { it.chapterRouteId == chapterRouteId }
        currentEpisodeTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifBlank { nowPlaying?.title.orEmpty() }
            .ifBlank { MediaRouteRegistry.chapter(chapterRouteId)?.name.orEmpty() }
        currentBookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
            .ifBlank { nowPlaying?.bookTitle.orEmpty() }
            .ifBlank { bookRouteId?.let { MediaRouteRegistry.detail(it)?.name.orEmpty() }.orEmpty() }
        forceStartPlayback = intent.getBooleanExtra(EXTRA_FORCE_START, false)
        autoPlayPlayback = intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)
    }

    private fun loadPlayer() {
        val token = ++loadToken
        renderTitleHeader()
        binding.audioPlayerState.text = "加载中..."
        renderPlayPauseIcon(false)
        binding.audioPlayerElapsed.text = "00:00"
        binding.audioPlayerDuration.text = "--:--"
        binding.audioPlayerProgress.progress = 0
        renderSavedProgress()
        updateCoverRotation(false)
        episodeAdapter.selectedIndex = currentEpisodeIndex
        updateEpisodeButtons()
        scope.launch {
            val bookRoute = bookRouteId
            if (bookRoute != null) {
                val detail = withContext(Dispatchers.IO) { MediaSourceRepository.detail(bookRoute) }
                if (token != loadToken) return@launch
                currentBookTitle = detail?.title.orEmpty().ifBlank { currentBookTitle }
                renderTitleHeader()
                currentCoverUrl = detail?.coverUrl.orEmpty()
                renderCover(currentCoverUrl)
                episodes = withContext(Dispatchers.IO) { MediaSourceRepository.chapters(bookRoute) }
                if (token != loadToken) return@launch
                episodeAdapter.refreshItems(episodes)
                episodeAdapter.selectedIndex = currentEpisodeIndex
                updateEpisodeButtons()
            } else {
                val nowPlaying = AudioPlaybackStateStore.current(this@AudioPlayerActivity)
                currentBookTitle = currentBookTitle.ifBlank { nowPlaying?.bookTitle.orEmpty() }
                currentCoverUrl = nowPlaying?.coverUrl.orEmpty()
                renderTitleHeader()
                renderCover(currentCoverUrl)
            }
            val restoredRequest = AudioPlaybackStateStore.current(this@AudioPlayerActivity)
                ?.takeIf { it.chapterRouteId == chapterRouteId && it.audioUrl.isNotBlank() && !forceStartPlayback }
                ?.let { MediaRequest(it.audioUrl, it.headers) }
            val request = restoredRequest ?: withContext(Dispatchers.IO) { MediaSourceRepository.audioRequest(chapterRouteId) }
            if (token != loadToken) return@launch
            if (request == null || request.url.isBlank()) {
                MediaSourceRepository.recordResolvedContent(chapterRouteId, 0)
                AiBridgeTrace.event(
                    "media_audio_request_resolved",
                    chapterRouteId,
                    AiBridgeTrace.fields("title" to currentEpisodeTitle, "url" to "blank")
                )
                binding.audioPlayerState.text = "未解析到音频地址"
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
                    .putExtra(AudioPlaybackService.EXTRA_FORCE_START, forceStartPlayback)
                    .putExtra(AudioPlaybackService.EXTRA_AUTO_PLAY, autoPlayPlayback)
            )
            connectController()
            val playing = autoPlayPlayback ||
                (AudioPlaybackStateStore.current(this@AudioPlayerActivity)
                    ?.takeIf { it.chapterRouteId == chapterRouteId }
                    ?.isPlaying == true)
            binding.audioPlayerState.text = if (playing) "正在播放" else "已暂停"
            renderPlayPauseIcon(playing)
            updateCoverRotation(playing)
        }
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
        persistControllerProgress()
        progressHandler.removeCallbacks(progressTicker)
        coverAnimator?.cancel()
        coverAnimator = null
        releaseCoverBackgroundTarget()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        scope.cancel()
        super.onDestroy()
    }

    private fun connectController() {
        progressHandler.removeCallbacks(progressTicker)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        val token = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                controller = future.get().apply {
                    addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            progressHandler.post { syncRouteFromMediaItem(mediaItem) }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            progressHandler.post { updatePlayerUi() }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            progressHandler.post { updatePlayerUi() }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            progressHandler.post {
                                binding.audioPlayerState.text = "播放失败，请稍后重试"
                                renderPlayPauseIcon(false)
                            }
                        }
                    })
                }
                progressHandler.removeCallbacks(progressTicker)
                progressHandler.post(progressTicker)
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun syncRouteFromMediaItem(mediaItem: MediaItem?) {
        val nextRouteId = mediaItem?.mediaId.orEmpty()
        if (nextRouteId.isBlank() || nextRouteId == chapterRouteId) return
        if (!MediaShelfStore.restoreForChapter(this, nextRouteId)) return
        val nextChapter = MediaRouteRegistry.chapter(nextRouteId) ?: return
        val nextBookRouteId = MediaRouteRegistry.bookRouteForChapter(nextRouteId) ?: return
        chapterRouteId = nextRouteId
        bookRouteId = nextBookRouteId
        currentEpisodeIndex = nextChapter.index
        currentEpisodeTitle = nextChapter.name
        MediaShelfStore.addOrUpdateForChapter(this, ReaderMediaKind.AUDIO, nextRouteId, currentEpisodeTitle)
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
        val player = controller ?: return
        val playing = player.isPlaying || player.playWhenReady
        renderPlayPauseIcon(playing)
        updateCoverRotation(playing)
        binding.audioPlayerState.text = when {
            player.playbackState == Player.STATE_BUFFERING -> "正在缓冲"
            player.playbackState == Player.STATE_READY && player.isPlaying -> "正在播放"
            player.playbackState == Player.STATE_READY -> "已暂停"
            player.playbackState == Player.STATE_ENDED -> "播放完成"
            else -> binding.audioPlayerState.text
        }
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

    private fun renderTab() {
        binding.audioPlayerNowPanel.visibility = if (showingCatalog) View.GONE else View.VISIBLE
        binding.audioPlayerCatalogList.visibility = if (showingCatalog) View.VISIBLE else View.GONE
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
        val target = episodes.getOrNull(currentEpisodeIndex + offset) ?: return
        start(this, target.routeId, target.title, bookTitle = currentBookTitle, forceStart = target.routeId != chapterRouteId, autoPlay = true)
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
        binding.audioPlayerPrevious.alpha = if (episodes.getOrNull(currentEpisodeIndex - 1) != null) 1f else 0.38f
        binding.audioPlayerNext.alpha = if (episodes.getOrNull(currentEpisodeIndex + 1) != null) 1f else 0.38f
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

    private fun Float.formatSpeed(): String {
        return if (this % 1f == 0f) toInt().toString() else toString()
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

    companion object {
        private const val SEEK_BAR_MAX = 1000
        private const val EXTRA_CHAPTER_ROUTE_ID = "chapter_route_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BOOK_TITLE = "book_title"
        private const val EXTRA_FORCE_START = "force_start"
        private const val EXTRA_AUTO_PLAY = "auto_play"
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
