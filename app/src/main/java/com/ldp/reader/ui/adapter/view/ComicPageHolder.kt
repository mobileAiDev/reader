package com.ldp.reader.ui.adapter.view

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemComicPageBinding
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.ui.base.adapter.ViewHolderImpl

class ComicPageHolder : ViewHolderImpl<MediaRequest>() {
    private lateinit var image: ImageView

    override fun initView() {
        image = ItemComicPageBinding.bind(getItemView()).comicPageImage
    }

    override fun onBind(item: MediaRequest, pos: Int) {
        Glide.with(image)
            .load(item.glideModel())
            .placeholder(R.drawable.ic_book_loading)
            .error(R.drawable.ic_load_error)
            .into(image)
    }

    override fun getItemLayoutId(): Int = R.layout.item_comic_page

    private fun MediaRequest.glideModel(): Any {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return url
        }
        if (headers.isEmpty()) return GlideUrl(url)
        val lazyHeaders = LazyHeaders.Builder()
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                lazyHeaders.addHeader(name, value)
            }
        }
        return GlideUrl(url, lazyHeaders.build())
    }
}
