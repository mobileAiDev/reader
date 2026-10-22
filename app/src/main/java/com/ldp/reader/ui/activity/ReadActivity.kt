package com.ldp.reader.ui.activity

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.content.res.ColorStateList
import android.content.res.Configuration
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import com.ldp.reader.BuildConfig
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityReadBinding
import com.ldp.reader.diagnostics.ReaderProcessMemorySampler
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.local.ReadSettingManager
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.source.SourceEngineCatalogMarkRegistry
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.source.SourceEngineCachedCatalogMigration
import com.ldp.reader.source.SourceEngineCatalogPersistencePolicy
import com.ldp.reader.source.SourceEnginePersistedCatalogMarks
import com.ldp.reader.source.SourceEngineV8ScanPhase
import com.ldp.reader.source.hasHiddenSourceIntegrityMark
import com.ldp.reader.sourceengine.catalog.CatalogTitleEnricher
import com.ldp.reader.ui.activity.BookDetailActivity.Companion.startActivity
import com.ldp.reader.ui.activity.MainActivity
import com.ldp.reader.ui.adapter.CategoryAdapter
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.glass.GlassStyle
import com.ldp.reader.ui.motion.Haptics
import com.ldp.reader.ui.reader.FloatingBar
import com.ldp.reader.ui.reader.FloatingReaderChrome
import com.ldp.reader.ui.reader.ReadSettingPanel
import com.ldp.reader.ui.theme.AppearanceMode
import com.ldp.reader.ui.theme.ReaderChrome
import com.ldp.reader.ui.theme.ReaderPalette
import com.ldp.reader.widget.page.PageStyle
import com.ldp.reader.utils.BrightnessUtils
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.LogUtils
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.StringUtils
import com.ldp.reader.utils.ReadingSessionTracker
import com.ldp.reader.widget.page.PageLoader
import com.ldp.reader.widget.page.PageView.TouchListener
import com.ldp.reader.widget.page.ReaderCatalogTerminalCopy
import com.ldp.reader.widget.page.ReaderCatalogTerminalState
import com.ldp.reader.widget.page.ReaderChapterSelectionResolver
import com.ldp.reader.widget.page.ReaderConfigurationReadingAnchor
import com.ldp.reader.widget.page.TxtChapter

/**
 * Created by ldp on 17-5-16.
 */
