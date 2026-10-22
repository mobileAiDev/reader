package com.ldp.reader.ui.activity

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityEpubReadBinding
import com.ldp.reader.document.DocumentFileName
import com.ldp.reader.document.epub.EpubDocument
import com.ldp.reader.document.epub.EpubCatalogEntry
import com.ldp.reader.document.epub.EpubPackageParser
import com.ldp.reader.document.epub.EpubParseException
import com.ldp.reader.document.epub.EpubUserMessage
import com.ldp.reader.document.epub.EpubReadingProgress
import com.ldp.reader.document.epub.EpubReadingProgressPersistence
import com.ldp.reader.document.epub.EpubReadingProgressStore
import com.ldp.reader.document.epub.epubMaxScrollY
import com.ldp.reader.document.epub.restoredEpubScrollY
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.glass.GlassStyle
import com.ldp.reader.ui.reader.FloatingBar
import com.ldp.reader.ui.reader.FloatingReaderChrome
import com.ldp.reader.ui.theme.AppearanceMode
import com.ldp.reader.utils.ReadingSessionTracker
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException

class EpubReadActivity : BaseActivity<ActivityEpubReadBinding>() {
    private lateinit var topBar: FloatingBar
    private lateinit var bottomBar: FloatingBar
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val progressStore: EpubReadingProgressStore by lazy {
        EpubReadingProgressPersistence.store
    }
    private val readingSessionTracker = ReadingSessionTracker()
    private val readerPreferences by lazy { MMKV.mmkvWithID(READER_PREFERENCES) }
    private lateinit var uri: Uri
    private var textZoom = 100
    private var pendingReadingProgress: EpubReadingProgress? = null
    private var isReadingContentReady = false
    private var isRestoreScheduled = false
    private var restoreRequestId = 0L
    private var controlsVisible = false
    private var fontPanelVisible = false
    private var readerTheme = EpubReaderTheme.PAPER
    private var textReflowRequestId = 0L
    private var controlsAccessibilityActionId = View.NO_ID

