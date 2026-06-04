package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityMediaCatalogBinding
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.ui.adapter.MediaChapterAdapter
import com.ldp.reader.ui.base.BaseActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaCatalogActivity : BaseActivity<ActivityMediaCatalogBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chapterAdapter = MediaChapterAdapter()
    private var routeId: String = ""
    private var title: String = ""
    private var selectedIndex: Int = -1
    private var chapters: List<MediaChapterItem> = emptyList()
    private var descending = false
    private var loadToken = 0

    override fun getViewBinding(): ActivityMediaCatalogBinding {
        return ActivityMediaCatalogBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.mediaCatalogToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        MediaUiChrome.light(this)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        readIntent(intent)
    }

    override fun initWidget() {
        super.initWidget()
        binding.mediaCatalogList.layoutManager = LinearLayoutManager(this)
        binding.mediaCatalogList.adapter = chapterAdapter
        renderHeader()
        renderAdapterChrome()
    }

    override fun initClick() {
        super.initClick()
        binding.mediaCatalogOrder.setOnClickListener {
            descending = !descending
            renderChapters()
        }
        chapterAdapter.setOnItemClickListener { _, pos ->
            val chapter = chapterAdapter.getItem(pos)
            when (MediaRouteRegistry.kind(routeId)) {
                ReaderMediaKind.COMIC -> ComicReadActivity.start(this, chapter.routeId, chapter.title)
                ReaderMediaKind.AUDIO -> AudioPlayerActivity.start(this, chapter.routeId, chapter.title, autoPlay = true)
                else -> Unit
            }
        }
    }

    override fun processLogic() {
        super.processLogic()
        loadCatalog()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        readIntent(intent)
        renderHeader()
        renderAdapterChrome()
        loadCatalog()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun readIntent(intent: Intent) {
        routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        selectedIndex = intent.getIntExtra(EXTRA_SELECTED_INDEX, -1)
    }

    private fun loadCatalog() {
        val token = ++loadToken
        binding.mediaCatalogState.text = "加载中..."
        chapterAdapter.refreshItems(emptyList())
        scope.launch {
            val detail = withContext(Dispatchers.IO) { MediaSourceRepository.detail(routeId) }
            if (token != loadToken) return@launch
            if (title.isBlank()) {
                title = detail?.title.orEmpty()
                renderHeader()
            }
            chapters = withContext(Dispatchers.IO) { MediaSourceRepository.chapters(routeId) }
            if (token != loadToken) return@launch
            renderChapters()
            if (chapters.isNotEmpty() && selectedIndex >= 0) {
                val visibleIndex = chapterAdapter.items.indexOfFirst { it.index == selectedIndex }
                if (visibleIndex >= 0) {
                    binding.mediaCatalogList.scrollToPosition(visibleIndex)
                }
            }
        }
    }

    private fun renderHeader() {
        val kind = MediaRouteRegistry.kind(routeId)
        val fallback = kind?.displayName ?: "目录"
        binding.mediaCatalogTitle.text = title.ifBlank { fallback }
        binding.mediaCatalogTabTitle.text = if (kind == ReaderMediaKind.AUDIO) "剧集目录" else "书籍目录"
    }

    private fun renderAdapterChrome() {
        when (MediaRouteRegistry.kind(routeId)) {
            ReaderMediaKind.AUDIO -> {
                chapterAdapter.accentColorRes = R.color.media_audio_accent
                chapterAdapter.metaPrefix = "剧集"
            }
            else -> {
                chapterAdapter.accentColorRes = R.color.media_comic_accent
                chapterAdapter.metaPrefix = "章节"
            }
        }
        chapterAdapter.selectedIndex = selectedIndex
    }

    private fun renderChapters() {
        val visible = if (descending) chapters.asReversed() else chapters
        chapterAdapter.refreshItems(visible)
        binding.mediaCatalogOrder.text = if (descending) "正序" else "倒序"
        val unit = if (MediaRouteRegistry.kind(routeId) == ReaderMediaKind.AUDIO) "集" else "话"
        binding.mediaCatalogState.text = if (chapters.isEmpty()) "暂无目录" else "全部 ${chapters.size} $unit"
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SELECTED_INDEX = "selected_index"

        fun start(context: Context, routeId: String, title: String = "", selectedIndex: Int = -1) {
            context.startActivity(
                Intent(context, MediaCatalogActivity::class.java)
                    .putExtra(EXTRA_ROUTE_ID, routeId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_SELECTED_INDEX, selectedIndex)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
