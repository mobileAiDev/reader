package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityComicReadBinding
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.ui.adapter.ComicPageAdapter
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.utils.ReadingSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    private val readingSessionTracker = ReadingSessionTracker()
    private lateinit var chapterRouteId: String
    private var bookRouteId: String? = null
    private var chapterIndex = -1
    private var chapters: List<MediaChapterItem> = emptyList()
    private var chapterPresentation = ComicReaderChapterPresentation.EMPTY
    private var controlsVisible = false
    private var horizontalMode = false
    private var nightMode = false
    private var eyeProtectionEnabled = false
    private var landscapeMode = false
    private var brightnessPercent = DEFAULT_COMIC_BRIGHTNESS_PERCENT
    private var brightnessOverridden = false
    private var loadToken = 0
    private var openingSibling = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartVisiblePage = 0
    private var touchStartedAtLeadingBoundary = false
    private var touchStartedAtTrailingBoundary = false
    private var currentVisiblePage = 0
    private var confirmedProgress = ComicReaderConfirmedProgress.NONE
    private var lastPageTrace = ""
    private var resetPageOnOpen = false
    private var chapterPageListResolved = false
    private var progressSaveJob: Job? = null
    private var readerForeground = false
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
        val persistedMode = ComicReaderModePreferences.loadOnline(defaultLandscape = false)
        val restoredMode = if (savedInstanceState == null) {
            persistedMode
        } else restoredComicReaderModeState(
            horizontalPages = savedInstanceState.getBoolean(
                STATE_HORIZONTAL_MODE,
                persistedMode.horizontalPages
            ),
            night = savedInstanceState.getBoolean(STATE_NIGHT_MODE, persistedMode.night),
            eyeProtection = savedInstanceState.getBoolean(
                STATE_EYE_PROTECTION,
                persistedMode.eyeProtection
            ),
            forcedLandscape = savedInstanceState.getBoolean(
                STATE_LANDSCAPE_MODE,
                persistedMode.forcedLandscape
            ),
            savedBrightnessPercent = savedInstanceState?.getInt(
                STATE_BRIGHTNESS_PERCENT,
                persistedMode.brightnessPercent
            ) ?: persistedMode.brightnessPercent,
            savedBrightnessOverridden = savedInstanceState.getBoolean(
                STATE_BRIGHTNESS_OVERRIDDEN,
                persistedMode.brightnessOverridden
            )
        )
        horizontalMode = restoredMode.horizontalPages
        nightMode = restoredMode.night
        eyeProtectionEnabled = restoredMode.eyeProtection
        landscapeMode = restoredMode.forcedLandscape
        brightnessPercent = restoredMode.brightnessPercent
        brightnessOverridden = restoredMode.brightnessOverridden
        if (landscapeMode && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    override fun initWidget() {
        super.initWidget()
        binding.comicReadTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        applyPageOrientation()
        binding.comicReadPages.adapter = pageAdapter
        MediaUiChrome.applyReaderInsets(
            this,
            binding.comicReadToolbar,
            binding.comicReadBottomPanel
        )
        MediaUiChrome.applyReaderProgressInsets(binding.comicReadState)
        ViewCompat.replaceAccessibilityAction(
            binding.comicReadPages,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.reader_toggle_controls)
        ) { _, _ ->
            setControlsVisible(!controlsVisible)
            true
        }
        applyReaderModeState()
        pageAdapter.onPageLoadResult = pageLoad@{ generation, position, request, success, detail ->
            if (!isCurrentPageRequest(generation, position, request)) {
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
            if (position == currentVisiblePage) {
                updatePageState(position)
            } else {
                refreshComicRetryState()
            }
            traceComicPageState(currentVisiblePage)
        }
        refreshComicRetryState()
        setControlsVisible(false)
    }

    override fun initClick() {
        super.initClick()
        binding.comicReadRetry.setOnClickListener {
            AiBridgeTrace.event(
                "media_comic_retry_clicked",
                chapterRouteId,
                AiBridgeTrace.fields(
                    "page" to (currentVisiblePage + 1),
                    "pageCount" to pageAdapter.itemCount
                )
            )
            loadChapter()
        }
        binding.comicReadPrevChapter.setOnClickListener { openSiblingChapter(-1) }
        binding.comicReadNextChapter.setOnClickListener { openSiblingChapter(1) }
        binding.comicReadCatalog.setOnClickListener {
            bookRouteId?.let { route ->
                // The reader toolbar contains the chapter title. Let the catalog resolve the
                // book title from its route instead of presenting a chapter as the work name.
                MediaCatalogActivity.start(this, route, title = "", selectedIndex = chapterIndex)
            }
        }
        binding.comicReadNight.setOnClickListener {
            nightMode = !nightMode
            applyNightMode()
            persistReaderMode()
        }
        binding.comicReadEye.setOnClickListener {
            eyeProtectionEnabled = !eyeProtectionEnabled
            applyEyeProtection()
            persistReaderMode()
        }
        binding.comicReadDirection.setOnClickListener {
            val retainedPage = dominantVisiblePosition(binding.comicReadPages)
            horizontalMode = !horizontalMode
            binding.comicReadDirection.isSelected = horizontalMode
            binding.comicReadDirection.text = if (horizontalMode) "左右翻页" else "上下翻页"
            applyPageOrientation(retainedPage)
            persistReaderMode()
        }
        binding.comicReadLandscape.setOnClickListener {
            landscapeMode = !landscapeMode
            requestedOrientation = if (landscapeMode) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            binding.comicReadLandscape.isSelected = landscapeMode
            persistReaderMode()
        }
        binding.comicReadBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                brightnessPercent = progress.coerceIn(
                    MIN_COMIC_BRIGHTNESS_PERCENT,
                    MAX_COMIC_BRIGHTNESS_PERCENT
                )
                brightnessOverridden = true
                applyBrightness()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persistReaderMode()
            }
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
        val restartReadingSession = readerForeground
        if (restartReadingSession) {
            readingSessionTracker.onPause()
        }
        saveCurrentPage()
        setIntent(intent)
        readIntent(intent)
        if (restartReadingSession) {
            readingSessionTracker.onResume(readingSessionIdentity())
        }
        binding.comicReadTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        openingSibling = false
        // Reader presentation preferences belong to this reader session and intentionally survive
        // sibling and cross-book intents handled by the same Activity instance.
        loadChapter()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_HORIZONTAL_MODE, horizontalMode)
        outState.putBoolean(STATE_NIGHT_MODE, nightMode)
        outState.putBoolean(STATE_EYE_PROTECTION, eyeProtectionEnabled)
        outState.putBoolean(STATE_LANDSCAPE_MODE, landscapeMode)
        outState.putInt(STATE_BRIGHTNESS_PERCENT, brightnessPercent)
        outState.putBoolean(STATE_BRIGHTNESS_OVERRIDDEN, brightnessOverridden)
        super.onSaveInstanceState(outState)
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
        // reset_page is a one-shot navigation command. Keeping it on the Activity intent makes
        // a later configuration recreation consume it again and overwrite saved page progress.
        intent.removeExtra(EXTRA_RESET_PAGE)
    }

    private fun loadChapter() {
        val request = ComicChapterLoadIdentity(++loadToken, bookRouteId, chapterRouteId)
        val savedChapterTitle = MediaRouteRegistry.chapter(request.chapterRouteId)?.name
            ?.takeIf { it.isNotBlank() }
            ?: binding.comicReadTitle.text?.toString().orEmpty()
        val savedChapterIndex = MediaRouteRegistry.chapter(request.chapterRouteId)?.index
            ?: chapterIndex
        val savedPage = MediaShelfStore.comicPageIndex(this, request.chapterRouteId)
        val resetRequested = resetPageOnOpen
        pageAdapter.refreshItemsForGeneration(request.token, emptyList())
        binding.comicReadState.text = "加载中..."
        renderComicRetryState(
            comicReaderRetryState(
                chapterResolved = false,
                pageCount = 0,
                currentPageFailed = false
            )
        )
        AiBridgeTrace.event(
            "media_comic_chapter_load_started",
            chapterRouteId,
            AiBridgeTrace.fields("title" to binding.comicReadTitle.text, "chapterIndex" to chapterIndex, "bookRoute" to bookRouteId.orEmpty())
        )
        currentVisiblePage = ComicReaderProgressPolicy.loadingVisiblePage(
            confirmedProgress,
            chapterRouteId
        )
        lastPageTrace = ""
        loadedPagePositions.clear()
        failedPagePositions.clear()
        chapterPageListResolved = false
        chapters = emptyList()
        chapterPresentation = ComicReaderChapterPresentation.EMPTY
        updateChapterButtons()
        scope.launch {
            val requestedBookRoute = request.bookRouteId
            // A restored shelf snapshot already owns the exact current chapter route. Resolve its
            // pages in parallel with the slower detail/catalog refresh so the catalog cannot hold
            // the first readable image hostage on a cold open.
            val fastPagesDeferred = async(Dispatchers.IO) {
                comicPagesLoadResult(request.chapterRouteId)
            }
            val catalogDeferred = async(Dispatchers.IO) {
                if (requestedBookRoute != null) {
                    runCatching {
                        val detail = MediaSourceRepository.detail(requestedBookRoute)
                        val sourceChapters = MediaSourceRepository.chapters(requestedBookRoute)
                        ComicChapterOrder.resolve(sourceChapters, detail?.latest).chronological
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

            val fastPages = fastPagesDeferred.await()
            var fastPagesPublished = false
            if (ComicColdResumePolicy.canPublishFastPages(
                    requestIsCurrent = request.isCurrent(loadToken, bookRouteId, chapterRouteId),
                    pageCount = fastPages.pages.size
                )
            ) {
                chapterPresentation = comicReaderChapterPresentation(
                    chronologicalChapters = emptyList(),
                    chapterRouteId = request.chapterRouteId,
                    fallbackTitle = savedChapterTitle,
                    fallbackSourceIndex = savedChapterIndex
                )
                renderChapterPresentation()
                fastPagesPublished = publishComicPages(
                    requestToken = request.token,
                    pages = fastPages.pages,
                    savedPage = savedPage,
                    resetRequested = resetRequested,
                    failure = fastPages.failure
                )
            }

            val resolvedChapters = catalogDeferred.await()
            if (!request.isCurrent(loadToken, bookRouteId, chapterRouteId)) {
                return@launch
            }
            val resolvedChapter = ComicChapterRouteResolver.resolve(
                chapters = resolvedChapters,
                savedRouteId = request.chapterRouteId,
                savedTitle = savedChapterTitle,
                savedSourceIndex = savedChapterIndex
            )
            val resolvedChapterRouteId = resolvedChapter?.routeId ?: request.chapterRouteId
            val displayRouteId = ComicColdResumePolicy.displayRouteAfterCatalog(
                savedRouteId = request.chapterRouteId,
                resolvedRouteId = resolvedChapterRouteId,
                fastPagesPublished = fastPagesPublished
            )

            if (fastPagesPublished) {
                // Keep the already readable source route stable; use the refreshed catalog only
                // for canonical title/position and sibling navigation.
                chapters = resolvedChapters
                chapterIndex = resolvedChapter?.index ?: savedChapterIndex
                chapterPresentation = comicReaderChapterPresentation(
                    chronologicalChapters = chapters,
                    chapterRouteId = resolvedChapter?.routeId ?: displayRouteId,
                    fallbackTitle = savedChapterTitle,
                    fallbackSourceIndex = chapterIndex
                )
                renderChapterPresentation()
                updateChapterButtons()
                updatePageState(currentVisiblePage)
                MediaShelfStore.updateComicChapter(
                    this@ComicReadActivity,
                    chapterRouteId,
                    chapterPresentation.title
                )
                return@launch
            }

            val finalPages = if (displayRouteId == request.chapterRouteId) {
                fastPages
            } else {
                withContext(Dispatchers.IO) { comicPagesLoadResult(displayRouteId) }
            }
            if (!request.isCurrent(loadToken, bookRouteId, chapterRouteId)) {
                return@launch
            }
            chapterRouteId = displayRouteId
            bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId)
                ?: requestedBookRoute
            chapterIndex = resolvedChapter?.index ?: savedChapterIndex
            // A source refresh can produce a new stable chapter route. Keep the Activity intent
            // aligned so a later configuration recreation does not jump back to the stale route.
            intent.putExtra(EXTRA_CHAPTER_ROUTE_ID, chapterRouteId)
            chapters = resolvedChapters
            chapterPresentation = comicReaderChapterPresentation(
                chronologicalChapters = chapters,
                chapterRouteId = chapterRouteId,
                fallbackTitle = savedChapterTitle,
                fallbackSourceIndex = chapterIndex
            )
            renderChapterPresentation()
            publishComicPages(
                requestToken = request.token,
                pages = finalPages.pages,
                savedPage = savedPage,
                resetRequested = resetRequested,
                failure = finalPages.failure
            )
        }
    }

    private fun comicPagesLoadResult(routeId: String): ComicPagesLoadResult {
        val pagesResult = runCatching { MediaSourceRepository.comicReadablePages(routeId) }
        return ComicPagesLoadResult(
            pages = pagesResult.getOrDefault(emptyList()),
            failure = pagesResult.exceptionOrNull()?.javaClass?.simpleName.orEmpty()
        )
    }

    private fun renderChapterPresentation() {
        if (chapterPresentation.title.isNotBlank()) {
            binding.comicReadTitle.text = chapterPresentation.title
            intent.putExtra(EXTRA_TITLE, chapterPresentation.title)
        }
    }

    private fun publishComicPages(
        requestToken: Int,
        pages: List<MediaRequest>,
        savedPage: Int,
        resetRequested: Boolean,
        failure: String
    ): Boolean {
            traceComicPagesResolved(pages)
            chapterPageListResolved = true
            pageAdapter.refreshItemsForGeneration(requestToken, pages)
            updateChapterButtons()
            if (pages.isEmpty()) {
                binding.comicReadState.text = "图片加载失败"
                refreshComicRetryState()
                traceComicChapterEmpty(
                    failure.ifBlank { "empty" }
                )
                return false
            }
            refreshComicRetryState()
            MediaShelfStore.updateComicChapter(
                this@ComicReadActivity,
                chapterRouteId,
                chapterPresentation.title.ifBlank {
                    binding.comicReadTitle.text?.toString().orEmpty()
                }
            )
            val pageToRestore = ComicReaderProgressPolicy.pageAfterSuccessfulRender(
                confirmed = confirmedProgress,
                chapterRouteId = chapterRouteId,
                // When the same logical chapter is mapped to another source route, carry the
                // old route's saved page instead of restarting at page one.
                persistedPage = savedPage,
                resetRequested = resetRequested,
                renderedPageCount = pages.size
            )
            resetPageOnOpen = false
            updatePageState(pageToRestore)
            binding.comicReadPages.post { binding.comicReadPages.scrollToPosition(pageToRestore) }
            return true
    }

    override fun onResume() {
        super.onResume()
        readerForeground = true
        readingSessionTracker.onResume(readingSessionIdentity())
    }

    override fun onPause() {
        readerForeground = false
        persistReaderMode()
        readingSessionTracker.onPause()
        super.onPause()
    }

    override fun onStop() {
        readingSessionTracker.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        saveCurrentPage()
        pageAdapter.cancelPendingRetries()
        scope.cancel()
        super.onDestroy()
    }

    private fun readingSessionIdentity(): String? {
        return bookRouteId ?: chapterRouteId.takeIf { it.isNotBlank() }
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val visibility = if (controlsVisible) View.VISIBLE else View.GONE
        binding.comicReadToolbar.visibility = visibility
        binding.comicReadBottomPanel.visibility = visibility
        binding.comicReadState.visibility = if (visible) View.GONE else View.VISIBLE
        MediaUiChrome.immersiveReader(this, controlsVisible)
    }

    private fun applyReaderModeState() {
        binding.comicReadDirection.isSelected = horizontalMode
        binding.comicReadDirection.text = if (horizontalMode) "左右翻页" else "上下翻页"
        binding.comicReadLandscape.isSelected = landscapeMode
        applyNightMode()
        applyEyeProtection()
        binding.comicReadBrightness.progress = brightnessPercent
        applyBrightness()
    }

    private fun persistReaderMode() {
        ComicReaderModePreferences.saveOnline(
            ComicReaderModeState(
                horizontalPages = horizontalMode,
                night = nightMode,
                eyeProtection = eyeProtectionEnabled,
                forcedLandscape = landscapeMode,
                brightnessPercent = brightnessPercent,
                brightnessOverridden = brightnessOverridden
            )
        )
    }

    private fun applyNightMode() {
        binding.comicReadNight.isSelected = nightMode
        binding.comicReadPages.setBackgroundColor(
            resources.getColor(if (nightMode) R.color.black else R.color.media_reader_dark)
        )
    }

    private fun applyEyeProtection() {
        binding.comicReadEye.isSelected = eyeProtectionEnabled
        binding.comicReadEyeLayer.visibility = if (eyeProtectionEnabled) View.VISIBLE else View.GONE
    }

    private fun applyBrightness() {
        val value = if (brightnessOverridden) {
            brightnessPercent.toFloat() / MAX_COMIC_BRIGHTNESS_PERCENT.toFloat()
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun applyPageOrientation(retainedPage: Int? = null) {
        val manager = LinearLayoutManager(
            this,
            if (horizontalMode) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL,
            false
        )
        binding.comicReadPages.layoutManager = manager
        val count = pageAdapter.itemCount
        if (retainedPage == null || count <= 0) return
        val page = retainedPage.coerceIn(0, count - 1)
        manager.scrollToPositionWithOffset(page, 0)
        updatePageState(page)
        binding.comicReadPages.post {
            if (binding.comicReadPages.layoutManager !== manager) return@post
            manager.scrollToPositionWithOffset(page, 0)
            updatePageState(dominantVisiblePosition(binding.comicReadPages))
        }
    }

    private fun openSiblingChapter(offset: Int) {
        if (openingSibling) return
        val currentPosition = currentChapterPosition() ?: return
        val target = chapters.getOrNull(currentPosition + offset) ?: return
        openingSibling = true
        start(this, target.routeId, target.title, resetPage = true)
    }

    private fun currentChapterPosition(): Int? {
        val routePosition = chapters.indexOfFirst { chapter -> chapter.routeId == chapterRouteId }
        if (routePosition >= 0) return routePosition
        return chapterPresentation.catalogPosition
            .takeIf { it > 0 }
            ?.minus(1)
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
            MotionEvent.ACTION_CANCEL -> return
            MotionEvent.ACTION_UP -> {
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
                    firstPageLoaded = loadedPagePositions.contains(0),
                    lastPageLoaded = loadedPagePositions.contains(pageAdapter.itemCount - 1),
                    distance = BOUNDARY_SWIPE_DISTANCE
                )
                if (target != null) {
                    traceBoundarySwipe(target.traceName, deltaX, deltaY, canForward, canBackward)
                    openSiblingChapter(target.offset)
                    return
                }
                val edgeLoadBlocked = ComicBoundaryNavigation.blockedByEdgeLoad(
                    horizontal = horizontalMode,
                    controlsVisible = controlsVisible,
                    itemCount = pageAdapter.itemCount,
                    deltaX = deltaX,
                    deltaY = deltaY,
                    canScrollForward = canForward,
                    canScrollBackward = canBackward,
                    firstPageLoaded = loadedPagePositions.contains(0),
                    lastPageLoaded = loadedPagePositions.contains(pageAdapter.itemCount - 1),
                    distance = BOUNDARY_SWIPE_DISTANCE
                )
                if (edgeLoadBlocked != null) {
                    val edgePosition = if (edgeLoadBlocked == ComicBoundaryDirection.NEXT) {
                        pageAdapter.itemCount - 1
                    } else {
                        0
                    }
                    binding.comicReadState.text = ComicBoundaryNavigation.edgeLoadFeedback(
                        edgeLoadBlocked,
                        failed = failedPagePositions.contains(edgePosition)
                    )
                    refreshComicRetryState()
                    traceBoundaryBlocked(
                        edgeLoadBlocked.traceName,
                        deltaX,
                        deltaY,
                        canForward,
                        canBackward,
                        reason = "edge_image_not_loaded"
                    )
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
        val currentPosition = currentChapterPosition()
        val hasPrevious = currentPosition != null &&
            chapters.getOrNull(currentPosition - 1) != null
        val hasNext = currentPosition != null &&
            chapters.getOrNull(currentPosition + 1) != null
        binding.comicReadPrevChapter.isEnabled = hasPrevious
        binding.comicReadNextChapter.isEnabled = hasNext
        binding.comicReadPrevChapter.alpha = if (hasPrevious) 1f else 0.38f
        binding.comicReadNextChapter.alpha = if (hasNext) 1f else 0.38f
    }

    private fun updatePageState(position: Int) {
        val count = pageAdapter.itemCount
        if (count <= 0) {
            binding.comicReadState.text = if (chapterPageListResolved) "图片加载失败" else "加载中..."
            binding.comicReadPageSeek.progress = 0
            refreshComicRetryState()
            return
        }
        val page = position.coerceIn(0, count - 1)
        currentVisiblePage = page
        confirmedProgress = ComicReaderProgressPolicy.confirmRenderedPage(
            previous = confirmedProgress,
            chapterRouteId = chapterRouteId,
            pageIndex = page,
            renderedPageCount = count
        )
        binding.comicReadState.text = chapterPresentation.pageStatus(
            pagePosition = page + 1,
            pageCount = count
        )
        binding.comicReadPageSeek.progress = if (count <= 1) 0 else page * SEEK_BAR_MAX / (count - 1)
        refreshComicRetryState()
        ComicReaderProgressPolicy.pageToPersist(confirmedProgress, chapterRouteId)?.let {
            scheduleComicProgressSave(it)
        }
        traceComicPageState(page)
    }

    private fun refreshComicRetryState() {
        renderComicRetryState(
            comicReaderRetryState(
                chapterResolved = chapterPageListResolved,
                pageCount = pageAdapter.itemCount,
                currentPageFailed = failedPagePositions.contains(currentVisiblePage)
            )
        )
    }

    private fun renderComicRetryState(state: ComicReaderRetryState) {
        binding.comicReadRetry.visibility = if (state.visible) View.VISIBLE else View.GONE
        binding.comicReadRetry.text = state.message
        binding.comicReadRetry.contentDescription = state.message
        binding.comicReadRetry.isEnabled = state.visible
    }

    private fun saveCurrentPage() {
        val page = ComicReaderProgressPolicy.pageToPersist(
            confirmedProgress,
            chapterRouteId
        ) ?: return
        progressSaveJob?.cancel()
        MediaShelfStore.updateComicProgress(this, chapterRouteId, page)
        lastSavedProgressRouteId = chapterRouteId
        lastSavedProgressPage = page
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
                "catalogPosition" to chapterPresentation.catalogPosition,
                "first" to pages.firstOrNull()?.url.comicTraceToken(),
                "middle" to pages.getOrNull(pages.size / 2)?.url.comicTraceToken(),
                "last" to pages.lastOrNull()?.url.comicTraceToken(),
                "tail" to pages.takeLast(3).joinToString("|") { it.url.comicTraceToken(48) }
            )
        )
    }

    private fun traceComicChapterEmpty(reason: String) {
        AiBridgeTrace.event(
            "media_comic_chapter_empty",
            chapterRouteId,
            AiBridgeTrace.fields(
                "title" to binding.comicReadTitle.text,
                "chapterIndex" to chapterIndex,
                "bookRoute" to bookRouteId.orEmpty(),
                "catalogCount" to chapters.size,
                "reason" to reason
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

    private fun isCurrentPageRequest(
        generation: Int,
        position: Int,
        request: MediaRequest
    ): Boolean {
        return isCurrentComicPageRequest(
            requestGeneration = generation,
            currentGeneration = loadToken,
            position = position,
            request = request,
            currentItems = pageAdapter.items
        )
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
        canBackward: Boolean,
        reason: String = "edge_visible_but_recycler_scrollable"
    ) {
        val count = pageAdapter.itemCount
        val firstVisible = firstVisiblePosition()
        val lastVisible = lastVisiblePosition()
        val edgeIndex = if (direction == "next") count - 1 else 0
        AiBridgeTrace.event(
            "media_comic_boundary_blocked",
            chapterRouteId,
            AiBridgeTrace.fields(
                "reason" to reason,
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
        private const val STATE_HORIZONTAL_MODE = "horizontal_mode"
        private const val STATE_NIGHT_MODE = "night_mode"
        private const val STATE_EYE_PROTECTION = "eye_protection"
        private const val STATE_LANDSCAPE_MODE = "landscape_mode"
        private const val STATE_BRIGHTNESS_PERCENT = "brightness_percent"
        private const val STATE_BRIGHTNESS_OVERRIDDEN = "brightness_overridden"

        fun start(context: Context, chapterRouteId: String, title: String, resetPage: Boolean = false) {
            context.startActivity(
                Intent(context, ComicReadActivity::class.java)
                    .putExtra(EXTRA_CHAPTER_ROUTE_ID, chapterRouteId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_RESET_PAGE, resetPage)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}

private data class ComicPagesLoadResult(
    val pages: List<MediaRequest>,
    val failure: String
)