    @Volatile
    private var document: EpubDocument? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        MediaUiChrome.prepareImmersiveReader(this)
        super.onCreate(savedInstanceState)
    }

    override fun getViewBinding(): ActivityEpubReadBinding {
        return ActivityEpubReadBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.epubReadToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        toolbar?.navigationIcon?.mutate()?.setTint(Color.WHITE)
        toolbar?.setNavigationContentDescription(R.string.epub_reader_navigate_back)
        MediaUiChrome.immersiveReader(this, controlsVisible, readerTheme.lightPage)
    }

    override fun initData(savedInstanceState: Bundle?) {
        super.initData(savedInstanceState)
        uri = intent.data ?: Uri.EMPTY
        pendingReadingProgress = savedInstanceState?.restoredReadingProgress()
            ?: progressStore.load(uri.toString())
        textZoom = pendingReadingProgress?.textZoom?.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
            ?: DEFAULT_TEXT_ZOOM
        val savedThemeOrdinal = savedInstanceState
            ?.takeIf { it.containsKey(STATE_READER_THEME) }
            ?.getInt(STATE_READER_THEME)
            ?: readerPreferences.decodeInt(
                PREFERENCE_READER_THEME,
                EpubReaderTheme.PAPER.ordinal
            )
        // Dark mode always opens on the night page; the saved day theme returns in light mode.
        readerTheme = if (AppearanceMode.isNight(this)) {
            EpubReaderTheme.NIGHT
        } else {
            EpubReaderTheme.fromSavedOrdinal(savedThemeOrdinal)
        }
    }

    override fun initWidget() {
        super.initWidget()
        FloatingReaderChrome.keepClear(binding.epubReadTopBar, atTop = true)
        FloatingReaderChrome.keepClear(binding.epubReadBottomPanel, atTop = false)
        binding.epubReadTopBar.glass.style = GlassStyle.mediaReader(this)
        binding.epubReadBottomPanel.glass.style = GlassStyle.mediaReader(this)
        topBar = FloatingBar(binding.epubReadTopBar, binding.root, fromTop = true)
        bottomBar = FloatingBar(binding.epubReadBottomPanel, binding.root, fromTop = false)
        MediaUiChrome.applyReaderCutoutInsets(binding.epubReadWeb)
        MediaUiChrome.applyReaderProgressInsets(binding.epubReadProgress)
        updateReaderThemeColors()
        binding.epubReadWeb.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateProgressLabel(scrollY)
        }
        showLoadingState()
        setControlsVisible(true)
    }

    override fun initClick() {
        super.initClick()
        binding.epubReadDecrease.setOnClickListener {
            changeTextZoom(-TEXT_ZOOM_STEP)
        }
        binding.epubReadIncrease.setOnClickListener {
            changeTextZoom(TEXT_ZOOM_STEP)
        }
        binding.epubReadCatalog.setOnClickListener { showCatalog() }
        binding.epubReadTheme.setOnClickListener { showThemePicker() }
        binding.epubReadFont.setOnClickListener {
            setFontPanelVisible(!fontPanelVisible)
        }
        installCenterTapToggle()
        installReaderControlsAccessibility()
    }

    override fun processLogic() {
        super.processLogic()
        if (uri == Uri.EMPTY) {
            binding.epubReadTitle.text = "EPUB 阅读"
            showLoadFailure("文件已失效，请重新选择")
            return
        }
        binding.epubReadWeb.settings.javaScriptEnabled = false
        binding.epubReadWeb.settings.builtInZoomControls = true
        binding.epubReadWeb.settings.displayZoomControls = false
        binding.epubReadWeb.settings.loadWithOverviewMode = true
        binding.epubReadWeb.settings.useWideViewPort = false
        binding.epubReadWeb.settings.textZoom = textZoom
        binding.epubReadWeb.settings.allowFileAccess = false
        binding.epubReadWeb.settings.allowContentAccess = false
        binding.epubReadWeb.settings.blockNetworkLoads = true
        binding.epubReadWeb.isVerticalScrollBarEnabled = true
        binding.epubReadWeb.webViewClient = epubWebViewClient()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val cancellationSignal = CancellationSignal()
                val cancellationWatcher = launch(Dispatchers.Default) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancellationSignal.cancel()
                    }
                }
                try {
                    loadDocument(
                        checkCancelled = { context.ensureActive() },
                        cancellationSignal = cancellationSignal
                    )
                } finally {
                    cancellationWatcher.cancel()
                }
            }
            binding.epubReadTitle.text = result.title.ifBlank { "EPUB 阅读" }
            when (result) {
                is EpubLoadResult.Success -> {
                    document = result.document
                    isReadingContentReady = false
                    binding.epubReadWeb.visibility = View.VISIBLE
                    binding.epubReadStatus.text = "正在排版 EPUB…"
                    binding.epubReadStatus.visibility = View.VISIBLE
                    binding.epubReadWeb.loadDataWithBaseURL(
                        EpubPackageParser.BASE_URL,
                        readableHtml(
                            result.document.headHtml,
                            result.document.bodyHtml,
                            readerTheme
                        ),
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
                is EpubLoadResult.Failure -> {
                    document = null
                    isReadingContentReady = false
                    showLoadFailure(result.message)
                }
            }
            applyTextZoom()
        }
    }

    override fun onResume() {
        super.onResume()
        if (uri != Uri.EMPTY) {
            readingSessionTracker.onResume("local-document:epub:$uri")
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

    override fun onSaveInstanceState(outState: Bundle) {
        readingProgressForLifecycle()?.let { progress ->
            outState.putInt(STATE_SCROLL_Y, progress.scrollY)
            outState.putInt(STATE_MAX_SCROLL_Y, progress.maxScrollY)
            outState.putInt(STATE_TEXT_ZOOM, progress.textZoom)
            saveReadingProgress(progress)
        }
        outState.putInt(STATE_READER_THEME, readerTheme.ordinal)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            MediaUiChrome.immersiveReader(this, controlsVisible, readerTheme.lightPage)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        binding.epubReadWeb.destroy()
        super.onDestroy()
    }

    private fun loadDocument(
        checkCancelled: () -> Unit,
        cancellationSignal: CancellationSignal
    ): EpubLoadResult {
        var title = ""
        return try {
            checkCancelled()
            title = DocumentFileName.displayName(applicationContext, uri, cancellationSignal)
            val parsed = contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
                ?.use { descriptor ->
                    descriptor.createInputStream().use { input ->
                        EpubPackageParser().parse(input, checkCancelled)
                    }
                } ?: return EpubLoadResult.Failure(title, "无法打开 EPUB 文件")
            checkCancelled()
            EpubLoadResult.Success(title, parsed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: OperationCanceledException) {
            throw CancellationException("EPUB open cancelled").also { it.initCause(error) }
        } catch (error: SecurityException) {
            EpubLoadResult.Failure(title, "没有读取 EPUB 文件的权限")
        } catch (error: EpubParseException) {
            EpubLoadResult.Failure(title, EpubUserMessage.forParseError(error.reason))
        } catch (error: IOException) {
            EpubLoadResult.Failure(title, "无法读取 EPUB 文件")
        } catch (error: RuntimeException) {
            EpubLoadResult.Failure(title, "文件已损坏或内容不完整")
        } catch (error: OutOfMemoryError) {
            EpubLoadResult.Failure(title, "文件过大，暂时无法打开")
        }
    }

    private fun applyTextZoom() {
        binding.epubReadWeb.settings.textZoom = textZoom
        binding.epubReadFontSize.text = "$textZoom%"
        binding.epubReadWeb.post { updateProgressLabel() }
    }

    private fun changeTextZoom(delta: Int) {
        val updatedZoom = (textZoom + delta).coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        if (updatedZoom == textZoom) return
        val progressBeforeReflow = readingProgressForLifecycle()
        textZoom = updatedZoom
        textReflowRequestId += 1L
        val requestId = textReflowRequestId
        applyTextZoom()
        if (progressBeforeReflow != null && isReadingContentReady) {
            binding.epubReadWeb.postDelayed({
                if (
                    requestId != textReflowRequestId ||
                    isFinishing ||
                    isDestroyed ||
                    document == null
                ) {
                    return@postDelayed
                }
                val restoredY = restoredEpubScrollY(
                    savedScrollY = progressBeforeReflow.scrollY,
                    savedMaxScrollY = progressBeforeReflow.maxScrollY,
                    currentMaxScrollY = currentMaxScrollY(binding.epubReadWeb)
                )
                binding.epubReadWeb.scrollTo(0, restoredY)
                updateProgressLabel(restoredY)
                persistReadingProgress()
            }, TEXT_REFLOW_DELAY_MS)
        }
    }

    private fun restoreReadingProgress(view: WebView) {
        if (isReadingContentReady || isRestoreScheduled || document == null) return
        isRestoreScheduled = true
        restoreRequestId += 1L
        view.postVisualStateCallback(
            restoreRequestId,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    view.post {
                        if (requestId != restoreRequestId) return@post
                        isRestoreScheduled = false
                        if (isFinishing || isDestroyed || document == null) return@post
                        val progress = pendingReadingProgress
                        if (progress != null) {
                            val maxScrollY = currentMaxScrollY(view)
                            view.scrollTo(
                                0,
                                restoredEpubScrollY(
                                    savedScrollY = progress.scrollY,
                                    savedMaxScrollY = progress.maxScrollY,
                                    currentMaxScrollY = maxScrollY
                                )
                            )
                        }
                        pendingReadingProgress = null
                        isReadingContentReady = true
                        updateProgressLabel()
                        persistReadingProgress()
                    }
                }
            }
        )
    }

    private fun persistReadingProgress() {
        readingProgressForLifecycle()?.let(::saveReadingProgress)
    }

    private fun saveReadingProgress(progress: EpubReadingProgress) {
        if (uri == Uri.EMPTY) return
        progressStore.save(uri.toString(), progress)
    }

    private fun readingProgressForLifecycle(): EpubReadingProgress? {
        if (!isReadingContentReady || document == null) return pendingReadingProgress
        val maxScrollY = currentMaxScrollY(binding.epubReadWeb)
        return EpubReadingProgress(
            scrollY = restoredEpubScrollY(binding.epubReadWeb.scrollY, maxScrollY),
            maxScrollY = maxScrollY,
            textZoom = textZoom
        )
    }

    private fun currentMaxScrollY(view: WebView): Int {
        return epubMaxScrollY(
            contentHeightCss = view.contentHeight,
            webViewScale = view.scale,
            viewportHeight = view.height
        )
    }

    private fun Bundle.restoredReadingProgress(): EpubReadingProgress? {
        if (!containsKey(STATE_SCROLL_Y)) return null
        return EpubReadingProgress(
            scrollY = getInt(STATE_SCROLL_Y),
            maxScrollY = getInt(STATE_MAX_SCROLL_Y),
            textZoom = getInt(STATE_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
        )
    }

    private fun readableHtml(head: String, body: String, theme: EpubReaderTheme): String {
        return """
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://epub.local; style-src 'unsafe-inline' https://epub.local; font-src data: https://epub.local" />
              $head
              <style>
                html, body { background:${theme.backgroundCss} !important; color:${theme.foregroundCss} !important; }
                body { box-sizing:border-box; min-height:100vh; margin:0; padding:30px 22px 88px; line-height:1.78; font-family:sans-serif; overflow-wrap:anywhere; }
                body, body * { color:${theme.foregroundCss} !important; }
                a { color:${theme.accentCss} !important; }
                img { max-width:100%; height:auto; }
                p { margin:0 0 1em; }
                .epub-section { max-width:46rem; margin:0 auto; background:${theme.backgroundCss} !important; }
                .epub-anchor-proxy { display:block; width:0; height:0; overflow:hidden; }
                hr { height:1px; margin:2.25em auto; border:0; background:${theme.dividerCss}; }
              </style>
            </head>
            <body>$body</body>
            </html>
        """.trimIndent()
    }

    private fun epubWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (view === binding.epubReadWeb && document != null) {
                    binding.epubReadStatus.visibility = View.GONE
                    binding.epubReadWeb.visibility = View.VISIBLE
                    restoreReadingProgress(view)
                    updateProgressLabel()
                    setControlsVisible(false)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val url = request.url
                val isCombinedDocumentFragment =
                    url.scheme == "https" &&
                        url.host == EPUB_HOST &&
                        url.path.orEmpty() == "/" &&
                        !url.fragment.isNullOrBlank()
                return !isCombinedDocumentFragment
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.url.scheme != "https" || request.url.host != EPUB_HOST) {
                    return emptyWebResponse()
                }
                val path = Uri.decode(request.url.encodedPath.orEmpty().removePrefix("/"))
                val resource = document?.resources?.get(path) ?: return emptyWebResponse()
                if (
                    resource.mediaType == "application/xhtml+xml" ||
                    resource.mediaType == "text/html"
                ) {
                    return emptyWebResponse()
                }
                val encoding = if (
                    resource.mediaType.startsWith("text/") ||
                    resource.mediaType.contains("xml")
                ) {
                    "UTF-8"
                } else {
                    null
                }
                return WebResourceResponse(
                    resource.mediaType,
                    encoding,
                    ByteArrayInputStream(resource.bytes)
                )
            }
        }
    }

    private fun emptyWebResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installCenterTapToggle() {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    val webView = binding.epubReadWeb
                    val insideCenter =
                        event.x in webView.width * CENTER_TAP_START..webView.width * CENTER_TAP_END &&
                            event.y in webView.height * CENTER_TAP_START..webView.height * CENTER_TAP_END
                    val targetsLink = epubHitTargetsLink(webView.hitTestResult?.type)
                    if (
                        EpubReaderUiPolicy.shouldToggleControls(
                            hasReadableDocument = hasReadableDocument(),
                            insideCenter = insideCenter,
                            targetsLink = targetsLink
                        )
                    ) {
                        setControlsVisible(!controlsVisible)
                        return true
                    }
                    return false
                }

                // GestureDetector withholds onSingleTapConfirmed when this second tap arrives,
                // leaving WebView's native double-tap zoom behavior untouched.
                override fun onDoubleTap(event: MotionEvent): Boolean = false
            }
        )
        binding.epubReadWeb.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    private fun installReaderControlsAccessibility() {
        binding.epubReadWeb.setOnKeyListener { _, keyCode, event ->
            val isToggleKey = keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_SPACE ||
                keyCode == KeyEvent.KEYCODE_MENU
            val targetsLink = epubHitTargetsLink(binding.epubReadWeb.hitTestResult?.type)
            if (!isToggleKey || !hasReadableDocument() || targetsLink) {
                return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_UP) {
                setControlsVisible(!controlsVisible)
            }
            true
        }
        updateReaderControlsAccessibilityAction(hasReadableDocument())
    }

    private fun updateReaderControlsAccessibilityAction(hasReadableDocument: Boolean) {
        if (controlsAccessibilityActionId != View.NO_ID) {
            ViewCompat.removeAccessibilityAction(
                binding.epubReadWeb,
                controlsAccessibilityActionId
            )
            controlsAccessibilityActionId = View.NO_ID
        }
        if (!hasReadableDocument) return
        val label = getString(
            if (controlsVisible) {
                R.string.epub_reader_hide_controls
            } else {
                R.string.epub_reader_show_controls
            }
        )
        controlsAccessibilityActionId = ViewCompat.addAccessibilityAction(
            binding.epubReadWeb,
            label
        ) { _, _ ->
            if (!hasReadableDocument()) {
                false
            } else {
                setControlsVisible(!controlsVisible)
                true
            }
        }
    }

    private fun hasReadableDocument(): Boolean {
        return document != null && binding.epubReadStatus.visibility != View.VISIBLE
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val hasReadableDocument = hasReadableDocument()
        topBar.setShown(visible)
        bottomBar.setShown(visible && hasReadableDocument)
        binding.epubReadProgress.visibility =
            if (!visible && hasReadableDocument) View.VISIBLE else View.GONE
        if (!visible || !hasReadableDocument) {
            setFontPanelVisible(false)
        }
        updateReaderControlsAccessibilityAction(hasReadableDocument)
        MediaUiChrome.immersiveReader(this, visible, readerTheme.lightPage)
    }

    private fun setFontPanelVisible(visible: Boolean) {
        fontPanelVisible = visible
        binding.epubReadFontPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.epubReadFont.isSelected = visible
    }

    private fun showCatalog() {
        val currentDocument = document ?: return
        val catalog = currentDocument.catalogEntries
        if (catalog.isEmpty()) return
        val webView = binding.epubReadWeb
        webView.settings.javaScriptEnabled = true
        webView.evaluateJavascript(EpubReaderUiPolicy.currentSectionScript()) { rawSectionIndex ->
            webView.settings.javaScriptEnabled = false
            if (isFinishing || isDestroyed || document !== currentDocument) {
                return@evaluateJavascript
            }
            val currentSectionIndex = rawSectionIndex
                .trim()
                .trim('"')
                .toIntOrNull()
                ?: -1
            showCatalogDialog(
                catalog = catalog,
                checkedEntryIndex = EpubReaderUiPolicy.checkedCatalogEntryIndex(
                    catalog,
                    currentSectionIndex
                )
            )
        }
    }

    private fun showCatalogDialog(
        catalog: List<EpubCatalogEntry>,
        checkedEntryIndex: Int
    ) {
        val labels = catalog.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("目录 · ${labels.size} 项")
            .setSingleChoiceItems(labels, checkedEntryIndex) { dialog, index ->
                dialog.dismiss()
                val webView = binding.epubReadWeb
                webView.settings.javaScriptEnabled = true
                val target = catalog[index]
                webView.evaluateJavascript(
                    EpubReaderUiPolicy.catalogJumpScript(target.sectionIndex, target.targetId)
                ) { didJump ->
                    webView.settings.javaScriptEnabled = false
                    if (didJump == "true") {
                        webView.post {
                            updateProgressLabel()
                            persistReadingProgress()
                        }
                    }
                }
                setControlsVisible(false)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showThemePicker() {
        val themes = EpubReaderTheme.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("阅读主题")
            .setSingleChoiceItems(
                themes.map { it.displayName }.toTypedArray(),
                readerTheme.ordinal
            ) { dialog, which ->
                dialog.dismiss()
                applyReaderTheme(themes[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyReaderTheme(theme: EpubReaderTheme) {
        if (theme == readerTheme) {
            setControlsVisible(false)
            return
        }
        val currentDocument = document
        val progressBeforeReload = readingProgressForLifecycle()
        readerTheme = theme
        readerPreferences.encode(PREFERENCE_READER_THEME, theme.ordinal)
        updateReaderThemeColors()
        if (currentDocument == null) return

        pendingReadingProgress = progressBeforeReload
        isReadingContentReady = false
        isRestoreScheduled = false
        restoreRequestId += 1L
        binding.epubReadStatus.text = "正在应用${theme.displayName}主题…"
        binding.epubReadStatus.visibility = View.VISIBLE
        binding.epubReadWeb.loadDataWithBaseURL(
            EpubPackageParser.BASE_URL,
            readableHtml(currentDocument.headHtml, currentDocument.bodyHtml, theme),
            "text/html",
            "UTF-8",
            null
        )
        setControlsVisible(false)
    }

    private fun updateReaderThemeColors() {
        val background = Color.parseColor(readerTheme.backgroundCss)
        val foreground = Color.parseColor(readerTheme.foregroundCss)
        binding.root.setBackgroundColor(background)
        binding.epubReadWeb.setBackgroundColor(background)
        binding.epubReadStatus.setTextColor(foreground)
        binding.epubReadProgress.setTextColor(ColorUtils.setAlphaComponent(foreground, 0xA6))
    }

    private fun showLoadingState() {
        binding.epubReadStatus.text = "正在打开 EPUB…"
        binding.epubReadStatus.visibility = View.VISIBLE
        binding.epubReadWeb.visibility = View.INVISIBLE
        binding.epubReadProgress.visibility = View.GONE
    }

    private fun showLoadFailure(message: String) {
        binding.epubReadWeb.visibility = View.INVISIBLE
        binding.epubReadStatus.text = "无法打开 EPUB\n\n$message"
        binding.epubReadStatus.visibility = View.VISIBLE
        setControlsVisible(true)
    }

    private fun updateProgressLabel(scrollY: Int = binding.epubReadWeb.scrollY) {
        if (document == null) return
        val percent = EpubReaderUiPolicy.readingPercent(
            scrollY,
            currentMaxScrollY(binding.epubReadWeb)
        )
        binding.epubReadProgress.text = "$percent%"
    }

    private sealed interface EpubLoadResult {
        val title: String

        data class Success(
            override val title: String,
            val document: EpubDocument
        ) : EpubLoadResult

        data class Failure(
            override val title: String,
            val message: String
        ) : EpubLoadResult
    }

    companion object {
        private const val EPUB_HOST = "epub.local"
        private const val DEFAULT_TEXT_ZOOM = 100
        private const val MIN_TEXT_ZOOM = 80
        private const val MAX_TEXT_ZOOM = 160
        private const val TEXT_ZOOM_STEP = 10
        private const val TEXT_REFLOW_DELAY_MS = 180L
        private const val CENTER_TAP_START = 0.2f
        private const val CENTER_TAP_END = 0.8f
        private const val READER_PREFERENCES = "epub_reader_preferences"
        private const val PREFERENCE_READER_THEME = "reader_theme"
        private const val STATE_SCROLL_Y = "epub_scroll_y"
        private const val STATE_MAX_SCROLL_Y = "epub_max_scroll_y"
        private const val STATE_TEXT_ZOOM = "epub_text_zoom"
        private const val STATE_READER_THEME = "epub_reader_theme"

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, EpubReadActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}

private fun epubHitTargetsLink(hitType: Int?): Boolean {
    return when (hitType) {
        WebView.HitTestResult.SRC_ANCHOR_TYPE,
        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
        WebView.HitTestResult.PHONE_TYPE,
        WebView.HitTestResult.EMAIL_TYPE,
        WebView.HitTestResult.GEO_TYPE -> true
        else -> false
    }
}

private enum class EpubReaderTheme(
    val displayName: String,
    val backgroundCss: String,
    val foregroundCss: String,
    val accentCss: String,
    val dividerCss: String,
    /** A light page takes dark status and navigation icons. */
    val lightPage: Boolean
) {
    // Matches the novel reader's paper and night pages. Night text sits at ~6.5:1 contrast
    // (see ComfortContrast) instead of near-white, which glares in the dark.
    PAPER("纸张", "#F4F0E6", "#2A2824", "#A8442A", "rgba(42,40,36,0.14)", true),
    LIGHT("明亮", "#FAFAF8", "#1D1C1A", "#A8442A", "rgba(29,28,26,0.12)", true),
    NIGHT("夜间", "#101010", "#999791", "#C98A70", "rgba(153,151,145,0.18)", false);

    companion object {
        fun fromSavedOrdinal(ordinal: Int): EpubReaderTheme {
            return values().getOrElse(ordinal) { PAPER }
        }
    }
}
