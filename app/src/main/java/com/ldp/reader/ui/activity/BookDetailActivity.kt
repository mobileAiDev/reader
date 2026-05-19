package com.ldp.reader.ui.activity

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityBookDetailBinding
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.utils.BookCoverUrl
import com.ldp.reader.utils.SystemBarUtils
import com.ldp.reader.utils.ToastUtils

/**
 * Created by ldp on 17-5-4.
 */
class BookDetailActivity : BaseActivity<ActivityBookDetailBinding>() {
    /** */
    private var mCollBookBean: CollBookBean? = null
    private var mProgressDialog: ProgressDialog? = null

    /** */
    private var mBookId: String? = null
    private var isBriefOpen = false
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
    }

    private fun observeBookDetailState() {
        viewModel.bookDetails.observe(this) { bean ->
            finishRefresh(bean)
            complete()
        }
        viewModel.refreshErrors.observe(this) {
            showError()
        }
        viewModel.bookShelfAddWaitEvents.observe(this) {
            waitToBookShelf()
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

            //可伸缩的TextView
            bookDetailTvBrief.setOnClickListener { view ->
                isBriefOpen = if (isBriefOpen) {
                    bookDetailTvBrief.setMaxLines(4)
                    false
                } else {
                    bookDetailTvBrief.setMaxLines(8)
                    true
                }
            }

            bookListLlChase.setOnClickListener { toggleBookShelf() }
            bookListAvChase.setOnClickListener { toggleBookShelf() }
            bookDetailTvRead.setOnClickListener { v ->
                val collBook = mCollBookBean
                if (collBook == null) {
                    ToastUtils.show("书籍信息加载中，请稍后")
                    return@setOnClickListener
                }
                startActivityForResult(
                    Intent(this@BookDetailActivity, ReadActivity::class.java)
                        .putExtra(ReadActivity.EXTRA_IS_COLLECTED, isCollected)
                        .putExtra(ReadActivity.EXTRA_COLL_BOOK, collBook), REQUEST_READ
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
        binding?.apply {
            isCollected = if (isCollected) {
                BookRepository.getInstance()
                    .deleteCollBookWithFiles(collBook)
                bookListAvChase.speed = -1f
                bookListAvChase.playAnimation()
                updateChaseButton(false)
                false
            } else {
                viewModel.addToBookShelf(collBook)
                bookListAvChase.speed = 1f
                bookListAvChase.playAnimation()
                updateChaseButton(true)
                true
            }
        }
    }

    private fun updateChaseButton(collected: Boolean) {
        binding?.apply {
            val drawable = ResourcesCompat.getDrawable(
                resources,
                R.drawable.bg_book_detail_action_secondary,
                null
            )
            bookListLlChase.background = drawable
            bookListTvChase.background = null
            bookListTvChase.text = resources.getString(
                if (collected) R.string.nb_book_detail_give_up else R.string.nb_book_detail_chase_update
            )
            bookListTvChase.setTextColor(
                ResourcesCompat.getColor(resources, R.color.home_primary, null)
            )
        }
    }

    override fun processLogic() {
        super.processLogic()
        SystemBarUtils.showStableStatusBar(this)
        SystemBarUtils.transparentStatusBar(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        binding?.refreshLayout?.showLoading()
        viewModel.refreshBookDetail(mBookId)
    }

    override fun getViewBinding(): ActivityBookDetailBinding {
        return ActivityBookDetailBinding.inflate(layoutInflater)
    }

    private fun finishRefresh(bean: BookDetailBeanInOwn) {
        binding?.apply {
            //封面
            BookCoverLoader.load(
                this@BookDetailActivity,
                bean.cover,
                bookDetailIvCover,
                R.drawable.ic_book_cover_placeholder
            )
            //书名
            bookDetailTvTitle.setText(bean.title)
            //作者
            bookDetailTvAuthor.setText(bean.author)
            bookDetailTvLastChapter.text = if (bean.lastChapter.isNullOrBlank()) {
                resources.getString(R.string.nb_book_detail_last_chapter)
            } else {
                resources.getString(R.string.nb_book_detail_last_chapter) + "  " + bean.lastChapter
            }
            //简介
            bookDetailTvBrief.setText(bean.desc)
            val freshCollBook = bean.collBookBean
            val existingCollBook = BookRepository.getInstance().getCollBook(freshCollBook.get_id())
                ?: BookRepository.getInstance().findSameOnlineBook(freshCollBook)
            mCollBookBean = existingCollBook?.also { updateExistingBookFromDetail(it, freshCollBook) }


            //判断是否收藏
            if (mCollBookBean != null) {
                Log.d(TAG, "finishRefresh: " + "mCollBookBean != null")
                isCollected = true
                updateChaseButton(true)
                bookListAvChase.speed = 1f
                bookListAvChase.playAnimation()
                bookDetailTvRead.setText("继续阅读")
            } else {
                mCollBookBean = freshCollBook
                Log.d(TAG, "finishRefresh: " + "mCollBookBean = bean.getCollBookBean()")
                updateChaseButton(false)
            }
        }


    }

    private fun updateExistingBookFromDetail(existing: CollBookBean, fresh: CollBookBean) {
        if (SourceEngineBookRoute.isBookId(fresh.bookIdInBiquge)) {
            existing.bookIdInBiquge = fresh.bookIdInBiquge
        }
        if (BookCoverUrl.isLikelyImage(fresh.cover) && fresh.cover != existing.cover) {
            existing.cover = fresh.cover
        }
        if (!fresh.shortIntro.isNullOrBlank()) {
            existing.shortIntro = fresh.shortIntro
        }
        if (!fresh.lastChapter.isNullOrBlank()) {
            existing.lastChapter = fresh.lastChapter
        }
        if (fresh.chaptersCount > 0) {
            existing.chaptersCount = fresh.chaptersCount
        }
        BookRepository.getInstance().saveCollBook(existing)
    }

    private fun waitToBookShelf() {
        if (mProgressDialog == null) {
            mProgressDialog = ProgressDialog(this)
            mProgressDialog!!.setTitle("正在添加到书架中")
        }
        mProgressDialog!!.show()
    }

    private fun errorToBookShelf() {
        if (mProgressDialog != null) {
            mProgressDialog!!.dismiss()
        }
        ToastUtils.show("加入书架失败，请检查网络")
    }

    private fun succeedToBookShelf() {
        if (mProgressDialog != null) {
            mProgressDialog!!.dismiss()
        }
        ToastUtils.show("加入书架成功")
    }

    private fun showError() {
        binding?.refreshLayout?.showError()
    }

    private fun complete() {
        binding?.refreshLayout?.showFinish()
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

            }
        }
    }

    companion object {
        const val RESULT_IS_COLLECTED = "result_is_collected"
        private const val TAG = "BookDetailActivity"
        private const val EXTRA_BOOK_ID = "extra_book_id"
        private const val REQUEST_READ = 1
        public fun startActivity(context: Context, bookId: String?) {
            val intent = Intent(context, BookDetailActivity::class.java)
            intent.putExtra(EXTRA_BOOK_ID, bookId)
            context.startActivity(intent)
        }
    }

}
