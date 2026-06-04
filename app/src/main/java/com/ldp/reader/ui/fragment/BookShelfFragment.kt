package com.ldp.reader.ui.fragment

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.content.Intent
import android.content.DialogInterface
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.audio.AudioPlaybackService
import com.ldp.reader.audio.AudioPlaybackStateStore
import com.ldp.reader.databinding.DialogDeleteBinding
import com.ldp.reader.databinding.FragmentBookshelfBinding
import com.ldp.reader.databinding.ViewEmptyBookShelfBinding
import com.ldp.reader.media.MediaShelfItem
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.fragment.BookShelfViewModel.FilterKey
import com.ldp.reader.ui.activity.FileSystemActivity
import com.ldp.reader.ui.activity.AudioPlayerActivity
import com.ldp.reader.ui.activity.ComicReadActivity
import com.ldp.reader.ui.activity.LoginActivity
import com.ldp.reader.ui.activity.ReadActivity
import com.ldp.reader.ui.activity.ReadingStatsActivity
import com.ldp.reader.ui.activity.SourceEngineActivity
import com.ldp.reader.ui.adapter.CollBookAdapter
import com.ldp.reader.ui.audio.AudioCoverChrome
import com.ldp.reader.ui.base.BaseFragment
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.ui.widget.BookshelfFilterMenuView
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ReadingStatsUtils
import com.ldp.reader.utils.ToastUtils
import com.ldp.reader.widget.refresh.ScrollRefreshRecyclerView
import com.mob.pushsdk.MobPush
import java.io.File
import kotlin.math.abs

/**
 * Created by ldp on 17-4-15.
 */
class BookShelfFragment : BaseFragment<FragmentBookshelfBinding>() {
    lateinit var mRvContent: ScrollRefreshRecyclerView

