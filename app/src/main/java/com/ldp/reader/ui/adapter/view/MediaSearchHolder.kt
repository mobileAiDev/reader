package com.ldp.reader.ui.adapter.view

import android.widget.ImageView
import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemMediaSearchBinding
import com.ldp.reader.media.MediaSearchBook
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.ui.base.adapter.ViewHolderImpl
import com.ldp.reader.ui.image.BookCoverLoader

class MediaSearchHolder : ViewHolderImpl<MediaSearchBook>() {
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var brief: TextView

    override fun initView() {
        val binding = ItemMediaSearchBinding.bind(getItemView())
        cover = binding.mediaSearchCover
        title = binding.mediaSearchTitle
        brief = binding.mediaSearchBrief
    }

    override fun onBind(book: MediaSearchBook, pos: Int) {
        BookCoverLoader.load(
            listOfNotNull(book.coverUrl.takeIf { it.isNotBlank() }),
            cover,
            R.drawable.ic_book_cover_placeholder,
            onLoaded = { url -> MediaSourceRepository.recordSearchCoverLoad(book.routeId, url, loaded = true) },
            onFailed = { url -> MediaSourceRepository.recordSearchCoverLoad(book.routeId, url, loaded = false) }
        )
        title.text = book.title
        val chapterLabel = book.chapterCount
            .takeIf { it > 0 }
            ?.let { "目录 $it" }
            .orEmpty()
        brief.text = listOf(book.sourceName, chapterLabel, book.author, book.latest, book.intro)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }

    override fun getItemLayoutId(): Int = R.layout.item_media_search
}
