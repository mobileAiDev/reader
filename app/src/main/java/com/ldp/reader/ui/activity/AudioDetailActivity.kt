package com.ldp.reader.ui.activity

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.ldp.reader.R
import com.ldp.reader.audio.AudioPlaybackStateStore
import com.ldp.reader.databinding.ActivityAudioDetailBinding
import com.ldp.reader.media.MediaBookDetail
import com.ldp.reader.media.MediaCatalogCompleteness
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.ui.adapter.MediaChapterAdapter
import com.ldp.reader.ui.audio.AudioCoverChrome
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    }

    override fun initClick() {
        super.initClick()
        binding.audioDetailAddShelf.setOnClickListener { addToMediaShelf() }
        episodeAdapter.setOnItemClickListener { _, pos ->
            val episode = episodeAdapter.getItem(pos)
            AudioPlayerActivity.start(this, episode.routeId, episode.title, bookTitle = detailTitle(), autoPlay = true)
        }
        binding.audioDetailPlayFirst.setOnClickListener {
            firstEpisode?.let { episode -> AudioPlayerActivity.start(this, episode.routeId, episode.title, bookTitle = detailTitle(), autoPlay = true) }
        }
        binding.audioDetailPlayLatest.setOnClickListener {
            latestEpisode?.let { episode -> AudioPlayerActivity.start(this, episode.routeId, episode.title, bookTitle = detailTitle(), autoPlay = true) }
        }
        binding.audioDetailMiniPlayer.setOnClickListener {
            AudioPlaybackStateStore.current(this)?.let { AudioPlayerActivity.start(this, it.chapterRouteId, it.title, bookTitle = it.bookTitle.ifBlank { detailTitle() }) }
        }
        binding.audioDetailOrder.setOnClickListener {
            descending = !descending
            renderEpisodes()
        }
        binding.audioDetailCatalogAll.setOnClickListener {
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
        val token = ++loadToken
        MediaShelfStore.restoreForBook(this, routeId)
        binding.audioDetailState.text = "加载中..."
        scope.launch {
            delay(DETAIL_LOAD_TIMEOUT_MS)
            if (token != loadToken) return@launch
            if (episodes.isEmpty()) {
                binding.audioDetailState.text = "目录解析中，正在尝试更多源..."
            }
        }
        scope.launch {
            val detail = withContext(Dispatchers.IO) { MediaSourceRepository.detail(routeId) }
            if (token != loadToken) return@launch
            if (detail == null) {
                binding.audioDetailState.text = "详情加载失败"
                return@launch
            }
            renderDetail(detail)
            episodes = withContext(Dispatchers.IO) { MediaSourceRepository.chapters(routeId) }
            if (token != loadToken) return@launch
            loadToken += 1
            renderEpisodes()
            firstEpisode = episodes.firstOrNull()
            latestEpisode = episodes.lastOrNull()
            binding.audioDetailPlayFirst.isEnabled = firstEpisode != null
            binding.audioDetailPlayFirst.alpha = if (firstEpisode != null) 1f else 0.56f
            binding.audioDetailPlayLatest.isEnabled = latestEpisode != null
            binding.audioDetailPlayLatest.alpha = if (latestEpisode != null) 1f else 0.56f
            binding.audioDetailAddShelf.isEnabled = true
            binding.audioDetailAddShelf.alpha = 1f
            renderShelfButton()
            val expectedCount = MediaCatalogCompleteness.expectedCount(detail.latest, detail.intro)
            binding.audioDetailState.text = when {
                episodes.isNotEmpty() -> "已更新 ${episodes.size} 集"
                expectedCount > 0 -> "目录解析失败（简介显示约 ${expectedCount} 集）"
                else -> "暂无剧集"
            }
            binding.audioDetailEpisodeHint.text = if (episodes.isEmpty()) "" else "全部 ${episodes.size} 集"
        }
    }

    override fun onResume() {
        super.onResume()
        renderMiniPlayer()
    }

    override fun onDestroy() {
        miniCoverAnimator?.cancel()
        miniCoverAnimator = null
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
        BookCoverLoader.load(
            listOfNotNull(nowPlaying?.coverUrl?.takeIf { it.isNotBlank() }),
            binding.audioDetailMiniCover,
            R.drawable.ic_book_cover_placeholder,
            circle = true
        )
    }

    private fun renderEpisodes() {
        val visible = if (descending) episodes.asReversed() else episodes
        episodeAdapter.refreshItems(visible.take(DETAIL_PREVIEW_EPISODES))
        binding.audioDetailOrder.text = if (descending) "正序" else "倒序"
    }

    private fun addToMediaShelf() {
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
            ToastUtils.show("目录未准备好")
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
        private const val DETAIL_LOAD_TIMEOUT_MS = 25_000L
        private const val DETAIL_PREVIEW_EPISODES = 12

        fun start(context: Context, routeId: String) {
            context.startActivity(
                Intent(context, AudioDetailActivity::class.java)
                    .putExtra(EXTRA_ROUTE_ID, routeId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