class ReadActivity : BaseActivity<ActivityReadBinding>() {
    // 注册 Brightness 的 uri
    private val BRIGHTNESS_MODE_URI =
        Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE)
    private val BRIGHTNESS_URI = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
    private val BRIGHTNESS_ADJ_URI = Settings.System.getUriFor("screen_auto_brightness_adj")
    private var sourceIndex = 0
    private var showWrongChapters = true
    private var wrongAnalysisRunning = false
    private var wrongAnalysisProcessed = 0
    private var wrongAnalysisTarget = 0
    private var wrongAnalysisWrong = 0
    private var wrongAnalysisPhase: SourceEngineV8ScanPhase? = null

    /*****************view */
    private var mSettingDialog: ReadSettingPanel? = null
    private var mPageLoader: PageLoader? = null

    /** The bars' intended state; they may still be springing towards it. */
    private var isMenuVisible = false

    private lateinit var topMenuBar: FloatingBar
    private lateinit var bottomMenuBar: FloatingBar
    private var mCategoryAdapter: CategoryAdapter? = null
    private var mCollBook: CollBookBean? = null
    private var isDisplayCatalogReady = false
    private var persistedBookChaptersSnapshot: List<BookChapterBean> = emptyList()
    private val catalogTitleEnricher = CatalogTitleEnricher()
    private val readingSessionTracker = ReadingSessionTracker()
    private val processMemorySampler = if (BuildConfig.DEBUG) {
        ReaderProcessMemorySampler()
    } else {
        null
    }
    private var readActivityStartedAtMs = 0L
    private lateinit var viewModel: ReadViewModel

    //控制屏幕常亮
    private var mWakeLock: WakeLock? = null
    private val mHandler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                WHAT_CATEGORY -> binding!!.readIvCategory.setSelection(
                    mPageLoader!!.chapterPos
                )

                WHAT_CHAPTER -> {
                    Log.e(TAG, "WHAT_CHAPTER")
                    mPageLoader!!.openChapter()
                }
            }
        }
    }

    // 接收电池信息和时间更新的广播
    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra("level", 0)
                mPageLoader!!.updateBattery(level)
            } else if (intent.action == Intent.ACTION_TIME_TICK) {
                mPageLoader!!.updateTime()
            }
        }
    }

    // 亮度调节监听
    // 由于亮度调节没有 Broadcast 而是直接修改 ContentProvider 的。所以需要创建一个 Observer 来监听 ContentProvider 的变化情况。
    private val mBrightObserver: ContentObserver? = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange)

            // 判断当前是否跟随屏幕亮度，如果不是则返回
            if (selfChange || !mSettingDialog!!.isBrightFollowSystem) return

            // 如果系统亮度改变，则修改当前 Activity 亮度
            if (BRIGHTNESS_MODE_URI == uri) {
                Log.d(TAG, "亮度模式改变")
            } else if (BRIGHTNESS_URI == uri && !BrightnessUtils.isAutoBrightness(this@ReadActivity)) {
                Log.d(TAG, "亮度模式为手动模式 值改变")
                BrightnessUtils.setBrightness(
                    this@ReadActivity,
                    BrightnessUtils.getScreenBrightness(this@ReadActivity)
                )
            } else if (BRIGHTNESS_ADJ_URI == uri && BrightnessUtils.isAutoBrightness(this@ReadActivity)) {
                Log.d(TAG, "亮度模式为自动模式 值改变")
                BrightnessUtils.setDefaultBrightness(this@ReadActivity)
            } else {
                Log.d(TAG, "亮度调整 其他")
            }
        }
    }

    /***************params */
    private var isCollected = false // isFromSDCard
    private var isNightMode = false
    private var readerPalette: ReaderPalette? = null
    private var isRegistered = false
    private var mBookId: String? = null
    private var showWrongChaptersPrefKey: String? = null
    private var restoredConfigurationReadingAnchor: ReaderConfigurationReadingAnchor? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        MediaUiChrome.prepareImmersiveReader(this)
        super.onCreate(savedInstanceState)
    }

    override fun initData(savedInstanceState: Bundle?) {
        super.initData(savedInstanceState)
        mCollBook = intent.getParcelableExtra(EXTRA_COLL_BOOK)
        isCollected = intent.getBooleanExtra(EXTRA_IS_COLLECTED, false)
        @Suppress("DEPRECATION")
        restoredConfigurationReadingAnchor = savedInstanceState?.getParcelable(STATE_READING_ANCHOR)
        // The reading page's night style is the app's dark mode: one switch, one source of truth.
        isNightMode = AppearanceMode.isNight(this)
        ReadSettingManager.getInstance().isNightMode = isNightMode
        if (mCollBook == null) {
            return
        }
        mBookId = mCollBook!!._id
        showWrongChaptersPrefKey = mBookId?.let { bookId -> SHOW_WRONG_CHAPTERS_KEY_PREFIX + bookId }
        showWrongChapters = showWrongChaptersPrefKey?.let { key ->
            SharedPreUtils.getInstance().getBoolean(key, true)
        } ?: true
        readActivityStartedAtMs = System.currentTimeMillis()
        AiBridgeTrace.event(
            "source_read_activity_started",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "bookId" to mBookId.orEmpty(),
                "sourceRoute" to (
                    SourceEngineBookRoute.isBookId(mBookId) ||
                        SourceEngineBookRoute.isBookId(mCollBook?.bookIdInBiquge)
                    ),
                "collected" to isCollected,
                "parcelChapters" to (mCollBook?.getBookChapters()?.size ?: 0),
                "lastChapter" to mCollBook?.lastChapter.orEmpty()
            )
        )
        if (BuildConfig.DEBUG) {
            processMemorySampler?.start(
                context = applicationContext,
                bookKey = mCollBook?.title.orEmpty(),
                sourceEngineBook = isSourceBackedNovel()
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        Log.d("+新处理", "通知")
        finish()
        startActivity(intent)
        super.onNewIntent(intent)
    }

    override fun setUpToolbar(toolbar: Toolbar?) {
        val title = mCollBook?.title.orEmpty()
        toolbar?.title = title
        supportActionBar?.title = title
    }

    override fun toolbarView(): Toolbar {
        return binding.toolbar
    }

    @SuppressLint("InvalidWakeLockTag")
    override fun initWidget() {
        super.initWidget()
        viewModel = ViewModelProvider(this)[ReadViewModel::class.java]
        observeReadState()
        applySystemUi(controlsVisible = false)
        binding!!.toolbar.title = mCollBook?.title.orEmpty()

//        // 如果 API < 18 取消硬件加速
//        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2
//                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
//            mPvPage.setLayerType(LAYER_TYPE_SOFTWARE, null);
//        }

        //获取页面加载器
        Log.d(TAG, "+initWidget")
        binding!!.readPvPage.bindReaderSafeInsets()
        mPageLoader = binding!!.readPvPage.getPageLoader(mCollBook!!)
        restoredConfigurationReadingAnchor?.let { anchor ->
            mPageLoader!!.restoreAfterConfigurationChange(
                anchor = anchor,
                persistResolvedPosition = isCollected
            )
            restoredConfigurationReadingAnchor = null
        }
        mPageLoader!!.setShowWrongChapters(effectiveShowWrongChapters())
        binding!!.readCbShowWrongChapters.isChecked = showWrongChapters
        updateWrongChapterControl()
        AiBridgeTrace.event(
            "source_read_page_loader_created",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "loader" to mPageLoader!!::class.java.simpleName,
                "status" to mPageLoader!!.pageStatus,
                "chapterPos" to mPageLoader!!.chapterPos,
                "elapsedMs" to (System.currentTimeMillis() - readActivityStartedAtMs)
            )
        )
        //禁止滑动展示DrawerLayout
        binding!!.readDlSlide.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        //侧边打开后，返回键能够起作用
        binding!!.readDlSlide.isFocusableInTouchMode = false
        mSettingDialog = ReadSettingPanel(this, binding!!.readPageRoot, mPageLoader) { style ->
            onPageStyleSelected(style)
        }
        setUpAdapter()
        binding!!.readTvCatalogTitle.text = mCollBook?.title.orEmpty().ifBlank { getString(R.string.nb_read_category) }
        applyReaderPalette()

        //注册广播
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED)
        intentFilter.addAction(Intent.ACTION_TIME_TICK)
        registerReceiver(mReceiver, intentFilter)

        //设置当前Activity的Brightness
        if (ReadSettingManager.getInstance().isBrightnessAuto) {
            BrightnessUtils.setDefaultBrightness(this)
        } else {
            BrightnessUtils.setBrightness(this, ReadSettingManager.getInstance().brightness)
        }

        //初始化屏幕常亮类
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "keep bright")

        //初始化TopMenu
        initTopMenu()

        FloatingReaderChrome.keepClear(binding!!.readTopMenu, atTop = true)
        // The bottom bar floats above the page footer; over it, the footer showed at its round corners.
        FloatingReaderChrome.keepClear(
            binding!!.readBottomMenu,
            atTop = false,
            clearancePx = resources.getDimensionPixelSize(R.dimen.reader_footer_height)
        )
        topMenuBar = FloatingBar(binding!!.readTopMenu, binding!!.readPageRoot, fromTop = true)
        bottomMenuBar = FloatingBar(binding!!.readBottomMenu, binding!!.readPageRoot, fromTop = false)
        binding!!.readDlSlide.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                applySystemUi(controlsVisible = true)
            }

            override fun onDrawerClosed(drawerView: View) {
                if (!isMenuVisible && mSettingDialog?.isShowing != true) {
                    applySystemUi(controlsVisible = false)
                }
            }
        })
    }

    private fun observeReadState() {
        viewModel.categories.observe(this) { result ->
            showCategory(result.bookChapterList, result.bookId, result.isBiqugeLoaded)
        }
        viewModel.v8AnalysisStatus.observe(this) { status ->
            wrongAnalysisRunning = status.running
            wrongAnalysisProcessed = status.processed
            wrongAnalysisTarget = status.target
            wrongAnalysisWrong = status.wrong
            wrongAnalysisPhase = status.phase
            updateWrongChapterControl()
        }
        SourceEngineCatalogMarkRegistry.updates.observe(this) { update ->
            val loader = mPageLoader ?: return@observe
            val chapters = loader.chapterCategory
            if (!SourceEngineCatalogMarkRegistry.updateTargetsChapters(update, chapters)) {
                return@observe
            }
            val stats = SourceEngineCatalogMarkRegistry.applyToWithStats(chapters)
            if (stats.matched <= 0) {
                AiBridgeTrace.event(
                    "source_read_catalog_marks_seen",
                    mCollBook?.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "changed" to stats.changed,
                        "matched" to stats.matched,
                        "hidden" to stats.hidden,
                        "chapters" to chapters.size,
                        "source" to update.sourceLabel,
                        "marks" to update.marks.size,
                        "first" to chapters.firstOrNull()?.title.orEmpty(),
                        "last" to chapters.lastOrNull()?.title.orEmpty()
                    )
                )
                return@observe
            }
            persistSourceIntegrityMarksFromTxtChapters(chapters, "v8-update")
            refreshCategoryAdapter(chapters)
            loader.refreshSourceIntegrityMarks()
            updateWrongChapterControl()
            AiBridgeTrace.state(
                "source_read_catalog_marks_applied",
                mCollBook?.title.orEmpty(),
                AiBridgeTrace.fields(
                    "changed" to stats.changed,
                    "matched" to stats.matched,
                    "hidden" to stats.hidden,
                    "source" to update.sourceLabel,
                    "marks" to update.marks.size,
                    "showWrong" to showWrongChapters
                )
            )
        }
        viewModel.chapterFinishedEvents.observe(this) { isRefresh ->
            finishChapter(isRefresh)
        }
        viewModel.chapterErrorEvents.observe(this) {
            errorChapter()
        }
    }

    private fun initTopMenu() {
        MediaUiChrome.applyReaderContentInsets(binding!!.readLlCatalogDrawer)
        // A local file has no online detail page to open.
        binding!!.readTvBrief.visibility = if (mCollBook!!.isLocal()) View.GONE else View.VISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controlsVisible =
                isMenuVisible ||
                    binding!!.readDlSlide.isDrawerOpen(Gravity.LEFT) ||
                    mSettingDialog?.isShowing == true
            applySystemUi(controlsVisible)
        }
    }

    private fun renderNightToggle(palette: ReaderPalette) {
        val night = binding!!.readTvNightMode
        night.setText(if (isNightMode) R.string.nb_mode_morning else R.string.nb_mode_night)
        night.setCompoundDrawablesWithIntrinsicBounds(
            0,
            if (isNightMode) R.drawable.ic_ink_day else R.drawable.ic_ink_night,
            0,
            0
        )
        ReaderChrome.tintText(night, palette.panelText)
    }

    private fun currentPageStyle(): PageStyle {
        return if (isNightMode) PageStyle.NIGHT else ReadSettingManager.getInstance().pageStyle
    }

    /**
     * Tints every piece of reader chrome — bars, drawer, catalog, settings sheet, window and
     * system-bar icons — from the page style on screen.
     */
    private fun applyReaderPalette() {
        val palette = ReaderPalette.of(this, currentPageStyle())
        readerPalette = palette
        val readBinding = binding!!
        val density = resources.displayMetrics.density
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.page))
        readBinding.readPageRoot.setBackgroundColor(palette.page)

        val glass = GlassStyle.readerPanel(palette.panel, palette.isDark)
        readBinding.readTopMenu.glass.style = glass
        readBinding.readBottomMenu.glass.style = glass
        readBinding.readPageTip.glass.style = glass
        readBinding.toolbar.setTitleTextColor(palette.panelText)
        readBinding.toolbar.setSubtitleTextColor(palette.panelTextSecondary)
        readBinding.toolbar.navigationIcon?.mutate()?.setTint(palette.panelText)
        val iconTint = ColorStateList.valueOf(palette.panelText)
        readBinding.tvChangeSource.imageTintList = iconTint
        readBinding.readTvBrief.imageTintList = iconTint

        readBinding.readVBottomMenuRule.setBackgroundColor(palette.panelDivider)
        ReaderChrome.tintText(readBinding.readTvPreChapter, palette.panelText)
        ReaderChrome.tintText(readBinding.readTvNextChapter, palette.panelText)
        ReaderChrome.tintText(readBinding.readTvCategory, palette.panelText)
        ReaderChrome.tintText(readBinding.readTvSetting, palette.panelText)
        ReaderChrome.tintSeekBar(readBinding.readSbChapterProgress, palette)
        renderNightToggle(palette)

        readBinding.readTvPageTip.setTextColor(palette.panelText)

        readBinding.readLlCatalogDrawer.background = ReaderChrome.endRounded(palette.panel, 24 * density)
        readBinding.readTvCatalogTitle.setTextColor(palette.panelText)
        readBinding.readTvCatalogSummary.setTextColor(palette.panelTextSecondary)
        readBinding.readTvWrongAnalysisStatus.setTextColor(palette.panelTextSecondary)
        readBinding.readPbWrongAnalysis.indeterminateTintList = ColorStateList.valueOf(palette.accent)
        readBinding.readVCatalogDivider.setBackgroundColor(palette.panelDivider)
        readBinding.readCbShowWrongChapters.setTextColor(palette.panelTextSecondary)
        readBinding.readCbShowWrongChapters.thumbTintList = ReaderChrome.checkableText(palette.onAccent, palette.panelTextSecondary)
        readBinding.readCbShowWrongChapters.trackTintList = ReaderChrome.checkableText(palette.accent, palette.panelControl)
        readBinding.readCbShowWrongChapters.trackDecorationTintList =
            ReaderChrome.checkableText(palette.accent, palette.panelDivider)
        mCategoryAdapter?.applyPalette(palette)
        mSettingDialog?.applyPalette(palette)
        applySystemUi(controlsVisible = isMenuVisible)
    }

    private fun applySystemUi(controlsVisible: Boolean) {
        val lightBars = readerPalette?.isDark == false
        MediaUiChrome.immersiveReader(this, controlsVisible, lightBars)
    }

    /** Picking a paper while the night page is showing means "read on this paper": leave dark mode. */
    private fun onPageStyleSelected(style: PageStyle) {
        if (isNightMode) {
            AppearanceMode.select(AppearanceMode.LIGHT)
            return
        }
        applyReaderPalette()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = AppearanceMode.isNight(this)
        if (night == isNightMode) return
        isNightMode = night
        mPageLoader!!.setNightMode(night)
        applyReaderPalette()
    }

    private fun setUpAdapter() {
        mCategoryAdapter = CategoryAdapter()
        binding!!.readIvCategory.adapter = mCategoryAdapter
        binding!!.readIvCategory.isFastScrollEnabled = true
    }

    // 注册亮度观察者
    private fun registerBrightObserver() {
        try {
            if (mBrightObserver != null) {
                if (!isRegistered) {
                    val cr = contentResolver
                    cr.unregisterContentObserver(mBrightObserver)
                    cr.registerContentObserver(BRIGHTNESS_MODE_URI, false, mBrightObserver)
                    cr.registerContentObserver(BRIGHTNESS_URI, false, mBrightObserver)
                    cr.registerContentObserver(BRIGHTNESS_ADJ_URI, false, mBrightObserver)
                    isRegistered = true
                }
            }
        } catch (throwable: Throwable) {
            LogUtils.e(TAG, "register mBrightObserver error! $throwable")
        }
    }

    //解注册
    private fun unregisterBrightObserver() {
        try {
            if (mBrightObserver != null) {
                if (isRegistered) {
                    contentResolver.unregisterContentObserver(mBrightObserver)
                    isRegistered = false
                }
            }
        } catch (throwable: Throwable) {
            LogUtils.e(TAG, "unregister BrightnessObserver error! $throwable")
        }
    }

    override fun initClick() {
        super.initClick()
        mPageLoader!!.setOnPageChangeListener(
            object : PageLoader.OnPageChangeListener {
                override fun onChapterChange(pos: Int) {
                    mCategoryAdapter!!.setChapter(pos)
                    binding!!.toolbar.subtitle = mPageLoader!!.chapterCategory.getOrNull(pos)?.title
                }

                override fun requestChapters(requestChapters: List<TxtChapter>) {
                    Log.d(TAG, "+requestChapters")
                    AiBridgeTrace.event(
                        "source_read_page_request_delivered",
                        mCollBook?.title.orEmpty(),
                        AiBridgeTrace.fields(
                            "requested" to requestChapters.size,
                            "currentTitle" to mPageLoader!!.currentChapterTitle.orEmpty(),
                            "firstRequest" to requestChapters.firstOrNull()?.title.orEmpty(),
                            "chapterPos" to mPageLoader!!.chapterPos,
                            "pageStatus" to mPageLoader!!.pageStatus,
                            "elapsedMs" to (System.currentTimeMillis() - readActivityStartedAtMs)
                        )
                    )
                    viewModel.loadChapter(
                        mBookId,
                        mCollBook!!,
                        requestChapters,
                        mPageLoader!!.currentChapterTitle,
                        persistToShelf = isCollected
                    )
                    mHandler.sendEmptyMessage(WHAT_CATEGORY)
                    //隐藏提示
                    hidePageTip()
                }

                override fun onCategoryFinish(chapters: List<TxtChapter>) {
                    val stats = SourceEngineCatalogMarkRegistry.applyToWithStats(chapters)
                    if (stats.changed > 0) {
                        persistSourceIntegrityMarksFromTxtChapters(chapters, "category-finish")
                    }
                    if (stats.changed > 0 || stats.matched > 0) {
                        mPageLoader!!.refreshSourceIntegrityMarks()
                    }
                    refreshCategoryAdapter(chapters)
                    if (stats.changed > 0 || stats.matched > 0) {
                        AiBridgeTrace.event(
                            "source_read_catalog_marks_refreshed",
                            mCollBook?.title.orEmpty(),
                            AiBridgeTrace.fields(
                                "changed" to stats.changed,
                                "matched" to stats.matched,
                                "hidden" to stats.hidden,
                                "chapters" to chapters.size,
                                "showWrong" to showWrongChapters
                            )
                        )
                    }
                    if (!isDisplayCatalogReady) {
                        AiBridgeTrace.event(
                            "source_read_catalog_bootstrap_displayed",
                            mCollBook?.title.orEmpty(),
                            AiBridgeTrace.fields(
                                "chapters" to chapters.size,
                                "showWrong" to showWrongChapters
                            )
                        )
                    }
                }

                override fun onPageCountChange(count: Int) {
                    binding!!.readSbChapterProgress.max = Math.max(0, count - 1)
                    binding!!.readSbChapterProgress.progress = 0
                    // 如果处于错误状态，那么就冻结使用
                    if (mPageLoader!!.pageStatus == PageLoader.STATUS_LOADING
                        || mPageLoader!!.pageStatus == PageLoader.STATUS_ERROR
                    ) {
                        binding!!.readSbChapterProgress.isEnabled = false
                    } else {
                        binding!!.readSbChapterProgress.isEnabled = true
                    }
                }

                override fun onPageChange(pos: Int) {
                    binding!!.readSbChapterProgress.post {
                        binding!!.readSbChapterProgress.progress = pos
                    }
                }
            }
        )
        binding!!.readSbChapterProgress.setOnSeekBarChangeListener(
            object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || !isMenuVisible) return
                    Haptics.tick(seekBar)
                    showPageTip(seekBar)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    showPageTip(seekBar)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    //进行切换
                    val pagePos = binding!!.readSbChapterProgress.progress
                    if (pagePos != mPageLoader!!.pagePos) {
                        mPageLoader!!.skipToPage(pagePos)
                    }
                    //隐藏提示
                    hidePageTip()
                }
            }
        )
        binding!!.readPvPage.setTouchListener(object : TouchListener {
            override fun onTouch(): Boolean {
                return !hideReadMenu()
            }

            override fun center() {
                toggleMenu(true)
            }

            override fun prePage() {
                retryCatalogIfNeeded()
            }

            override fun nextPage() {
                retryCatalogIfNeeded()
            }
            override fun cancel() {}
        })
        binding!!.readIvCategory.onItemClickListener =
            AdapterView.OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
                binding!!.readDlSlide.closeDrawer(Gravity.LEFT)
                Log.d("+点击章节", position.toString() + "")
                mPageLoader!!.skipToChapter(fullChapterPositionForAdapterPosition(position))
            }
        binding!!.readTvCategory.setOnClickListener { v: View? ->
            if (mCategoryAdapter!!.count <= 0) {
                val message = when {
                    retryCatalogIfNeeded() -> "正在重新加载目录"
                    mPageLoader!!.pageStatus == PageLoader.STATUS_CATEGORY_EMPTY -> {
                        ReaderCatalogTerminalCopy.messageFor(
                            ReaderCatalogTerminalState.CONFIRMED_EMPTY
                        )
                    }
                    else -> "目录加载中，请稍后再试"
                }
                com.ldp.reader.utils.ToastUtils.show(message)
                return@setOnClickListener
            }
            //移动到指定位置
            binding!!.readIvCategory.setSelection(adapterPositionForFullChapter(mPageLoader!!.chapterPos))
            //切换菜单
            toggleMenu(true)
            //打开侧滑动栏
            binding!!.readDlSlide.openDrawer(Gravity.LEFT)
        }
        binding!!.readCbShowWrongChapters.setOnCheckedChangeListener { _, isChecked ->
            applyShowWrongChapterToggle(isChecked)
        }
        binding!!.readLlCatalogHeader.setOnClickListener {
            if (binding!!.readCbShowWrongChapters.visibility != View.VISIBLE) {
                return@setOnClickListener
            }
            binding!!.readCbShowWrongChapters.isChecked = !binding!!.readCbShowWrongChapters.isChecked
        }
        binding!!.readTvSetting.setOnClickListener { v: View? ->
            toggleMenu(false)
            mSettingDialog!!.show()
        }
        binding!!.readTvPreChapter.setOnClickListener { v: View? ->
            if (mPageLoader!!.skipPreChapter()) {
                mCategoryAdapter!!.setChapter(mPageLoader!!.chapterPos)
                sourceIndex = 0
            }
        }
        binding!!.readTvNextChapter.setOnClickListener { v: View? ->
            if (mPageLoader!!.skipNextChapter()) {
                mCategoryAdapter!!.setChapter(mPageLoader!!.chapterPos)
                sourceIndex = 0
            }
        }
        binding!!.readTvNightMode.setOnClickListener { toggle ->
            Haptics.toggle(toggle, !isNightMode)
            // Switches the whole app; onConfigurationChanged recolours the page in place.
            AppearanceMode.select(if (isNightMode) AppearanceMode.LIGHT else AppearanceMode.DARK)
        }
        binding!!.readTvBrief.setOnClickListener { startActivity(this@ReadActivity, currentDetailBookId()) }

        binding.tvChangeSource.setOnClickListener {
            val chapterPos = mPageLoader!!.chapterPos
            val chapter = mPageLoader!!.chapterCategory.getOrNull(chapterPos)
            if (chapter == null) {
                com.ldp.reader.utils.ToastUtils.show("目录加载中，请稍后再试")
                return@setOnClickListener
            }
            sourceIndex++;
            viewModel.refreshChapter(
                mBookId,
                mCollBook!!,
                chapter,
                sourceIndex
            )
        }
        mSettingDialog!!.onDismiss = {
            if (!isMenuVisible && !binding!!.readDlSlide.isDrawerOpen(Gravity.LEFT)) {
                applySystemUi(controlsVisible = false)
            }
        }
    }

    /**
     * 隐藏阅读界面的菜单显示
     *
     * @return 是否隐藏成功
     */
    private fun hideReadMenu(): Boolean {
        if (isMenuVisible) {
            toggleMenu(true)
            return true
        } else if (mSettingDialog!!.isShowing) {
            mSettingDialog!!.dismiss()
            return true
        }
        return false
    }

    /**
     * 切换菜单栏的可视状态
     * 默认是隐藏的
     */
    private fun toggleMenu(hideStatusBar: Boolean) {
        if (isMenuVisible) {
            isMenuVisible = false
            hidePageTip()
            topMenuBar.setShown(false)
            bottomMenuBar.setShown(false)
            applySystemUi(controlsVisible = !hideStatusBar)
        } else {
            isMenuVisible = true
            topMenuBar.setShown(true)
            bottomMenuBar.setShown(true)
            applySystemUi(controlsVisible = true)
        }
    }

    /** The scrub bubble names the chapter and page, and rides above the slider thumb. */
    private fun showPageTip(seekBar: SeekBar) {
        val readBinding = binding!!
        val pageLabel = "${seekBar.progress + 1}/${seekBar.max + 1}"
        val chapterTitle = mPageLoader!!.chapterCategory.getOrNull(mPageLoader!!.chapterPos)?.title
        readBinding.readTvPageTip.text = if (chapterTitle.isNullOrBlank()) {
            pageLabel
        } else {
            "$chapterTitle · $pageLabel"
        }
        val tip = readBinding.readPageTip
        val root = readBinding.readPageRoot
        val track = seekBar.width - seekBar.paddingLeft - seekBar.paddingRight
        val fraction = if (seekBar.max == 0) 0f else seekBar.progress / seekBar.max.toFloat()
        val row = seekBar.parent as View
        val thumbX = readBinding.readBottomMenu.left + row.left + seekBar.left +
            seekBar.paddingLeft + track * fraction
        val margin = resources.getDimensionPixelSize(R.dimen.reader_float_margin)
        val reach = ((root.width - tip.width) / 2f - margin).coerceAtLeast(0f)
        tip.translationX = (thumbX - root.width / 2f).coerceIn(-reach, reach)
        // A fade-out still running (scrubbing resumed quickly) must not hide the bubble later.
        tip.animate().cancel()
        if (tip.visibility != View.VISIBLE) {
            tip.visibility = View.VISIBLE
            tip.alpha = 0f
            tip.scaleX = PAGE_TIP_ENTER_SCALE
            tip.scaleY = PAGE_TIP_ENTER_SCALE
        }
        tip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(PAGE_TIP_FADE_MS).start()
    }

    private fun hidePageTip() {
        val tip = binding!!.readPageTip
        if (tip.visibility != View.VISIBLE) return
        tip.animate().alpha(0f).setDuration(PAGE_TIP_FADE_MS)
            .withEndAction { tip.visibility = View.INVISIBLE }
            .start()
    }

    override fun processLogic() {
        super.processLogic()
        AiBridgeTrace.event(
            "source_read_activity_process",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "collected" to isCollected,
                "local" to (mCollBook?.isLocal() ?: false),
                "bookId" to mBookId.orEmpty(),
                "sourceRoute" to (
                    SourceEngineBookRoute.isBookId(mBookId) ||
                        SourceEngineBookRoute.isBookId(mCollBook?.bookIdInBiquge)
                    ),
                "cachedChapters" to (mCollBook?.getBookChapters()?.size ?: 0),
                "elapsedMs" to (System.currentTimeMillis() - readActivityStartedAtMs)
            )
        )
        // 如果是已经收藏的，那么就从数据库中获取目录
        if (isCollected) {
            val storedBookChapters = BookRepository.getInstance().getBookChapters(mBookId)
            val cachedCatalogMigration = migratePersistedSourceEngineCatalog(storedBookChapters)
            val bookChapterBeen = cachedCatalogMigration?.chapters ?: storedBookChapters
            val persistedRecord = if (cachedCatalogMigration?.changed == true) {
                BookRepository.getInstance().getBookRecord(mBookId)
            } else {
                null
            }
            val recordMigrated = cachedCatalogMigration?.let { cachedCatalogMigration ->
                persistedRecord?.let { persistedRecord ->
                    cachedCatalogMigration.remapRecord(persistedRecord)
                } == true
            } == true
            val replayedV8Caches = mCollBook?.let { book ->
                BookContentProviderRouter.restoreCachedV8MarksForBook(
                    mBookId,
                    book,
                    "reading-cached-catalog"
                )
            } ?: 0
            val cachedMarkStats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(bookChapterBeen)
            if (cachedCatalogMigration?.changed == true) {
                BookRepository.getInstance().persistSourceEngineCatalogMigration(
                    bookChapterBeen,
                    persistedRecord
                )
                persistedRecord?.let { persistedRecord ->
                    mPageLoader!!.applyMigratedBookRecord(persistedRecord)
                }
            } else if (cachedMarkStats.changed > 0) {
                BookRepository.getInstance().saveBookChaptersWithAsync(bookChapterBeen)
            }
            persistedBookChaptersSnapshot = bookChapterBeen
            AiBridgeTrace.event(
                "source_read_cached_catalog_loaded",
                mCollBook?.title.orEmpty(),
                AiBridgeTrace.fields(
                    "chapters" to bookChapterBeen.size,
                    "persistedMarks" to SourceEnginePersistedCatalogMarks.countMarked(bookChapterBeen),
                    "persistedHidden" to SourceEnginePersistedCatalogMarks.countHidden(bookChapterBeen),
                    "replayedV8Caches" to replayedV8Caches,
                    "registryChanged" to cachedMarkStats.changed,
                    "registryMatched" to cachedMarkStats.matched,
                    "registryHidden" to cachedMarkStats.hidden,
                    "navigationRemoved" to (cachedCatalogMigration?.removedCount ?: 0),
                    "recordMigrated" to recordMigrated,
                    "last" to bookChapterBeen.lastOrNull()?.title.orEmpty(),
                    "elapsedMs" to (System.currentTimeMillis() - readActivityStartedAtMs)
                )
            )
            when (ReaderCatalogStartupPolicy.decide(mCollBook!!.isLocal(), bookChapterBeen.size)) {
                ReaderCatalogStartupAction.LOAD_PERSISTED_CATALOG -> {
                    isDisplayCatalogReady = true
                    mPageLoader!!.collBook.bookChapters = bookChapterBeen
                    mPageLoader!!.refreshChapterList()
                    mPageLoader!!.refreshSourceIntegrityMarks()
                    updateWrongChapterControl()
                }

                ReaderCatalogStartupAction.WAIT_FOR_REMOTE_CATALOG -> {
                    isDisplayCatalogReady = false
                    mPageLoader!!.showCatalogLoading()
                    AiBridgeTrace.state(
                        "source_read_catalog_waiting_for_remote",
                        mCollBook?.title.orEmpty(),
                        AiBridgeTrace.fields(
                            "reason" to "persisted_catalog_empty",
                            "bookId" to mBookId.orEmpty()
                        )
                    )
                }
            }
            if (mCollBook!!.isLocal()) {
                return
            }
            viewModel.loadCategory(mBookId, mCollBook!!, persistToShelf = true)
        } else {
            // 从网络中获取目录
            mPageLoader!!.showCatalogLoading()
            viewModel.loadCategory(mBookId, mCollBook!!, persistToShelf = false)
        }
    }

    private fun migratePersistedSourceEngineCatalog(
        chapters: List<BookChapterBean>
    ): SourceEngineCachedCatalogMigration.Result? {
        val sourceRoute = SourceEngineBookRoute.isBookId(mBookId) ||
            SourceEngineBookRoute.isShelfBookId(mBookId) ||
            SourceEngineBookRoute.isBookId(mCollBook?.bookIdInBiquge)
        if (!sourceRoute || chapters.isEmpty()) return null
        return SourceEngineCachedCatalogMigration.sanitize(chapters)
            .takeIf { migration -> migration.changed }
    }

    /***************************view */
    private fun showCategory(
        bookChapters: List<BookChapterBean>,
        bookId: String,
        isBiqugeLoaded: Boolean
    ) {
        val startedAt = System.currentTimeMillis()
        if (!ReaderCatalogStartupPolicy.canDisplayRemoteCatalog(bookChapters.size)) {
            isDisplayCatalogReady = false
            mPageLoader!!.showCatalogEmpty()
            AiBridgeTrace.state(
                "source_read_catalog_empty_retryable",
                mCollBook?.title.orEmpty(),
                AiBridgeTrace.fields(
                    "bookId" to bookId,
                    "biqugeLoaded" to isBiqugeLoaded
                )
            )
            return
        }
        val persistedChapters = if (isCollected) {
            persistedBookChaptersSnapshot.ifEmpty {
                mPageLoader?.collBook?.getBookChapters().orEmpty()
            }
        } else {
            emptyList()
        }
        val sourceCatalogEvaluation = if (isSourceBackedNovel()) {
            SourceEngineCatalogPersistencePolicy.evaluate(persistedChapters, bookChapters)
        } else {
            null
        }
        val catalogPersistenceAllowed = sourceCatalogEvaluation?.compatible ?: true
        val catalogChanged = sourceCatalogEvaluation?.changed
            ?: catalogsDiffer(persistedChapters, bookChapters)
        val persistedMerge = SourceEnginePersistedCatalogMarks.mergeInto(bookChapters, persistedChapters)
        val hasCatalogTitleUpgrade = isCollected && (
            sourceCatalogEvaluation?.safeTitleUpgrades?.let { count -> count > 0 }
                ?: hasPersistedCatalogTitleUpgrade(persistedChapters, bookChapters)
            )
        AiBridgeTrace.event(
            "source_read_catalog_persisted_marks_restore",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "incomingMarks" to persistedMerge.incomingMarkedBefore,
                "storedMarks" to persistedMerge.persistedMarked,
                "storedHidden" to persistedMerge.persistedHidden,
                "restored" to persistedMerge.restored,
                "titleUpgrade" to hasCatalogTitleUpgrade,
                "catalogChanged" to catalogChanged,
                "persistenceAllowed" to catalogPersistenceAllowed,
                "exactRestored" to persistedMerge.exactRestored,
                "identityRestored" to persistedMerge.identityRestored,
                "chapters" to bookChapters.size,
                "storedChapters" to persistedChapters.size
            )
        )
        val markStats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(bookChapters)
        val markChanged = markStats.changed
        val markMatched = markStats.matched
        val finalMarked = SourceEnginePersistedCatalogMarks.countMarked(bookChapters)
        val finalHidden = SourceEnginePersistedCatalogMarks.countHidden(bookChapters)
        isDisplayCatalogReady = isBiqugeLoaded
        AiBridgeTrace.event(
            "source_read_catalog_apply_started",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapters" to bookChapters.size,
                "bookId" to bookId,
                "biqugeLoaded" to isBiqugeLoaded,
                "bootstrap" to !isBiqugeLoaded,
                "first" to bookChapters.firstOrNull()?.title.orEmpty(),
                "last" to bookChapters.lastOrNull()?.title.orEmpty(),
                "markChanged" to markChanged,
                "markMatched" to markMatched,
                "persistedRestored" to persistedMerge.restored,
                "registryHidden" to markStats.hidden,
                "marked" to finalMarked,
                "hidden" to finalHidden,
                "elapsedMs" to (startedAt - readActivityStartedAtMs)
            )
        )
        mPageLoader!!.collBook.bookChapters = bookChapters
        mPageLoader!!.refreshChapterList()
        mPageLoader!!.refreshSourceIntegrityMarks()
        updateWrongChapterControl()
        mCollBook?.let { book ->
            viewModel.ensureV8ForCurrentReading(mBookId, book, "catalog-applied")
        }
        AiBridgeTrace.state(
            "source_read_catalog_applied",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "chapters" to bookChapters.size,
                "chapterPos" to mPageLoader!!.chapterPos,
                "current" to mPageLoader!!.currentChapterTitle.orEmpty(),
                "pageStatus" to mPageLoader!!.pageStatus,
                "bootstrap" to !isBiqugeLoaded,
                "marked" to finalMarked,
                "hidden" to finalHidden,
                "applyMs" to (System.currentTimeMillis() - startedAt),
                "elapsedMs" to (System.currentTimeMillis() - readActivityStartedAtMs)
            )
        )

        // 如果是目录更新的情况，那么就需要存储更新数据
        if (ReaderCatalogWritebackPolicy.shouldPersist(
                remoteCatalogLoaded = isBiqugeLoaded,
                collected = isCollected,
                bookUpdated = mCollBook!!.isUpdate(),
                changedIntegrityMarks = markChanged,
                restoredIntegrityMarks = persistedMerge.restored,
                hasSafeTitleUpgrade = hasCatalogTitleUpgrade,
                catalogChanged = catalogChanged,
                catalogPersistenceAllowed = catalogPersistenceAllowed
            )
        ) {
            BookRepository.getInstance()
                .saveBookChaptersWithAsync(bookChapters)
            persistedBookChaptersSnapshot = bookChapters
        }
    }

    private fun hasPersistedCatalogTitleUpgrade(
        persistedChapters: List<BookChapterBean>,
        incomingChapters: List<BookChapterBean>
    ): Boolean {
        val persistedTitlesByLink = persistedChapters.mapNotNull { chapter ->
            val link = chapter.link?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            link to chapter.title.orEmpty()
        }.toMap()
        if (persistedTitlesByLink.isEmpty()) return false
        val incomingTitlesByLink = incomingChapters.mapNotNull { chapter ->
            val link = chapter.link?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            link to chapter.title.orEmpty()
        }.toMap()
        return catalogTitleEnricher.hasSafeTitleUpgradeForSameKey(
            currentTitlesByKey = persistedTitlesByLink,
            candidateTitlesByKey = incomingTitlesByLink
        )
    }

    private fun catalogsDiffer(
        persistedChapters: List<BookChapterBean>,
        incomingChapters: List<BookChapterBean>
    ): Boolean {
        if (persistedChapters.size != incomingChapters.size) return true
        return persistedChapters.indices.any { index ->
            val persisted = persistedChapters[index]
            val incoming = incomingChapters[index]
            persisted.link != incoming.link || persisted.title != incoming.title
        }
    }

    private fun persistSourceIntegrityMarksFromTxtChapters(chapters: List<TxtChapter>, reason: String) {
        if (!isCollected) return
        val bookChapters = mPageLoader?.collBook?.getBookChapters()
            ?: mCollBook?.getBookChapters()
            ?: return
        if (bookChapters.isEmpty()) return
        val chaptersByLink = chapters
            .filter { chapter -> !chapter.link.isNullOrBlank() }
            .associateBy { chapter -> chapter.link }
        var changed = 0
        bookChapters.forEachIndexed { index, bean ->
            val txtChapter = chaptersByLink[bean.link]
                ?: chapters.firstOrNull { chapter ->
                    (chapter.catalogIndex.takeIf { catalogIndex -> catalogIndex >= 0 } ?: index) == bean.start.toInt()
                }
                ?: return@forEachIndexed
            if (bean.sourceIntegrityState != txtChapter.sourceIntegrityState ||
                bean.sourceIntegrityConfidence != txtChapter.sourceIntegrityConfidence ||
                bean.sourceIntegrityReason != txtChapter.sourceIntegrityReason
            ) {
                bean.sourceIntegrityState = txtChapter.sourceIntegrityState
                bean.sourceIntegrityConfidence = txtChapter.sourceIntegrityConfidence
                bean.sourceIntegrityReason = txtChapter.sourceIntegrityReason
                changed += 1
            }
        }
        val markedAfter = SourceEnginePersistedCatalogMarks.countMarked(bookChapters)
        val hiddenAfter = SourceEnginePersistedCatalogMarks.countHidden(bookChapters)
        if (changed <= 0) {
            AiBridgeTrace.event(
                "source_read_catalog_marks_persist_skipped",
                mCollBook?.title.orEmpty(),
                AiBridgeTrace.fields(
                    "reason" to reason,
                    "changed" to changed,
                    "marked" to markedAfter,
                    "hidden" to hiddenAfter,
                    "chapters" to bookChapters.size
                )
            )
            return
        }
        BookRepository.getInstance().saveBookChaptersWithAsync(bookChapters)
        AiBridgeTrace.event(
            "source_read_catalog_marks_persisted",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "reason" to reason,
                "changed" to changed,
                "marked" to markedAfter,
                "hidden" to hiddenAfter,
                "chapters" to bookChapters.size
            )
        )
    }

    private fun refreshCategoryAdapter(chapters: List<TxtChapter>) {
        val currentChapterPos = mPageLoader?.chapterPos ?: 0
        val visibleChapters = if (!hasWrongChapterAnalysisCapability() || showWrongChapters) {
            chapters
        } else {
            chapters.filterIndexed { index, chapter ->
                !chapter.hasHiddenSourceIntegrityMark() ||
                    (chapter.catalogIndex.takeIf { catalogIndex -> catalogIndex >= 0 } ?: index) == currentChapterPos
            }
        }
        mCategoryAdapter!!.refreshItems(visibleChapters)
        mCategoryAdapter!!.setChapter(currentChapterPos)
        binding!!.readTvCatalogSummary.text = getString(R.string.reader_catalog_summary, chapters.size)
    }

    private fun updateWrongChapterControl() {
        val uiState = ReaderWrongChapterUiPolicy.resolve(
            featureEnabled = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled(),
            sourceBackedNovel = isSourceBackedNovel(),
            analysisRunning = wrongAnalysisRunning,
            processed = wrongAnalysisProcessed,
            target = wrongAnalysisTarget,
            wrong = wrongAnalysisWrong,
            phase = wrongAnalysisPhase
        )
        binding!!.readLlWrongAnalysisLoading.visibility = if (uiState.showStatus) View.VISIBLE else View.GONE
        binding!!.readPbWrongAnalysis.visibility = if (uiState.showLoading) View.VISIBLE else View.GONE
        binding!!.readTvWrongAnalysisStatus.text = when {
            uiState.statusProcessed != null && uiState.statusTarget != null -> {
                getString(uiState.statusTextRes, uiState.statusProcessed, uiState.statusTarget)
            }
            uiState.statusWrong != null -> {
                getString(uiState.statusTextRes, uiState.statusWrong)
            }
            else -> getString(uiState.statusTextRes)
        }
        binding!!.readCbShowWrongChapters.visibility = if (uiState.showToggle) View.VISIBLE else View.GONE
        binding!!.readCbShowWrongChapters.isEnabled = uiState.showToggle
    }

    private fun effectiveShowWrongChapters(): Boolean {
        return hasWrongChapterAnalysisCapability() && showWrongChapters
    }

    private fun hasWrongChapterAnalysisCapability(): Boolean {
        return ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled() && isSourceBackedNovel()
    }

    private fun isSourceBackedNovel(): Boolean {
        return SourceEngineBookRoute.isBookId(mBookId) ||
            SourceEngineBookRoute.isBookId(mCollBook?.bookIdInBiquge)
    }

    private fun currentDetailBookId(): String? {
        if (SourceEngineBookRoute.isBookId(mBookId)) return mBookId
        mCollBook?.bookIdInBiquge
            ?.takeIf { SourceEngineBookRoute.isBookId(it) }
            ?.let { return it }
        return BookRepository.getInstance()
            .getCollBook(mBookId)
            ?.bookIdInBiquge
            ?.takeIf { SourceEngineBookRoute.isBookId(it) }
            ?: mBookId
    }

    private fun applyShowWrongChapterToggle(isChecked: Boolean) {
        showWrongChapters = isChecked
        showWrongChaptersPrefKey?.let { key ->
            SharedPreUtils.getInstance().putBoolean(key, isChecked)
        }
        mPageLoader?.setShowWrongChapters(effectiveShowWrongChapters())
        refreshCategoryAdapter(mPageLoader?.chapterCategory.orEmpty())
        binding!!.readIvCategory.setSelection(adapterPositionForFullChapter(mPageLoader?.chapterPos ?: 0))
        AiBridgeTrace.event(
            "source_catalog_wrong_toggle_changed",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "showWrong" to isChecked,
                "persisted" to (showWrongChaptersPrefKey != null)
            )
        )
    }

    private fun fullChapterPositionForAdapterPosition(adapterPosition: Int): Int {
        val adapter = mCategoryAdapter ?: return adapterPosition
        val chapter = adapter.getItem(adapterPosition)
        val resolvedPosition = ReaderChapterSelectionResolver.fullChapterPosition(
            chapter,
            adapterPosition
        )
        if (resolvedPosition != adapterPosition || chapter.catalogIndex >= 0) {
            return resolvedPosition
        }
        val fullIndex = mPageLoader?.chapterCategory?.indexOf(chapter) ?: -1
        return if (fullIndex >= 0) fullIndex else resolvedPosition
    }

    private fun adapterPositionForFullChapter(chapterPos: Int): Int {
        val adapter = mCategoryAdapter ?: return chapterPos
        if (adapter.count == 0) return 0
        for (index in 0 until adapter.count) {
            val chapter = adapter.getItem(index)
            val catalogIndex = chapter.catalogIndex.takeIf { value -> value >= 0 } ?: index
            if (catalogIndex == chapterPos) return index
        }
        return if (chapterPos !in 0 until adapter.count) {
            chapterPos.coerceIn(0, adapter.count - 1)
        } else {
            chapterPos
        }
    }

    private fun finishChapter(isRefresh: Boolean) {
        AiBridgeTrace.event(
            "source_read_chapter_finish_event",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "refresh" to isRefresh,
                "pageStatus" to mPageLoader!!.pageStatus,
                "current" to mPageLoader!!.currentChapterTitle.orEmpty(),
                "chapterPos" to mPageLoader!!.chapterPos,
                "elapsedMs" to (System.currentTimeMillis() - readActivityStartedAtMs)
            )
        )
        if (mPageLoader!!.pageStatus == PageLoader.STATUS_LOADING ||
            mPageLoader!!.pageStatus == PageLoader.STATUS_ERROR ||
            isRefresh
        ) {
            mHandler.sendEmptyMessage(WHAT_CHAPTER)
            Log.d("+finishChapter", "加载")
        }
        if (ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()) {
            mCollBook?.let { book ->
                if (isRefresh) {
                    viewModel.triggerV8ForChapterRefresh(mBookId, book)
                } else {
                    viewModel.ensureV8ForCurrentReading(mBookId, book, "chapter-finish")
                }
            }
        }
        persistSourceIntegrityMarksFromTxtChapters(
            mPageLoader!!.chapterCategory,
            if (isRefresh) "chapter-refresh" else "chapter-finish"
        )
        mPageLoader!!.refreshSourceIntegrityMarks()
        refreshCategoryAdapter(mPageLoader!!.chapterCategory)
        updateWrongChapterControl()
        // 当完成章节的时候，刷新列表
        mCategoryAdapter!!.notifyDataSetChanged()
        Log.d("+finishChapter", "完成")
    }

    private fun errorChapter() {
        AiBridgeTrace.event(
            "source_read_chapter_error_event",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "pageStatus" to mPageLoader!!.pageStatus,
                "current" to mPageLoader!!.currentChapterTitle.orEmpty(),
                "chapterPos" to mPageLoader!!.chapterPos,
                "elapsedMs" to (System.currentTimeMillis() - readActivityStartedAtMs)
            )
        )
        when (mPageLoader!!.pageStatus) {
            PageLoader.STATUS_CATALOG_LOADING -> mPageLoader!!.showCatalogError()
            PageLoader.STATUS_LOADING -> mPageLoader!!.chapterError()
        }
    }

    private fun retryCatalogIfNeeded(): Boolean {
        val pageLoader = mPageLoader ?: return false
        val collBook = mCollBook ?: return false
        val retryAction = ReaderCatalogRetryPolicy.manualRetry(
            pageStatus = pageLoader.pageStatus,
            isLocalBook = collBook.isLocal()
        )
        if (retryAction == ReaderCatalogManualRetryAction.NO_RETRY) return false
        pageLoader.showCatalogLoading()
        AiBridgeTrace.event(
            "source_read_catalog_manual_retry",
            collBook.title.orEmpty(),
            AiBridgeTrace.fields(
                "bookId" to mBookId.orEmpty(),
                "persistToShelf" to isCollected,
                "route" to retryAction.name
            )
        )
        when (retryAction) {
            ReaderCatalogManualRetryAction.REFRESH_LOCAL_CATALOG -> {
                pageLoader.refreshChapterList()
            }

            ReaderCatalogManualRetryAction.RELOAD_REMOTE_CATALOG -> {
                viewModel.loadCategory(mBookId, collBook, persistToShelf = isCollected)
            }

            ReaderCatalogManualRetryAction.NO_RETRY -> return false
        }
        return true
    }

    override fun onBackPressed() {
        if (isMenuVisible) {
            toggleMenu(true)
            return
        } else if (mSettingDialog!!.isShowing) {
            mSettingDialog!!.dismiss()
            applySystemUi(controlsVisible = false)
            return
        } else if (binding!!.readDlSlide.isDrawerOpen(Gravity.LEFT)) {
            binding!!.readDlSlide.closeDrawer(Gravity.LEFT)
            return
        }
        if (!mCollBook!!.isLocal() && !isCollected
            && !mCollBook!!.bookChapters!!.isEmpty()
        ) {
            val alertDialog = MaterialAlertDialogBuilder(this)
                .setTitle("加入书架")
                .setMessage("喜欢本书就加入书架吧")
                .setPositiveButton("确定") { dialog: DialogInterface?, which: Int ->
                    //设置为已收藏
                    isCollected = true
                    //设置阅读时间
                    mCollBook!!.lastRead = StringUtils.dateConvert(
                        System.currentTimeMillis(),
                        Constant.FORMAT_BOOK_DATE
                    )
                    BookRepository.getInstance()
                        .saveCollBookWithAsync(mCollBook!!)
                    exit()
                }
                .setNegativeButton("取消") { dialog: DialogInterface?, which: Int -> exit() }
                .create()
            alertDialog.show()
        } else {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }
    }

    // 退出
    private fun exit() {
        // 返回给BookDetail。
        val result = Intent()
        result.putExtra(BookDetailActivity.RESULT_IS_COLLECTED, isCollected)
        setResult(RESULT_OK, result)
        // 退出
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        registerBrightObserver()
    }

    override fun onResume() {
        super.onResume()
        mWakeLock!!.acquire()
        readingSessionTracker.onResume(mCollBook?._id)
        applyReaderFeatureSwitches()
    }

    private fun applyReaderFeatureSwitches() {
        mPageLoader?.setShowWrongChapters(effectiveShowWrongChapters())
        if (mCategoryAdapter != null) {
            refreshCategoryAdapter(mPageLoader?.chapterCategory.orEmpty())
        }
        updateWrongChapterControl()
    }

    override fun onPause() {
        super.onPause()
        readingSessionTracker.onPause()
        mWakeLock!!.release()
        if (isCollected) {
            mPageLoader!!.saveRecord()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mPageLoader?.captureConfigurationReadingAnchor()?.let { anchor ->
            outState.putParcelable(STATE_READING_ANCHOR, anchor)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (BuildConfig.DEBUG) {
            processMemorySampler?.cancel()
        }
        readingSessionTracker.onStop()
        super.onStop()
        unregisterBrightObserver()
    }

    override fun onDestroy() {
        super.onDestroy()
        AiBridgeTrace.event(
            "source_read_activity_destroyed",
            mCollBook?.title.orEmpty(),
            AiBridgeTrace.fields(
                "pageStatus" to (mPageLoader?.pageStatus ?: -1),
                "chapterPos" to (mPageLoader?.chapterPos ?: -1),
                "elapsedMs" to (System.currentTimeMillis() - readActivityStartedAtMs)
            )
        )
        unregisterReceiver(mReceiver)
        mHandler.removeMessages(WHAT_CATEGORY)
        mHandler.removeMessages(WHAT_CHAPTER)
        mPageLoader!!.closeBook()
        mPageLoader = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isVolumeTurnPage = ReadSettingManager
            .getInstance().isVolumeTurnPage
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (isVolumeTurnPage) {
                return mPageLoader!!.skipToPrePage()
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> if (isVolumeTurnPage) {
                return mPageLoader!!.skipToNextPage()
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun getViewBinding(): ActivityReadBinding {
        return ActivityReadBinding.inflate(layoutInflater)
    }

    companion object {
        private const val TAG = "ReadActivity"
        const val EXTRA_COLL_BOOK = "extra_coll_book"
        const val EXTRA_IS_COLLECTED = "extra_is_collected"
        private const val SHOW_WRONG_CHAPTERS_KEY_PREFIX = "read_show_wrong_chapters_book_"
        private const val STATE_READING_ANCHOR = "state_reading_anchor"
        private const val WHAT_CATEGORY = 1
        private const val WHAT_CHAPTER = 2
        private const val PAGE_TIP_ENTER_SCALE = 0.92f
        private const val PAGE_TIP_FADE_MS = 140L
        fun createIntentBookPayload(collBook: CollBookBean?): CollBookBean? {
            if (collBook == null) return null
            return CollBookBean(
                collBook.get_id(),
                collBook.title,
                collBook.author,
                collBook.shortIntro,
                collBook.cover,
                collBook.bookStatus,
                collBook.updated,
                collBook.lastRead,
                collBook.chaptersCount,
                collBook.lastChapter,
                collBook.isUpdate(),
                collBook.isLocal(),
                collBook.bookIdInBiquge
            )
        }

        fun startActivity(context: Context, collBook: CollBookBean?, isCollected: Boolean) {
            startActivity(context, collBook, isCollected, null)
        }

        /** [source] is the tapped cover: the reader opens by growing out of it. */
        fun startActivity(context: Context, collBook: CollBookBean?, isCollected: Boolean, source: View?) {
            val intent = Intent(context, ReadActivity::class.java)
                .putExtra(EXTRA_IS_COLLECTED, isCollected)
                .putExtra(EXTRA_COLL_BOOK, createIntentBookPayload(collBook))
            val options = source?.let { cover ->
                ActivityOptionsCompat.makeScaleUpAnimation(cover, 0, 0, cover.width, cover.height).toBundle()
            }
            context.startActivity(intent, options)
        }
    }
}
