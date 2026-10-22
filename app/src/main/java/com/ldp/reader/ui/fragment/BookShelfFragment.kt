package com.ldp.reader.ui.fragment

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.audio.AudioMiniPlayerAction
import com.ldp.reader.audio.AudioMiniPlayerCommand
import com.ldp.reader.audio.AudioPlaybackProgressStore
import com.ldp.reader.audio.AudioPlaybackService
import com.ldp.reader.audio.AudioPlaybackStateStore
import com.ldp.reader.databinding.DialogBookshelfRemoveSheetBinding
import com.ldp.reader.databinding.FragmentBookshelfBinding
import com.ldp.reader.databinding.ViewEmptyBookShelfBinding
import com.ldp.reader.document.LocalTextSourceReferencePersistence
import com.ldp.reader.media.ComicReadingProgressStore
import com.ldp.reader.media.MediaShelfItem
import com.ldp.reader.media.MediaShelfStore
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.fragment.BookShelfViewModel.FilterKey
import com.ldp.reader.ui.activity.FileSystemActivity
import com.ldp.reader.ui.activity.AudioPlayerActivity
import com.ldp.reader.ui.activity.ComicReadActivity
import com.ldp.reader.ui.activity.LoginActivity
import com.ldp.reader.ui.activity.MainActivity
import com.ldp.reader.ui.activity.ReadActivity
import com.ldp.reader.ui.activity.ReadingStatsActivity
import com.ldp.reader.ui.activity.SearchActivity
import com.ldp.reader.ui.adapter.HomeShelfAdapter
import com.ldp.reader.ui.audio.AudioCoverChrome
import com.ldp.reader.ui.base.BaseFragment
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.ui.home.BookshelfPinStore
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.ui.home.ShelfPlankDecoration
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.ui.motion.Haptics
import com.ldp.reader.ui.motion.SpringValue
import com.ldp.reader.ui.theme.EdgeToEdge
import com.ldp.reader.ui.theme.InkThemeTraits
import com.ldp.reader.ui.widget.BookshelfFilterSheet
import com.ldp.reader.utils.ReadingStatsUtils
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils
import com.ldp.reader.widget.page.LocalTxtEncodingPreferences
import com.ldp.reader.widget.refresh.ScrollRefreshRecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tencent.mmkv.MMKV
import com.mob.pushsdk.MobPush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Created by ldp on 17-4-15.
 */
class BookShelfFragment : BaseFragment<FragmentBookshelfBinding>() {
    lateinit var mRvContent: ScrollRefreshRecyclerView

