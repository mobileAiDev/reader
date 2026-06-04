package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityComicReadBinding
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.ui.adapter.ComicPageAdapter
import com.ldp.reader.ui.base.BaseActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ComicReadActivity : BaseActivity<ActivityComicReadBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pageAdapter = ComicPageAdapter()
    private lateinit var chapterRouteId: String
    private var bookRouteId: String? = null
    private var chapterIndex = -1
    private var chapters: List<MediaChapterItem> = emptyList()
    private var controlsVisible = false
    private var horizontalMode = false
    private var nightMode = false
    private var loadToken = 0
    private var openingSibling = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartedAtLeadingBoundary = false
    private var touchStartedAtTrailingBoundary = false

    override fun getViewBinding(): ActivityComicReadBinding {
        return ActivityComicReadBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        MediaUiChrome.prepareImmersiveReader(this)
        super.onCreate(savedInstanceState)
    }

    override fun toolbarView(): Toolbar = binding.comicReadToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        MediaUiChrome.darkReader(this)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        readIntent(intent)
    }

    override fun initWidget() {
        super.initWidget()
        binding.comicReadTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        applyPageOrientation()
        binding.comicReadPages.adapter = pageAdapter
        setControlsVisible(false)
    }

    override fun initClick() {
        super.initClick()
        binding.root.setOnClickListener { toggleControls() }
        binding.comicReadPages.setOnClickListener { toggleControls() }
        binding.comicReadPrevChapter.setOnClickListener { openSiblingChapter(-1) }
        binding.comicReadNextChapter.setOnClickListener { openSiblingChapter(1) }
        binding.comicReadCatalog.setOnClickListener {
            bookRouteId?.let { route ->
                MediaCatalogActivity.start(this, route, binding.comicReadTitle.text?.toString().orEmpty(), chapterIndex)
            }
        }
        binding.comicReadNight.setOnClickListener {
            nightMode = !nightMode
            binding.comicReadNight.isSelected = nightMode
            binding.comicReadPages.setBackgroundColor(resources.getColor(if (nightMode) R.color.black else R.color.media_reader_dark))
        }
        binding.comicReadEye.setOnClickListener {
            val visible = binding.comicReadEyeLayer.visibility != View.VISIBLE
            binding.comicReadEye.isSelected = visible
            binding.comicReadEyeLayer.visibility = if (visible) View.VISIBLE else View.GONE
        }
        binding.comicReadDirection.setOnClickListener {
            horizontalMode = !horizontalMode
            binding.comicReadDirection.isSelected = horizontalMode
            binding.comicReadDirection.text = if (horizontalMode) "左右翻页" else "上下翻页"
            applyPageOrientation()
        }
        binding.comicReadLandscape.setOnClickListener {
            val landscape = requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            binding.comicReadLandscape.isSelected = landscape
        }
        binding.comicReadBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress.coerceIn(5, 100) / 100f)
                window.attributes = window.attributes.apply { screenBrightness = value }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.comicReadPageSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val count = pageAdapter.itemCount
                if (count <= 0) return
                val target = ((seekBar?.progress ?: 0) * (count - 1) / SEEK_BAR_MAX).coerceIn(0, count - 1)
                binding.comicReadPages.scrollToPosition(target)
                updatePageState(target)
            }
        })
        binding.comicReadPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                updatePageState(manager.findFirstVisibleItemPosition().coerceAtLeast(0))
            }
        })
    }

    override fun processLogic() {
        super.processLogic()
        loadChapter()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        readIntent(intent)
        binding.comicReadTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        openingSibling = false
        loadChapter()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            MediaUiChrome.immersiveReader(this, controlsVisible)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeBoundarySwipe(event)
        return super.dispatchTouchEvent(event)
    }

    private fun readIntent(intent: Intent) {
        chapterRouteId = intent.getStringExtra(EXTRA_CHAPTER_ROUTE_ID).orEmpty()
        MediaShelfStore.restoreForChapter(this, chapterRouteId)
        bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId)
        chapterIndex = MediaRouteRegistry.chapter(chapterRouteId)?.index ?: -1
    }

    private fun loadChapter() {
        val token = ++loadToken
        binding.comicReadState.text = "加载中..."
        pageAdapter.refreshItems(emptyList())
        scope.launch {
            val bookRoute = bookRouteId
            if (bookRoute != null) {
                chapters = withContext(Dispatchers.IO) { MediaSourceRepository.chapters(bookRoute) }
            }
            val pages = withContext(Dispatchers.IO) { MediaSourceRepository.comicPages(chapterRouteId) }
            if (token != loadToken) return@launch
            pageAdapter.refreshItems(pages)
            updateChapterButtons()
            if (pages.isEmpty()) {
                binding.comicReadState.text = "图片加载失败"
                return@launch
            }
            val savedPage = MediaShelfStore.comicPageIndex(this@ComicReadActivity, chapterRouteId)
                .coerceIn(0, pages.size - 1)
            updatePageState(savedPage)
            binding.comicReadPages.post { binding.comicReadPages.scrollToPosition(savedPage) }
        }
    }

    override fun onDestroy() {
        saveCurrentPage()
        scope.cancel()
        super.onDestroy()
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val visibility = if (controlsVisible) View.VISIBLE else View.GONE
        binding.comicReadToolbar.visibility = visibility
        binding.comicReadBottomPanel.visibility = visibility
        MediaUiChrome.immersiveReader(this, controlsVisible)
    }

    private fun applyPageOrientation() {
        binding.comicReadPages.layoutManager = LinearLayoutManager(
            this,
            if (horizontalMode) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL,
            false
        )
    }

    private fun openSiblingChapter(offset: Int) {
        if (openingSibling) return
        val target = chapters.getOrNull(chapterIndex + offset) ?: return
        openingSibling = true
        start(this, target.routeId, target.title)
    }

    private fun observeBoundarySwipe(event: MotionEvent) {
        if (pageAdapter.itemCount <= 0) return
        val pages = binding.comicReadPages
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.rawX
                touchStartY = event.rawY
                openingSibling = false
                touchStartedAtLeadingBoundary = if (horizontalMode) {
                    !pages.canScrollHorizontally(-1)
                } else {
                    !pages.canScrollVertically(-1)
                }
                touchStartedAtTrailingBoundary = if (horizontalMode) {
                    !pages.canScrollHorizontally(1)
                } else {
                    !pages.canScrollVertically(1)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val deltaX = event.rawX - touchStartX
                val deltaY = event.rawY - touchStartY
                if (abs(deltaX) < READER_TAP_SLOP && abs(deltaY) < READER_TAP_SLOP) {
                    if (!controlsVisible || !isTouchInsideVisibleChrome(event.rawY)) {
                        toggleControls()
                    }
                    return
                }
                if (controlsVisible) return
                if (horizontalMode) {
                    when {
                        touchStartedAtTrailingBoundary &&
                            deltaX < -BOUNDARY_SWIPE_DISTANCE &&
                            !pages.canScrollHorizontally(1) -> openSiblingChapter(1)
                        touchStartedAtLeadingBoundary &&
                            deltaX > BOUNDARY_SWIPE_DISTANCE &&
                            !pages.canScrollHorizontally(-1) -> openSiblingChapter(-1)
                    }
                } else {
                    when {
                        touchStartedAtTrailingBoundary &&
                            deltaY < -BOUNDARY_SWIPE_DISTANCE &&
                            !pages.canScrollVertically(1) -> openSiblingChapter(1)
                        touchStartedAtLeadingBoundary &&
                            deltaY > BOUNDARY_SWIPE_DISTANCE &&
                            !pages.canScrollVertically(-1) -> openSiblingChapter(-1)
                    }
                }
            }
        }
    }

    private fun isTouchInsideVisibleChrome(rawY: Float): Boolean {
        if (!controlsVisible) return false
        val y = rawY.toInt()
        return y <= binding.comicReadToolbar.bottom || y >= binding.comicReadBottomPanel.top
    }

    private fun updateChapterButtons() {
        binding.comicReadPrevChapter.alpha = if (chapters.getOrNull(chapterIndex - 1) != null) 1f else 0.38f
        binding.comicReadNextChapter.alpha = if (chapters.getOrNull(chapterIndex + 1) != null) 1f else 0.38f
    }

    private fun updatePageState(position: Int) {
        val count = pageAdapter.itemCount
        if (count <= 0) {
            binding.comicReadState.text = "图片加载失败"
            binding.comicReadPageSeek.progress = 0
            return
        }
        val page = position.coerceIn(0, count - 1)
        binding.comicReadState.text = "${page + 1} / $count"
        binding.comicReadPageSeek.progress = if (count <= 1) 0 else page * SEEK_BAR_MAX / (count - 1)
        MediaShelfStore.updateComicProgress(this, chapterRouteId, page)
    }

    private fun saveCurrentPage() {
        val manager = binding.comicReadPages.layoutManager as? LinearLayoutManager ?: return
        val page = manager.findFirstVisibleItemPosition()
        if (page >= 0) {
            MediaShelfStore.updateComicProgress(this, chapterRouteId, page)
        }
    }

    companion object {
        private const val SEEK_BAR_MAX = 1000
        private const val BOUNDARY_SWIPE_DISTANCE = 96f
        private const val READER_TAP_SLOP = 24f
        private const val EXTRA_CHAPTER_ROUTE_ID = "chapter_route_id"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, chapterRouteId: String, title: String) {
            context.startActivity(
                Intent(context, ComicReadActivity::class.java)
                    .putExtra(EXTRA_CHAPTER_ROUTE_ID, chapterRouteId)
                    .putExtra(EXTRA_TITLE, title)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
