package com.ldp.reader.ui.activity

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ldp.reader.R
import com.ldp.reader.audio.AudioPlaybackProgressStore
import com.ldp.reader.audio.AudioPlaybackService
import com.ldp.reader.audio.AudioPlaybackStateStore
import com.ldp.reader.databinding.ActivityAudioPlayerBinding
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.ui.adapter.MediaChapterAdapter
import com.ldp.reader.ui.audio.AudioCoverChrome
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.image.BookCoverLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayerActivity : BaseActivity<ActivityAudioPlayerBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            updatePlayerUi()
            progressHandler.postDelayed(this, 1_000L)
        }
    }
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var chapterRouteId: String
    private var bookRouteId: String? = null
    private var currentEpisodeIndex = -1
    private var episodes: List<MediaChapterItem> = emptyList()
    private val episodeAdapter = MediaChapterAdapter()
    private var userSeeking = false
    private var speedIndex = 0
    private var sleepIndex = 0
    private var showingCatalog = false
    private var loadToken = 0
    private var currentCoverUrl: String = ""
    private var coverAnimator: ObjectAnimator? = null
    private var forceStartPlayback = false
    private var autoPlayPlayback = false
    private val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.8f)
    private val sleepMinutes = listOf(0, 15, 30, 60)

    override fun getViewBinding(): ActivityAudioPlayerBinding {
        return ActivityAudioPlayerBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.audioPlayerToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_audio_player_close_32)
        MediaUiChrome.light(this)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        readIntent(intent)
    }

    override fun initWidget() {
        super.initWidget()
        binding.audioPlayerTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.audioPlayerState.text = "加载中..."
        renderPlayPauseIcon(false)
        binding.audioPlayerElapsed.text = "00:00"
        binding.audioPlayerDuration.text = "00:00"
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
                binding.audioPlayerTitle.text = episode.title
                renderPlayPauseIcon(false)
                episodeAdapter.selectedIndex = MediaRouteRegistry.chapter(episode.routeId)?.index ?: pos
                updateCoverRotation(false)
            }
            start(this, episode.routeId, episode.title, forceStart = forceStart, autoPlay = true)
        }
        binding.audioPlayerSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val speed = speeds[speedIndex]
            controller?.setPlaybackSpeed(speed)
            binding.audioPlayerSpeed.text = if (speed == 1.0f) "倍速" else "${speed.formatSpeed()}x"
        }
        binding.audioPlayerSleep.setOnClickListener {
            sleepIndex = (sleepIndex + 1) % sleepMinutes.size
            val minutes = sleepMinutes[sleepIndex]
            sleepHandler.removeCallbacksAndMessages(null)
            if (minutes <= 0) {
                binding.audioPlayerSleep.text = "定时"
            } else {
                binding.audioPlayerSleep.text = "${minutes}分"
                sleepHandler.postDelayed({ controller?.pause() }, minutes * 60_000L)
            }
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
        forceStartPlayback = intent.getBooleanExtra(EXTRA_FORCE_START, false)
        autoPlayPlayback = intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)
    }

    private fun loadPlayer() {
        val token = ++loadToken
        binding.audioPlayerTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
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
                currentCoverUrl = detail?.coverUrl.orEmpty()
                renderCover(currentCoverUrl)
                episodes = withContext(Dispatchers.IO) { MediaSourceRepository.chapters(bookRoute) }
                if (token != loadToken) return@launch
                episodeAdapter.refreshItems(episodes)
                episodeAdapter.selectedIndex = currentEpisodeIndex
                updateEpisodeButtons()
            } else {
                currentCoverUrl = AudioPlaybackStateStore.current(this@AudioPlayerActivity)?.coverUrl.orEmpty()
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
                    AiBridgeTrace.fields("title" to binding.audioPlayerTitle.text, "url" to "blank")
                )
                binding.audioPlayerState.text = "未解析到音频地址"
                return@launch
            }
            MediaSourceRepository.recordResolvedContent(chapterRouteId, 1)
            val title = binding.audioPlayerTitle.text?.toString().orEmpty()
            AiBridgeTrace.event(
                "media_audio_request_resolved",
                chapterRouteId,
                AiBridgeTrace.fields("title" to title, "url" to request.url.audioTraceToken())
            )
            AudioPlaybackStateStore.setNowPlaying(
                this@AudioPlayerActivity,
                chapterRouteId,
                title,
                bookRouteId.orEmpty(),
                currentCoverUrl,
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
        MediaShelfStore.updateAudioProgress(this, chapterRouteId, position, duration)
    }

    override fun onDestroy() {
        persistControllerProgress()
        progressHandler.removeCallbacks(progressTicker)
        sleepHandler.removeCallbacksAndMessages(null)
        coverAnimator?.cancel()
        coverAnimator = null
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
            resources.getColor(if (showingCatalog) R.color.media_text_secondary else R.color.media_audio_accent)
        )
        binding.audioPlayerTabCatalog.setTextColor(
            resources.getColor(if (showingCatalog) R.color.media_audio_accent else R.color.media_text_secondary)
        )
        binding.audioPlayerTabNowLine.visibility = if (showingCatalog) View.INVISIBLE else View.VISIBLE
        binding.audioPlayerTabCatalogLine.visibility = if (showingCatalog) View.VISIBLE else View.INVISIBLE
        binding.audioPlayerTabNow.setTypeface(null, android.graphics.Typeface.NORMAL)
        binding.audioPlayerTabCatalog.setTypeface(null, android.graphics.Typeface.NORMAL)
    }

    private fun openSiblingEpisode(offset: Int) {
        val target = episodes.getOrNull(currentEpisodeIndex + offset) ?: return
        start(this, target.routeId, target.title, forceStart = target.routeId != chapterRouteId, autoPlay = true)
    }

    private fun renderCover(coverUrl: String) {
        BookCoverLoader.load(
            listOfNotNull(coverUrl.takeIf { it.isNotBlank() }),
            binding.audioPlayerCover,
            R.drawable.ic_book_cover_placeholder,
            circle = true
        )
    }

    private fun updateCoverRotation(playing: Boolean) {
        coverAnimator = AudioCoverChrome.updateRotation(binding.audioPlayerCover, playing, coverAnimator)
    }

    private fun updateEpisodeButtons() {
        binding.audioPlayerPrevious.alpha = if (episodes.getOrNull(currentEpisodeIndex - 1) != null) 1f else 0.38f
        binding.audioPlayerNext.alpha = if (episodes.getOrNull(currentEpisodeIndex + 1) != null) 1f else 0.38f
    }

    private fun renderPlayPauseIcon(playing: Boolean) {
        binding.audioPlayerPlayPause.setImageResource(
            if (playing) R.drawable.ic_audio_legado_pause_24 else R.drawable.ic_audio_legado_play_24
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

    companion object {
        private const val SEEK_BAR_MAX = 1000
        private const val EXTRA_CHAPTER_ROUTE_ID = "chapter_route_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FORCE_START = "force_start"
        private const val EXTRA_AUTO_PLAY = "auto_play"

        fun createIntent(
            context: Context,
            chapterRouteId: String,
            title: String,
            forceStart: Boolean = false,
            autoPlay: Boolean = false
        ): Intent {
            return Intent(context, AudioPlayerActivity::class.java)
                .putExtra(EXTRA_CHAPTER_ROUTE_ID, chapterRouteId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FORCE_START, forceStart)
                .putExtra(EXTRA_AUTO_PLAY, autoPlay)
        }

        fun start(
            context: Context,
            chapterRouteId: String,
            title: String,
            forceStart: Boolean = false,
            autoPlay: Boolean = false
        ) {
            context.startActivity(
                createIntent(context, chapterRouteId, title, forceStart, autoPlay)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
