package com.ldp.reader.ui.adapter.view

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemComicPageBinding
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.ui.base.adapter.ViewHolderImpl

class ComicPageHolder(
    private val onLoadResult: ((position: Int, request: MediaRequest, success: Boolean, detail: String) -> Unit)? = null
) : ViewHolderImpl<MediaRequest>() {
    private lateinit var image: ImageView

    override fun initView() {
        image = ItemComicPageBinding.bind(getItemView()).comicPageImage
    }

    override fun onBind(item: MediaRequest, pos: Int) {
        Glide.with(image)
            .load(item.glideModel())
            .placeholder(R.drawable.ic_book_loading)
            .error(R.drawable.ic_load_error)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    val detail = e?.rootCauses
                        ?.joinToString("|") { it.javaClass.simpleName + ":" + it.message.orEmpty().take(80) }
                        .orEmpty()
                        .ifBlank { e?.javaClass?.simpleName.orEmpty() }
                    onLoadResult?.invoke(pos, item, false, detail)
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    onLoadResult?.invoke(pos, item, true, dataSource.name)
                    return false
                }
            })
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
