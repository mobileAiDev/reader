package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityComicDetailBinding
import com.ldp.reader.media.MediaCatalogCompleteness
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaBookDetail
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.ui.adapter.MediaChapterAdapter
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

class ComicDetailActivity : BaseActivity<ActivityComicDetailBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chapterAdapter = MediaChapterAdapter()
    private lateinit var routeId: String
    private var firstChapter: MediaChapterItem? = null
    private var latestChapter: MediaChapterItem? = null
    private var chapters: List<MediaChapterItem> = emptyList()
    private var descending = false
    private var loadToken = 0

    override fun getViewBinding(): ActivityComicDetailBinding {
        return ActivityComicDetailBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.comicDetailToolbar

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
        binding.comicDetailChapters.layoutManager = GridLayoutManager(this, 3)
        binding.comicDetailChapters.adapter = chapterAdapter
        chapterAdapter.accentColorRes = R.color.media_comic_accent
        chapterAdapter.metaPrefix = "章节"
        binding.comicDetailAddShelf.isEnabled = false
        binding.comicDetailAddShelf.alpha = 0.56f
        binding.comicDetailStart.isEnabled = false
        binding.comicDetailStart.alpha = 0.56f
        binding.comicDetailLatest.isEnabled = false
        binding.comicDetailLatest.alpha = 0.56f
    }

    override fun initClick() {
        super.initClick()
        binding.comicDetailAddShelf.setOnClickListener { addToMediaShelf() }
        chapterAdapter.setOnItemClickListener { _, pos ->
            val chapter = chapterAdapter.getItem(pos)
            ComicReadActivity.start(this, chapter.routeId, chapter.title)
        }
        binding.comicDetailStart.setOnClickListener {
            firstChapter?.let { chapter -> ComicReadActivity.start(this, chapter.routeId, chapter.title) }
        }
        binding.comicDetailLatest.setOnClickListener {
            latestChapter?.let { chapter -> ComicReadActivity.start(this, chapter.routeId, chapter.title) }
        }
        binding.comicDetailOrder.setOnClickListener {
            descending = !descending
            renderChapters()
        }
        binding.comicDetailCatalogAll.setOnClickListener {
            MediaCatalogActivity.start(this, routeId, binding.comicDetailTitle.text?.toString().orEmpty())
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
        binding.comicDetailState.text = "加载中..."
        scope.launch {
            delay(DETAIL_LOAD_TIMEOUT_MS)
            if (token != loadToken) return@launch
            loadToken += 1
            chapters = emptyList()
            firstChapter = null
            latestChapter = null
            renderChapters()
            binding.comicDetailStart.isEnabled = false
            binding.comicDetailStart.alpha = 0.56f
            binding.comicDetailLatest.isEnabled = false
            binding.comicDetailLatest.alpha = 0.56f
            binding.comicDetailChapterHint.text = ""
            binding.comicDetailState.text = "目录解析失败（源响应超时）"
        }
        scope.launch {
            val detail = withContext(Dispatchers.IO) { MediaSourceRepository.detail(routeId) }
            if (token != loadToken) return@launch
            if (detail == null) {
                binding.comicDetailState.text = "详情加载失败"
                return@launch
            }
            renderDetail(detail)
            chapters = withContext(Dispatchers.IO) { MediaSourceRepository.chapters(routeId) }
            if (token != loadToken) return@launch
            loadToken += 1
            renderChapters()
            firstChapter = chapters.firstOrNull()
            latestChapter = chapters.lastOrNull()
            binding.comicDetailStart.isEnabled = firstChapter != null
            binding.comicDetailStart.alpha = if (firstChapter != null) 1f else 0.56f
            binding.comicDetailLatest.isEnabled = latestChapter != null
            binding.comicDetailLatest.alpha = if (latestChapter != null) 1f else 0.56f
            binding.comicDetailAddShelf.isEnabled = true
            binding.comicDetailAddShelf.alpha = 1f
            renderShelfButton()
            val expectedCount = MediaCatalogCompleteness.expectedCount(detail.latest, detail.intro)
            binding.comicDetailState.text = when {
                chapters.isNotEmpty() -> "已更新 ${chapters.size} 话"
                expectedCount > 0 -> "目录解析失败（简介显示约 ${expectedCount} 话）"
                else -> "暂无章节"
            }
            binding.comicDetailChapterHint.text = if (chapters.isEmpty()) "" else "全部 ${chapters.size} 话"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun renderDetail(detail: MediaBookDetail) {
        binding.comicDetailTitle.text = detail.title
        binding.comicDetailMeta.text = listOf(detail.author, detail.latest)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        binding.comicDetailIntro.text = detail.intro.ifBlank { "暂无简介" }
        BookCoverLoader.load(
            listOfNotNull(detail.coverUrl.takeIf { it.isNotBlank() }),
            binding.comicDetailCover,
            R.drawable.ic_book_cover_placeholder
        )
    }

    private fun renderChapters() {
        val visible = if (descending) chapters.asReversed() else chapters
        chapterAdapter.refreshItems(visible.take(DETAIL_PREVIEW_CHAPTERS))
        binding.comicDetailOrder.text = if (descending) "正序" else "倒序"
    }

    private fun addToMediaShelf() {
        val chapter = firstChapter
        val item = MediaShelfStore.addOrUpdate(
            context = this,
            kind = ReaderMediaKind.COMIC,
            bookRouteId = routeId,
            currentChapterRouteId = chapter?.routeId.orEmpty(),
            currentChapterTitle = chapter?.title.orEmpty(),
            currentChapterIndex = chapter?.index ?: -1
        )
        if (item == null) {
            ToastUtils.show("目录未准备好")
            return
        }
        renderShelfButton()
        ToastUtils.show("已加入书架")
    }

    private fun renderShelfButton() {
        binding.comicDetailAddShelf.text = if (MediaShelfStore.isAdded(this, routeId)) "已在书架" else "加入书架"
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val DETAIL_LOAD_TIMEOUT_MS = 25_000L
        private const val DETAIL_PREVIEW_CHAPTERS = 12

        fun start(context: Context, routeId: String) {
            context.startActivity(
                Intent(context, ComicDetailActivity::class.java)
                    .putExtra(EXTRA_ROUTE_ID, routeId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
