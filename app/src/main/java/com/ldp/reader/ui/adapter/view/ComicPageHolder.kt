package com.ldp.reader.ui.adapter.view

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
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
import com.ldp.reader.ui.theme.GlareGuard
import com.ldp.reader.databinding.ItemComicPageBinding
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.ui.adapter.ComicPageLayoutMode
import com.ldp.reader.ui.adapter.ComicPageLayoutSpec
import com.ldp.reader.ui.adapter.ComicPageScaleType
import com.ldp.reader.ui.adapter.comicPageLayoutSpec
import com.ldp.reader.ui.base.adapter.ViewHolderImpl

internal class ComicPageHolder(
    private val generationProvider: () -> Int,
    private val layoutModeProvider: () -> ComicPageLayoutMode,
    private val onLoadResult: ((generation: Int, position: Int, request: MediaRequest, success: Boolean, detail: String) -> Unit)? = null
) : ViewHolderImpl<MediaRequest>() {
    private lateinit var image: ImageView
    private var pendingRetry: Runnable? = null

    override fun initView() {
        image = ItemComicPageBinding.bind(getItemView()).comicPageImage
    }

    override fun onBind(data: MediaRequest, pos: Int) {
        cancelPendingRetries()
        val generation = generationProvider()
        val layoutMode = layoutModeProvider()
        applyPageLayout(comicPageLayoutSpec(layoutMode))
        val requestKey = data.requestKey(generation, pos, layoutMode)
        image.setTag(R.id.comic_page_request_key, requestKey)
        loadPage(data, pos, requestKey, generation, layoutMode, attempt = 0)
    }

    private fun loadPage(
        item: MediaRequest,
        pos: Int,
        requestKey: String,
        generation: Int,
        layoutMode: ComicPageLayoutMode,
        attempt: Int
    ) {
        val requestManager = requestManagerOrSkip(item, pos, requestKey, generation, attempt) ?: return
        requestManager.clear(image)
        val request = requestManager
            .load(item.glideModel())
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .downsample(DownsampleStrategy.AT_MOST)
            .fitCenter()
            .dontAnimate()
            .placeholder(R.drawable.ic_comic_page_loading)
            .error(R.drawable.ic_comic_page_error)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    if (!isCurrentRequest(requestKey, generation)) {
                        return true
                    }
                    val detail = e?.rootCauses
                        ?.joinToString("|") { it.javaClass.simpleName + ":" + it.message.orEmpty().take(80) }
                        .orEmpty()
                        .ifBlank { e?.javaClass?.simpleName.orEmpty() }
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        val nextAttempt = attempt + 1
                        val retry = Runnable {
                            pendingRetry = null
                            if (isCurrentRequest(requestKey, generation) &&
                                ActivityUtils.isActivityAlive(image.context)
                            ) {
                                loadPage(
                                    item,
                                    pos,
                                    requestKey,
                                    generation,
                                    layoutMode,
                                    attempt = nextAttempt
                                )
                            }
                        }
                        pendingRetry?.let(image::removeCallbacks)
                        pendingRetry = retry
                        image.postDelayed(retry, retryDelayMs(nextAttempt))
                        return true
                    }
                    onLoadResult?.invoke(generation, pos, item, false, "attempt_${attempt + 1}:$detail")
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    if (!isCurrentRequest(requestKey, generation)) {
                        return true
                    }
                    GlareGuard.apply(image, resource)
                    onLoadResult?.invoke(
                        generation,
                        pos,
                        item,
                        true,
                        "attempt_${attempt + 1}:${dataSource.name}"
                    )
                    return false
                }
            })
        val viewport = getItemView().parent as? RecyclerView
        val targetWidth = viewport?.width?.takeIf { it > 0 }
            ?: image.width.takeIf { it > 0 }
            ?: image.resources.displayMetrics.widthPixels
        val targetHeight = if (comicPageLayoutSpec(layoutMode).constrainDecodeToViewport) {
            viewport?.height?.takeIf { it > 0 }
                ?: image.resources.displayMetrics.heightPixels
        } else {
            Target.SIZE_ORIGINAL
        }
        if (targetWidth > 0 && targetHeight != 0) {
            request.override(targetWidth, targetHeight)
        }
        if (GlareGuard.isActive(image.context)) {
            // Anti-glare samples page pixels; hardware bitmaps cannot be read back.
            request.disallowHardwareConfig()
        } else {
            GlareGuard.clear(image)
        }
        request.into(image)
    }

    override fun getItemLayoutId(): Int = R.layout.item_comic_page

    private fun requestManagerOrSkip(
        item: MediaRequest,
        pos: Int,
        requestKey: String,
        generation: Int,
        attempt: Int
    ): RequestManager? {
        if (!isCurrentRequest(requestKey, generation)) return null
        if (!ActivityUtils.isActivityAlive(image.context)) {
            onLoadResult?.invoke(
                generation,
                pos,
                item,
                false,
                "attempt_${attempt + 1}:activity_not_alive"
            )
            return null
        }
        return runCatching { Glide.with(image) }
            .getOrElse { error ->
                onLoadResult?.invoke(
                    generation,
                    pos,
                    item,
                    false,
                    "attempt_${attempt + 1}:glide_context_invalid:${error.message.orEmpty().take(80)}"
                )
                null
            }
    }

    fun cancelPendingRetries() {
        if (!::image.isInitialized) return
        pendingRetry?.let(image::removeCallbacks)
        pendingRetry = null
        image.setTag(R.id.comic_page_request_key, null)
        runCatching { Glide.with(image).clear(image) }
    }

    private fun isCurrentRequest(requestKey: String, generation: Int): Boolean {
        return generationProvider() == generation &&
            image.getTag(R.id.comic_page_request_key) == requestKey
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

    private fun applyPageLayout(spec: ComicPageLayoutSpec) {
        getItemView().layoutParams = getItemView().layoutParams.apply {
            width = if (spec.itemFillsViewportWidth) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            height = if (spec.itemFillsViewportHeight) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        image.layoutParams = image.layoutParams.apply {
            width = if (spec.imageFillsPageWidth) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            height = if (spec.imageFillsPageHeight) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        image.adjustViewBounds = spec.adjustViewBounds
        image.scaleType = when (spec.scaleType) {
            ComicPageScaleType.FIT_CENTER -> ImageView.ScaleType.FIT_CENTER
        }
        getItemView().requestLayout()
    }

    private fun MediaRequest.requestKey(
        generation: Int,
        pos: Int,
        layoutMode: ComicPageLayoutMode
    ): String {
        return "$generation|$pos|${layoutMode.name}|$url|" +
            headers.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
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
