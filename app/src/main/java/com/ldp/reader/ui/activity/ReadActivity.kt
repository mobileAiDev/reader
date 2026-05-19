package com.ldp.reader.ui.activity

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
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
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import com.blankj.utilcode.util.BarUtils
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityReadBinding
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.local.ReadSettingManager
import com.ldp.reader.ui.activity.BookDetailActivity.Companion.startActivity
import com.ldp.reader.ui.activity.MainActivity
import com.ldp.reader.ui.adapter.CategoryAdapter
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.dialog.ReadSettingDialog
import com.ldp.reader.utils.BrightnessUtils
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.LogUtils
import com.ldp.reader.utils.ScreenUtils
import com.ldp.reader.utils.StringUtils
import com.ldp.reader.utils.ReadingStatsUtils
import com.ldp.reader.widget.page.PageLoader
import com.ldp.reader.widget.page.PageView.TouchListener
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

    /*****************view */
    private var mSettingDialog: ReadSettingDialog? = null
    private var mPageLoader: PageLoader? = null
    private var mTopInAnim: Animation? = null
    private var mTopOutAnim: Animation? = null
    private var mBottomInAnim: Animation? = null
    private var mBottomOutAnim: Animation? = null
    private var mCategoryAdapter: CategoryAdapter? = null
    private var mCollBook: CollBookBean? = null
    private var readingSessionStartMs = 0L
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
    private var isFullScreen = false
    private var isRegistered = false
    private var mBookId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun initData(savedInstanceState: Bundle?) {
        super.initData(savedInstanceState)
        mCollBook = intent.getParcelableExtra(EXTRA_COLL_BOOK)
        isCollected = intent.getBooleanExtra(EXTRA_IS_COLLECTED, false)
        isNightMode = ReadSettingManager.getInstance().isNightMode
        isFullScreen = ReadSettingManager.getInstance().isFullScreen
        if (mCollBook == null) {
            return
        }
        mBookId = mCollBook!!._id
    }

    override fun onNewIntent(intent: Intent) {
        Log.d("+新处理", "通知")
        finish()
        startActivity(intent)
        super.onNewIntent(intent)
    }

    override fun setUpToolbar(toolbar: Toolbar?) {}

    override fun toolbarView(): Toolbar {
        return binding.toolbar
    }

    @SuppressLint("InvalidWakeLockTag")
    override fun initWidget() {
        super.initWidget()
        viewModel = ViewModelProvider(this)[ReadViewModel::class.java]
        observeReadState()
        BarUtils.transparentStatusBar(this)
        BarUtils.setNavBarLightMode(this, false)

//        // 如果 API < 18 取消硬件加速
//        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2
//                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
//            mPvPage.setLayerType(LAYER_TYPE_SOFTWARE, null);
//        }

        //获取页面加载器
        Log.d(TAG, "+initWidget")
        mPageLoader = binding!!.readPvPage.getPageLoader(mCollBook!!)
        //禁止滑动展示DrawerLayout
        binding!!.readDlSlide.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        //侧边打开后，返回键能够起作用
        binding!!.readDlSlide.isFocusableInTouchMode = false
        mSettingDialog = ReadSettingDialog(this, mPageLoader)
        setUpAdapter()

        //夜间模式按钮的状态
        toggleNightMode()

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

        //初始化BottomMenu
        initBottomMenu()
    }

    private fun observeReadState() {
        viewModel.categories.observe(this) { result ->
            showCategory(result.bookChapterList, result.bookId, result.isBiqugeLoaded)
        }
        viewModel.chapterFinishedEvents.observe(this) { isRefresh ->
            finishChapter(isRefresh)
        }
        viewModel.chapterErrorEvents.observe(this) {
            errorChapter()
        }
    }

    private fun initTopMenu() {
        if (Build.VERSION.SDK_INT >= 19) {
            binding!!.readAblTopMenu.setPadding(0, ScreenUtils.getStatusBarHeight(), 0, 0)
        }
    }

    private fun initBottomMenu() {
        //判断是否全屏
        if (ReadSettingManager.getInstance().isFullScreen) {
            //还需要设置mBottomMenu的底部高度
//            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mLlBottomMenu.getLayoutParams();
//            params.bottomMargin = ScreenUtils.getNavigationBarHeight();
//            mLlBottomMenu.setLayoutParams(params);
        } else {
            //设置mBottomMenu的底部距离
            val params = binding!!.readLlBottomMenu.layoutParams as MarginLayoutParams
            params.bottomMargin = 0
            binding!!.readLlBottomMenu.layoutParams = params
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged: " + binding!!.readAblTopMenu.measuredHeight)
    }

    private fun toggleNightMode() {
        if (isNightMode) {
            binding!!.readTvNightMode.text = StringUtils.getString(R.string.nb_mode_morning)
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_read_menu_morning)
            binding!!.readTvNightMode.setCompoundDrawablesWithIntrinsicBounds(
                null,
                drawable,
                null,
                null
            )
        } else {
            binding!!.readTvNightMode.text = StringUtils.getString(R.string.nb_mode_night)
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_read_menu_night)
            binding!!.readTvNightMode.setCompoundDrawablesWithIntrinsicBounds(
                null,
                drawable,
                null,
                null
            )
        }
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
                }

                override fun requestChapters(requestChapters: List<TxtChapter>) {
                    Log.d(TAG, "+requestChapters")
                    viewModel.loadChapter(mBookId, mCollBook!!, requestChapters)
                    mHandler.sendEmptyMessage(WHAT_CATEGORY)
                    //隐藏提示
                    binding!!.readTvPageTip.visibility = View.GONE
                }

                override fun onCategoryFinish(chapters: List<TxtChapter>) {
                    for (chapter in chapters) {
                        chapter.title = chapter.title
                    }
                    mCategoryAdapter!!.refreshItems(chapters)
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
                    if (binding!!.readLlBottomMenu.visibility == View.VISIBLE) {
                        //显示标题
                        binding!!.readTvPageTip.text =
                            (progress + 1).toString() + "/" + (binding!!.readSbChapterProgress.max + 1)
                        binding!!.readTvPageTip.visibility = View.VISIBLE
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    //进行切换
                    val pagePos = binding!!.readSbChapterProgress.progress
                    if (pagePos != mPageLoader!!.pagePos) {
                        mPageLoader!!.skipToPage(pagePos)
                    }
                    //隐藏提示
                    binding!!.readTvPageTip.visibility = View.GONE
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

            override fun prePage() {}
            override fun nextPage() {}
            override fun cancel() {}
        })
        binding!!.readIvCategory.onItemClickListener =
            AdapterView.OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
                binding!!.readDlSlide.closeDrawer(Gravity.LEFT)
                Log.d("+点击章节", position.toString() + "")
                mPageLoader!!.skipToChapter(position)
            }
        binding!!.readTvCategory.setOnClickListener { v: View? ->
            //移动到指定位置
            if (mCategoryAdapter!!.count > 0) {
                binding!!.readIvCategory.setSelection(mPageLoader!!.chapterPos)
            }
            //切换菜单
            toggleMenu(true)
            //打开侧滑动栏
            binding!!.readDlSlide.openDrawer(Gravity.LEFT)
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
        binding!!.readTvNightMode.setOnClickListener { v: View? ->
            isNightMode = if (isNightMode) {
                false
            } else {
                true
            }
            mPageLoader!!.setNightMode(isNightMode)
            toggleNightMode()
        }
        binding!!.readTvBrief.setOnClickListener { startActivity(this@ReadActivity, mBookId) }

        binding.tvChangeSource.setOnClickListener {
            val adapter = mCategoryAdapter
            val chapterPos = mPageLoader!!.chapterPos
            if (adapter == null || adapter.count == 0 || chapterPos !in 0 until adapter.count) {
                com.ldp.reader.utils.ToastUtils.show("目录加载中，请稍后再试")
                return@setOnClickListener
            }
            sourceIndex++;
            viewModel.refreshChapter(
                mBookId,
                mCollBook!!,
                adapter.getItem(chapterPos),
                sourceIndex
            )
        }
        mSettingDialog!!.setOnDismissListener { dialog: DialogInterface? -> }
    }

    /**
     * 隐藏阅读界面的菜单显示
     *
     * @return 是否隐藏成功
     */
    private fun hideReadMenu(): Boolean {
        if (binding!!.readAblTopMenu.visibility == View.VISIBLE) {
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
        initMenuAnim()
        if (binding!!.readAblTopMenu.visibility == View.VISIBLE) {
            //关闭
            binding!!.readAblTopMenu.startAnimation(mTopOutAnim)
            binding!!.readLlBottomMenu.startAnimation(mBottomOutAnim)
            binding!!.readAblTopMenu.visibility = View.GONE
            binding!!.readLlBottomMenu.visibility = View.GONE
            binding!!.readTvPageTip.visibility = View.GONE
        } else {
            binding!!.readAblTopMenu.visibility = View.VISIBLE
            binding!!.readLlBottomMenu.visibility = View.VISIBLE
            binding!!.readLlBottomMenu.startAnimation(mBottomInAnim)
        }
    }

    //初始化菜单动画
    private fun initMenuAnim() {
        if (mTopInAnim != null) return
        mTopInAnim = AnimationUtils.loadAnimation(this, R.anim.slide_top_in)
        mTopOutAnim = AnimationUtils.loadAnimation(this, R.anim.slide_top_out)
        mBottomInAnim = AnimationUtils.loadAnimation(this, R.anim.slide_bottom_in)
        mBottomOutAnim = AnimationUtils.loadAnimation(this, R.anim.slide_bottom_out)
        //退出的速度要快
        mTopOutAnim?.setDuration(200)
        mBottomOutAnim?.setDuration(200)
    }

    override fun processLogic() {
        super.processLogic()
        // 如果是已经收藏的，那么就从数据库中获取目录
        if (isCollected) {
            val bookChapterBeen = BookRepository.getInstance().getBookChapters(mBookId)
            // 设置 CollBook
            mPageLoader!!.collBook.bookChapters = bookChapterBeen
            // 刷新章节列表
            mPageLoader!!.refreshChapterList()
            if (mCollBook!!.isLocal()) {
                return
            }
            viewModel.loadCategory(mBookId, mCollBook!!)
        } else {
            // 从网络中获取目录
            viewModel.loadCategory(mBookId, mCollBook!!)
        }
    }

    /***************************view */
    private fun showCategory(
        bookChapters: List<BookChapterBean>,
        bookId: String,
        isBiqugeLoaded: Boolean
    ) {
        mPageLoader!!.collBook.bookChapters = bookChapters
        mPageLoader!!.refreshChapterList()

        // 如果是目录更新的情况，那么就需要存储更新数据
        if (mCollBook!!.isUpdate() && isCollected) {
            BookRepository.getInstance()
                .saveBookChaptersWithAsync(bookChapters)
        }
    }

    private fun finishChapter(isRefresh: Boolean) {
        if (mPageLoader!!.pageStatus == PageLoader.STATUS_LOADING || isRefresh) {
            mHandler.sendEmptyMessage(WHAT_CHAPTER)
            Log.d("+finishChapter", "加载")
        }
        // 当完成章节的时候，刷新列表
        mCategoryAdapter!!.notifyDataSetChanged()
        Log.d("+finishChapter", "完成")
    }

    private fun errorChapter() {
        if (mPageLoader!!.pageStatus == PageLoader.STATUS_LOADING) {
            mPageLoader!!.chapterError()
        }
    }

    override fun onBackPressed() {
        if (binding!!.readAblTopMenu.visibility == View.VISIBLE) {
            // 非全屏下才收缩，全屏下直接退出
            if (!ReadSettingManager.getInstance().isFullScreen) {
                toggleMenu(true)
                return
            }
        } else if (mSettingDialog!!.isShowing) {
            mSettingDialog!!.dismiss()
            return
        } else if (binding!!.readDlSlide.isDrawerOpen(Gravity.LEFT)) {
            binding!!.readDlSlide.closeDrawer(Gravity.LEFT)
            return
        }
        if (!mCollBook!!.isLocal() && !isCollected
            && !mCollBook!!.bookChapters!!.isEmpty()
        ) {
            val alertDialog = AlertDialog.Builder(this)
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
        readingSessionStartMs = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        commitReadingDuration()
        mWakeLock!!.release()
        if (isCollected) {
            mPageLoader!!.saveRecord()
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterBrightObserver()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun commitReadingDuration() {
        if (readingSessionStartMs <= 0L) {
            return
        }
        val endMs = System.currentTimeMillis()
        ReadingStatsUtils.recordReading(mCollBook?._id, readingSessionStartMs, endMs)
        readingSessionStartMs = 0L
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MORE_SETTING) {
            val fullScreen = ReadSettingManager.getInstance().isFullScreen
            if (isFullScreen != fullScreen) {
                isFullScreen = fullScreen
                // 刷新BottomMenu
                initBottomMenu()
            }
        }
    }

    override fun getViewBinding(): ActivityReadBinding {
        return ActivityReadBinding.inflate(layoutInflater)
    }

    companion object {
        private const val TAG = "ReadActivity"
        const val REQUEST_MORE_SETTING = 1
        const val EXTRA_COLL_BOOK = "extra_coll_book"
        const val EXTRA_IS_COLLECTED = "extra_is_collected"
        private const val WHAT_CATEGORY = 1
        private const val WHAT_CHAPTER = 2
        fun startActivity(context: Context, collBook: CollBookBean?, isCollected: Boolean) {
            context.startActivity(
                Intent(context, ReadActivity::class.java)
                    .putExtra(EXTRA_IS_COLLECTED, isCollected)
                    .putExtra(EXTRA_COLL_BOOK, collBook)
            )
        }
    }
}
