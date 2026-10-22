package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.ldp.reader.R
import com.ldp.reader.audio.AudioChapterOrder
import com.ldp.reader.databinding.ActivityMediaCatalogBinding
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.ui.adapter.MediaChapterAdapter
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.theme.EdgeToEdge
import kotlinx.coroutines.CancellationException
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
    private var pendingScrollChapterRouteId: String = ""
    private var pendingScrollOffset = 0

    override fun getViewBinding(): ActivityMediaCatalogBinding {
        return ActivityMediaCatalogBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.mediaCatalogToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_ink_back)
        toolbar?.navigationIcon?.setTint(getColor(R.color.ink_text_primary))
    }

    override fun initData(savedInstanceState: Bundle?) {
        super.initData(savedInstanceState)
        readIntent(intent)
        descending = savedInstanceState?.getBoolean(STATE_DESCENDING, false) ?: false
        if (savedInstanceState?.containsKey(STATE_SELECTED_INDEX) == true) {
            selectedIndex = savedInstanceState.getInt(STATE_SELECTED_INDEX, selectedIndex)
        }
        pendingScrollChapterRouteId = savedInstanceState
            ?.getString(STATE_SCROLL_CHAPTER_ROUTE_ID)
            .orEmpty()
        pendingScrollOffset = savedInstanceState?.getInt(STATE_SCROLL_OFFSET, 0) ?: 0
    }

    override fun initWidget() {
        super.initWidget()
        EdgeToEdge.apply(this)
        EdgeToEdge.padTop(binding.mediaCatalogToolbar)
        EdgeToEdge.padBottom(binding.mediaCatalogList, 0)
        binding.mediaCatalogList.layoutManager = LinearLayoutManager(this)
        binding.mediaCatalogList.adapter = chapterAdapter
        renderHeader()
        chapterAdapter.selectedIndex = selectedIndex
    }

    override fun initClick() {
        super.initClick()
        binding.mediaCatalogOrder.setOnClickListener {
            if (!binding.mediaCatalogOrder.isEnabled || chapters.isEmpty()) return@setOnClickListener
            descending = !descending
            renderChapters(scrollToSelection = true)
        }
        binding.mediaCatalogState.setOnClickListener {
            if (binding.mediaCatalogState.isClickable) {
                loadCatalog()
            }
        }
        chapterAdapter.setOnItemClickListener { _, pos ->
            val chapter = chapterAdapter.getItem(pos)
            selectedIndex = chapter.index
            chapterAdapter.selectedIndex = selectedIndex
            when (MediaRouteRegistry.kind(routeId)) {
                ReaderMediaKind.COMIC -> ComicReadActivity.start(this, chapter.routeId, chapter.title)
                ReaderMediaKind.AUDIO -> AudioPlayerActivity.startManualSelection(
                    this,
                    chapter.routeId,
                    chapter.title,
                    bookTitle = title
                )
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
        descending = false
        pendingScrollChapterRouteId = ""
        pendingScrollOffset = 0
        readIntent(intent)
        renderHeader()
        chapterAdapter.selectedIndex = selectedIndex
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        if (routeId.isBlank()) return
        MediaShelfStore.restoreForBook(this, routeId)
        if (refreshSelectionFromShelf()) {
            scrollToSelectedChapter()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_DESCENDING, descending)
        outState.putInt(STATE_SELECTED_INDEX, selectedIndex)
        val manager = binding.mediaCatalogList.layoutManager as? LinearLayoutManager
        val firstVisible = manager?.findFirstVisibleItemPosition() ?: -1
        val visibleChapter = chapterAdapter.items.getOrNull(firstVisible)
        val scrollChapterRouteId = visibleChapter?.routeId.orEmpty()
            .ifBlank { pendingScrollChapterRouteId }
        if (scrollChapterRouteId.isNotBlank()) {
            outState.putString(STATE_SCROLL_CHAPTER_ROUTE_ID, scrollChapterRouteId)
            val topOffset = visibleChapter
                ?.let {
                    manager?.findViewByPosition(firstVisible)?.top
                        ?.minus(binding.mediaCatalogList.paddingTop)
                }
                ?: pendingScrollOffset
            outState.putInt(STATE_SCROLL_OFFSET, topOffset)
        }
        super.onSaveInstanceState(outState)
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
        MediaShelfStore.restoreForBook(this, routeId)
        refreshSelectionFromShelf()
        renderHeader()
        chapterAdapter.selectedIndex = selectedIndex
        chapters = emptyList()
        chapterAdapter.refreshItems(emptyList())
        applyCatalogState(MediaCatalogStatePolicy.loading())
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val detail = MediaSourceRepository.detail(routeId)
                    val sourceChapters = MediaSourceRepository.chapters(routeId)
                    detail to sourceChapters
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (token != loadToken) return@launch
            if (result == null) {
                renderChapters()
                return@launch
            }
            val (detail, sourceChapters) = result
            if (title.isBlank()) {
                title = detail?.title.orEmpty()
                renderHeader()
            }
            chapters = when (MediaRouteRegistry.kind(routeId)) {
                ReaderMediaKind.AUDIO -> AudioChapterOrder.chronological(sourceChapters)
                ReaderMediaKind.COMIC -> ComicChapterOrder.resolve(sourceChapters, detail?.latest).chronological
                else -> sourceChapters
            }
            refreshSelectionFromShelf()
            renderChapters(scrollToSelection = true)
        }
    }

    private fun renderHeader() {
        val kind = MediaRouteRegistry.kind(routeId)
        val fallback = kind?.displayName ?: "目录"
        binding.mediaCatalogTitle.text = title.ifBlank { fallback }
        binding.mediaCatalogTabTitle.text = MediaCatalogLabels.title(kind)
    }

    private fun renderChapters(scrollToSelection: Boolean = false) {
        when (MediaRouteRegistry.kind(routeId)) {
            ReaderMediaKind.AUDIO,
            ReaderMediaKind.COMIC -> chapterAdapter.useChronologicalDisplayOrder(chapters)
            else -> chapterAdapter.useSourceIndexDisplayOrder()
        }
        val visible = if (descending) chapters.asReversed() else chapters
        chapterAdapter.refreshItems(visible)
        chapterAdapter.selectedIndex = selectedIndex
        binding.mediaCatalogOrder.text = if (descending) "正序" else "倒序"
        val unit = MediaCatalogLabels.unit(MediaRouteRegistry.kind(routeId))
        applyCatalogState(MediaCatalogStatePolicy.resolved(chapters.size, unit))
        if (scrollToSelection) {
            if (!restorePendingScrollPosition()) {
                scrollToSelectedChapter()
            }
        }
    }

    private fun applyCatalogState(state: MediaCatalogState) {
        binding.mediaCatalogState.text = state.message
        binding.mediaCatalogState.contentDescription = state.message
        binding.mediaCatalogState.isClickable = state.retryEnabled
        binding.mediaCatalogState.isFocusable = state.retryEnabled
        binding.mediaCatalogOrder.isEnabled = state.orderEnabled
        binding.mediaCatalogOrder.alpha = if (state.orderEnabled) 1f else 0.38f
    }

    private fun refreshSelectionFromShelf(): Boolean {
        val previousIndex = selectedIndex
        val shelfItem = MediaShelfStore.itemForBook(this, routeId)
        val comicShelfResolutionAvailable =
            MediaRouteRegistry.kind(routeId) == ReaderMediaKind.COMIC &&
            shelfItem != null &&
            chapters.isNotEmpty()
        val shelfIndex = if (comicShelfResolutionAvailable) {
            val comicShelfItem = checkNotNull(shelfItem)
            ComicChapterRouteResolver.resolve(
                chapters = chapters,
                savedRouteId = comicShelfItem.currentChapterRouteId,
                savedTitle = comicShelfItem.currentChapterTitle,
                savedSourceIndex = comicShelfItem.currentChapterIndex
            )?.index ?: -1
        } else {
            shelfItem?.currentChapterIndex ?: -1
        }
        selectedIndex = if (comicShelfResolutionAvailable) {
            shelfIndex
        } else {
            MediaCatalogStatePolicy.selectedIndex(previousIndex, shelfIndex)
        }
        chapterAdapter.selectedIndex = selectedIndex
        return selectedIndex != previousIndex
    }

    private fun scrollToSelectedChapter() {
        if (selectedIndex < 0 || chapterAdapter.itemCount <= 0) return
        val visibleIndex = chapterAdapter.items.indexOfFirst { it.index == selectedIndex }
        if (visibleIndex >= 0) {
            binding.mediaCatalogList.scrollToPosition(visibleIndex)
        }
    }

    private fun restorePendingScrollPosition(): Boolean {
        val chapterRouteId = pendingScrollChapterRouteId
        if (chapterRouteId.isBlank()) return false
        val visibleIndex = chapterAdapter.items.indexOfFirst { it.routeId == chapterRouteId }
        if (visibleIndex < 0) return false
        val manager = binding.mediaCatalogList.layoutManager as? LinearLayoutManager ?: return false
        manager.scrollToPositionWithOffset(visibleIndex, pendingScrollOffset)
        pendingScrollChapterRouteId = ""
        pendingScrollOffset = 0
        return true
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SELECTED_INDEX = "selected_index"
        private const val STATE_DESCENDING = "mediaCatalog.descending"
        private const val STATE_SELECTED_INDEX = "mediaCatalog.selectedIndex"
        private const val STATE_SCROLL_CHAPTER_ROUTE_ID = "mediaCatalog.scrollChapterRouteId"
        private const val STATE_SCROLL_OFFSET = "mediaCatalog.scrollOffset"

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

internal data class MediaCatalogState(
    val message: String,
    val orderEnabled: Boolean,
    val retryEnabled: Boolean
)

internal object MediaCatalogStatePolicy {
    fun loading(): MediaCatalogState {
        return MediaCatalogState(
            message = "加载中...",
            orderEnabled = false,
            retryEnabled = false
        )
    }

    fun resolved(itemCount: Int, unit: String): MediaCatalogState {
        if (itemCount <= 0) {
            return MediaCatalogState(
                message = "暂时没有可用目录，点击重试",
                orderEnabled = false,
                retryEnabled = true
            )
        }
        return MediaCatalogState(
            message = "共 $itemCount $unit",
            orderEnabled = true,
            retryEnabled = false
        )
    }

    fun selectedIndex(currentIndex: Int, shelfIndex: Int): Int {
        return shelfIndex.takeIf { it >= 0 } ?: currentIndex
    }
}
