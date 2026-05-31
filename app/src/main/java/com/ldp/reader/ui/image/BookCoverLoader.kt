package com.ldp.reader.ui.image

import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
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
        load(listOfNotNull(coverUrl), target, placeholderResId)
    }

    fun load(
        coverUrls: List<String>,
        target: ImageView,
        placeholderResId: Int
    ) {
        val candidates = coverUrls
            .map { url -> BookCoverUrl.clean(url) }
            .filter { url -> BookCoverUrl.isUsable(url) }
            .distinct()
        val requestManager = Glide.with(target)
        if (candidates.isEmpty()) {
            requestManager.clear(target)
            target.setTag(R.id.book_cover_request_url, null)
            target.setTag(R.id.book_cover_request_key, null)
            target.setImageResource(placeholderResId)
            return
        }
        val requestKey = candidates.joinToString("\n")
        val previousKey = target.getTag(R.id.book_cover_request_key) as? String
        if (previousKey != requestKey) {
            target.setImageDrawable(null)
            target.setTag(R.id.book_cover_request_key, requestKey)
        }
        loadCandidate(requestManager, candidates, 0, target, placeholderResId, requestKey)
    }

    private fun loadCandidate(
        requestManager: RequestManager,
        candidates: List<String>,
        index: Int,
        imageView: ImageView,
        placeholderResId: Int,
        requestKey: String
    ) {
        if (imageView.getTag(R.id.book_cover_request_key) != requestKey) return
        val url = candidates[index]
        imageView.setTag(R.id.book_cover_request_url, url)
        val request = requestManager
            .load(glideModel(url))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (imageView.getTag(R.id.book_cover_request_key) != requestKey) return true
                    val nextIndex = index + 1
                    if (nextIndex < candidates.size) {
                        loadCandidate(requestManager, candidates, nextIndex, imageView, placeholderResId, requestKey)
                    } else {
                        imageView.setTag(R.id.book_cover_request_url, null)
                        imageView.setImageResource(placeholderResId)
                    }
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    return imageView.getTag(R.id.book_cover_request_key) != requestKey
                }
            })
            .error(placeholderResId)
            .dontAnimate()
            .centerCrop()
        if (imageView.width > 0 && imageView.height > 0) {
            request.override(imageView.width, imageView.height)
        }
        request.into(imageView)
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
