package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import android.view.View
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityPdfReadBinding
import com.ldp.reader.document.DocumentFileName
import com.ldp.reader.document.LocalDocumentPageProgressPersistence
import com.ldp.reader.document.LocalDocumentPageProgressStore
import com.ldp.reader.document.LocalDocumentVisiblePage
import com.ldp.reader.document.dominantLocalDocumentPageIndex
import com.ldp.reader.document.pdf.PdfReadPolicy
import com.ldp.reader.document.restoredLocalDocumentPageIndex
import com.ldp.reader.ui.adapter.PdfPageAdapter
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.utils.ReadingSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfReadActivity : BaseActivity<ActivityPdfReadBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val progressStore: LocalDocumentPageProgressStore by lazy {
        LocalDocumentPageProgressPersistence.store
    }
    private val readingSessionTracker = ReadingSessionTracker()
    private lateinit var uri: Uri
    private var renderer: PdfRenderer? = null
    private var documentPageCount = 0
    private var pageIndex = 0
    private var restoredPageIndex: Int? = null
    private var controlsVisible = false
    private var errorDialog: AlertDialog? = null
    private val notifiedPageFailures = HashSet<Int>()
    private val pageAdapter = PdfPageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        MediaUiChrome.prepareImmersiveReader(this)
        super.onCreate(savedInstanceState)
    }

    override fun getViewBinding(): ActivityPdfReadBinding {
        return ActivityPdfReadBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.pdfReadToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        toolbar?.setNavigationContentDescription(R.string.pdf_reader_navigate_back)
        MediaUiChrome.immersiveReader(this, controlsVisible)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        uri = intent.data ?: Uri.EMPTY
        restoredPageIndex = savedInstanceState
            ?.takeIf { it.containsKey(STATE_PAGE_INDEX) }
            ?.getInt(STATE_PAGE_INDEX)
            ?: progressStore.load(documentProgressId())
    }

    override fun initClick() {
        super.initClick()
        binding.pdfReadPrev.setOnClickListener { movePage(-1) }
        binding.pdfReadNext.setOnClickListener { movePage(1) }
        binding.pdfReadCatalog.setOnClickListener { showPageCatalog() }
        binding.root.setOnClickListener { setControlsVisible(!controlsVisible) }
    }

    override fun initWidget() {
        super.initWidget()
        binding.pdfReadPages.layoutManager = LinearLayoutManager(this)
        binding.pdfReadPages.setItemViewCacheSize(0)
        binding.pdfReadPages.adapter = pageAdapter
        MediaUiChrome.applyReaderInsets(
            this,
            binding.pdfReadToolbar,
            binding.pdfReadBottomPanel
        )
        MediaUiChrome.applyReaderProgressInsets(binding.pdfReadProgress)
        pageAdapter.onPageTap = { setControlsVisible(!controlsVisible) }
        pageAdapter.onPageRenderFailure = { position, message ->
            if (notifiedPageFailures.add(position)) {
                binding.root.post { showPageError(message) }
            }
        }
        binding.pdfReadPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateVisiblePage(recyclerView)
            }
        })
        setControlsVisible(false)
    }

    override fun processLogic() {
        super.processLogic()
        if (uri == Uri.EMPTY) {
            Log.w(TAG, "PDF reader launched without a document URI")
            showFatalError(PdfReadPolicy.UNREADABLE_DOCUMENT_MESSAGE)
            return
        }
        openDocument()
    }

    override fun onResume() {
        super.onResume()
        if (uri != Uri.EMPTY) {
            readingSessionTracker.onResume("local-document:pdf:$uri")
        }
    }

    override fun onPause() {
        persistReadingProgress()
        readingSessionTracker.onPause()
        super.onPause()
    }

    override fun onStop() {
        persistReadingProgress()
        readingSessionTracker.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        errorDialog?.dismiss()
        errorDialog = null
        binding.pdfReadPages.adapter = null
        pageAdapter.detach()
        pageAdapter.onPageTap = null
        pageAdapter.onPageRenderFailure = null
        renderer = null
        documentPageCount = 0
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (renderer != null && documentPageCount > 0) {
            updateVisiblePage(binding.pdfReadPages)
            outState.putInt(STATE_PAGE_INDEX, pageIndex)
            persistReadingProgress()
        } else {
            restoredPageIndex?.let { outState.putInt(STATE_PAGE_INDEX, it) }
        }
        super.onSaveInstanceState(outState)
    }

    private fun persistReadingProgress() {
        if (renderer == null || documentPageCount <= 0) return
        updateVisiblePage(binding.pdfReadPages)
        pageIndex = restoredPdfPageIndex(pageIndex, documentPageCount)
        progressStore.save(documentProgressId(), pageIndex)
    }

    private fun documentProgressId(): String = "$PROGRESS_DOCUMENT_TYPE|$uri"

    private fun updatePageState() {
        if (renderer == null) return
        val state = PdfReadPolicy.pageState(pageIndex, documentPageCount) ?: return
        pageIndex = state.index
        val label = "${state.number} / ${state.count}"
        binding.pdfReadPageNumber.text = label
        binding.pdfReadProgress.text = label
        binding.pdfReadPrev.isEnabled = state.hasPrevious
        binding.pdfReadNext.isEnabled = state.hasNext
        binding.pdfReadPrev.alpha = if (state.hasPrevious) 1f else 0.42f
        binding.pdfReadNext.alpha = if (state.hasNext) 1f else 0.42f
    }

    private fun updateVisiblePage(recyclerView: RecyclerView) {
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val pageCount = pageAdapter.itemCount
        if (pageCount <= 0) return
        val viewportTop = recyclerView.paddingTop
        val viewportBottom = recyclerView.height - recyclerView.paddingBottom
        val visiblePages = ArrayList<PdfVisiblePage>(recyclerView.childCount)
        repeat(recyclerView.childCount) { childIndex ->
            val child = recyclerView.getChildAt(childIndex) ?: return@repeat
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) return@repeat
            val visiblePixels = minOf(child.bottom, viewportBottom) -
                maxOf(child.top, viewportTop)
            if (visiblePixels > 0) {
                visiblePages += PdfVisiblePage(position, visiblePixels)
            }
        }
        val fallback = manager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: pageIndex
        val visibleIndex = dominantPdfPageIndex(visiblePages, fallback, pageCount)
        if (visibleIndex != pageIndex) {
            pageIndex = visibleIndex
            updatePageState()
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.pdfReadToolbar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.pdfReadBottomPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.pdfReadProgress.visibility = if (visible) View.GONE else View.VISIBLE
        MediaUiChrome.immersiveReader(this, visible)
    }

    private fun showPageCatalog() {
        if (renderer == null) return
        val pageCount = documentPageCount
        if (pageCount <= 0) return
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = pageCount
            value = PdfReadPolicy.pageState(pageIndex, pageCount)?.number ?: 1
            wrapSelectorWheel = false
        }
        AlertDialog.Builder(this)
            .setTitle("跳转页码（共 $pageCount 页）")
            .setView(picker)
            .setNegativeButton("取消", null)
            .setPositiveButton("跳转") { dialog, _ ->
                val pageLayoutManager = binding.pdfReadPages.layoutManager
                    as? LinearLayoutManager
                pageIndex = applyPdfPageTarget(
                    targetPageIndex = picker.value - 1,
                    pageCount = pageCount,
                    alignPageTop = { target, offset ->
                        pageLayoutManager?.scrollToPositionWithOffset(target, offset)
                    },
                    whenViewportReady = { action ->
                        binding.pdfReadPages.doOnNextLayout {
                            if (
                                !isFinishing &&
                                !isDestroyed &&
                                renderer != null &&
                                binding.pdfReadPages.layoutManager === pageLayoutManager
                            ) {
                                action()
                            }
                        }
                    }
                )
                updatePageState()
                dialog.dismiss()
                setControlsVisible(false)
            }
            .show()
    }

    private fun openDocument() {
        scope.launch(Dispatchers.IO) {
            val openContext = currentCoroutineContext()
            val cancellationSignal = CancellationSignal()
            val cancellationWatcher = launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    cancellationSignal.cancel()
                }
            }
            var openedToClose: PdfOpenResult.Success? = null
            try {
                val title = try {
                    DocumentFileName.displayName(applicationContext, uri, cancellationSignal)
                } catch (error: OperationCanceledException) {
                    openContext.ensureActive()
                    throw CancellationException("PDF title query cancelled").also { it.initCause(error) }
                }
                openContext.ensureActive()
                when (val result = openDocumentBlocking(cancellationSignal) { openContext.ensureActive() }) {
                    is PdfOpenResult.Failure -> {
                        withContext(Dispatchers.Main) {
                            if (isActive && !isFinishing && !isDestroyed) {
                                binding.pdfReadTitle.text = title
                                showFatalError(result.message)
                            }
                        }
                    }
                    is PdfOpenResult.Success -> {
                        openedToClose = result
                        if (!isActive) return@launch
                        withContext(Dispatchers.Main) {
                            if (!isActive || isFinishing || isDestroyed) return@withContext
                            binding.pdfReadTitle.text = title
                            val savedPageIndex = restoredPageIndex
                            pageAdapter.attach(
                                result.renderer,
                                result.pageCount,
                                resources.displayMetrics.widthPixels
                            )
                            renderer = result.renderer
                            openedToClose = null
                            documentPageCount = result.pageCount
                            val pageLayoutManager = binding.pdfReadPages.layoutManager
                                as? LinearLayoutManager
                            pageIndex = applyPdfPageRestore(
                                savedPageIndex = savedPageIndex,
                                pageCount = result.pageCount,
                                alignPageTop = { target, offset ->
                                    pageLayoutManager?.scrollToPositionWithOffset(target, offset)
                                },
                                whenViewportReady = { action ->
                                    binding.pdfReadPages.doOnNextLayout {
                                        if (
                                            !isFinishing &&
                                            !isDestroyed &&
                                            renderer === result.renderer &&
                                            binding.pdfReadPages.layoutManager === pageLayoutManager
                                        ) {
                                            action()
                                        }
                                    }
                                }
                            )
                            restoredPageIndex = null
                            updatePageState()
                        }
                    }
                }
            } finally {
                cancellationWatcher.cancel()
                openedToClose?.let { closeRendererAfterOpenFailure(it.renderer) }
            }
        }
    }

    private fun openDocumentBlocking(
        cancellationSignal: CancellationSignal,
        checkCancelled: () -> Unit
    ): PdfOpenResult {
        val openedDescriptor = try {
            contentResolver.openFileDescriptor(uri, "r", cancellationSignal)
        } catch (error: OperationCanceledException) {
            throw CancellationException("PDF open cancelled").also { it.initCause(error) }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to open PDF source", error)
            return PdfOpenResult.Failure(PdfReadPolicy.sourceOpenFailure(error))
        }
        if (openedDescriptor == null) {
            Log.w(TAG, "PDF source returned no file descriptor")
            return PdfOpenResult.Failure(PdfReadPolicy.UNREADABLE_DOCUMENT_MESSAGE)
        }
        try {
            checkCancelled()
        } catch (error: CancellationException) {
            closeDescriptorAfterOpenFailure(openedDescriptor)
            throw error
        }
        if (PdfReadPolicy.isDefinitelyEmpty(openedDescriptor.statSize)) {
            Log.w(TAG, "PDF source is empty")
            try {
                openedDescriptor.close()
            } catch (_: Exception) {
                // The empty-file result is already known and shown below.
            }
            return PdfOpenResult.Failure(PdfReadPolicy.EMPTY_DOCUMENT_MESSAGE)
        }
        val openedRenderer = try {
            PdfRenderer(openedDescriptor)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to create PDF renderer", error)
            closeDescriptorAfterOpenFailure(openedDescriptor)
            return PdfOpenResult.Failure(PdfReadPolicy.documentOpenFailure(error))
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Unable to create PDF renderer because memory is exhausted", error)
            closeDescriptorAfterOpenFailure(openedDescriptor)
            return PdfOpenResult.Failure(PdfReadPolicy.documentOpenFailure(error))
        }
        val pageCount = try {
            openedRenderer.pageCount
        } catch (error: Exception) {
            Log.w(TAG, "Unable to read PDF page count", error)
            closeRendererAfterOpenFailure(openedRenderer)
            return PdfOpenResult.Failure(PdfReadPolicy.documentOpenFailure(error))
        }
        if (pageCount <= 0) {
            Log.w(TAG, "PDF renderer returned no pages: count=$pageCount")
            closeRendererAfterOpenFailure(openedRenderer)
            return PdfOpenResult.Failure(PdfReadPolicy.NO_PAGES_MESSAGE)
        }
        if (!PdfReadPolicy.isSupportedPageCount(pageCount)) {
            Log.w(TAG, "PDF page count exceeds reader limit: count=$pageCount")
            closeRendererAfterOpenFailure(openedRenderer)
            return PdfOpenResult.Failure(PdfReadPolicy.TOO_MANY_PAGES_MESSAGE)
        }
        try {
            checkCancelled()
        } catch (error: CancellationException) {
            closeRendererAfterOpenFailure(openedRenderer)
            throw error
        }
        return PdfOpenResult.Success(openedRenderer, pageCount)
    }

    private fun movePage(delta: Int) {
        if (renderer == null) return
        val state = PdfReadPolicy.move(pageIndex, documentPageCount, delta) ?: return
        if (state.index == pageIndex) return
        pageIndex = state.index
        binding.pdfReadPages.smoothScrollToPosition(pageIndex)
        updatePageState()
    }

    private fun showFatalError(message: String) {
        errorDialog?.dismiss()
        errorDialog = AlertDialog.Builder(this)
            .setTitle("无法打开 PDF")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("返回") { _, _ -> finish() }
            .show()
    }

    private fun showPageError(message: String) {
        if (isFinishing || isDestroyed || errorDialog?.isShowing == true) return
        errorDialog = AlertDialog.Builder(this)
            .setTitle("页面加载失败")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun closeDescriptorAfterOpenFailure(descriptor: android.os.ParcelFileDescriptor) {
        try {
            descriptor.close()
        } catch (_: Exception) {
            // PdfRenderer may already have taken ownership and closed it.
        }
    }

    private fun closeRendererAfterOpenFailure(pdfRenderer: PdfRenderer) {
        try {
            pdfRenderer.close()
        } catch (_: Exception) {
            // The document is unusable and no further operation is attempted.
        }
    }

    companion object {
        private const val TAG = "PdfReadActivity"
        private const val PROGRESS_DOCUMENT_TYPE = "pdf"
        private const val STATE_PAGE_INDEX = "pdf_page_index"

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, PdfReadActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    private sealed interface PdfOpenResult {
        data class Success(
            val renderer: PdfRenderer,
            val pageCount: Int
        ) : PdfOpenResult

        data class Failure(val message: String) : PdfOpenResult
    }
}

internal fun restoredPdfPageIndex(savedPageIndex: Int?, pageCount: Int): Int {
    return restoredLocalDocumentPageIndex(savedPageIndex, pageCount)
}

internal fun applyPdfPageRestore(
    savedPageIndex: Int?,
    pageCount: Int,
    alignPageTop: (index: Int, offset: Int) -> Unit,
    whenViewportReady: (() -> Unit) -> Unit
): Int {
    return applyPdfPageTarget(
        targetPageIndex = restoredPdfPageIndex(savedPageIndex, pageCount),
        pageCount = pageCount,
        alignPageTop = alignPageTop,
        whenViewportReady = whenViewportReady
    )
}

internal fun applyPdfPageTarget(
    targetPageIndex: Int,
    pageCount: Int,
    alignPageTop: (index: Int, offset: Int) -> Unit,
    whenViewportReady: (() -> Unit) -> Unit
): Int {
    val targetIndex = restoredPdfPageIndex(targetPageIndex, pageCount)
    alignPageTop(targetIndex, 0)
    whenViewportReady { alignPageTop(targetIndex, 0) }
    return targetIndex
}

internal data class PdfVisiblePage(
    val index: Int,
    val visiblePixels: Int
)

internal fun dominantPdfPageIndex(
    visiblePages: Iterable<PdfVisiblePage>,
    fallbackIndex: Int,
    pageCount: Int
): Int {
    return dominantLocalDocumentPageIndex(
        visiblePages = visiblePages.map { page ->
            LocalDocumentVisiblePage(page.index, page.visiblePixels)
        },
        fallbackIndex = fallbackIndex,
        pageCount = pageCount
    )
}
