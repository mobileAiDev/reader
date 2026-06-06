package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
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
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.ui.adapter.ComicPageAdapter
import com.ldp.reader.ui.base.BaseActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    private var touchStartVisiblePage = 0
    private var touchStartedAtLeadingBoundary = false
    private var touchStartedAtTrailingBoundary = false
    private var currentVisiblePage = 0
    private var lastPageTrace = ""
    private var resetPageOnOpen = false
    private var chapterPageListResolved = false
    private var progressSaveJob: Job? = null
    private var lastSavedProgressRouteId = ""
    private var lastSavedProgressPage = -1
    private val loadedPagePositions = HashSet<Int>()
    private val failedPagePositions = HashSet<Int>()

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
        BookContentProviderRouter.stopLowPriorityV8Maintenance("comic-reader")
        readIntent(intent)
    }

    override fun initWidget() {
        super.initWidget()
        binding.comicReadTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        applyPageOrientation()
        binding.comicReadPages.adapter = pageAdapter
        pageAdapter.onPageLoadResult = pageLoad@{ position, request, success, detail ->
            if (!isCurrentPageRequest(position, request)) {
                traceComicImageLoadIgnored(position, request, success, detail)
                return@pageLoad
            }
            if (success) {
                loadedPagePositions.add(position)
                failedPagePositions.remove(position)
            } else {
                failedPagePositions.add(position)
                loadedPagePositions.remove(position)
            }
            traceComicImageLoad(position, request, success, detail)
            traceComicPageState(currentVisiblePage)
        }
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
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updatePageState(dominantVisiblePosition(recyclerView))
                }
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
        resetPageOnOpen = intent.getBooleanExtra(EXTRA_RESET_PAGE, false)
    }

    private fun loadChapter() {
        val token = ++loadToken
        binding.comicReadState.text = "加载中..."
        AiBridgeTrace.event(
            "media_comic_chapter_load_started",
            chapterRouteId,
            AiBridgeTrace.fields("title" to binding.comicReadTitle.text, "chapterIndex" to chapterIndex, "bookRoute" to bookRouteId.orEmpty())
        )
        currentVisiblePage = 0
        lastPageTrace = ""
        loadedPagePositions.clear()
        failedPagePositions.clear()
        chapterPageListResolved = false
        pageAdapter.refreshItems(emptyList())
        scope.launch {
            val bookRoute = bookRouteId
            if (bookRoute != null) {
                chapters = withContext(Dispatchers.IO) { MediaSourceRepository.chapters(bookRoute) }
            }
            val pages = withContext(Dispatchers.IO) { MediaSourceRepository.comicPages(chapterRouteId) }
            if (token != loadToken) return@launch
            traceComicPagesResolved(pages)
            chapterPageListResolved = true
            pageAdapter.refreshItems(pages)
            updateChapterButtons()
            if (pages.isEmpty()) {
                binding.comicReadState.text = "图片加载失败"
                traceComicChapterEmpty()
                return@launch
            }
            MediaShelfStore.addOrUpdateForChapter(
                this@ComicReadActivity,
                ReaderMediaKind.COMIC,
                chapterRouteId,
                binding.comicReadTitle.text?.toString().orEmpty()
            )
            val savedPage = if (resetPageOnOpen) {
                0
            } else {
                MediaShelfStore.comicPageIndex(this@ComicReadActivity, chapterRouteId)
            }.coerceIn(0, pages.size - 1)
            resetPageOnOpen = false
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
        start(this, target.routeId, target.title, resetPage = true)
    }

    private fun observeBoundarySwipe(event: MotionEvent) {
        if (pageAdapter.itemCount <= 0) return
        val pages = binding.comicReadPages
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.rawX
                touchStartY = event.rawY
                touchStartVisiblePage = currentVisiblePage
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
                val visiblePage = dominantVisiblePosition(pages)
                if (visiblePage != currentVisiblePage) {
                    updatePageState(visiblePage)
                }
                val canForward = if (horizontalMode) {
                    pages.canScrollHorizontally(1)
                } else {
                    pages.canScrollVertically(1)
                }
                val canBackward = if (horizontalMode) {
                    pages.canScrollHorizontally(-1)
                } else {
                    pages.canScrollVertically(-1)
                }
                val target = ComicBoundaryNavigation.target(
                    horizontal = horizontalMode,
                    controlsVisible = controlsVisible,
                    itemCount = pageAdapter.itemCount,
                    deltaX = deltaX,
                    deltaY = deltaY,
                    canScrollForward = canForward,
                    canScrollBackward = canBackward,
                    firstPageVisible = isFirstPageVisible(),
                    lastPageVisible = isLastPageVisible(),
                    distance = BOUNDARY_SWIPE_DISTANCE
                )
                if (target != null) {
                    traceBoundarySwipe(target.traceName, deltaX, deltaY, canForward, canBackward)
                    openSiblingChapter(target.offset)
                    return
                }
                if (handleReleaseScroll(deltaX, deltaY, canForward, canBackward, visiblePage)) {
                    return
                }
                val blocked = ComicBoundaryNavigation.blockedTarget(
                    horizontal = horizontalMode,
                    controlsVisible = controlsVisible,
                    itemCount = pageAdapter.itemCount,
                    deltaX = deltaX,
                    deltaY = deltaY,
                    canScrollForward = canForward,
                    canScrollBackward = canBackward,
                    firstPageVisible = isFirstPageVisible(),
                    lastPageVisible = isLastPageVisible(),
                    distance = BOUNDARY_SWIPE_DISTANCE
                )
                if (blocked != null) {
                    traceBoundaryBlocked(blocked.traceName, deltaX, deltaY, canForward, canBackward)
                }
            }
        }
    }

    private fun isFirstPageVisible(): Boolean {
        val first = firstVisiblePosition()
        return currentVisiblePage <= 0 || first <= 0
    }

    private fun handleReleaseScroll(
        deltaX: Float,
        deltaY: Float,
        canForward: Boolean,
        canBackward: Boolean,
        visiblePage: Int
    ): Boolean {
        if (visiblePage != touchStartVisiblePage) return false
        val direction = if (horizontalMode) {
            if (abs(deltaX) < BOUNDARY_SWIPE_DISTANCE || abs(deltaX) <= abs(deltaY)) return false
            if (deltaX < 0f) ComicBoundaryDirection.NEXT else ComicBoundaryDirection.PREVIOUS
        } else {
            if (abs(deltaY) < BOUNDARY_SWIPE_DISTANCE || abs(deltaY) <= abs(deltaX)) return false
            if (deltaY < 0f) ComicBoundaryDirection.NEXT else ComicBoundaryDirection.PREVIOUS
        }
        if (direction == ComicBoundaryDirection.NEXT && !canForward) return false
        if (direction == ComicBoundaryDirection.PREVIOUS && !canBackward) return false
        val pages = binding.comicReadPages
        val scroll = if (horizontalMode) -deltaX.toInt() else -deltaY.toInt()
        if (scroll == 0) return false
        if (horizontalMode) {
            pages.scrollBy(scroll, 0)
        } else {
            pages.scrollBy(0, scroll)
        }
        val afterPage = dominantVisiblePosition(pages)
        updatePageState(afterPage)
        AiBridgeTrace.event(
            "media_comic_drag_scroll",
            chapterRouteId,
            AiBridgeTrace.fields(
                "direction" to direction.traceName,
                "before" to (visiblePage + 1),
                "after" to (afterPage + 1),
                "deltaX" to deltaX.toInt(),
                "deltaY" to deltaY.toInt(),
                "scroll" to scroll,
                "horizontal" to horizontalMode
            )
        )
        return true
    }

    private fun isLastPageVisible(): Boolean {
        val count = pageAdapter.itemCount
        val lastIndex = count - 1
        val last = lastVisiblePosition()
        return count > 0 && (currentVisiblePage >= lastIndex || last >= lastIndex)
    }

    private fun isPageLoadSettled(position: Int): Boolean {
        return loadedPagePositions.contains(position) || failedPagePositions.contains(position)
    }

    private fun dominantVisiblePosition(recyclerView: RecyclerView): Int {
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return currentVisiblePage
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return currentVisiblePage
        }
        var bestPosition = first
        var bestVisibleSize = -1
        for (position in first..last) {
            val child = manager.findViewByPosition(position) ?: continue
            val visibleSize = if (horizontalMode) {
                min(child.right, recyclerView.width - recyclerView.paddingRight) -
                    max(child.left, recyclerView.paddingLeft)
            } else {
                min(child.bottom, recyclerView.height - recyclerView.paddingBottom) -
                    max(child.top, recyclerView.paddingTop)
            }.coerceAtLeast(0)
            if (visibleSize > bestVisibleSize) {
                bestVisibleSize = visibleSize
                bestPosition = position
            }
        }
        return bestPosition.coerceAtLeast(0)
    }

    private fun firstVisiblePosition(): Int {
        val manager = binding.comicReadPages.layoutManager as? LinearLayoutManager ?: return currentVisiblePage
        return manager.findFirstVisibleItemPosition().takeUnless { it == RecyclerView.NO_POSITION } ?: currentVisiblePage
    }

    private fun lastVisiblePosition(): Int {
        val manager = binding.comicReadPages.layoutManager as? LinearLayoutManager ?: return currentVisiblePage
        return manager.findLastVisibleItemPosition().takeUnless { it == RecyclerView.NO_POSITION } ?: currentVisiblePage
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
            binding.comicReadState.text = if (chapterPageListResolved) "图片加载失败" else "加载中..."
            binding.comicReadPageSeek.progress = 0
            return
        }
        val page = position.coerceIn(0, count - 1)
        currentVisiblePage = page
        binding.comicReadState.text = "${page + 1} / $count"
        binding.comicReadPageSeek.progress = if (count <= 1) 0 else page * SEEK_BAR_MAX / (count - 1)
        scheduleComicProgressSave(page)
        traceComicPageState(page)
    }

    private fun saveCurrentPage() {
        val page = dominantVisiblePosition(binding.comicReadPages)
        if (page >= 0) {
            progressSaveJob?.cancel()
            MediaShelfStore.updateComicProgress(this, chapterRouteId, page)
        }
    }

    private fun scheduleComicProgressSave(page: Int) {
        val routeId = chapterRouteId
        if (routeId.isBlank()) return
        if (routeId == lastSavedProgressRouteId && page == lastSavedProgressPage) return
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                MediaShelfStore.updateComicProgress(applicationContext, routeId, page)
            }
            lastSavedProgressRouteId = routeId
            lastSavedProgressPage = page
        }
    }

    private fun traceComicPagesResolved(pages: List<MediaRequest>) {
        AiBridgeTrace.state(
            "media_comic_pages_resolved",
            chapterRouteId,
            AiBridgeTrace.fields(
                "title" to binding.comicReadTitle.text,
                "count" to pages.size,
                "chapterIndex" to chapterIndex,
                "first" to pages.firstOrNull()?.url.comicTraceToken(),
                "middle" to pages.getOrNull(pages.size / 2)?.url.comicTraceToken(),
                "last" to pages.lastOrNull()?.url.comicTraceToken(),
                "tail" to pages.takeLast(3).joinToString("|") { it.url.comicTraceToken(48) }
            )
        )
    }

    private fun traceComicChapterEmpty() {
        AiBridgeTrace.event(
            "media_comic_chapter_empty",
            chapterRouteId,
            AiBridgeTrace.fields(
                "title" to binding.comicReadTitle.text,
                "chapterIndex" to chapterIndex,
                "bookRoute" to bookRouteId.orEmpty(),
                "catalogCount" to chapters.size
            )
        )
    }

    private fun traceComicImageLoad(
        position: Int,
        request: MediaRequest,
        success: Boolean,
        detail: String
    ) {
        AiBridgeTrace.event(
            "media_comic_image_load",
            "$chapterRouteId:$position",
            AiBridgeTrace.fields(
                "position" to (position + 1),
                "count" to pageAdapter.itemCount,
                "success" to success,
                "detail" to detail,
                "url" to request.url.comicTraceToken()
            )
        )
    }

    private fun traceComicImageLoadIgnored(
        position: Int,
        request: MediaRequest,
        success: Boolean,
        detail: String
    ) {
        AiBridgeTrace.event(
            "media_comic_image_load_ignored",
            "$chapterRouteId:$position",
            AiBridgeTrace.fields(
                "reason" to "stale_request",
                "position" to (position + 1),
                "count" to pageAdapter.itemCount,
                "success" to success,
                "detail" to detail,
                "url" to request.url.comicTraceToken()
            )
        )
    }

    private fun isCurrentPageRequest(position: Int, request: MediaRequest): Boolean {
        if (position !in 0 until pageAdapter.itemCount) return false
        return pageAdapter.items.getOrNull(position) == request
    }

    private fun traceComicPageState(page: Int) {
        val manager = binding.comicReadPages.layoutManager as? LinearLayoutManager
        val first = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val last = manager?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val count = pageAdapter.itemCount
        val canForward = if (horizontalMode) {
            binding.comicReadPages.canScrollHorizontally(1)
        } else {
            binding.comicReadPages.canScrollVertically(1)
        }
        val lastIndex = count - 1
        val lastLoaded = count > 0 && loadedPagePositions.contains(lastIndex)
        val lastFailed = count > 0 && failedPagePositions.contains(lastIndex)
        val lastSettled = lastLoaded || lastFailed
        val state = "page_${page + 1}_count_${count}_first_${first + 1}_last_${last + 1}" +
            "_canForward_${canForward}_lastSettled_${lastSettled}" +
            "_lastLoaded_${lastLoaded}_lastFailed_${lastFailed}"
        if (state == lastPageTrace) return
        lastPageTrace = state
        AiBridgeTrace.state(
            "media_comic_page_state",
            chapterRouteId,
            AiBridgeTrace.fields(
                "page" to (page + 1),
                "count" to count,
                "first" to (first + 1),
                "last" to (last + 1),
                "canForward" to canForward,
                "lastSettled" to lastSettled,
                "lastLoaded" to lastLoaded,
                "lastFailed" to lastFailed,
                "horizontal" to horizontalMode
            )
        )
    }

    private fun traceBoundarySwipe(
        direction: String,
        deltaX: Float,
        deltaY: Float,
        canForward: Boolean,
        canBackward: Boolean
    ) {
        val count = pageAdapter.itemCount
        val firstVisible = firstVisiblePosition()
        val lastVisible = lastVisiblePosition()
        val edgeIndex = if (direction == "next") count - 1 else 0
        AiBridgeTrace.event(
            "media_comic_boundary_sibling",
            chapterRouteId,
            AiBridgeTrace.fields(
                "direction" to direction,
                "page" to (currentVisiblePage + 1),
                "count" to count,
                "first" to (firstVisible + 1),
                "last" to (lastVisible + 1),
                "edgeSettled" to isPageLoadSettled(edgeIndex),
                "edgeLoaded" to loadedPagePositions.contains(edgeIndex),
                "edgeFailed" to failedPagePositions.contains(edgeIndex),
                "deltaX" to deltaX.toInt(),
                "deltaY" to deltaY.toInt(),
                "canForward" to canForward,
                "canBackward" to canBackward,
                "startedLeading" to touchStartedAtLeadingBoundary,
                "startedTrailing" to touchStartedAtTrailingBoundary,
                "chapterIndex" to chapterIndex
            )
        )
    }

    private fun traceBoundaryBlocked(
        direction: String,
        deltaX: Float,
        deltaY: Float,
        canForward: Boolean,
        canBackward: Boolean
    ) {
        val count = pageAdapter.itemCount
        val firstVisible = firstVisiblePosition()
        val lastVisible = lastVisiblePosition()
        val edgeIndex = if (direction == "next") count - 1 else 0
        AiBridgeTrace.event(
            "media_comic_boundary_blocked",
            chapterRouteId,
            AiBridgeTrace.fields(
                "reason" to "edge_visible_but_recycler_scrollable",
                "direction" to direction,
                "page" to (currentVisiblePage + 1),
                "count" to count,
                "first" to (firstVisible + 1),
                "last" to (lastVisible + 1),
                "edgeSettled" to isPageLoadSettled(edgeIndex),
                "edgeLoaded" to loadedPagePositions.contains(edgeIndex),
                "edgeFailed" to failedPagePositions.contains(edgeIndex),
                "deltaX" to deltaX.toInt(),
                "deltaY" to deltaY.toInt(),
                "canForward" to canForward,
                "canBackward" to canBackward,
                "startedLeading" to touchStartedAtLeadingBoundary,
                "startedTrailing" to touchStartedAtTrailingBoundary,
                "chapterIndex" to chapterIndex
            )
        )
    }

    private fun String?.comicTraceToken(limit: Int = 72): String {
        val value = this.orEmpty()
        if (value.isBlank()) return "-"
        val uri = Uri.parse(value)
        return listOf(uri.host.orEmpty(), uri.lastPathSegment.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString("|")
            .ifBlank { value.hashCode().toString() }
            .take(limit)
    }

    companion object {
        private const val SEEK_BAR_MAX = 1000
        private const val BOUNDARY_SWIPE_DISTANCE = 96f
        private const val READER_TAP_SLOP = 24f
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 500L
        private const val EXTRA_CHAPTER_ROUTE_ID = "chapter_route_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_RESET_PAGE = "reset_page"

        fun start(context: Context, chapterRouteId: String, title: String, resetPage: Boolean = false) {
            context.startActivity(
                Intent(context, ComicReadActivity::class.java)
                    .putExtra(EXTRA_CHAPTER_ROUTE_ID, chapterRouteId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_RESET_PAGE, resetPage)
            )
        }
    }
}
