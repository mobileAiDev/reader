package com.ldp.reader.ui.adapter.view

import android.widget.ImageView
import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemSearchBookBinding
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.ui.base.adapter.ViewHolderImpl
import com.ldp.reader.ui.image.BookCoverLoader

/**
 * Created by ldp on 17-6-2.
 */
class SearchBookHolder : ViewHolderImpl<BookSearchResult>() {
    private lateinit var mIvCover: ImageView
    private lateinit var mTvName: TextView
    private lateinit var mTvBrief: TextView

    override fun initView() {
        val binding = ItemSearchBookBinding.bind(getItemView())
        mIvCover = binding.searchBookIvCover
        mTvName = binding.searchBookTvName
        mTvBrief = binding.searchBookTvBrief
    }

    override fun onBind(book: BookSearchResult, pos: Int) {
        BookCoverLoader.load(book.cover, mIvCover, R.drawable.ic_book_cover_placeholder)
        mTvName.text = book.title
        mTvBrief.text = listOf(book.author, book.desc)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }

    override fun getItemLayoutId(): Int {
        return R.layout.item_search_book
    }
}
