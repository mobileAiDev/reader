package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityComicDetailBinding
import com.ldp.reader.media.MediaDetailLoadProgress
import com.ldp.reader.media.MediaCatalogCompleteness
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaBookDetail
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.ui.adapter.MediaChapterAdapter
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.image.AmbientCover
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.ui.motion.Press
import com.ldp.reader.ui.theme.EdgeToEdge
import com.ldp.reader.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ComicDetailActivity : BaseActivity<ActivityComicDetailBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chapterAdapter = MediaChapterAdapter()
    private lateinit var routeId: String
    private var firstChapter: MediaChapterItem? = null
    private var latestChapter: MediaChapterItem? = null
    private var chapters: List<MediaChapterItem> = emptyList()
    private var descending = false
    private var loadToken = 0
    private var detailLoadStartedAt = 0L
    private var detailLoadJob: Job? = null
    private var detailPromptJob: Job? = null

    override fun getViewBinding(): ActivityComicDetailBinding {
        return ActivityComicDetailBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.comicDetailToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_ink_back)
        toolbar?.navigationIcon?.setTint(getColor(R.color.ink_text_primary))
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty()
    }

    override fun initWidget() {
        super.initWidget()
        EdgeToEdge.apply(this)
        EdgeToEdge.padTop(binding.comicDetailToolbar)
        EdgeToEdge.padBottom(binding.comicDetailScroll, resources.getDimensionPixelSize(R.dimen.home_list_bottom_room))
        EdgeToEdge.liftAboveNavigation(binding.comicDetailBottomBar)
        Press.attach(binding.comicDetailAddShelf, Press.BUTTON)
        Press.attach(binding.comicDetailStart, Press.BUTTON)
        binding.comicDetailChapters.layoutManager = LinearLayoutManager(this)
        binding.comicDetailChapters.adapter = chapterAdapter
        binding.comicDetailAddShelf.isEnabled = false
        binding.comicDetailAddShelf.alpha = 0.56f
        binding.comicDetailStart.isEnabled = false
        binding.comicDetailStart.alpha = 0.56f
        binding.comicDetailLatest.isEnabled = false
        binding.comicDetailLatest.alpha = 0.56f
        binding.comicDetailOrder.isEnabled = false
        binding.comicDetailOrder.alpha = 0.56f
        binding.comicDetailCatalogAll.isEnabled = false
        binding.comicDetailCatalogAll.alpha = 0.56f
    }

    override fun initClick() {
        super.initClick()
        binding.comicDetailAddShelf.setOnClickListener { addToMediaShelf() }
        chapterAdapter.setOnItemClickListener { _, pos ->
            val chapter = chapterAdapter.getItem(pos)
            ComicReadActivity.start(this, chapter.routeId, chapter.title)
        }
        binding.comicDetailStart.setOnClickListener {
            readingChapter()?.let { chapter -> ComicReadActivity.start(this, chapter.routeId, chapter.title) }
        }
        binding.comicDetailLatest.setOnClickListener {
            latestChapter?.let { chapter -> ComicReadActivity.start(this, chapter.routeId, chapter.title) }
        }
        binding.comicDetailOrder.setOnClickListener {
            descending = !descending
            renderChapters()
        }
        binding.comicDetailCatalogAll.setOnClickListener {
            if (chapters.isEmpty()) return@setOnClickListener
            MediaCatalogActivity.start(this, routeId, binding.comicDetailTitle.text?.toString().orEmpty())
        }
        binding.comicDetailLoadingRetry.setOnClickListener { loadDetail() }
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

    override fun onResume() {
        super.onResume()
        renderShelfState()
    }

    private fun loadDetail() {
        detailLoadJob?.cancel()
        detailPromptJob?.cancel()
        val token = ++loadToken
        detailLoadStartedAt = System.currentTimeMillis()
        MediaShelfStore.restoreForBook(this, routeId)
        chapters = emptyList()
        firstChapter = null
        latestChapter = null
        renderChapters()
        setActionButtonsEnabled(false)
        binding.comicDetailChapterHint.text = ""
        renderLoadingStep(MediaDetailLoadProgress.Step.STARTED)
        detailPromptJob = scope.launch {
            delay(DETAIL_PROGRESS_PROMPT_MS)
            if (token != loadToken) return@launch
            if (chapters.isEmpty()) {
                renderLoadingStep(MediaDetailLoadProgress.Step.CATALOG)
            }
        }
        detailLoadJob = scope.launch {
            val detailOutcome = withTimeoutOrNull(DETAIL_LOAD_TIMEOUT_MS) {
                renderLoadingStep(MediaDetailLoadProgress.Step.DETAIL)
                runMediaDetailCallSafely { MediaSourceRepository.detail(routeId) }
            }
            if (token != loadToken) return@launch
            val detail = when (detailOutcome) {
                null -> {
                    renderLoadingFailure(MediaDetailFailureStage.DETAIL, "detail_timeout")
                    return@launch
                }
                is MediaDetailCallOutcome.Failed -> {
                    renderLoadingFailure(MediaDetailFailureStage.DETAIL, "detail_unavailable")
                    return@launch
                }
                is MediaDetailCallOutcome.Succeeded -> detailOutcome.value ?: run {
                    renderLoadingFailure(MediaDetailFailureStage.DETAIL, "detail_empty")
                    return@launch
                }
            }
            renderDetail(detail)
            renderLoadingStep(MediaDetailLoadProgress.Step.CATALOG)
            val catalogOutcome = withTimeoutOrNull(DETAIL_LOAD_TIMEOUT_MS) {
                runMediaDetailCallSafely { MediaSourceRepository.chapters(routeId) }
            }
            if (token != loadToken) return@launch
            val sourceChapters = when (catalogOutcome) {
                null -> {
                    renderLoadingFailure(MediaDetailFailureStage.CATALOG, "catalog_timeout")
                    return@launch
                }
                is MediaDetailCallOutcome.Failed -> {
                    renderLoadingFailure(MediaDetailFailureStage.CATALOG, "catalog_unavailable")
                    return@launch
                }
                is MediaDetailCallOutcome.Succeeded -> catalogOutcome.value
            }
            loadToken += 1
            val chapterOrder = ComicChapterOrder.resolve(sourceChapters, detail.latest)
            chapters = chapterOrder.chronological
            renderChapters()
            firstChapter = chapterOrder.first
            latestChapter = chapterOrder.latest
            traceChapterOrder(chapterOrder, sourceChapters)
            setActionButtonsEnabled(true)
            renderShelfState()
            val expectedCount = MediaCatalogCompleteness.expectedCount(detail.latest, detail.intro)
            binding.comicDetailState.text = when {
                chapters.isNotEmpty() -> "共 ${chapters.size} 话"
                else -> MediaDetailLoadProgress.Step.FAILED.label
            }
            binding.comicDetailChapterHint.text = if (chapters.isEmpty()) "" else "共 ${chapters.size} 话"
            if (chapters.isEmpty()) {
                renderLoadingFailure(
                    MediaDetailFailureStage.CATALOG,
                    if (expectedCount > 0) "chapters_empty_expected_$expectedCount" else "chapters_empty"
                )
            } else {
                renderLoadingStep(MediaDetailLoadProgress.Step.READY)
                binding.comicDetailLoadingOverlay.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        detailLoadJob?.cancel()
        detailPromptJob?.cancel()
        detailLoadJob = null
        detailPromptJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun renderDetail(detail: MediaBookDetail) {
        binding.comicDetailTitle.text = detail.title
        binding.comicDetailMeta.text = detail.author
        binding.comicDetailMeta.visibility = if (detail.author.isBlank()) View.GONE else View.VISIBLE
        binding.comicDetailLatest.text = getString(R.string.media_detail_latest, detail.latest)
        binding.comicDetailLatest.visibility = if (detail.latest.isBlank()) View.GONE else View.VISIBLE
        binding.comicDetailIntro.text = detail.intro.ifBlank { "暂无简介" }
        val covers = listOfNotNull(detail.coverUrl.takeIf { it.isNotBlank() })
        BookCoverLoader.load(covers, binding.comicDetailCover, R.drawable.ic_book_cover_placeholder)
        AmbientCover.show(binding.comicDetailAmbient, covers)
    }

    private fun renderChapters() {
        chapterAdapter.useChronologicalDisplayOrder(chapters)
        val visible = if (descending) chapters.asReversed() else chapters
        chapterAdapter.refreshItems(visible.take(DETAIL_PREVIEW_CHAPTERS))
        binding.comicDetailOrder.text = if (descending) "正序" else "倒序"
        val hasChapters = chapters.isNotEmpty()
        binding.comicDetailOrder.isEnabled = hasChapters
        binding.comicDetailOrder.alpha = if (hasChapters) 1f else 0.56f
        binding.comicDetailCatalogAll.isEnabled = hasChapters
        binding.comicDetailCatalogAll.alpha = if (hasChapters) 1f else 0.56f
    }

    private fun setActionButtonsEnabled(hasLoaded: Boolean) {
        val hasChapters = chapters.isNotEmpty()
        binding.comicDetailAddShelf.isEnabled = hasLoaded && hasChapters
        binding.comicDetailAddShelf.alpha = if (hasLoaded && hasChapters) 1f else 0.56f
        binding.comicDetailStart.isEnabled = hasLoaded && firstChapter != null
        binding.comicDetailStart.alpha = if (hasLoaded && firstChapter != null) 1f else 0.56f
        binding.comicDetailLatest.isEnabled = hasLoaded && latestChapter != null
        binding.comicDetailLatest.alpha = if (hasLoaded && latestChapter != null) 1f else 0.56f
    }

    private fun renderLoadingStep(step: MediaDetailLoadProgress.Step, text: String = step.label) {
        binding.comicDetailLoadingOverlay.visibility = View.VISIBLE
        binding.comicDetailLoadingRetry.visibility = View.GONE
        binding.comicDetailLoadingTitle.text = "正在加载"
        binding.comicDetailLoadingStep.text = text
        binding.comicDetailLoadingProgress.visibility = View.VISIBLE
        binding.comicDetailLoadingProgress.isIndeterminate = true
        if (step != MediaDetailLoadProgress.Step.READY) {
            binding.comicDetailState.text = text
        }
        traceDetailLoad("step", text, "")
    }

    private fun renderLoadingFailure(stage: MediaDetailFailureStage, reason: String) {
        val text = MediaDetailFailureCopy.messageFor(stage)
        loadToken += 1
        binding.comicDetailLoadingOverlay.visibility = View.VISIBLE
        binding.comicDetailLoadingRetry.visibility = View.VISIBLE
        binding.comicDetailLoadingTitle.text = "加载失败"
        binding.comicDetailLoadingStep.text = text
        binding.comicDetailLoadingProgress.visibility = View.GONE
        binding.comicDetailState.text = text
        traceDetailLoad("failed", text, reason)
    }

    private fun traceDetailLoad(stage: String, text: String, reason: String) {
        AiBridgeTrace.state(
            "media_comic_detail_loading",
            routeId,
            AiBridgeTrace.fields(
                "stage" to stage,
                "text" to text,
                "reason" to reason,
                "chapters" to chapters.size,
                "elapsedMs" to (System.currentTimeMillis() - detailLoadStartedAt).coerceAtLeast(0L)
            )
        )
    }

    private fun traceChapterOrder(
        order: ComicChapterOrder.Result,
        sourceChapters: List<MediaChapterItem>
    ) {
        AiBridgeTrace.state(
            "media_comic_chapter_order",
            routeId,
            AiBridgeTrace.fields(
                "sourceDirection" to order.sourceDirection.name.lowercase(),
                "evidence" to order.evidence,
                "count" to sourceChapters.size,
                "sourceFirst" to sourceChapters.firstOrNull()?.title.orEmpty(),
                "sourceLast" to sourceChapters.lastOrNull()?.title.orEmpty(),
                "readingFirst" to order.first?.title.orEmpty(),
                "latest" to order.latest?.title.orEmpty()
            )
        )
    }

    private fun addToMediaShelf() {
        if (MediaShelfStore.isAdded(this, routeId)) {
            renderShelfState()
            ToastUtils.show("已在书架")
            return
        }
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
            ToastUtils.show("作品信息还在加载，请稍后再试")
            return
        }
        renderShelfState()
        ToastUtils.show("已加入书架")
    }

    private fun readingChapter(): MediaChapterItem? {
        val shelfItem = MediaShelfStore.itemForBook(this, routeId)
        return ComicReadingResume.chapter(
            chapters = chapters,
            savedChapterRouteId = shelfItem?.currentChapterRouteId,
            savedChapterIndex = shelfItem?.currentChapterIndex,
            savedChapterTitle = shelfItem?.currentChapterTitle
        )
    }

    private fun renderShelfState() {
        val shelfItem = MediaShelfStore.itemForBook(this, routeId)
        binding.comicDetailAddShelf.text = if (shelfItem != null) "已在书架" else "加入书架"
        binding.comicDetailAddShelf.setIconResource(if (shelfItem != null) R.drawable.ic_ink_check else R.drawable.ic_ink_add)
        binding.comicDetailStart.text = if (
            shelfItem != null && ComicReadingResume.hasSavedChapter(
                chapters = chapters,
                savedChapterRouteId = shelfItem.currentChapterRouteId,
                savedChapterIndex = shelfItem.currentChapterIndex,
                savedChapterTitle = shelfItem.currentChapterTitle
            )
        ) {
            "继续阅读"
        } else {
            "开始阅读"
        }
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val DETAIL_LOAD_TIMEOUT_MS = 45_000L
        private const val DETAIL_PROGRESS_PROMPT_MS = 10_000L
        private const val DETAIL_PREVIEW_CHAPTERS = 8

        fun start(context: Context, routeId: String) {
            context.startActivity(
                Intent(context, ComicDetailActivity::class.java)
                    .putExtra(EXTRA_ROUTE_ID, routeId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