    /** */
    private var mCollBookAdapter: CollBookAdapter? = null
    private var currentShelfFilter = FilterKey.ALL
    private var bookshelfFilterMenuView: BookshelfFilterMenuView? = null
    private var isEditMode = false
    private var homeAudioCoverAnimator: ObjectAnimator? = null
    private var audioFloatDownX = 0f
    private var audioFloatDownY = 0f
    private var audioFloatStartX = 0f
    private var audioFloatStartY = 0f
    private var audioFloatDragged = false
    private lateinit var viewModel: BookShelfViewModel
    private val loginSyncLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK &&
                BookshelfSyncRequest.isRequested(result.data)
            ) {
                requestBookShelfSync()
            }
        }

    //是否是第一次进入
    private var isInit = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun initWidget(savedInstanceState: Bundle?) {
        super.initWidget(savedInstanceState)
        viewModel = ViewModelProvider(this)[BookShelfViewModel::class.java]
        observeBookShelfState()
        binding?.apply {
            mRvContent = this.bookShelfRvContent
            setUpAdapter()
            AudioCoverChrome.configureCircularCover(homeBookshelfAudioCover)
            updateFilterLabel()
            renderMediaShelf()
        }

    }

    private fun observeBookShelfState() {
        viewModel.collBooks.observe(viewLifecycleOwner) { collBooks ->
            finishRefresh(collBooks)
        }
        viewModel.updateFinishedEvents.observe(viewLifecycleOwner) {
            finishUpdate()
        }
        viewModel.syncFinishedEvents.observe(viewLifecycleOwner) {
            finishSyncBook()
        }
        viewModel.completeEvents.observe(viewLifecycleOwner) {
            complete()
        }
        viewModel.errorTips.observe(viewLifecycleOwner) { error ->
            showErrorTip(error)
        }
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentBookshelfBinding {
        return FragmentBookshelfBinding.inflate(inflater, container, false)
    }

    private fun setUpAdapter() {
        //添加Footer
        mCollBookAdapter = CollBookAdapter()
        mRvContent!!.setLayoutManager(GridLayoutManager(context, 3))
        mRvContent!!.addItemDecoration(ReaderBookGridSpacingDecoration(3, dp(16)))
        mRvContent!!.setAdapter(mCollBookAdapter)
        mRvContent!!.getReyclerView().clipToPadding = false
        mRvContent!!.setColorSchemeColors(resources.getColor(R.color.home_primary))

    }

    override fun initClick() {
        super.initClick()
        binding?.homeReadingTimeChip?.setOnClickListener {
            startActivity(Intent(requireContext(), ReadingStatsActivity::class.java))
        }
        binding?.homeBookshelfFilter?.setOnClickListener { showFilterMenu() }
        binding?.homeBookshelfSourceEngine?.setOnClickListener {
            SourceEngineActivity.start(requireContext())
        }
        binding?.homeBookshelfFilterEmptyReset?.setOnClickListener { resetShelfFilter() }
        binding?.homeBookshelfFilterEmptyImport?.setOnClickListener { openLocalImport() }
        binding?.bookShelfRvContent?.emptyView?.let { emptyView ->
            ViewEmptyBookShelfBinding.bind(emptyView)
                .bookShelfEmptyImport
                .setOnClickListener { openLocalImport() }
        }
        binding?.homeBookshelfEdit?.setOnClickListener {
            setBookshelfEditMode(!isEditMode)
        }
        installAudioFloatDrag()
        binding?.homeBookshelfAudioFloat?.setOnClickListener {
            AudioPlaybackStateStore.current(requireContext())?.let { nowPlaying ->
                MediaShelfStore.restoreForChapter(requireContext(), nowPlaying.chapterRouteId)
                AudioPlayerActivity.start(requireContext(), nowPlaying.chapterRouteId, nowPlaying.title)
            }
        }
        binding?.homeBookshelfAudioPlay?.setOnClickListener {
            requireContext().startService(
                Intent(requireContext(), AudioPlaybackService::class.java)
                    .setAction(AudioPlaybackService.ACTION_TOGGLE)
            )
            binding?.homeBookshelfAudioFloat?.postDelayed({ renderAudioFloat() }, 250L)
        }
        binding?.homeBookshelfAudioClose?.setOnClickListener {
            requireContext().startService(
                Intent(requireContext(), AudioPlaybackService::class.java)
                    .setAction(AudioPlaybackService.ACTION_STOP)
            )
            AudioPlaybackStateStore.clear(requireContext())
            renderAudioFloat()
        }
        binding?.homeBookshelfSelectAll?.setOnClickListener {
            toggleSelectAllBooks()
        }
        binding?.homeBookshelfDeleteSelected?.setOnClickListener {
            deleteSelectedBooks()
        }
        mRvContent!!.setOnRefreshListener { viewModel.updateCollBooks(mCollBookAdapter!!.items) }
        mCollBookAdapter!!.setOnItemClickListener { view: View?, pos: Int ->
            if (isEditMode) {
                mCollBookAdapter!!.toggleSelection(mCollBookAdapter!!.getItem(pos))
                updateEditUi()
                return@setOnItemClickListener
            }
            //如果是本地文件，首先判断这个文件是否存在
            val collBook = mCollBookAdapter!!.getItem(pos)
            if (collBook.isLocal()) {
                //id表示本地文件的路径
                val path = collBook.cover
                val file = File(path)
                //判断这个本地文件是否存在
                if (file.exists() && file.length() != 0L) {
                    ReadActivity.startActivity(
                        requireContext(),
                        mCollBookAdapter!!.getItem(pos), true
                    )
                } else {
                    val tip = requireContext().getString(R.string.nb_bookshelf_book_not_exist)
                    //提示(从目录中移除这个文件)
                    AlertDialog.Builder((context)!!)
                        .setTitle(resources.getString(R.string.nb_common_tip))
                        .setMessage(tip)
                        .setPositiveButton(resources.getString(R.string.nb_common_sure),
                            DialogInterface.OnClickListener { dialog, which -> deleteBook(collBook) })
                        .setNegativeButton(resources.getString(R.string.nb_common_cancel), null)
                        .show()
                }
            } else {
                ReadActivity.startActivity(
                    requireContext(),
                    mCollBookAdapter!!.getItem(pos), true
                )
            }
        }
        mCollBookAdapter!!.setOnItemLongClickListener { v: View?, pos: Int ->
            showBookLongPressFeedback(v)
            if (isEditMode) {
                mCollBookAdapter!!.toggleSelection(mCollBookAdapter!!.getItem(pos))
                updateEditUi()
                return@setOnItemLongClickListener true
            }
            //开启Dialog,最方便的Dialog,就是AlterDialog
            openItemDialog(mCollBookAdapter!!.getItem(pos))
            true
        }
    }

    private fun showBookLongPressFeedback(view: View?) {
        view ?: return
        view.isSelected = true
        view.postDelayed({ view.isSelected = false }, BOOK_LONG_PRESS_FEEDBACK_MS)
    }

    override fun processLogic() {
        super.processLogic()
        updateReadingSummary()
        //  mRvContent.startRefresh();
    }

    private fun showFilterMenu() {
        if (bookshelfFilterMenuView != null) {
            bookshelfFilterMenuView?.dismiss()
            return
        }
        setBookshelfEditMode(false)
        val menuView = BookshelfFilterMenuView(requireContext())
        menuView.onFilterSelected = { key ->
            currentShelfFilter = key
            refreshShelfDisplay(BookRepository.getInstance().collBooks)
        }
        menuView.onDismiss = {
            bookshelfFilterMenuView = null
        }
        bookshelfFilterMenuView = menuView
        (requireActivity().window.decorView as ViewGroup).addView(
            menuView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        menuView.showFrom(binding?.homeBookshelfFilter, currentShelfFilter)
    }

    private fun refreshShelfDisplay(collBookBeans: List<CollBookBean>) {
        val filteredBooks = filterShelfBooks(collBookBeans)
        mCollBookAdapter!!.refreshItems(filteredBooks)
        updateFilterLabel()
        updateFilterEmptyState(filteredBooks.size)
        updateEditUi()
    }

    private fun filterShelfBooks(collBookBeans: List<CollBookBean>): List<CollBookBean> {
        val nowMillis = System.currentTimeMillis()
        return collBookBeans.filter { book ->
            BookShelfViewModel.matchesFilter(
                currentShelfFilter,
                book,
                BookRepository.getInstance().getBookRecord(book._id),
                BookshelfLocalProgressStore.getProgressTenths(book._id),
                nowMillis
            )
        }
    }

    private fun setBookshelfEditMode(editMode: Boolean) {
        isEditMode = editMode
        mCollBookAdapter?.setEditMode(editMode)
        updateEditUi()
    }

    fun exitEditModeIfNeeded(): Boolean {
        if (!isEditMode) {
            return false
        }
        setBookshelfEditMode(false)
        return true
    }

    fun exitFilterMenuIfNeeded(): Boolean {
        if (bookshelfFilterMenuView == null) {
            return false
        }
        bookshelfFilterMenuView?.dismiss()
        return true
    }

    private fun toggleSelectAllBooks() {
        if (!isEditMode) {
            return
        }
        if (mCollBookAdapter!!.isAllVisibleSelected) {
            mCollBookAdapter!!.clearSelection()
        } else {
            mCollBookAdapter!!.selectAllVisible()
        }
        updateEditUi()
    }

    private fun deleteSelectedBooks() {
        if (!isEditMode) {
            return
        }
        val selectedBooks = mCollBookAdapter!!.selectedBooks
        if (selectedBooks.isEmpty()) {
            ToastUtils.show("请选择书籍")
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除书籍")
            .setMessage("确定从书架移除选中的${selectedBooks.size}本书吗？")
            .setPositiveButton(resources.getString(R.string.nb_common_sure)) { _, _ ->
                var hasOnlineBook = false
                selectedBooks.forEach { book ->
                    if (!book.isLocal()) {
                        hasOnlineBook = true
                    }
                    BookRepository.getInstance().deleteCollBook(book)
                    BookRepository.getInstance().deleteBookRecord(book._id)
                }
                mCollBookAdapter!!.removeItems(selectedBooks)
                setBookshelfEditMode(false)
                if (hasOnlineBook) {
                    synBook()
                }
                ToastUtils.show("已从书架移除")
            }
            .setNegativeButton(resources.getString(R.string.nb_common_cancel), null)
            .show()
    }

    private fun updateEditUi() {
        binding?.apply {
            homeBookshelfEdit.text = if (isEditMode) "完成" else "编辑"
            homeBookshelfFilter.visibility = if (isEditMode) View.GONE else View.VISIBLE
            homeBookshelfToolDivider.visibility = if (isEditMode) View.GONE else View.VISIBLE
            homeBookshelfSourceEngine.visibility = View.GONE
            homeBookshelfSourceDivider.visibility = View.GONE
            homeBookshelfEditBar.visibility = if (isEditMode) View.VISIBLE else View.GONE
            updateFilterLabel()
            if (isEditMode) {
                val selectedCount = mCollBookAdapter?.selectedCount ?: 0
                homeReadingTimeChip.text = "已选择${selectedCount}本"
                homeBookshelfEdit.isEnabled = true
                homeBookshelfEdit.alpha = 1f
                homeBookshelfSelectAll.text =
                    if (mCollBookAdapter?.isAllVisibleSelected == true) "取消全选" else "全选"
                homeBookshelfDeleteSelected.text =
                    if (selectedCount > 0) "删除($selectedCount)" else "删除"
            } else {
                val hasVisibleBooks = (mCollBookAdapter?.itemCount ?: 0) > 0
                homeBookshelfEdit.isEnabled = hasVisibleBooks
                homeBookshelfEdit.alpha = if (hasVisibleBooks) 1f else 0.38f
                updateReadingSummary()
            }
            renderMediaShelf()
            renderAudioFloat()
        }
    }

    private fun updateReadingSummary() {
        binding?.homeReadingTimeChip?.text = ReadingStatsUtils.getWeeklyReadingLabel()
    }

    private fun updateFilterLabel() {
        binding?.homeBookshelfFilter?.text = BookShelfViewModel.filterToolbarLabel(currentShelfFilter)
    }

    private fun updateFilterEmptyState(visibleBookCount: Int) {
        val showFilterEmpty = BookShelfViewModel.shouldShowFilterEmpty(
            currentShelfFilter,
            visibleBookCount
        )
        binding?.apply {
            homeBookshelfFilterEmptyTitle.text =
                BookShelfViewModel.filterEmptyTitle(currentShelfFilter)
            homeBookshelfFilterEmptyReset.text = BookShelfViewModel.filterEmptyResetText()
            homeBookshelfFilterEmptyImport.text = BookShelfViewModel.emptyImportText()
            homeBookshelfFilterEmpty.visibility = if (showFilterEmpty) View.VISIBLE else View.GONE
            bookShelfRvContent.visibility = if (showFilterEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun resetShelfFilter() {
        currentShelfFilter = FilterKey.ALL
        refreshShelfDisplay(BookRepository.getInstance().collBooks)
    }

    private fun openLocalImport() {
        startActivity(Intent(requireContext(), FileSystemActivity::class.java))
    }

    fun requestBookShelfSync() {
        val token = SharedPreUtils.getInstance().getString("token")
        if (TextUtils.isEmpty(token)) {
            loginSyncLauncher.launch(LoginActivity.syncIntent(requireContext()))
            return
        }
        if (("password" == SharedPreUtils.getInstance().getString("loginType"))) {
            viewModel.getBookShelf(token)
        } else {
            val mobile = SharedPreUtils.getInstance().getString("userName")
            viewModel.getBookShelfByMobile(mobile, token)
        }
    }


    private fun openItemDialog(collBook: CollBookBean) {
        val menus: Array<String>
        menus = if (collBook.isLocal()) {
            resources.getStringArray(R.array.nb_menu_local_book)
        } else {
            resources.getStringArray(R.array.nb_menu_net_book)
        }
        val collBookDialog = AlertDialog.Builder(
            requireContext()
        )
            .setTitle(collBook.title)
            .setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1, menus
                )
            ) { dialog: DialogInterface?, which: Int -> onItemMenuClick(menus[which], collBook) }
            .setNegativeButton(null, null)
            .setPositiveButton(null, null)
            .create()
        collBookDialog.show()
    }

    private fun onItemMenuClick(which: String, collBook: CollBookBean) {
        when (which) {
            "置顶" -> {}
            "删除" -> deleteBook(collBook)
            "批量管理" -> {}
            else -> {}
        }
    }

    /**
     * 默认删除本地文件
     *
     * @param collBook
     */
    private fun deleteBook(collBook: CollBookBean) {
        if (collBook.isLocal()) {
            val deleteBinding = DialogDeleteBinding.inflate(LayoutInflater.from(requireContext()))
            AlertDialog.Builder(requireContext())
                .setTitle("删除文件")
                .setView(deleteBinding.root)
                .setPositiveButton(resources.getString(R.string.nb_common_sure)) { dialog, which ->
                    val isSelected = deleteBinding.deleteCbSelect.isChecked
                    if (isSelected) {
                        val progressDialog = ProgressDialog(context)
                        progressDialog.setMessage("正在删除中")
                        progressDialog.show()
                        //删除
                        val file = File(collBook.cover)
                        if (file.exists()) file.delete()
                        BookRepository.getInstance().deleteCollBook(collBook)
                        BookRepository.getInstance().deleteBookRecord(collBook._id)

                        //从Adapter中删除
                        mCollBookAdapter!!.removeItem(collBook)
                        progressDialog.dismiss()
                    } else {
                        BookRepository.getInstance().deleteCollBook(collBook)
                        BookRepository.getInstance().deleteBookRecord(collBook._id)
                        //从Adapter中删除
                        mCollBookAdapter!!.removeItem(collBook)
                    }
                }
                .setNegativeButton(resources.getString(R.string.nb_common_cancel), null)
                .show()
        } else {
            BookRepository.getInstance().deleteCollBook(collBook)
            BookRepository.getInstance().deleteBookRecord(collBook._id)
            //从Adapter中删除
            mCollBookAdapter!!.removeItem(collBook)
            synBook()
        }
    }

    private fun synBook() {
        val collBookBeans = BookRepository.getInstance().collBooks
        val bookIds = BookShelfViewModel.onlineBookIdsFrom(collBookBeans)
        if ("password" == SharedPreUtils.getInstance().getString("loginType")) {
            viewModel.setBookShelf(bookIds)
        } else {
            Log.e(TAG, "onClick:  正在删除中 同步书架")
            val mobileToken = SharedPreUtils.getInstance().getString("token")
            val mobile = SharedPreUtils.getInstance().getString("userName")
            if (mobileToken.isEmpty() || mobile.isEmpty()) {
                return
            }
            viewModel.setBookShelfByMobile(bookIds, mobile, mobileToken)
        }
    }

    /*******************************************************************8 */
    private fun complete() {
        if (mRvContent!!.isRefreshing) {
            mRvContent!!.finishRefresh()
        }
    }

    private fun finishRefresh(collBookBeans: List<CollBookBean>) {
        refreshShelfDisplay(collBookBeans)
        //如果是初次进入，则更新书籍信息
        if (isInit) {
            isInit = false
            mRvContent!!.post { viewModel.updateCollBooks(mCollBookAdapter!!.items) }
        }
    }

    private fun finishUpdate() {
        //重新从数据库中获取数据
        requireActivity().runOnUiThread {
            refreshShelfDisplay(BookRepository.getInstance().collBooks)
        }
    }

    private fun finishSyncBook() {
        ToastUtils.show("书架同步成功")
    }

    private fun showErrorTip(error: String?) {
//        mRvContent.setTip(error);
//        mRvContent.showTip();
        com.blankj.utilcode.util.ToastUtils.showLong(error)
    }

    override fun onResume() {
        super.onResume()
//        rxPermissions = RxPermissions(this)
//        permission
        updateReadingSummary()
        renderMediaShelf()
        renderAudioFloat()
        viewModel.refreshCollBooks()
        initMobPush()
    }

    override fun onDestroyView() {
        homeAudioCoverAnimator?.cancel()
        homeAudioCoverAnimator = null
        super.onDestroyView()
    }

    var registrationId: String? = null
    private fun initMobPush() {
        Log.d(TAG, "preDirectLogin: registrationId")
        registrationId = SharedPreUtils.getInstance().getString("registrationId")
        Log.d(TAG, "onCallback: registrationId  $registrationId")
        MobPush.setShowBadge(true)
        if (TextUtils.isEmpty(registrationId)) {
            MobPush.getRegistrationId { s: String ->
                Log.d(TAG, "onCallback: registrationId  $s")
                registrationId = s
                SharedPreUtils.getInstance().putString("registrationId", registrationId)
            }
        }
    }

    companion object {
        private const val TAG = "BookShelfFragment"
        private const val BOOK_LONG_PRESS_FEEDBACK_MS = 180L
        private const val MEDIA_SHELF_MAX_ITEMS = 12
        private const val AUDIO_FLOAT_DRAG_SLOP = 12f
        private const val AUDIO_FLOAT_PREFS = "reader_audio_float"
        private const val AUDIO_FLOAT_X_FRACTION = "x_fraction"
        private const val AUDIO_FLOAT_Y_FRACTION = "y_fraction"
    }

    private fun renderAudioFloat() {
        val nowPlaying = AudioPlaybackStateStore.current(requireContext())
        binding?.apply {
            homeBookshelfAudioFloat.visibility = if (nowPlaying == null || isEditMode) View.GONE else View.VISIBLE
            homeBookshelfAudioTitle.text = nowPlaying?.title.orEmpty()
            val playing = nowPlaying?.isPlaying == true
            homeBookshelfAudioPlay.setImageResource(
                if (playing) R.drawable.ic_audio_legado_pause_24 else R.drawable.ic_audio_legado_play_24
            )
            homeBookshelfAudioPlay.contentDescription = if (playing) "暂停" else "播放"
            homeAudioCoverAnimator = AudioCoverChrome.updateRotation(
                homeBookshelfAudioCover,
                playing && !isEditMode,
                homeAudioCoverAnimator
            )
            BookCoverLoader.load(
                listOfNotNull(nowPlaying?.coverUrl?.takeIf { it.isNotBlank() }),
                homeBookshelfAudioCover,
                R.drawable.ic_book_cover_placeholder,
                circle = true
            )
            if (nowPlaying != null && !isEditMode) {
                homeBookshelfAudioFloat.post { applyAudioFloatPosition(homeBookshelfAudioFloat) }
            }
        }
    }

    private fun installAudioFloatDrag() {
        binding?.homeBookshelfAudioFloat?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    audioFloatDownX = event.rawX
                    audioFloatDownY = event.rawY
                    audioFloatStartX = view.x
                    audioFloatStartY = view.y
                    audioFloatDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - audioFloatDownX
                    val dy = event.rawY - audioFloatDownY
                    if (!audioFloatDragged && (abs(dx) > AUDIO_FLOAT_DRAG_SLOP || abs(dy) > AUDIO_FLOAT_DRAG_SLOP)) {
                        audioFloatDragged = true
                    }
                    if (audioFloatDragged) {
                        moveAudioFloat(view, audioFloatStartX + dx, audioFloatStartY + dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (audioFloatDragged) {
                        persistAudioFloatPosition(view)
                    } else {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun moveAudioFloat(view: View, targetX: Float, targetY: Float) {
        val parent = view.parent as? View ?: return
        if (parent.width <= view.width || parent.height <= view.height) return
        view.x = targetX.coerceIn(0f, (parent.width - view.width).toFloat())
        view.y = targetY.coerceIn(0f, (parent.height - view.height).toFloat())
    }

    private fun applyAudioFloatPosition(view: View) {
        val parent = view.parent as? View ?: return
        if (parent.width <= view.width || parent.height <= view.height) return
        val prefs = requireContext().getSharedPreferences(AUDIO_FLOAT_PREFS, Context.MODE_PRIVATE)
        val xFraction = prefs.getFloat(AUDIO_FLOAT_X_FRACTION, -1f)
        val yFraction = prefs.getFloat(AUDIO_FLOAT_Y_FRACTION, -1f)
        if (xFraction < 0f || yFraction < 0f) return
        val maxX = (parent.width - view.width).toFloat()
        val maxY = (parent.height - view.height).toFloat()
        view.x = (maxX * xFraction).coerceIn(0f, maxX)
        view.y = (maxY * yFraction).coerceIn(0f, maxY)
    }

    private fun persistAudioFloatPosition(view: View) {
        val parent = view.parent as? View ?: return
        val maxX = (parent.width - view.width).coerceAtLeast(1).toFloat()
        val maxY = (parent.height - view.height).coerceAtLeast(1).toFloat()
        requireContext().getSharedPreferences(AUDIO_FLOAT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(AUDIO_FLOAT_X_FRACTION, (view.x / maxX).coerceIn(0f, 1f))
            .putFloat(AUDIO_FLOAT_Y_FRACTION, (view.y / maxY).coerceIn(0f, 1f))
            .apply()
    }

    private fun renderMediaShelf() {
        val currentBinding = binding ?: return
        val items = MediaShelfStore.items(requireContext())
            .filter { it.mediaKind == ReaderMediaKind.AUDIO || it.mediaKind == ReaderMediaKind.COMIC }
        currentBinding.homeMediaShelfPanel.visibility = if (items.isEmpty() || isEditMode) View.GONE else View.VISIBLE
        currentBinding.homeMediaShelfItems.removeAllViews()
        items.take(MEDIA_SHELF_MAX_ITEMS).forEach { item ->
            currentBinding.homeMediaShelfItems.addView(createMediaShelfCard(item))
        }
    }

    private fun createMediaShelfCard(item: MediaShelfItem): View {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = resources.getDrawable(R.drawable.bg_media_panel)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(216), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                rightMargin = dp(10)
            }
            setOnClickListener { openMediaShelfItem(item) }
            setOnLongClickListener {
                confirmRemoveMediaShelfItem(item)
                true
            }
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = resources.getDrawable(R.drawable.bg_media_cover)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(78))
        }
        BookCoverLoader.load(
            listOfNotNull(item.coverUrl.takeIf { it.isNotBlank() }),
            cover,
            R.drawable.ic_book_cover_placeholder
        )
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(10)
            }
        }
        val title = TextView(context).apply {
            text = item.title
            setTextColor(resources.getColor(R.color.home_text_primary))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val chapter = TextView(context).apply {
            text = item.currentChapterTitle.ifBlank { item.latest }.ifBlank { item.sourceName }
            setTextColor(resources.getColor(R.color.home_text_secondary))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        val progress = TextView(context).apply {
            text = mediaShelfProgressText(item)
            setTextColor(resources.getColor(R.color.home_text_tertiary))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        textColumn.addView(title)
        textColumn.addView(chapter)
        textColumn.addView(progress)
        card.addView(cover)
        card.addView(textColumn)
        return card
    }

    private fun openMediaShelfItem(item: MediaShelfItem) {
        MediaShelfStore.restoreItemRoutes(item)
        val chapterRouteId = item.currentChapterRouteId.ifBlank {
            item.routeSnapshot.chapters.firstOrNull()?.routeId.orEmpty()
        }
        if (chapterRouteId.isBlank()) {
            ToastUtils.show("目录未准备好")
            return
        }
        val title = item.currentChapterTitle.ifBlank { item.title }
        when (item.mediaKind) {
            ReaderMediaKind.AUDIO -> AudioPlayerActivity.start(requireContext(), chapterRouteId, title)
            ReaderMediaKind.COMIC -> ComicReadActivity.start(requireContext(), chapterRouteId, title)
            else -> Unit
        }
    }

    private fun confirmRemoveMediaShelfItem(item: MediaShelfItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setMessage("从媒体书架移除？")
            .setPositiveButton(resources.getString(R.string.nb_common_sure)) { _, _ ->
                MediaShelfStore.remove(requireContext(), item.id)
                renderMediaShelf()
                ToastUtils.show("已从媒体书架移除")
            }
            .setNegativeButton(resources.getString(R.string.nb_common_cancel), null)
            .show()
    }

    private fun mediaShelfProgressText(item: MediaShelfItem): String {
        return when (item.mediaKind) {
            ReaderMediaKind.AUDIO -> {
                if (item.audioPositionMs > 0L && item.audioDurationMs > 0L) {
                    "${formatMediaMillis(item.audioPositionMs)} / ${formatMediaMillis(item.audioDurationMs)}"
                } else {
                    "听书 | ${item.sourceName}"
                }
            }
            ReaderMediaKind.COMIC -> {
                val page = item.comicPageIndex + 1
                "漫画 | 第 ${page.coerceAtLeast(1)} 页"
            }
            else -> item.sourceName
        }
    }

    private fun formatMediaMillis(value: Long): String {
        val seconds = (value / 1_000L).coerceAtLeast(0L)
        val minutes = seconds / 60L
        val remain = seconds % 60L
        return "%02d:%02d".format(minutes, remain)
    }

    private class ReaderBookGridSpacingDecoration(
        private val spanCount: Int,
        private val spacingPx: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION || spanCount <= 0) {
                return
            }
            val column = position % spanCount
            outRect.left = column * spacingPx / spanCount
            outRect.right = spacingPx - (column + 1) * spacingPx / spanCount
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

}
