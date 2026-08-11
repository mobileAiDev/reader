package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityLocalComicReadBinding
import com.ldp.reader.document.ComicArchiveCachePolicy
import com.ldp.reader.document.ComicArchiveExtractionResult
import com.ldp.reader.document.ComicArchiveFailureCopy
import com.ldp.reader.document.ComicArchiveFailureReason
import com.ldp.reader.document.ComicArchiveLimits
import com.ldp.reader.document.ComicArchiveStoragePolicy
import com.ldp.reader.document.DocumentFileName
import com.ldp.reader.document.LocalComicArchiveExtractor
import com.ldp.reader.document.LocalDocumentPageProgressPersistence
import com.ldp.reader.document.LocalDocumentPageProgressStore
import com.ldp.reader.document.LocalDocumentVisiblePage
import com.ldp.reader.document.dominantLocalDocumentPageIndex
import com.ldp.reader.document.localDocumentPageLabel
import com.ldp.reader.document.restoredLocalDocumentPageIndex
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.ui.adapter.ComicPageAdapter
import com.ldp.reader.ui.adapter.localComicPageLayoutMode
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.utils.ReadingSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LocalComicReadActivity : BaseActivity<ActivityLocalComicReadBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val progressStore: LocalDocumentPageProgressStore by lazy {
        LocalDocumentPageProgressPersistence.store
    }
    private val readingSessionTracker = ReadingSessionTracker()
    private val pageAdapter = ComicPageAdapter()
    private val horizontalPageSnapHelper = PagerSnapHelper()
    private val archiveLimits = ComicArchiveLimits()
    private val archiveExtractor = LocalComicArchiveExtractor(archiveLimits)
    private lateinit var uri: Uri
    private var extractedDirectory: File? = null
    private var documentPageCount = 0
    private var pageIndex = 0
    private var restoredPageIndex: Int? = null
    private var isDocumentReady = false
    private var controlsVisible = false
    private var horizontalMode = false
    private var loadGeneration = 0
    private var pageLoadState = LocalComicPageLoadState.EMPTY

    override fun onCreate(savedInstanceState: Bundle?) {
        MediaUiChrome.prepareImmersiveReader(this)
        super.onCreate(savedInstanceState)
    }

    override fun getViewBinding(): ActivityLocalComicReadBinding {
        return ActivityLocalComicReadBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.localComicReadToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        MediaUiChrome.immersiveReader(this, controlsVisible)
    }

    override fun initData(savedInstanceState: Bundle?) {
        super.initData(savedInstanceState)
        uri = intent.data ?: Uri.EMPTY
        restoredPageIndex = savedInstanceState
            ?.takeIf { it.containsKey(STATE_PAGE_INDEX) }
            ?.getInt(STATE_PAGE_INDEX)
            ?: progressStore.load(documentProgressId())
        horizontalMode = savedInstanceState?.getBoolean(
            STATE_HORIZONTAL_MODE,
            ComicReaderModePreferences.loadLocalHorizontal(directionDocumentId())
        ) ?: ComicReaderModePreferences.loadLocalHorizontal(directionDocumentId())
    }

    override fun initWidget() {
        super.initWidget()
        applyPageOrientation()
        binding.localComicReadPages.adapter = pageAdapter
        pageAdapter.onPageLoadResult = pageLoad@{ generation, position, request, success, detail ->
            val updatedState = pageLoadState.record(
                requestGeneration = generation,
                currentGeneration = loadGeneration,
                position = position,
                request = request,
                currentItems = pageAdapter.items,
                success = success
            )
            if (updatedState === pageLoadState) return@pageLoad
            pageLoadState = updatedState
            isDocumentReady = pageLoadState.hasReadablePage
            if (!success) {
                Log.w(
                    TAG,
                    "Local comic image load failed: generation=$generation; " +
                        "position=$position; detail=$detail"
                )
            }
            refreshPageRetryState()
        }
        MediaUiChrome.applyReaderInsets(
            this,
            binding.localComicReadToolbar,
            binding.localComicReadBottomPanel
        )
        MediaUiChrome.applyReaderProgressInsets(binding.localComicReadProgress)
        binding.localComicReadPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateVisiblePage(recyclerView)
            }
        })
        setControlsVisible(false)
    }

    override fun initClick() {
        super.initClick()
        binding.localComicReadPages.setOnClickListener { toggleControls() }
        pageAdapter.setOnItemClickListener { _, _ -> toggleControls() }
        binding.localComicReadRetry.setOnClickListener { loadArchive() }
        binding.localComicReadPageRetry.setOnClickListener { retryPageLoads() }
        binding.localComicReadErrorBack.setOnClickListener { finish() }
        binding.localComicReadDirection.setOnClickListener {
            val retainedPage = dominantVisiblePage(binding.localComicReadPages)
            horizontalMode = !horizontalMode
            renderDirectionMode()
            applyPageOrientation(retainedPage)
            persistDirectionMode()
        }
        renderDirectionMode()
    }

    override fun processLogic() {
        super.processLogic()
        loadArchive()
    }

    private fun loadArchive() {
        val generation = ++loadGeneration
        pageLoadState = LocalComicPageLoadState.EMPTY
        isDocumentReady = false
        pageAdapter.refreshItemsForGeneration(generation, emptyList())
        binding.localComicReadError.visibility = View.GONE
        renderPageState(getString(R.string.local_comic_loading))
        refreshPageRetryState()
        setControlsVisible(false)
        scope.launch {
            val outputDirectory = File(cacheDir, "local-comic/${UUID.randomUUID()}")
            var keepExtractedPages = false
            try {
                val (displayName, result) = withContext(Dispatchers.IO) {
                    val loadContext = currentCoroutineContext()
                    val cancellationSignal = CancellationSignal()
                    val cancellationWatcher = launch(Dispatchers.Default) {
                        try {
                            awaitCancellation()
                        } finally {
                            cancellationSignal.cancel()
                        }
                    }
                    try {
                        loadContext.ensureActive()
                        val loadedDisplayName = DocumentFileName.displayName(
                            this@LocalComicReadActivity,
                            uri,
                            cancellationSignal
                        )
                        loadContext.ensureActive()
                        loadedDisplayName to extractImages(
                            outputDirectory = outputDirectory,
                            cancellationSignal = cancellationSignal,
                            checkCancelled = { loadContext.ensureActive() }
                        )
                    } finally {
                        cancellationWatcher.cancel()
                    }
                }
                if (!isActive || generation != loadGeneration) return@launch
                binding.localComicReadTitle.text = displayName
                when (result) {
                    is ComicArchiveExtractionResult.Success -> {
                        binding.localComicReadError.visibility = View.GONE
                        extractedDirectory = outputDirectory
                        keepExtractedPages = true
                        val pages = result.pages.map { MediaRequest(it.file.absolutePath) }
                        pageAdapter.refreshItemsForGeneration(generation, pages)
                        documentPageCount = result.pages.size
                        pageIndex = restoredLocalDocumentPageIndex(
                            restoredPageIndex,
                            documentPageCount
                        )
                        restoredPageIndex = null
                        isDocumentReady = false
                        binding.localComicReadPages.scrollToPosition(pageIndex)
                        updatePageState()
                        refreshPageRetryState()
                        setControlsVisible(false)
                    }
                    is ComicArchiveExtractionResult.Failure -> {
                        pageAdapter.refreshItemsForGeneration(generation, emptyList())
                        documentPageCount = 0
                        isDocumentReady = false
                        Log.w(
                            TAG,
                            "Local comic open failed: reason=${result.reason}; detail=${result.message}"
                        )
                        showArchiveFailure(ComicArchiveFailureCopy.messageFor(result.reason))
                    }
                }
            } finally {
                if (!keepExtractedPages) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        outputDirectory.deleteRecursively()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (uri != Uri.EMPTY) {
            readingSessionTracker.onResume("local-document:comic:$uri")
        }
    }

    override fun onPause() {
        persistReadingProgress()
        persistDirectionMode()
        readingSessionTracker.onPause()
        super.onPause()
    }

    override fun onStop() {
        persistReadingProgress()
        readingSessionTracker.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_HORIZONTAL_MODE, horizontalMode)
        updateVisiblePage(binding.localComicReadPages)
        if (pageLoadState.canPersist(pageIndex, documentPageCount)) {
            outState.putInt(STATE_PAGE_INDEX, pageIndex)
            persistReadingProgress()
        } else {
            restoredPageIndex?.let { outState.putInt(STATE_PAGE_INDEX, it) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            MediaUiChrome.immersiveReader(this, controlsVisible)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        loadGeneration += 1
        horizontalPageSnapHelper.attachToRecyclerView(null)
        pageAdapter.setOnItemClickListener(null)
        pageAdapter.onPageLoadResult = null
        pageAdapter.cancelPendingRetries()
        val directory = extractedDirectory
        extractedDirectory = null
        if (directory != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    directory.deleteRecursively()
                } finally {
                    cancel()
                }
            }
        }
        super.onDestroy()
    }

    private fun updateVisiblePage(recyclerView: RecyclerView) {
        val visibleIndex = dominantVisiblePage(recyclerView)
        if (visibleIndex != pageIndex) {
            pageIndex = visibleIndex
            updatePageState()
        }
        refreshPageRetryState()
    }

    private fun dominantVisiblePage(recyclerView: RecyclerView): Int {
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return pageIndex
        val pageCount = pageAdapter.itemCount
        if (pageCount <= 0) return pageIndex
        val viewportStart = if (horizontalMode) recyclerView.paddingLeft else recyclerView.paddingTop
        val viewportEnd = if (horizontalMode) {
            recyclerView.width - recyclerView.paddingRight
        } else {
            recyclerView.height - recyclerView.paddingBottom
        }
        val visiblePages = ArrayList<LocalDocumentVisiblePage>(recyclerView.childCount)
        repeat(recyclerView.childCount) { childIndex ->
            val child = recyclerView.getChildAt(childIndex) ?: return@repeat
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) return@repeat
            val childStart = if (horizontalMode) child.left else child.top
            val childEnd = if (horizontalMode) child.right else child.bottom
            val visiblePixels = minOf(childEnd, viewportEnd) - maxOf(childStart, viewportStart)
            if (visiblePixels > 0) {
                visiblePages += LocalDocumentVisiblePage(position, visiblePixels)
            }
        }
        val fallback = manager.findFirstVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: pageIndex
        return dominantLocalDocumentPageIndex(
            visiblePages,
            fallbackIndex = fallback,
            pageCount = pageCount
        )
    }

    private fun applyPageOrientation(retainedPage: Int? = null) {
        val manager = LinearLayoutManager(
            this,
            if (horizontalMode) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL,
            false
        )
        binding.localComicReadPages.layoutManager = manager
        pageAdapter.updatePageLayoutMode(localComicPageLayoutMode(horizontalMode))
        horizontalPageSnapHelper.attachToRecyclerView(
            binding.localComicReadPages.takeIf { horizontalMode }
        )
        val count = pageAdapter.itemCount
        if (retainedPage == null || count <= 0) return
        val page = retainedPage.coerceIn(0, count - 1)
        pageIndex = page
        manager.scrollToPositionWithOffset(page, 0)
        updatePageState()
        binding.localComicReadPages.post {
            if (binding.localComicReadPages.layoutManager !== manager) return@post
            manager.scrollToPositionWithOffset(page, 0)
            updateVisiblePage(binding.localComicReadPages)
        }
    }

    private fun renderDirectionMode() {
        binding.localComicReadDirection.isSelected = horizontalMode
        binding.localComicReadDirection.setText(
            if (horizontalMode) {
                R.string.local_comic_horizontal_mode
            } else {
                R.string.local_comic_vertical_mode
            }
        )
    }

    private fun updatePageState() {
        renderPageState(localDocumentPageLabel(pageIndex, documentPageCount))
    }

    private fun retryPageLoads() {
        val pages = pageAdapter.items.toList()
        if (pages.isEmpty()) return
        val retainedPage = pageIndex.coerceIn(0, pages.lastIndex)
        val generation = ++loadGeneration
        pageLoadState = LocalComicPageLoadState.EMPTY
        isDocumentReady = false
        pageAdapter.refreshItemsForGeneration(generation, pages)
        binding.localComicReadPages.scrollToPosition(retainedPage)
        refreshPageRetryState()
    }

    private fun refreshPageRetryState() {
        val retryState = pageLoadState.retryState(
            visiblePageIndex = pageIndex,
            pageCount = documentPageCount
        )
        binding.localComicReadPageRetry.visibility = if (retryState.visible) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.localComicReadPageRetry.text = retryState.message
        binding.localComicReadPageRetry.contentDescription = retryState.message
        binding.localComicReadPageRetry.isEnabled = retryState.visible
    }

    private fun renderPageState(label: String) {
        binding.localComicReadState.text = label
        binding.localComicReadProgress.text = label
    }

    private fun showArchiveFailure(message: String) {
        renderPageState(message)
        binding.localComicReadPageRetry.visibility = View.GONE
        binding.localComicReadErrorMessage.text = message
        binding.localComicReadError.visibility = View.VISIBLE
        binding.localComicReadToolbar.visibility = View.GONE
        binding.localComicReadBottomPanel.visibility = View.GONE
        binding.localComicReadProgress.visibility = View.GONE
        MediaUiChrome.immersiveReader(this, controlsVisible = true)
    }

    private fun toggleControls() {
        if (binding.localComicReadError.visibility == View.VISIBLE) return
        setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.localComicReadToolbar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.localComicReadBottomPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.localComicReadProgress.visibility = if (visible) View.GONE else View.VISIBLE
        MediaUiChrome.immersiveReader(this, visible)
    }

    private fun persistReadingProgress() {
        if (!isDocumentReady || documentPageCount <= 0) return
        updateVisiblePage(binding.localComicReadPages)
        pageIndex = restoredLocalDocumentPageIndex(pageIndex, documentPageCount)
        if (!pageLoadState.canPersist(pageIndex, documentPageCount)) return
        progressStore.save(documentProgressId(), pageIndex)
    }

    private fun documentProgressId(): String = "$PROGRESS_DOCUMENT_TYPE|$uri"

    private fun directionDocumentId(): String {
        return documentProgressId().takeIf { uri != Uri.EMPTY }.orEmpty()
    }

    private fun persistDirectionMode() {
        ComicReaderModePreferences.saveLocalHorizontal(directionDocumentId(), horizontalMode)
    }

    private fun extractImages(
        outputDirectory: File,
        cancellationSignal: CancellationSignal,
        checkCancelled: () -> Unit
    ): ComicArchiveExtractionResult {
        return try {
            checkCancelled()
            cleanStaleExtractionDirectories(
                extractionRoot = checkNotNull(outputDirectory.parentFile),
                currentDirectory = outputDirectory,
                checkCancelled = checkCancelled
            )
            checkCancelled()
            if (!ComicArchiveStoragePolicy.hasEnoughSpace(cacheDir.usableSpace, archiveLimits)) {
                return ComicArchiveExtractionResult.Failure(
                    reason = ComicArchiveFailureReason.INSUFFICIENT_STORAGE,
                    message = ComicArchiveStoragePolicy.INSUFFICIENT_STORAGE_MESSAGE
                )
            }
            checkCancelled()
            contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
                ?.use { descriptor ->
                    descriptor.createInputStream().use { input ->
                        archiveExtractor.extract(
                            input = input,
                            outputDirectory = outputDirectory,
                            checkCancelled = checkCancelled
                        )
                    }
                } ?: ComicArchiveExtractionResult.Failure(
                reason = ComicArchiveFailureReason.IO_ERROR,
                message = "无法读取漫画文件"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: OperationCanceledException) {
            throw CancellationException("Comic archive open cancelled").also { it.initCause(error) }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to read local comic archive", error)
            ComicArchiveExtractionResult.Failure(
                reason = ComicArchiveFailureReason.IO_ERROR,
                message = "无法读取漫画文件"
            )
        }
    }

    private fun cleanStaleExtractionDirectories(
        extractionRoot: File,
        currentDirectory: File,
        checkCancelled: () -> Unit
    ) {
        val nowMillis = System.currentTimeMillis()
        val currentPath = currentDirectory.absolutePath
        extractionRoot.listFiles().orEmpty().forEach { candidate ->
            checkCancelled()
            val shouldDelete = candidate.isDirectory &&
                ComicArchiveCachePolicy.shouldDeleteDirectory(
                    candidateName = candidate.name,
                    candidatePath = candidate.absolutePath,
                    currentPath = currentPath,
                    lastModifiedMillis = candidate.lastModified(),
                    nowMillis = nowMillis
                )
            if (shouldDelete) {
                candidate.deleteRecursively()
            }
        }
    }

    companion object {
        private const val TAG = "LocalComicRead"
        private const val PROGRESS_DOCUMENT_TYPE = "local_comic"
        private const val STATE_PAGE_INDEX = "local_comic_page_index"
        private const val STATE_HORIZONTAL_MODE = "local_comic_horizontal_mode"

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, LocalComicReadActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}
