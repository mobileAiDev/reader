package com.ldp.reader.ui.image

import android.content.Context
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.ldp.reader.utils.BookCoverUrl

object BookCoverLoader {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"

    fun load(
        context: Context,
        coverUrl: String?,
        target: ImageView,
        placeholderResId: Int
    ) {
        target.setImageResource(placeholderResId)
        val url = BookCoverUrl.clean(coverUrl)
        if (!BookCoverUrl.isUsable(url)) return
        Glide.with(context)
            .load(glideModel(url))
            .placeholder(placeholderResId)
            .error(placeholderResId)
            .centerCrop()
            .into(target)
    }

    private fun glideModel(url: String): Any {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url
        }
        return GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", refererFor(url))
                .build()
        )
    }

    private fun refererFor(url: String): String {
        val uri = Uri.parse(url)
        val scheme = uri.scheme ?: return url
        val host = uri.host ?: return url
        return "$scheme://$host/"
    }
}