    /** */
    private var mCollBookAdapter: HomeShelfAdapter? = null
    private var currentShelfFilter = FilterKey.ALL
    private var isEditMode = false
    private var editBarSpring: SpringValue? = null
    private var navigationInsetBottom = 0
    private var hasPlayedEnterAnimation = false
    private var shelfBooks: List<CollBookBean> = emptyList()
    private var homeAudioCoverAnimator: ObjectAnimator? = null
    private var lastHomeAudioCoverUrl = ""
    private var lastAudioFloatRenderKey = ""
    private var lastAudioFloatRouteId = ""
    private var wasAudioFloatVisible = false
    private val audioFloatHandler = Handler(Looper.getMainLooper())
    private val audioFloatTicker = object : Runnable {
        override fun run() {
            if (!isAdded || binding == null) return
            renderAudioFloat()
            audioFloatHandler.postDelayed(this, AUDIO_FLOAT_REFRESH_MS)
        }
    }
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
        currentShelfFilter = restoredShelfFilter(
            savedInstanceState?.getString(STATE_SHELF_FILTER)
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SHELF_FILTER, currentShelfFilter.name)
        // Edit mode and its selection intentionally exit on recreation. Restoring a partial
        // destructive selection after the visible dataset changed would be unsafe.
        super.onSaveInstanceState(outState)
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
        viewModel.failureContexts.observe(viewLifecycleOwner) { context ->
            showErrorTip(context)
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
        mCollBookAdapter = HomeShelfAdapter()
        val grid = GridLayoutManager(context, 3)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (mCollBookAdapter!!.isFullSpan(position)) 3 else 1
            }
        }
        mRvContent!!.setLayoutManager(grid)
        mRvContent!!.addItemDecoration(ReaderBookGridSpacingDecoration(3, dp(14)))
        // The theme decides whether books stand on shelves and whether captions show under them.
        val traits = InkThemeTraits.of(requireContext())
        mCollBookAdapter!!.shelfCaptions = traits.shelfCaptions
        traits.shelfPlank?.let { plank ->
            mRvContent!!.addItemDecoration(
                ShelfPlankDecoration(plank, mCollBookAdapter!!::isFullSpan, dp(SHELF_SINK_DP), dp(SHELF_ROOM_DP))
            )
        }
        mRvContent!!.setAdapter(mCollBookAdapter)
        mRvContent!!.getReyclerView().apply {
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), 0)
            // Books fade as they pass under the header instead of being cut off at its edge.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(SHELF_FADE_DP))
            // Books rise into place, one after another, the first time the shelf appears.
            layoutAnimation = LayoutAnimationController(
                AnimationUtils.loadAnimation(context, R.anim.ink_item_enter),
                0.06f
            )
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                navigationInsetBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                updateShelfListBottomInset(binding?.homeBookshelfAudioFloat?.visibility == View.VISIBLE)
                insets
            }
        }
        mRvContent!!.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.ink_primary))
        mRvContent!!.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.ink_surface_raised)
        )
        binding?.homeBookshelfEditBar?.let { EdgeToEdge.liftAboveNavigation(it) }
        binding?.homeBookshelfAudioFloat?.let { EdgeToEdge.liftAboveNavigation(it) }
        editBarSpring = SpringValue(requireContext(), response = 0.42f, dampingRatio = 0.8f, restDelta = 0.002f).apply {
            listener = SpringValue.Listener { spring ->
                val bar = binding?.homeBookshelfEditBar ?: return@Listener
                val hidden = 1f - spring.value
                bar.translationY = hidden * dp(EDIT_BAR_TRAVEL_DP)
                bar.alpha = spring.value
                bar.visibility = if (hidden >= 0.999f && !spring.isRunning) View.GONE else View.VISIBLE
            }
            jumpTo(0f)
        }

    }

    override fun initClick() {
        super.initClick()
        binding?.homeReadingTimeChip?.setOnClickListener {
            startActivity(Intent(requireContext(), ReadingStatsActivity::class.java))
        }
        binding?.homeBookshelfFilter?.setOnClickListener { showFilterMenu() }
        binding?.homeBookshelfFilterEmptyReset?.setOnClickListener { resetShelfFilter() }
        binding?.bookShelfRvContent?.emptyView?.let { emptyView ->
            val emptyBinding = ViewEmptyBookShelfBinding.bind(emptyView)
            emptyBinding.bookShelfEmptyImport.setOnClickListener { openLocalImport() }
            emptyBinding.bookShelfEmptySearch.setOnClickListener {
                startActivity(Intent(requireContext(), SearchActivity::class.java))
            }
        }
        binding?.homeBookshelfEdit?.setOnClickListener {
            if (isEditMode) {
                setBookshelfEditMode(false)
            } else {
                enterEmptyBookshelfManageMode()
            }
        }
        installAudioFloatDrag()
        binding?.homeBookshelfAudioFloat?.setOnClickListener {
            AudioPlaybackStateStore.current(requireContext())?.let { nowPlaying ->
                val hasShelfItem = MediaShelfStore.hasItemForChapter(requireContext(), nowPlaying.chapterRouteId)
                val restored = MediaShelfStore.restoreForChapter(requireContext(), nowPlaying.chapterRouteId)
                traceAudioFloat("open_player", nowPlaying.chapterRouteId, nowPlaying.title, hasShelfItem, restored)
                AudioPlayerActivity.openCurrent(
                    requireContext(),
                    nowPlaying.chapterRouteId,
                    nowPlaying.title,
                    bookTitle = nowPlaying.bookTitle
                )
            }
        }
        binding?.homeBookshelfAudioPlay?.setOnClickListener {
            handleAudioFloatPlayClick()
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
            toggleSelectAllShelfItems()
        }
        binding?.homeBookshelfPinSelected?.setOnClickListener {
            toggleSelectedShelfBookPin()
        }
        binding?.homeBookshelfDeleteSelected?.setOnClickListener {
            confirmRemoveSelectedShelfItems()
        }
        mRvContent!!.setOnRefreshListener {
            // A shelf filter only changes presentation. Refreshing while viewing local,
            // unread, or recently-updated items must still check every online novel.
            viewModel.updateCollBooks(BookRepository.getInstance().collBooks)
        }
        mCollBookAdapter!!.onBookClick = bookClick@{ view: View?, collBook: CollBookBean ->
            if (isEditMode) {
                view?.let { Haptics.tick(it) }
                mCollBookAdapter!!.toggleSelection(collBook)
                updateEditUi()
                return@bookClick
            }
            openBookShelfItem(collBook, view)
        }
        mCollBookAdapter!!.onBookLongClick = bookLongClick@{ v: View?, collBook: CollBookBean ->
            showBookLongPressFeedback(v)
            enterBookshelfManageMode(collBook)
            true
        }
        mCollBookAdapter!!.onMediaClick = { view: View?, item: MediaShelfItem ->
            if (isEditMode) {
                view?.let { Haptics.tick(it) }
                mCollBookAdapter!!.toggleMediaSelection(item)
                updateEditUi()
            } else {
                openMediaShelfItem(item)
            }
        }
        mCollBookAdapter!!.onMediaLongClick = { view: View?, item: MediaShelfItem ->
            showBookLongPressFeedback(view)
            enterBookshelfManageMode(item)
            true
        }
    }

    private fun showBookLongPressFeedback(view: View?) {
        view ?: return
        view.isSelected = true
        view.postDelayed({ view.isSelected = false }, BOOK_LONG_PRESS_FEEDBACK_MS)
    }

    private fun openBookShelfItem(collBook: CollBookBean, cover: View?) {
        if (collBook.isLocal()) {
            val file = File(collBook.cover)
            if (file.exists() && file.length() != 0L) {
                ReadActivity.startActivity(requireContext(), collBook, true, cover)
                return
            }
            val tip = requireContext().getString(R.string.nb_bookshelf_book_not_exist)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(resources.getString(R.string.nb_common_tip))
                .setMessage(tip)
                .setPositiveButton(resources.getString(R.string.home_bookshelf_remove_confirm)) { _, _ ->
                    removeShelfItems(listOf(collBook), emptyList())
                }
                .setNegativeButton(resources.getString(R.string.nb_common_cancel), null)
                .show()
            return
        }
        ReadActivity.startActivity(requireContext(), collBook, true, cover)
    }

    override fun processLogic() {
        super.processLogic()
        updateReadingSummary()
        //  mRvContent.startRefresh();
    }

    private fun showFilterMenu() {
        setBookshelfEditMode(false)
        BookshelfFilterSheet(requireContext(), currentShelfFilter) { key ->
            currentShelfFilter = key
            refreshShelfDisplay(BookRepository.getInstance().collBooks)
        }.show()
    }

    private fun refreshShelfDisplay(collBookBeans: List<CollBookBean>) {
        val nowMillis = System.currentTimeMillis()
        val repository = BookRepository.getInstance()
        val recordsByBookId = repository.getBookRecords(collBookBeans.map { it._id })
        val nullIdRecord = if (collBookBeans.any { it._id == null }) {
            repository.getBookRecord(null)
        } else {
            null
        }
        val filteredBooks = filterShelfBooks(
            collBookBeans,
            recordsByBookId,
            nullIdRecord,
            nowMillis
        )
        val mediaItems = filterMediaShelfItems(mediaShelfItems(), nowMillis)
        shelfBooks = collBookBeans
        mCollBookAdapter!!.setPinnedBookKeys(BookshelfPinStore.pinnedKeys())
        mCollBookAdapter!!.setHero(heroBook())
        mCollBookAdapter!!.refreshItems(filteredBooks, mediaItems, recordsByBookId, nullIdRecord)
        if (!hasPlayedEnterAnimation && mCollBookAdapter!!.itemCount > 0) {
            hasPlayedEnterAnimation = true
            mRvContent.getReyclerView().scheduleLayoutAnimation()
        }
        updateFilterLabel()
        updateFilterEmptyState(filteredBooks.size + mediaItems.size)
        updateEditUi()
    }

    /** The most recently read book leads the full, unfiltered shelf; edit mode shows only the grid. */
    private fun heroBook(): CollBookBean? {
        return if (currentShelfFilter == FilterKey.ALL && !isEditMode) shelfBooks.firstOrNull() else null
    }

    private fun filterShelfBooks(
        collBookBeans: List<CollBookBean>,
        recordsByBookId: Map<String, BookRecordBean>,
        nullIdRecord: BookRecordBean?,
        nowMillis: Long
    ): List<CollBookBean> {
        return collBookBeans.filter { book ->
            val record = if (book._id == null) {
                nullIdRecord
            } else {
                recordsByBookId[book._id]
            }
            BookShelfViewModel.matchesFilter(
                currentShelfFilter,
                book,
                record,
                BookshelfLocalProgressStore.getProgressTenths(book._id),
                nowMillis
            )
        }
    }

    private fun filterMediaShelfItems(
        mediaItems: List<MediaShelfItem>,
        nowMillis: Long
    ): List<MediaShelfItem> {
        return mediaItems.filter { item ->
            val comicPageIndex = if (item.mediaKind == ReaderMediaKind.COMIC) {
                ComicReadingProgressStore.page(requireContext(), item.currentChapterRouteId)
            } else {
                -1
            }
            val audioPositionMs = if (item.mediaKind == ReaderMediaKind.AUDIO) {
                AudioPlaybackProgressStore.position(requireContext(), item.currentChapterRouteId)
            } else {
                0L
            }
            val hasReadingProgress = MediaShelfFilterPolicy.hasReadingProgress(
                item.mediaKind,
                comicPageIndex,
                audioPositionMs
            )
            MediaShelfFilterPolicy.matches(
                currentShelfFilter,
                item.updatedAtMs,
                hasReadingProgress,
                nowMillis
            )
        }
    }

    private fun setBookshelfEditMode(editMode: Boolean) {
        isEditMode = editMode
        mCollBookAdapter?.setHero(heroBook())
        mCollBookAdapter?.setEditMode(editMode)
        setHomeBottomNavigationVisible(!editMode)
        updateEditUi()
    }

    private fun enterEmptyBookshelfManageMode() {
        mCollBookAdapter?.clearSelection()
        setBookshelfEditMode(true)
    }

    private fun enterBookshelfManageMode(collBook: CollBookBean) {
        if (!isEditMode) {
            mCollBookAdapter?.clearSelection()
            setBookshelfEditMode(true)
        }
        mCollBookAdapter?.selectBook(collBook)
        updateEditUi()
    }

    private fun enterBookshelfManageMode(item: MediaShelfItem) {
        if (!isEditMode) {
            mCollBookAdapter?.clearSelection()
            setBookshelfEditMode(true)
        }
        mCollBookAdapter?.selectMediaItem(item)
        updateEditUi()
    }

    private fun setHomeBottomNavigationVisible(visible: Boolean) {
        (activity as? MainActivity)?.setHomeBottomNavigationVisible(visible)
    }

    fun exitEditModeIfNeeded(): Boolean {
        if (!isEditMode) {
            return false
        }
        setBookshelfEditMode(false)
        return true
    }

    private fun toggleSelectAllShelfItems() {
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

    private fun toggleSelectedShelfBookPin() {
        if (!isEditMode) return
        val adapter = mCollBookAdapter ?: return
        val collBook = adapter.selectedBooks.singleOrNull() ?: return
        if (adapter.selectedMediaItems.isNotEmpty()) return

        val wasPinned = BookshelfPinStore.isPinned(collBook)
        if (wasPinned) {
            BookshelfPinStore.unpin(collBook)
        } else {
            BookshelfPinStore.pin(collBook)
        }
        setBookshelfEditMode(false)
        refreshShelfDisplay(BookRepository.getInstance().collBooks)
        ToastUtils.show(
            getString(
                if (wasPinned) {
                    R.string.home_bookshelf_unpinned
                } else {
                    R.string.home_bookshelf_pinned
                }
            )
        )
    }

    private fun confirmRemoveSelectedShelfItems() {
        if (!isEditMode) {
            return
        }
        val selectedBooks = mCollBookAdapter!!.selectedBooks
        val selectedMediaItems = mCollBookAdapter!!.selectedMediaItems
        val selectedCount = selectedBooks.size + selectedMediaItems.size
        if (selectedCount == 0) {
            return
        }
        val hasLocalBooks = selectedBooks.any { it.isLocal() }
        val sheetBinding = DialogBookshelfRemoveSheetBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(
            requireContext(),
            R.style.ReaderBookshelfRemoveBottomSheetDialog
        )
        sheetBinding.bookshelfRemoveMessage.text =
            getString(R.string.home_bookshelf_remove_message_count, selectedCount)
        sheetBinding.bookshelfRemoveDeleteLocalFile.visibility =
            if (hasLocalBooks) View.VISIBLE else View.GONE
        sheetBinding.bookshelfRemoveDeleteLocalFile.isChecked = false
        sheetBinding.bookshelfRemoveCancel.setOnClickListener {
            dialog.dismiss()
        }
        sheetBinding.bookshelfRemoveConfirm.setOnClickListener { confirmView ->
            confirmView.isEnabled = false
            sheetBinding.bookshelfRemoveCancel.isEnabled = false
            val deleteLocalFiles = sheetBinding.bookshelfRemoveDeleteLocalFile.isChecked
            dialog.dismiss()
            removeShelfItems(
                selectedBooks,
                selectedMediaItems,
                deleteLocalFiles = deleteLocalFiles
            )
        }
        dialog.setContentView(sheetBinding.root)
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun removeShelfItems(
        selectedBooks: List<CollBookBean>,
        selectedMediaItems: List<MediaShelfItem>,
        deleteLocalFiles: Boolean = false
    ) {
        val appContext = requireContext().applicationContext
        val localFileEntries = selectedBooks
            .filter { it.isLocal() }
            .map { book ->
                val shelfFile = File(book.cover.orEmpty())
                BookshelfLocalFileEntry(
                    bookId = book.get_id().orEmpty(),
                    shelfPath = book.cover,
                    isManagedCopy = isManagedLocalCopy(shelfFile, appContext.filesDir)
                )
            }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val fileDeleter = BookshelfLocalFileDeleter { target ->
                    deleteContentDocument(appContext, target)
                }
                val localRemoval = BookshelfLocalFileRemovalExecutor(
                    sourceReferences = LocalTextSourceReferencePersistence.store,
                    fileDeleter = fileDeleter,
                    encodingOverrideClearer = LocalTxtEncodingPreferences::clear
                ).execute(localFileEntries, deleteLocalFiles)
                val failedLocalBookIds = localRemoval.failedBookIds.toSet()
                val removableBooks = selectedBooks.filterNot { book ->
                    book.isLocal() && book.get_id().orEmpty() in failedLocalBookIds
                }
                removableBooks.forEach { book ->
                    BookshelfPinStore.unpin(book)
                    deleteManagedLocalCopy(book, appContext.filesDir)
                    BookRepository.getInstance().deleteCollBookWithFiles(book)
                }
                selectedMediaItems.forEach { item ->
                    MediaShelfStore.remove(appContext, item.id)
                }
                ShelfRemovalResult(
                    removedBooks = removableBooks,
                    removedMediaItems = selectedMediaItems,
                    failedLocalFileCount = localRemoval.failedBookIds.size,
                    removedOnlineBook = removableBooks.any { !it.isLocal() }
                )
            }

            mCollBookAdapter?.removeBooks(result.removedBooks)
            mCollBookAdapter?.removeMediaItems(result.removedMediaItems)
            setBookshelfEditMode(false)
            refreshShelfDisplay(BookRepository.getInstance().collBooks)
            if (result.removedOnlineBook) {
                synBook()
            }
            if (result.failedLocalFileCount > 0) {
                ToastUtils.show(
                    getString(
                        R.string.home_bookshelf_remove_file_failed,
                        result.failedLocalFileCount
                    )
                )
            } else {
                ToastUtils.show(getString(R.string.home_bookshelf_removed))
            }
        }
    }

    private fun updateEditUi() {
        binding?.apply {
            homeBookshelfEdit.setText(if (isEditMode) R.string.bookshelf_edit_done else R.string.bookshelf_edit)
            homeBookshelfEdit.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isEditMode) R.color.ink_primary else R.color.ink_text_primary
                )
            )
            homeBookshelfFilter.visibility = if (isEditMode) View.GONE else View.VISIBLE
            editBarSpring?.animateTo(if (isEditMode) 1f else 0f)
            updateFilterLabel()
            if (isEditMode) {
                val selectedCount = mCollBookAdapter?.selectedCount ?: 0
                val selectedBooks = mCollBookAdapter?.selectedBooks.orEmpty()
                val selectedMediaItems = mCollBookAdapter?.selectedMediaItems.orEmpty()
                val canPin = selectedBooks.size == 1 && selectedMediaItems.isEmpty()
                val selectedBookIsPinned = selectedBooks.singleOrNull()
                    ?.let { BookshelfPinStore.isPinned(it) } == true
                homeReadingTimeChip.text = getString(R.string.home_bookshelf_selected_count, selectedCount)
                homeReadingTimeChip.isEnabled = false
                homeReadingTimeChip.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                homeBookshelfEdit.isEnabled = true
                homeBookshelfEdit.alpha = 1f
                homeBookshelfSelectAll.text =
                    if (mCollBookAdapter?.isAllVisibleSelected == true) {
                        getString(R.string.home_bookshelf_deselect_all)
                    } else {
                        getString(R.string.home_bookshelf_select_all)
                    }
                homeBookshelfPinSelected.isEnabled = canPin
                homeBookshelfPinSelected.alpha = if (canPin) 1f else DISABLED_ACTION_ALPHA
                homeBookshelfPinSelected.text = getString(
                    if (selectedBookIsPinned) {
                        R.string.home_bookshelf_unpin
                    } else {
                        R.string.home_bookshelf_pin
                    }
                )
                homeBookshelfDeleteSelected.text =
                    getString(R.string.home_bookshelf_remove_count, selectedCount)
                homeBookshelfDeleteSelected.isEnabled = selectedCount > 0
                homeBookshelfDeleteSelected.alpha =
                    if (selectedCount > 0) 1f else DISABLED_ACTION_ALPHA
            } else {
                val hasVisibleItems = (mCollBookAdapter?.visibleItemCount ?: 0) > 0
                homeBookshelfEdit.isEnabled = hasVisibleItems
                homeBookshelfEdit.alpha = if (hasVisibleItems) 1f else 0.38f
                homeReadingTimeChip.isEnabled = true
                homeReadingTimeChip.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_ink_schedule_16,
                    0,
                    R.drawable.ic_ink_chevron_right_16,
                    0
                )
                updateReadingSummary()
            }
            renderAudioFloat()
        }
    }

    private fun updateReadingSummary() {
        binding?.homeReadingTimeChip?.text = ReadingStatsUtils.getWeeklyReadingLabel()
    }

    private fun updateFilterLabel() {
        val filter = binding?.homeBookshelfFilter ?: return
        filter.text = BookShelfViewModel.filterToolbarLabel(currentShelfFilter)
        val color = ContextCompat.getColor(
            requireContext(),
            if (currentShelfFilter == FilterKey.ALL) R.color.ink_text_primary else R.color.ink_primary
        )
        filter.setTextColor(color)
        filter.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
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
            // The adapter may already be showing a restored filter, so its items are only
            // the visible subset. Initial refresh still has to update every online novel.
            mRvContent!!.post {
                viewModel.updateCollBooks(BookRepository.getInstance().collBooks)
            }
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

    private fun showErrorTip(context: BookshelfFailureContext?) {
//        mRvContent.setTip(error);
//        mRvContent.showTip();
        if (context == null) return
        ToastUtils.showLong(
            BookshelfFailureFeedback.messageFor(context)
        )
    }

    override fun onResume() {
        super.onResume()
//        rxPermissions = RxPermissions(this)
//        permission
        updateReadingSummary()
        audioFloatHandler.removeCallbacks(audioFloatTicker)
        audioFloatHandler.post(audioFloatTicker)
        viewModel.refreshCollBooks()
        initMobPush()
    }

    override fun onPause() {
        audioFloatHandler.removeCallbacks(audioFloatTicker)
        super.onPause()
    }

    private fun deleteManagedLocalCopy(book: CollBookBean, filesDir: File) {
        if (!book.isLocal()) return
        val file = File(book.cover.orEmpty())
        if (isManagedLocalCopy(file, filesDir) && file.exists()) {
            file.delete()
        }
    }

    private fun isManagedLocalCopy(file: File, filesDir: File): Boolean {
        val importsRoot = runCatching {
            File(filesDir, "imports").canonicalPath + File.separator
        }.getOrNull() ?: return false
        val candidate = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return candidate.startsWith(importsRoot)
    }

    private fun deleteContentDocument(context: Context, target: String): Boolean {
        val uri = Uri.parse(target)
        val resolver = context.contentResolver
        if (!DocumentsContract.isDocumentUri(context, uri)) {
            return resolver.delete(uri, null, null) > 0
        }
        val supportsDelete = resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getLong(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong() != 0L
        } == true
        return supportsDelete && DocumentsContract.deleteDocument(resolver, uri)
    }

    private data class ShelfRemovalResult(
        val removedBooks: List<CollBookBean>,
        val removedMediaItems: List<MediaShelfItem>,
        val failedLocalFileCount: Int,
        val removedOnlineBook: Boolean
    )

    override fun onDestroyView() {
        audioFloatHandler.removeCallbacks(audioFloatTicker)
        setHomeBottomNavigationVisible(true)
        homeAudioCoverAnimator?.cancel()
        homeAudioCoverAnimator = null
        lastHomeAudioCoverUrl = ""
        lastAudioFloatRouteId = ""
        wasAudioFloatVisible = false
        lastAudioFloatRenderKey = ""
        editBarSpring?.stop()
        editBarSpring = null
        hasPlayedEnterAnimation = false
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
        private const val DISABLED_ACTION_ALPHA = 0.38f
        private const val AUDIO_FLOAT_DRAG_SLOP = 12f
        private const val AUDIO_FLOAT_LIST_BOTTOM_INSET_DP = 66
        private const val EDIT_BAR_TRAVEL_DP = 104
        private const val SHELF_SINK_DP = 3
        private const val SHELF_ROOM_DP = 18
        private const val SHELF_FADE_DP = 24
        private const val AUDIO_FLOAT_REFRESH_MS = 1_000L
        private const val AUDIO_FLOAT_PREFS = "reader_audio_float"
        private const val AUDIO_FLOAT_X_FRACTION = "x_fraction"
        private const val AUDIO_FLOAT_Y_FRACTION = "y_fraction"
        private const val STATE_SHELF_FILTER = "bookshelf_filter"

        @JvmStatic
        fun restoredShelfFilter(rawName: String?): FilterKey {
            if (rawName.isNullOrBlank()) return FilterKey.ALL
            return runCatching { FilterKey.valueOf(rawName) }.getOrDefault(FilterKey.ALL)
        }
    }

    private fun renderAudioFloat() {
        val context = context ?: return
        val currentBinding = binding ?: return
        val nowPlaying = AudioPlaybackStateStore.current(context)
        currentBinding.apply {
            val showAudioFloat = nowPlaying != null && !isEditMode
            val shouldRestorePosition = showAudioFloat &&
                (!wasAudioFloatVisible || lastAudioFloatRouteId != nowPlaying?.chapterRouteId)
            homeBookshelfAudioFloat.visibility = if (showAudioFloat) View.VISIBLE else View.GONE
            updateShelfListBottomInset(showAudioFloat)
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
            val coverUrl = nowPlaying?.coverUrl.orEmpty()
            if (coverUrl != lastHomeAudioCoverUrl) {
                lastHomeAudioCoverUrl = coverUrl
                BookCoverLoader.load(
                    listOfNotNull(coverUrl.takeIf { it.isNotBlank() }),
                    homeBookshelfAudioCover,
                    R.drawable.ic_book_cover_placeholder,
                    circle = true
                )
            }
            if (nowPlaying != null && !isEditMode) {
                val renderKey = listOf(
                    nowPlaying.chapterRouteId,
                    nowPlaying.title,
                    playing.toString()
                ).joinToString("|")
                if (renderKey != lastAudioFloatRenderKey) {
                    lastAudioFloatRenderKey = renderKey
                    val hasShelfItem = MediaShelfStore.hasItemForChapter(context, nowPlaying.chapterRouteId)
                    traceAudioFloat("render", nowPlaying.chapterRouteId, nowPlaying.title, hasShelfItem, restored = null)
                }
                if (shouldRestorePosition) {
                    homeBookshelfAudioFloat.post { applyAudioFloatPosition(homeBookshelfAudioFloat) }
                }
            } else {
                lastAudioFloatRenderKey = ""
            }
            wasAudioFloatVisible = showAudioFloat
            lastAudioFloatRouteId = nowPlaying?.chapterRouteId.orEmpty()
        }
    }

    /** Room under the last row for the floating tab capsule, and the mini player when it shows. */
    private fun updateShelfListBottomInset(showAudioFloat: Boolean) {
        val recyclerView = mRvContent.getReyclerView()
        val floatingRoom = resources.getDimensionPixelSize(R.dimen.home_list_bottom_room)
        val audioRoom = if (showAudioFloat) dp(AUDIO_FLOAT_LIST_BOTTOM_INSET_DP) else 0
        val bottomInset = navigationInsetBottom + floatingRoom + audioRoom
        if (recyclerView.paddingBottom == bottomInset) return
        recyclerView.setPadding(
            recyclerView.paddingLeft,
            recyclerView.paddingTop,
            recyclerView.paddingRight,
            bottomInset
        )
    }

    private fun handleAudioFloatPlayClick() {
        val nowPlaying = AudioPlaybackStateStore.current(requireContext())
        when (AudioMiniPlayerAction.playButtonCommand(nowPlaying)) {
            AudioMiniPlayerCommand.TOGGLE_SERVICE -> {
                requireContext().startService(
                    Intent(requireContext(), AudioPlaybackService::class.java)
                        .setAction(AudioPlaybackService.ACTION_TOGGLE)
                )
            }
            AudioMiniPlayerCommand.RESUME_SERVICE -> {
                nowPlaying ?: return
                traceAudioFloat(
                    "resume_service",
                    nowPlaying.chapterRouteId,
                    nowPlaying.title,
                    MediaShelfStore.hasItemForChapter(requireContext(), nowPlaying.chapterRouteId),
                    restored = null
                )
                requireContext().startService(
                    Intent(requireContext(), AudioPlaybackService::class.java)
                        .putExtra(AudioPlaybackService.EXTRA_URL, nowPlaying.audioUrl)
                        .putExtra(AudioPlaybackService.EXTRA_TITLE, nowPlaying.title)
                        .putExtra(AudioPlaybackService.EXTRA_CHAPTER_ROUTE_ID, nowPlaying.chapterRouteId)
                        .putExtra(AudioPlaybackService.EXTRA_BOOK_ROUTE_ID, nowPlaying.bookRouteId)
                        .putExtra(AudioPlaybackService.EXTRA_BOOK_TITLE, nowPlaying.bookTitle)
                        .putExtra(AudioPlaybackService.EXTRA_COVER_URL, nowPlaying.coverUrl)
                        .putExtra(AudioPlaybackService.EXTRA_HEADERS_JSON, AudioPlaybackService.encodeHeaders(nowPlaying.headers))
                        .putExtra(AudioPlaybackService.EXTRA_FORCE_START, false)
                        .putExtra(AudioPlaybackService.EXTRA_AUTO_PLAY, true)
                )
            }
            AudioMiniPlayerCommand.REPLAY_SERVICE -> {
                nowPlaying ?: return
                traceAudioFloat(
                    "replay_service",
                    nowPlaying.chapterRouteId,
                    nowPlaying.title,
                    MediaShelfStore.hasItemForChapter(requireContext(), nowPlaying.chapterRouteId),
                    restored = null
                )
                requireContext().startService(
                    Intent(requireContext(), AudioPlaybackService::class.java)
                        .putExtra(AudioPlaybackService.EXTRA_URL, nowPlaying.audioUrl)
                        .putExtra(AudioPlaybackService.EXTRA_TITLE, nowPlaying.title)
                        .putExtra(AudioPlaybackService.EXTRA_CHAPTER_ROUTE_ID, nowPlaying.chapterRouteId)
                        .putExtra(AudioPlaybackService.EXTRA_BOOK_ROUTE_ID, nowPlaying.bookRouteId)
                        .putExtra(AudioPlaybackService.EXTRA_BOOK_TITLE, nowPlaying.bookTitle)
                        .putExtra(AudioPlaybackService.EXTRA_COVER_URL, nowPlaying.coverUrl)
                        .putExtra(AudioPlaybackService.EXTRA_HEADERS_JSON, AudioPlaybackService.encodeHeaders(nowPlaying.headers))
                        .putExtra(AudioPlaybackService.EXTRA_FORCE_START, true)
                        .putExtra(AudioPlaybackService.EXTRA_AUTO_PLAY, true)
                )
            }
            AudioMiniPlayerCommand.OPEN_PLAYER -> {
                nowPlaying ?: return
                val hasShelfItem = MediaShelfStore.hasItemForChapter(requireContext(), nowPlaying.chapterRouteId)
                val restored = MediaShelfStore.restoreForChapter(requireContext(), nowPlaying.chapterRouteId)
                traceAudioFloat("open_player_from_action", nowPlaying.chapterRouteId, nowPlaying.title, hasShelfItem, restored)
                AudioPlayerActivity.openCurrent(
                    requireContext(),
                    nowPlaying.chapterRouteId,
                    nowPlaying.title,
                    bookTitle = nowPlaying.bookTitle
                )
            }
            null -> Unit
        }
    }

    private fun traceAudioFloat(
        action: String,
        chapterRouteId: String,
        title: String,
        hasShelfItem: Boolean,
        restored: Boolean?
    ) {
        AiBridgeTrace.state(
            "media_audio_float",
            chapterRouteId,
            AiBridgeTrace.fields(
                "action" to action,
                "title" to title,
                "hasShelfItem" to hasShelfItem,
                "restored" to restored,
                "editMode" to isEditMode
            )
        )
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
        val mmkv = MMKV.mmkvWithID(AUDIO_FLOAT_PREFS)
        val xFraction = mmkv.decodeFloat(AUDIO_FLOAT_X_FRACTION, -1f)
        val yFraction = mmkv.decodeFloat(AUDIO_FLOAT_Y_FRACTION, -1f)
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
        val mmkv = MMKV.mmkvWithID(AUDIO_FLOAT_PREFS)
        mmkv.encode(AUDIO_FLOAT_X_FRACTION, (view.x / maxX).coerceIn(0f, 1f))
        mmkv.encode(AUDIO_FLOAT_Y_FRACTION, (view.y / maxY).coerceIn(0f, 1f))
    }

    private fun mediaShelfItems(): List<MediaShelfItem> {
        return MediaShelfStore.items(requireContext())
            .filter { it.mediaKind == ReaderMediaKind.AUDIO || it.mediaKind == ReaderMediaKind.COMIC }
    }

    private fun openMediaShelfItem(item: MediaShelfItem) {
        MediaShelfStore.restoreItemRoutes(item)
        val chapterRouteId = item.currentChapterRouteId.ifBlank {
            item.routeSnapshot.chapters.firstOrNull()?.routeId.orEmpty()
        }
        if (chapterRouteId.isBlank()) {
            ToastUtils.show("这部作品暂时无法打开，请稍后重试")
            return
        }
        val title = item.currentChapterTitle.ifBlank { item.title }
        when (item.mediaKind) {
            ReaderMediaKind.AUDIO -> AudioPlayerActivity.startManualSelection(
                requireContext(),
                chapterRouteId,
                title,
                bookTitle = item.title
            )
            ReaderMediaKind.COMIC -> ComicReadActivity.start(requireContext(), chapterRouteId, title)
            else -> Unit
        }
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
            val params = view.layoutParams as GridLayoutManager.LayoutParams
            if (params.spanSize == spanCount) return
            val column = params.spanIndex
            outRect.left = column * spacingPx / spanCount
            outRect.right = spacingPx - (column + 1) * spacingPx / spanCount
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

}
