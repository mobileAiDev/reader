package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityBookDetailBinding
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.image.AmbientCover
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.source.SourceEngineMetadataCleaner
import com.ldp.reader.utils.BookCoverUrl
import com.ldp.reader.ui.motion.Haptics
import com.ldp.reader.ui.motion.Press
import com.ldp.reader.ui.theme.EdgeToEdge
import com.ldp.reader.utils.ToastUtils
import com.ldp.reader.widget.RefreshLayout

/**
 * Created by ldp on 17-5-4.
 */
class BookDetailActivity : BaseActivity<ActivityBookDetailBinding>() {
    /** */
    private var mCollBookBean: CollBookBean? = null

    /** */
    private var mBookId: String? = null
    private var isCollected = false
    private lateinit var viewModel: BookDetailViewModel

    override fun initData(savedInstanceState: Bundle?) {
        super.initData(savedInstanceState)
        mBookId = if (savedInstanceState != null) {
            savedInstanceState.getString(EXTRA_BOOK_ID)
        } else {
            intent.getStringExtra(EXTRA_BOOK_ID)
        }
    }

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar!!.title = "书籍详情"
    }

    override fun initWidget() {
        super.initWidget()
        viewModel = ViewModelProvider(this)[BookDetailViewModel::class.java]
        observeBookDetailState()
        EdgeToEdge.apply(this)
        EdgeToEdge.padTop(binding.bookDetailTopBar)
        EdgeToEdge.padBottom(binding.bookDetailScroll, resources.getDimensionPixelSize(R.dimen.home_list_bottom_room))
        EdgeToEdge.liftAboveNavigation(binding.bookDetailBottomBar)
        Press.attach(binding.bookListLlChase, Press.BUTTON)
        Press.attach(binding.bookDetailTvRead, Press.BUTTON)
    }

    private fun observeBookDetailState() {
        viewModel.bookDetails.observe(this) { bean ->
            finishRefresh(bean)
        }
        viewModel.refreshState.observe(this) { state ->
            renderRefreshState(state)
        }
        viewModel.latestChapterUiState.observe(this) { state ->
            renderLatestChapter(state)
        }
        viewModel.bookShelfAddErrorEvents.observe(this) {
            errorToBookShelf()
        }
        viewModel.bookShelfAddSuccessEvents.observe(this) {
            succeedToBookShelf()
        }
    }

    override fun initClick() {
        super.initClick()

        binding?.apply {
            bookDetailNavBack.setOnClickListener { finish() }
            refreshLayout.setOnReloadingListener(object : RefreshLayout.OnReloadingListener {
                override fun onReload() {
                    retryBookDetail()
                }
            })
            bookDetailPartialRefreshError.setOnClickListener {
                retryBookDetail()
            }

            bookListLlChase.setOnClickListener { toggleBookShelf() }
            bookDetailBriefPanel.setOnClickListener { toggleBriefExpanded() }
            bookDetailTvRead.setOnClickListener { v ->
                val collBook = mCollBookBean
                if (collBook == null) {
                    ToastUtils.show("书籍信息加载中，请稍后")
                    return@setOnClickListener
                }
                val intentCollBook = ReadActivity.createIntentBookPayload(collBook)
                AiBridgeTrace.event(
                    "source_detail_read_clicked",
                    collBook.title.orEmpty(),
                    AiBridgeTrace.fields(
                        "bookId" to mBookId.orEmpty(),
                        "collected" to isCollected,
                        "sourceRoute" to SourceEngineBookRoute.isBookId(collBook.bookIdInBiquge),
                        "cachedChapters" to (collBook.getBookChapters()?.size ?: 0),
                        "intentChapters" to (intentCollBook?.getBookChapters()?.size ?: 0),
                        "chaptersCount" to collBook.chaptersCount,
                        "last" to collBook.lastChapter.orEmpty()
                    )
                )
                viewModel.prepareForReading()
                // The book opens by growing out of its cover.
                val cover = bookDetailIvCover
                val options = ActivityOptionsCompat.makeScaleUpAnimation(cover, 0, 0, cover.width, cover.height)
                startActivityForResult(
                    Intent(this@BookDetailActivity, ReadActivity::class.java)
                        .putExtra(ReadActivity.EXTRA_IS_COLLECTED, isCollected)
                        .putExtra(ReadActivity.EXTRA_COLL_BOOK, intentCollBook), REQUEST_READ,
                    options.toBundle()
                )
            }

        }

    }

    private fun toggleBookShelf() {
        val collBook = mCollBookBean
        if (collBook == null) {
            ToastUtils.show("书籍信息加载中，请稍后")
            return
        }
        val shelfState = BookDetailShelfAction.forPresence(isCollected)
        when (shelfState.tapAction) {
            BookDetailShelfTapAction.ADD_TO_SHELF -> binding?.apply {
                Haptics.confirm(bookListLlChase)
                viewModel.addToBookShelf(collBook)
                updateChaseButton(true)
                isCollected = true
            }

            BookDetailShelfTapAction.KEEP_ON_SHELF -> {
                ToastUtils.show(resources.getString(R.string.nb_book_detail_already_in_shelf_hint))
            }
        }
    }

    private fun bindBrief(brief: String?) {
        val briefView = binding.bookDetailTvBrief
        briefView.text = brief
        briefView.maxLines = BRIEF_COLLAPSED_LINES
        binding.bookDetailBriefToggle.setText(R.string.book_detail_brief_expand)
        briefView.post {
            val layout = briefView.layout ?: return@post
            val truncated = layout.lineCount > 0 &&
                layout.getEllipsisCount(layout.lineCount - 1) > 0
            binding.bookDetailBriefToggle.visibility = if (truncated) View.VISIBLE else View.GONE
        }
    }

    private fun toggleBriefExpanded() {
        if (binding.bookDetailBriefToggle.visibility != View.VISIBLE) return
        val briefView = binding.bookDetailTvBrief
        val expanded = briefView.maxLines == Int.MAX_VALUE
        briefView.maxLines = if (expanded) BRIEF_COLLAPSED_LINES else Int.MAX_VALUE
        binding.bookDetailBriefToggle.setText(
            if (expanded) R.string.book_detail_brief_expand else R.string.book_detail_brief_collapse
        )
        binding.bookDetailBriefToggle.setCompoundDrawablesWithIntrinsicBounds(
            0,
            0,
            if (expanded) R.drawable.ic_ink_expand_16 else R.drawable.ic_ink_expand_less_16,
            0
        )
    }

    private fun updateChaseButton(collected: Boolean) {
        val shelfState = BookDetailShelfAction.forPresence(collected)
        binding?.apply {
            val drawable = ContextCompat.getDrawable(this@BookDetailActivity, R.drawable.bg_book_detail_action_secondary)
            bookListLlChase.background = drawable
            bookListTvChase.background = null
            bookListTvChase.text = resources.getString(
                when (shelfState.label) {
                    BookDetailShelfLabel.ADD_TO_SHELF -> R.string.nb_book_detail_chase_update
                    BookDetailShelfLabel.ALREADY_IN_SHELF -> R.string.nb_book_detail_give_up
                }
            )
            bookListLlChase.contentDescription = bookListTvChase.text
            bookListIvChase.setImageResource(
                when (shelfState.label) {
                    BookDetailShelfLabel.ADD_TO_SHELF -> R.drawable.ic_ink_add
                    BookDetailShelfLabel.ALREADY_IN_SHELF -> R.drawable.ic_ink_check
                }
            )
        }
    }

    override fun processLogic() {
        super.processLogic()
        // A book already on the shelf shows so at once, and can be read on even if the detail fails to load.
        BookRepository.getInstance().getCollBook(mBookId)?.let { shelfBook ->
            mCollBookBean = shelfBook
            isCollected = true
            updateChaseButton(true)
            binding?.bookDetailTvRead?.setText("继续阅读")
        }
        binding?.refreshLayout?.showLoading()
        AiBridgeTrace.event(
            "source_detail_activity_started",
            mBookId.orEmpty(),
            AiBridgeTrace.fields("sourceRoute" to SourceEngineBookRoute.isBookId(mBookId))
        )
        viewModel.refreshBookDetail(mBookId)
    }

    override fun getViewBinding(): ActivityBookDetailBinding {
        return ActivityBookDetailBinding.inflate(layoutInflater)
    }

    private fun finishRefresh(bean: BookDetailBeanInOwn) {
        AiBridgeTrace.state(
            "source_detail_activity_render",
            bean.title.orEmpty(),
            AiBridgeTrace.fields(
                "author" to bean.author.orEmpty(),
                "route" to bean.routeId.orEmpty(),
                "cover" to BookCoverUrl.isLikelyImage(bean.cover),
                "coverUsable" to BookCoverUrl.isUsable(bean.cover),
                "coverCandidates" to bean.coverCandidates.orEmpty().size,
                "coverCandidateLikely" to bean.coverCandidates.orEmpty().count { BookCoverUrl.isLikelyImage(it) },
                "intro" to !bean.desc.isNullOrBlank(),
                "chapters" to bean.chaptersCount,
                "last" to bean.lastChapter.orEmpty()
            )
        )
        binding?.apply {
            //封面
            val covers = listOfNotNull(bean.cover) + bean.coverCandidates.orEmpty()
            BookCoverLoader.load(covers, bookDetailIvCover, R.drawable.ic_book_cover_placeholder)
            AmbientCover.show(binding.bookDetailAmbient, covers)
            //书名
            bookDetailTvTitle.setText(bean.title)
            //作者
            bookDetailTvAuthor.setText(bean.author)
            //简介
            bindBrief(bean.desc)
            val freshCollBook = bean.collBookBean
            val existingCollBook = BookRepository.getInstance().getCollBook(freshCollBook.get_id())
                ?: BookRepository.getInstance().findSameOnlineBook(freshCollBook)
            mCollBookBean = existingCollBook?.also { updateExistingBookFromDetail(it, freshCollBook) }


            //判断是否收藏
            if (mCollBookBean != null) {
                Log.d(TAG, "finishRefresh: " + "mCollBookBean != null")
                isCollected = true
                updateChaseButton(true)
                bookDetailTvRead.setText("继续阅读")
            } else {
                mCollBookBean = freshCollBook
                Log.d(TAG, "finishRefresh: " + "mCollBookBean = bean.getCollBookBean()")
                updateChaseButton(false)
            }
        }


    }

    /**
     * A heavily blurred copy of the cover tints the header with the book's own colours.
     * Live blur exists from Android 12; earlier systems keep the plain page.
     */

    private fun updateExistingBookFromDetail(existing: CollBookBean, fresh: CollBookBean) {
        if (SourceEngineBookRoute.isBookId(fresh.bookIdInBiquge)) {
            existing.bookIdInBiquge = fresh.bookIdInBiquge
        }
        if (BookCoverUrl.isLikelyImage(fresh.cover) && fresh.cover != existing.cover) {
            existing.cover = fresh.cover
        }
        val cleanedExistingIntro = SourceEngineMetadataCleaner.cleanIntro(existing.shortIntro.orEmpty())
        if (cleanedExistingIntro.isNotBlank() && cleanedExistingIntro != existing.shortIntro) {
            existing.shortIntro = cleanedExistingIntro
        }
        if (existing.shortIntro.isNullOrBlank() && !fresh.shortIntro.isNullOrBlank()) {
            existing.shortIntro = fresh.shortIntro
        }
        if (!fresh.lastChapter.isNullOrBlank()) {
            existing.lastChapter = fresh.lastChapter
        }
        if (fresh.chaptersCount > 0) {
            existing.chaptersCount = fresh.chaptersCount
        }
        val freshChapters = fresh.getBookChapters()
        if (!freshChapters.isNullOrEmpty()) {
            existing.bookChapters = freshChapters
            existing.chaptersCount = freshChapters.size
            existing.lastChapter = freshChapters.lastOrNull()?.title ?: existing.lastChapter
        }
        BookRepository.getInstance().saveCollBook(existing)
    }

    private fun errorToBookShelf() {
        ToastUtils.show(BookDetailShelfFailureCopy.localPersistence())
    }

    private fun succeedToBookShelf() {
        ToastUtils.show("加入书架成功")
    }

    private fun renderRefreshState(state: BookDetailRefreshState) {
        binding?.apply {
            when (state.phase) {
                BookDetailRefreshPhase.LOADING -> {
                    bookDetailPartialRefreshError.visibility = View.GONE
                    refreshLayout.showLoading()
                }

                BookDetailRefreshPhase.PRELIMINARY,
                BookDetailRefreshPhase.READY -> {
                    bookDetailPartialRefreshError.visibility = View.GONE
                    refreshLayout.showFinish()
                }

                BookDetailRefreshPhase.PARTIAL_FAILURE -> {
                    refreshLayout.showFinish()
                    bookDetailPartialRefreshError.visibility = View.VISIBLE
                }

                BookDetailRefreshPhase.FAILURE -> {
                    bookDetailPartialRefreshError.visibility = View.GONE
                    refreshLayout.showError()
                }
            }
        }
    }

    private fun renderLatestChapter(state: BookDetailLatestChapterUiState) {
        val latestLabel = resources.getString(R.string.nb_book_detail_last_chapter)
        binding?.bookDetailTvLastChapter?.text = when (state) {
            BookDetailLatestChapterUiState.Loading -> "最新章节加载中…"
            BookDetailLatestChapterUiState.Unavailable -> "$latestLabel  暂无"
            is BookDetailLatestChapterUiState.Ready -> "$latestLabel  ${state.title}"
        }
    }

    private fun retryBookDetail() {
        binding?.refreshLayout?.showLoading()
        AiBridgeTrace.event(
            "source_detail_retry_clicked",
            mBookId.orEmpty(),
            AiBridgeTrace.fields("sourceRoute" to SourceEngineBookRoute.isBookId(mBookId))
        )
        viewModel.refreshBookDetail(mBookId)
    }

    /** */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_BOOK_ID, mBookId)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        //如果进入阅读页面收藏了，页面结束的时候，就需要返回改变收藏按钮
        if (requestCode == REQUEST_READ) {
            if (data == null) {
                return
            }
            isCollected = data.getBooleanExtra(RESULT_IS_COLLECTED, false)
            if (isCollected) {
                binding?.apply {
                    updateChaseButton(true)
                    bookDetailTvRead.setText("继续阅读")
                }
            } else {
                binding?.apply {
                    updateChaseButton(false)
                    bookDetailTvRead.setText(R.string.nb_book_detail_start_read)
                }
            }
        }
    }

    companion object {
        const val RESULT_IS_COLLECTED = "result_is_collected"
        private const val TAG = "BookDetailActivity"
        private const val EXTRA_BOOK_ID = "extra_book_id"
        private const val REQUEST_READ = 1
        private const val BRIEF_COLLAPSED_LINES = 6
        public fun startActivity(context: Context, bookId: String?) {
            val intent = Intent(context, BookDetailActivity::class.java)
            intent.putExtra(EXTRA_BOOK_ID, bookId)
            context.startActivity(intent)
        }

        /** Opens the detail page growing out of [source], usually the tapped cover. */
        fun startActivity(context: Context, bookId: String?, source: View) {
            val intent = Intent(context, BookDetailActivity::class.java)
            intent.putExtra(EXTRA_BOOK_ID, bookId)
            val options = ActivityOptionsCompat.makeScaleUpAnimation(source, 0, 0, source.width, source.height)
            context.startActivity(intent, options.toBundle())
        }
    }
}
