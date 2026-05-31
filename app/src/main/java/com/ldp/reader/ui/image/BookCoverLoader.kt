package com.ldp.reader.ui.image

import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.ldp.reader.R
import com.ldp.reader.utils.BookCoverUrl

object BookCoverLoader {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"

    fun load(
        coverUrl: String?,
        target: ImageView,
        placeholderResId: Int
    ) {
        val url = BookCoverUrl.clean(coverUrl)
        val requestManager = Glide.with(target)
        if (!BookCoverUrl.isUsable(url)) {
            requestManager.clear(target)
            target.setTag(R.id.book_cover_request_url, null)
            target.setImageResource(placeholderResId)
            return
        }
        val previousUrl = target.getTag(R.id.book_cover_request_url) as? String
        if (previousUrl != url) {
            target.setImageDrawable(null)
            target.setTag(R.id.book_cover_request_url, url)
        }
        val request = requestManager
            .load(glideModel(url))
            .error(placeholderResId)
            .dontAnimate()
            .centerCrop()
        if (target.width > 0 && target.height > 0) {
            request.override(target.width, target.height)
        }
        request.into(target)
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
