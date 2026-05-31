package com.ldp.reader.ui.adapter.view

import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemCollBookBinding
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.adapter.CollBookAdapter
import com.ldp.reader.ui.base.adapter.ViewHolderImpl
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.utils.StringUtils
import com.ldp.reader.widget.page.PageLoader
import java.util.Locale

/**
 * Created by ldp on 17-5-8.
 * CollectionBookView
 */
class CollBookHolder(private val adapter: CollBookAdapter) : ViewHolderImpl<CollBookBean>() {
    private lateinit var mIvCover: ImageView
    private lateinit var mLocalCover: View
    private lateinit var mLocalCoverTitle: TextView
    private lateinit var mLocalCoverType: TextView
    private lateinit var mTvName: TextView
    private lateinit var mTvChapter: TextView
    private lateinit var mTvTime: TextView
    private lateinit var mCbSelected: CheckBox
    private lateinit var mIvRedDot: ImageView

    override fun initView() {
        val binding = ItemCollBookBinding.bind(getItemView())
        mIvCover = binding.collBookIvCover
        mLocalCover = binding.collBookLocalCover
        mLocalCoverTitle = binding.collBookLocalCoverTitle
        mLocalCoverType = binding.collBookLocalCoverType
        mTvName = binding.collBookTvName
        mTvChapter = binding.collBookTvChapter
        mTvTime = binding.collBookTvLatelyUpdate
        mCbSelected = binding.collBookCbSelected
        mIvRedDot = binding.collBookIvRedRot
    }

    override fun onBind(value: CollBookBean, pos: Int) {
        getItemView().isSelected = false
        if (value.isLocal()) {
            mIvCover.visibility = View.GONE
            mLocalCover.visibility = View.VISIBLE
            mLocalCoverTitle.text = coverTitle(value.title)
            mLocalCoverType.text = fileTypeLabel(value.cover)
        } else {
            mIvCover.visibility = View.VISIBLE
            mLocalCover.visibility = View.GONE
            BookCoverLoader.load(getContext(), value.cover, value.title, mIvCover, R.drawable.ic_book_loading)
        }

        mTvName.text = value.title
        if (!value.isLocal()) {
            mTvTime.text = StringUtils.formatBookUpdateTime(value.updated)
            mTvTime.visibility = View.VISIBLE
            mTvChapter.visibility = View.VISIBLE
        } else {
            mTvTime.text = progressLabel(
                value,
                findBookRecord(value),
                BookshelfLocalProgressStore.getProgressTenths(value.get_id())
            )
            mTvTime.visibility = View.VISIBLE
            mTvChapter.visibility = View.GONE
        }

        mTvChapter.text = value.lastChapter
        mCbSelected.visibility = if (adapter.isEditMode) View.VISIBLE else View.GONE
        mCbSelected.isChecked = adapter.isSelected(value)
        if (value.isUpdate()) {
            mIvRedDot.visibility = if (value.isLocal()) View.GONE else View.VISIBLE
        } else {
            mIvRedDot.visibility = View.GONE
        }
    }

    private fun findBookRecord(value: CollBookBean): BookRecordBean? {
        return try {
            BookRepository.getInstance().getBookRecord(value.get_id())
        } catch (ignored: RuntimeException) {
            null
        }
    }

    override fun getItemLayoutId(): Int {
        return R.layout.item_coll_book
    }

    companion object {
        private const val DEFAULT_LOCAL_TITLE = "本地书"
        private const val DEFAULT_LOCAL_TYPE = "TXT"
        private const val START_READING = "开始阅读"

        @JvmStatic
        fun coverTitle(title: String?): String {
            if (title == null) {
                return DEFAULT_LOCAL_TITLE
            }
            val cleanTitle = title.trim()
            return if (cleanTitle.isEmpty()) DEFAULT_LOCAL_TITLE else cleanTitle
        }

        @JvmStatic
        fun fileTypeLabel(path: String?): String {
            var extension = DEFAULT_LOCAL_TYPE
            if (path != null) {
                val dotIndex = path.lastIndexOf('.')
                if (dotIndex >= 0 && dotIndex + 1 < path.length) {
                    extension = path.substring(dotIndex + 1)
                }
            }
            return "-" + extension.uppercase(Locale.CHINA) + "-"
        }

        @JvmStatic
        fun progressLabel(book: CollBookBean?, record: BookRecordBean?): String {
            return progressLabel(book, record, -1)
        }

        @JvmStatic
        fun progressLabel(
            book: CollBookBean?,
            record: BookRecordBean?,
            storedProgressTenths: Int
        ): String {
            if (book == null) {
                return "未读"
            }
            if (storedProgressTenths >= 0) {
                return "已读" + formatPercent(storedProgressTenths)
            }
            if (record != null && (record.chapter > 0 || record.pagePos > 0)) {
                return "已读" + formatPercent(fallbackProgressTenths(book, record))
            }
            val lastChapter = book.lastChapter
            if (lastChapter == null || lastChapter.trim().isEmpty() ||
                START_READING == lastChapter.trim()
            ) {
                return "未读"
            }
            return "已读0.1%"
        }

        private fun fallbackProgressTenths(book: CollBookBean, record: BookRecordBean): Int {
            val chapterCount = book.chaptersCount.coerceAtLeast(0)
            if (chapterCount > 1) {
                return PageLoader.calculateProgressTenths(
                    chapterCount,
                    record.chapter,
                    record.pagePos,
                    0
                )
            }
            return 1
        }

        private fun formatPercent(tenths: Int): String {
            val safeTenths = tenths.coerceAtLeast(1).coerceAtMost(999)
            val whole = safeTenths / 10
            val decimal = safeTenths % 10
            if (decimal == 0) {
                return "$whole%"
            }
            return "$whole.$decimal%"
        }
    }
}
