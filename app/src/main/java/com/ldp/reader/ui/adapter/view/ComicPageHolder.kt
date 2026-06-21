package com.ldp.reader.ui.adapter.view

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.blankj.utilcode.util.ActivityUtils
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

    override fun onBind(data: MediaRequest, pos: Int) {
        val requestKey = data.requestKey(pos)
        image.setTag(R.id.comic_page_request_key, requestKey)
        loadPage(data, pos, requestKey, attempt = 0)
    }

    private fun loadPage(item: MediaRequest, pos: Int, requestKey: String, attempt: Int) {
        val requestManager = requestManagerOrSkip(item, pos, requestKey, attempt) ?: return
        requestManager.clear(image)
        val request = requestManager
            .load(item.glideModel())
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .downsample(DownsampleStrategy.AT_MOST)
            .dontAnimate()
            .placeholder(R.drawable.ic_book_loading)
            .error(R.drawable.ic_load_error)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    if (image.getTag(R.id.comic_page_request_key) != requestKey) {
                        return true
                    }
                    val detail = e?.rootCauses
                        ?.joinToString("|") { it.javaClass.simpleName + ":" + it.message.orEmpty().take(80) }
                        .orEmpty()
                        .ifBlank { e?.javaClass?.simpleName.orEmpty() }
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        val nextAttempt = attempt + 1
                        image.postDelayed(
                            {
                                if (
                                    image.getTag(R.id.comic_page_request_key) == requestKey &&
                                    ActivityUtils.isActivityAlive(image.context)
                                ) {
                                    loadPage(item, pos, requestKey, attempt = nextAttempt)
                                }
                            },
                            retryDelayMs(nextAttempt)
                        )
                        return true
                    }
                    onLoadResult?.invoke(pos, item, false, "attempt_${attempt + 1}:$detail")
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    if (image.getTag(R.id.comic_page_request_key) != requestKey) {
                        return true
                    }
                    onLoadResult?.invoke(pos, item, true, "attempt_${attempt + 1}:${dataSource.name}")
                    return false
                }
            })
        val targetWidth = image.width.takeIf { it > 0 } ?: image.resources.displayMetrics.widthPixels
        if (targetWidth > 0) {
            request.override(targetWidth, Target.SIZE_ORIGINAL)
        }
        request.into(image)
    }

    override fun getItemLayoutId(): Int = R.layout.item_comic_page

    private fun requestManagerOrSkip(
        item: MediaRequest,
        pos: Int,
        requestKey: String,
        attempt: Int
    ): RequestManager? {
        if (image.getTag(R.id.comic_page_request_key) != requestKey) return null
        if (!ActivityUtils.isActivityAlive(image.context)) {
            onLoadResult?.invoke(pos, item, false, "attempt_${attempt + 1}:activity_not_alive")
            return null
        }
        return runCatching { Glide.with(image) }
            .getOrElse { error ->
                onLoadResult?.invoke(
                    pos,
                    item,
                    false,
                    "attempt_${attempt + 1}:glide_context_invalid:${error.message.orEmpty().take(80)}"
                )
                null
            }
    }

    private fun retryDelayMs(attempt: Int): Long {
        return when (attempt) {
            1 -> 450L
            2 -> 1_200L
            else -> 2_000L
        }
    }

    private fun MediaRequest.glideModel(): Any {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return url
        }
        val lazyHeaders = LazyHeaders.Builder()
        val requestHeaders = headers.toMutableMap()
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestHeaders["User-Agent"] = DEFAULT_USER_AGENT
        }
        if (requestHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
            requestHeaders["Referer"] = refererFor(url)
        }
        requestHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                lazyHeaders.addHeader(name, value)
            }
        }
        return GlideUrl(url, lazyHeaders.build())
    }

    private fun MediaRequest.requestKey(pos: Int): String {
        return "$pos|$url|${headers.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}"
    }

    private fun refererFor(url: String): String {
        val uri = android.net.Uri.parse(url)
        val scheme = uri.scheme ?: return url
        val host = uri.host ?: return url
        return "$scheme://$host/"
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"
    }
}
