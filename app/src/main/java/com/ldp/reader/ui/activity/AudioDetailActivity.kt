package com.ldp.reader.ui.activity

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.ldp.reader.R
import com.ldp.reader.audio.AudioChapterOrder
import com.ldp.reader.audio.AudioDetailShelfAction
import com.ldp.reader.audio.AudioMiniPlayerAction
import com.ldp.reader.audio.AudioMiniPlayerCommand
import com.ldp.reader.audio.AudioPlaybackService
import com.ldp.reader.audio.AudioPlaybackStateStore
import com.ldp.reader.databinding.ActivityAudioDetailBinding
import com.ldp.reader.media.MediaDetailLoadProgress
import com.ldp.reader.media.MediaBookDetail
import com.ldp.reader.media.MediaCatalogCompleteness
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.ui.adapter.MediaChapterAdapter
import com.ldp.reader.ui.audio.AudioCoverChrome
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AudioDetailActivity : BaseActivity<ActivityAudioDetailBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val episodeAdapter = MediaChapterAdapter()
    private lateinit var routeId: String
    private var firstEpisode: MediaChapterItem? = null
    private var latestEpisode: MediaChapterItem? = null
    private var episodes: List<MediaChapterItem> = emptyList()
    private var descending = false
    private var loadToken = 0
    private var miniCoverAnimator: ObjectAnimator? = null
    private var detailLoadStartedAt = 0L
    private var lastMiniCoverUrl = ""
    private var detailLoadJob: Job? = null
    private var detailPromptJob: Job? = null
    private val miniPlayerHandler = Handler(Looper.getMainLooper())
    private val miniPlayerTicker = object : Runnable {
        override fun run() {
            renderMiniPlayer()
            miniPlayerHandler.postDelayed(this, MINI_PLAYER_REFRESH_MS)
        }
    }

    override fun getViewBinding(): ActivityAudioDetailBinding {
        return ActivityAudioDetailBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.audioDetailToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        MediaUiChrome.light(this)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty()
    }

    override fun initWidget() {
        super.initWidget()
        binding.audioDetailEpisodes.layoutManager = LinearLayoutManager(this)
        binding.audioDetailEpisodes.adapter = episodeAdapter
        AudioCoverChrome.configureCircularCover(binding.audioDetailMiniCover)
        episodeAdapter.accentColorRes = R.color.media_audio_accent
        episodeAdapter.metaPrefix = "剧集"
        binding.audioDetailAddShelf.isEnabled = false
        binding.audioDetailAddShelf.alpha = 0.56f
        binding.audioDetailPlayFirst.isEnabled = false
        binding.audioDetailPlayFirst.alpha = 0.56f
        binding.audioDetailPlayLatest.isEnabled = false
        binding.audioDetailPlayLatest.alpha = 0.56f
        binding.audioDetailOrder.isEnabled = false
        binding.audioDetailOrder.alpha = 0.56f
        binding.audioDetailCatalogAll.isEnabled = false
        binding.audioDetailCatalogAll.alpha = 0.56f
    }

    override fun initClick() {
        super.initClick()
        binding.audioDetailAddShelf.setOnClickListener { addToMediaShelf() }
        episodeAdapter.setOnItemClickListener { _, pos ->
            val episode = episodeAdapter.getItem(pos)
            AudioPlayerActivity.startManualSelection(
                this,
                episode.routeId,
                episode.title,
                bookTitle = detailTitle()
            )
        }
        binding.audioDetailPlayFirst.setOnClickListener {
            firstEpisode?.let { episode ->
                AudioPlayerActivity.startManualSelection(
                    this,
                    episode.routeId,
                    episode.title,
                    bookTitle = detailTitle()
                )
            }
        }
        binding.audioDetailPlayLatest.setOnClickListener {
            latestEpisode?.let { episode ->
                AudioPlayerActivity.startManualSelection(
                    this,
                    episode.routeId,
                    episode.title,
                    bookTitle = detailTitle()
                )
            }
        }
        binding.audioDetailMiniPlayer.setOnClickListener {
            AudioPlaybackStateStore.current(this)?.let { nowPlaying ->
                AudioPlayerActivity.openCurrent(
                    this,
                    nowPlaying.chapterRouteId,
                    nowPlaying.title,
                    bookTitle = nowPlaying.bookTitle.ifBlank { detailTitle() }
                )
            }
        }
        binding.audioDetailMiniAction.setOnClickListener { handleMiniPlayerAction() }
        binding.audioDetailLoadingRetry.setOnClickListener { loadDetail() }
        binding.audioDetailOrder.setOnClickListener {
            descending = !descending
            renderEpisodes()
        }
        binding.audioDetailCatalogAll.setOnClickListener {
            if (episodes.isEmpty()) return@setOnClickListener
            MediaCatalogActivity.start(this, routeId, binding.audioDetailTitle.text?.toString().orEmpty())
        }
    }

    override fun processLogic() {
        super.processLogic()
        loadDetail()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty()
        loadDetail()
    }

    private fun loadDetail() {
        detailLoadJob?.cancel()
        detailPromptJob?.cancel()
        val token = ++loadToken
        detailLoadStartedAt = System.currentTimeMillis()
        MediaShelfStore.restoreForBook(this, routeId)
        episodes = emptyList()
        firstEpisode = null
        latestEpisode = null
        renderEpisodes()
        setActionButtonsEnabled(false)
        binding.audioDetailEpisodeHint.text = ""
        renderLoadingStep(MediaDetailLoadProgress.Step.STARTED)
        detailPromptJob = scope.launch {
            delay(DETAIL_PROGRESS_PROMPT_MS)
            if (token != loadToken) return@launch
            if (episodes.isEmpty()) {
                renderLoadingStep(MediaDetailLoadProgress.Step.CATALOG)
            }
        }
        detailLoadJob = scope.launch {
            var detailResolved = false
            val result = withTimeoutOrNull(DETAIL_LOAD_TIMEOUT_MS) {
                renderLoadingStep(MediaDetailLoadProgress.Step.DETAIL)
                val detail = when (
                    val detailOutcome = runMediaDetailCallSafely {
                        MediaSourceRepository.detail(routeId)
                    }
                ) {
                    is MediaDetailCallOutcome.Failed -> return@withTimeoutOrNull null
                    is MediaDetailCallOutcome.Succeeded -> detailOutcome.value
                        ?: return@withTimeoutOrNull null
                }
                detailResolved = true
                if (token != loadToken) return@withTimeoutOrNull null
                renderDetail(detail)
                renderLoadingStep(MediaDetailLoadProgress.Step.CATALOG)
                val loadedEpisodes = when (
                    val catalogOutcome = runMediaDetailCallSafely {
                        MediaSourceRepository.chapters(routeId)
                    }
                ) {
                    is MediaDetailCallOutcome.Failed -> return@withTimeoutOrNull null
                    is MediaDetailCallOutcome.Succeeded -> catalogOutcome.value
                }
                AudioDetailLoadResult(detail, loadedEpisodes)
            }
            if (token != loadToken) return@launch
            if (result == null) {
                renderLoadingFailure(
                    if (detailResolved) MediaDetailFailureStage.CATALOG else MediaDetailFailureStage.DETAIL,
                    if (detailResolved) "catalog_unavailable" else "detail_unavailable"
                )
                return@launch
            }
            val detail = result.detail
            episodes = AudioChapterOrder.chronological(result.episodes)
            loadToken += 1
            renderEpisodes()
            firstEpisode = episodes.firstOrNull()
            latestEpisode = episodes.lastOrNull()
            setActionButtonsEnabled(true)
            renderShelfButton()
            val expectedCount = MediaCatalogCompleteness.expectedCount(detail.latest, detail.intro)
            binding.audioDetailState.text = when {
                episodes.isNotEmpty() -> "共 ${episodes.size} 集"
                else -> MediaDetailLoadProgress.Step.FAILED.label
            }
            binding.audioDetailEpisodeHint.text = if (episodes.isEmpty()) "" else "共 ${episodes.size} 集"
            if (episodes.isEmpty()) {
                renderLoadingFailure(
                    MediaDetailFailureStage.CATALOG,
                    if (expectedCount > 0) "chapters_empty_expected_$expectedCount" else "chapters_empty"
                )
            } else {
                renderLoadingStep(MediaDetailLoadProgress.Step.READY)
                binding.audioDetailLoadingOverlay.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MediaShelfStore.restoreForBook(this, routeId)
        renderShelfButton()
        miniPlayerHandler.removeCallbacks(miniPlayerTicker)
        miniPlayerHandler.post(miniPlayerTicker)
    }

    override fun onPause() {
        miniPlayerHandler.removeCallbacks(miniPlayerTicker)
        super.onPause()
    }

    override fun onDestroy() {
        detailLoadJob?.cancel()
        detailPromptJob?.cancel()
        detailLoadJob = null
        detailPromptJob = null
        miniCoverAnimator?.cancel()
        miniCoverAnimator = null
        miniPlayerHandler.removeCallbacks(miniPlayerTicker)
        scope.cancel()
        super.onDestroy()
    }

    private fun renderDetail(detail: MediaBookDetail) {
        binding.audioDetailTitle.text = detail.title
        binding.audioDetailMeta.text = listOf(detail.author, detail.latest)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        binding.audioDetailIntro.text = detail.intro.ifBlank { "暂无简介" }
        BookCoverLoader.load(
            listOfNotNull(detail.coverUrl.takeIf { it.isNotBlank() }),
            binding.audioDetailCover,
            R.drawable.ic_book_cover_placeholder
        )
    }

    private fun detailTitle(): String {
        return binding.audioDetailTitle.text?.toString().orEmpty()
    }

    private fun renderMiniPlayer() {
        val nowPlaying = AudioPlaybackStateStore.current(this)
        binding.audioDetailMiniPlayer.visibility = if (nowPlaying == null) View.GONE else View.VISIBLE
        binding.audioDetailMiniTitle.text = nowPlaying?.title.orEmpty()
        val playing = nowPlaying?.isPlaying == true
        binding.audioDetailMiniAction.setImageResource(
            if (playing) R.drawable.ic_audio_legado_pause_24 else R.drawable.ic_audio_legado_play_24
        )
        binding.audioDetailMiniAction.contentDescription = if (playing) "暂停" else "播放"
        miniCoverAnimator = AudioCoverChrome.updateRotation(
            binding.audioDetailMiniCover,
            playing,
            miniCoverAnimator
        )
        val coverUrl = nowPlaying?.coverUrl.orEmpty()
        if (coverUrl != lastMiniCoverUrl) {
            lastMiniCoverUrl = coverUrl
            BookCoverLoader.load(
                listOfNotNull(coverUrl.takeIf { it.isNotBlank() }),
                binding.audioDetailMiniCover,
                R.drawable.ic_book_cover_placeholder,
                circle = true
            )
        }
    }

    private fun handleMiniPlayerAction() {
        val nowPlaying = AudioPlaybackStateStore.current(this)
        when (AudioMiniPlayerAction.playButtonCommand(nowPlaying)) {
            AudioMiniPlayerCommand.TOGGLE_SERVICE -> {
                startService(
                    Intent(this, AudioPlaybackService::class.java)
                        .setAction(AudioPlaybackService.ACTION_TOGGLE)
                )
            }
            AudioMiniPlayerCommand.RESUME_SERVICE -> {
                nowPlaying ?: return
                startService(
                    Intent(this, AudioPlaybackService::class.java)
                        .putExtra(AudioPlaybackService.EXTRA_URL, nowPlaying.audioUrl)
                        .putExtra(AudioPlaybackService.EXTRA_TITLE, nowPlaying.title)
                        .putExtra(AudioPlaybackService.EXTRA_CHAPTER_ROUTE_ID, nowPlaying.chapterRouteId)
                        .putExtra(AudioPlaybackService.EXTRA_BOOK_ROUTE_ID, nowPlaying.bookRouteId)
                        .putExtra(AudioPlaybackService.EXTRA_BOOK_TITLE, nowPlaying.bookTitle)
                        .putExtra(AudioPlaybackService.EXTRA_COVER_URL, nowPlaying.coverUrl)
                        .putExtra(
                            AudioPlaybackService.EXTRA_HEADERS_JSON,
                            AudioPlaybackService.encodeHeaders(nowPlaying.headers)
                        )
                        .putExtra(AudioPlaybackService.EXTRA_FORCE_START, false)
                        .putExtra(AudioPlaybackService.EXTRA_AUTO_PLAY, true)
                )
            }
            AudioMiniPlayerCommand.REPLAY_SERVICE -> {
                nowPlaying ?: return
                startService(
                    Intent(this, AudioPlaybackService::class.java)
                        .putExtra(AudioPlaybackService.EXTRA_URL, nowPlaying.audioUrl)
                        .putExtra(AudioPlaybackService.EXTRA_TITLE, nowPlaying.title)
                        .putExtra(AudioPlaybackService.EXTRA_CHAPTER_ROUTE_ID, nowPlaying.chapterRouteId)
                        .putExtra(AudioPlaybackService.EXTRA_BOOK_ROUTE_ID, nowPlaying.bookRouteId)
                        .putExtra(AudioPlaybackService.EXTRA_BOOK_TITLE, nowPlaying.bookTitle)
                        .putExtra(AudioPlaybackService.EXTRA_COVER_URL, nowPlaying.coverUrl)
                        .putExtra(
                            AudioPlaybackService.EXTRA_HEADERS_JSON,
                            AudioPlaybackService.encodeHeaders(nowPlaying.headers)
                        )
                        .putExtra(AudioPlaybackService.EXTRA_FORCE_START, true)
                        .putExtra(AudioPlaybackService.EXTRA_AUTO_PLAY, true)
                )
            }
            AudioMiniPlayerCommand.OPEN_PLAYER -> {
                nowPlaying ?: return
                AudioPlayerActivity.openCurrent(
                    this,
                    nowPlaying.chapterRouteId,
                    nowPlaying.title,
                    bookTitle = nowPlaying.bookTitle.ifBlank { detailTitle() }
                )
            }
            null -> Unit
        }
        miniPlayerHandler.postDelayed({ renderMiniPlayer() }, MINI_ACTION_REFRESH_DELAY_MS)
    }

    private fun renderEpisodes() {
        episodeAdapter.useChronologicalDisplayOrder(episodes)
        val visible = if (descending) episodes.asReversed() else episodes
        episodeAdapter.refreshItems(visible.take(DETAIL_PREVIEW_EPISODES))
        binding.audioDetailOrder.text = if (descending) "正序" else "倒序"
        val hasEpisodes = episodes.isNotEmpty()
        binding.audioDetailOrder.isEnabled = hasEpisodes
        binding.audioDetailOrder.alpha = if (hasEpisodes) 1f else 0.56f
        binding.audioDetailCatalogAll.isEnabled = hasEpisodes
        binding.audioDetailCatalogAll.alpha = if (hasEpisodes) 1f else 0.56f
    }

    private fun setActionButtonsEnabled(hasLoaded: Boolean) {
        val hasEpisodes = episodes.isNotEmpty()
        binding.audioDetailAddShelf.isEnabled = hasLoaded && hasEpisodes
        binding.audioDetailAddShelf.alpha = if (hasLoaded && hasEpisodes) 1f else 0.56f
        binding.audioDetailPlayFirst.isEnabled = hasLoaded && firstEpisode != null
        binding.audioDetailPlayFirst.alpha = if (hasLoaded && firstEpisode != null) 1f else 0.56f
        binding.audioDetailPlayLatest.isEnabled = hasLoaded && latestEpisode != null
        binding.audioDetailPlayLatest.alpha = if (hasLoaded && latestEpisode != null) 1f else 0.56f
    }

    private fun renderLoadingStep(step: MediaDetailLoadProgress.Step, text: String = step.label) {
        binding.audioDetailLoadingOverlay.visibility = View.VISIBLE
        binding.audioDetailLoadingTitle.text = "正在加载"
        binding.audioDetailLoadingStep.text = text
        binding.audioDetailLoadingProgress.visibility = View.VISIBLE
        binding.audioDetailLoadingProgress.isIndeterminate = true
        binding.audioDetailLoadingRetry.visibility = View.GONE
        if (step != MediaDetailLoadProgress.Step.READY) {
            binding.audioDetailState.text = text
        }
        traceDetailLoad("step", text, "")
    }

    private fun renderLoadingFailure(stage: MediaDetailFailureStage, reason: String) {
        val text = MediaDetailFailureCopy.messageFor(stage)
        loadToken += 1
        binding.audioDetailLoadingOverlay.visibility = View.VISIBLE
        binding.audioDetailLoadingTitle.text = "加载失败"
        binding.audioDetailLoadingStep.text = text
        binding.audioDetailLoadingProgress.visibility = View.GONE
        binding.audioDetailLoadingRetry.visibility = View.VISIBLE
        binding.audioDetailState.text = text
        traceDetailLoad("failed", text, reason)
    }

    private fun traceDetailLoad(stage: String, text: String, reason: String) {
        AiBridgeTrace.state(
            "media_audio_detail_loading",
            routeId,
            AiBridgeTrace.fields(
                "stage" to stage,
                "text" to text,
                "reason" to reason,
                "episodes" to episodes.size,
                "elapsedMs" to (System.currentTimeMillis() - detailLoadStartedAt).coerceAtLeast(0L)
            )
        )
    }

    private fun addToMediaShelf() {
        when (AudioDetailShelfAction.decide(MediaShelfStore.isAdded(this, routeId))) {
            AudioDetailShelfAction.KEEP_EXISTING -> {
                renderShelfButton()
                ToastUtils.show("已在书架")
                return
            }
            AudioDetailShelfAction.ADD_INITIAL -> Unit
        }
        val nowPlaying = AudioPlaybackStateStore.current(this)
        val currentEpisode = nowPlaying?.takeIf { it.bookRouteId == routeId }
        val fallbackEpisode = firstEpisode
        val item = MediaShelfStore.addOrUpdate(
            context = this,
            kind = ReaderMediaKind.AUDIO,
            bookRouteId = routeId,
            currentChapterRouteId = currentEpisode?.chapterRouteId ?: fallbackEpisode?.routeId.orEmpty(),
            currentChapterTitle = currentEpisode?.title ?: fallbackEpisode?.title.orEmpty(),
            currentChapterIndex = currentEpisode?.let { MediaRouteRegistry.chapter(it.chapterRouteId)?.index }
                ?: fallbackEpisode?.index
                ?: -1
        )
        if (item == null) {
            ToastUtils.show("作品信息还在加载，请稍后再试")
            return
        }
        renderShelfButton()
        ToastUtils.show("已加入书架")
    }

    private fun renderShelfButton() {
        binding.audioDetailAddShelf.text = if (MediaShelfStore.isAdded(this, routeId)) "已在书架" else "加入书架"
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val DETAIL_LOAD_TIMEOUT_MS = 45_000L
        private const val DETAIL_PROGRESS_PROMPT_MS = 10_000L
        private const val DETAIL_PREVIEW_EPISODES = 12
        private const val MINI_PLAYER_REFRESH_MS = 1_000L
        private const val MINI_ACTION_REFRESH_DELAY_MS = 250L

        fun start(context: Context, routeId: String) {
            context.startActivity(
                Intent(context, AudioDetailActivity::class.java)
                    .putExtra(EXTRA_ROUTE_ID, routeId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private data class AudioDetailLoadResult(
        val detail: MediaBookDetail,
        val episodes: List<MediaChapterItem>
    )
}
