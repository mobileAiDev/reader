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
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.blankj.utilcode.util.ActivityUtils
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ldp.reader.R
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.utils.BookCoverUrl
import com.ldp.reader.widget.transform.CircleTransform

object BookCoverLoader {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"

    fun load(
        coverUrl: String?,
        target: ImageView,
        placeholderResId: Int,
        circle: Boolean = false,
        onLoaded: ((String) -> Unit)? = null
    ) {
        load(listOfNotNull(coverUrl), target, placeholderResId, circle, onLoaded)
    }

    fun load(
        coverUrls: List<String>,
        target: ImageView,
        placeholderResId: Int,
        circle: Boolean = false,
        onLoaded: ((String) -> Unit)? = null
    ) {
        if (!ActivityUtils.isActivityAlive(target.context)) return
        val candidates = coverUrls
            .map { url -> BookCoverUrl.clean(url) }
            .filter { url -> BookCoverUrl.isUsable(url) }
            .distinct()
        val requestManager = runCatching { Glide.with(target) }.getOrNull() ?: return
        if (candidates.isEmpty()) {
            AiBridgeTrace.event(
                "book_cover_candidates_empty",
                traceTarget(target),
                AiBridgeTrace.fields("placeholder" to placeholderResId)
            )
            requestManager.clear(target)
            target.setTag(R.id.book_cover_request_url, null)
            target.setTag(R.id.book_cover_request_key, null)
            target.setImageResource(placeholderResId)
            return
        }
        val requestKey = candidates.joinToString("\n")
        val previousKey = target.getTag(R.id.book_cover_request_key) as? String
        val loadedUrl = target.getTag(R.id.book_cover_request_url) as? String
        if (previousKey == requestKey && loadedUrl in candidates) {
            return
        }
        if (previousKey != requestKey) {
            target.setTag(R.id.book_cover_request_key, requestKey)
            if (loadedUrl in candidates) {
                return
            }
            requestManager.clear(target)
            target.setTag(R.id.book_cover_request_url, null)
            target.setImageDrawable(null)
        }
        loadCandidate(requestManager, candidates, 0, target, placeholderResId, requestKey, circle, onLoaded)
    }

    private fun loadCandidate(
        requestManager: RequestManager,
        candidates: List<String>,
        index: Int,
        imageView: ImageView,
        placeholderResId: Int,
        requestKey: String,
        circle: Boolean,
        onLoaded: ((String) -> Unit)?
    ) {
        if (imageView.getTag(R.id.book_cover_request_key) != requestKey) return
        if (!ActivityUtils.isActivityAlive(imageView.context)) return
        val url = candidates[index]
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
                    AiBridgeTrace.event(
                        "book_cover_candidate_failed",
                        traceTarget(imageView),
                        AiBridgeTrace.fields(
                            "index" to index,
                            "count" to candidates.size,
                            "host" to urlHost(url),
                            "likely" to BookCoverUrl.isLikelyImage(url),
                            "error" to (e?.javaClass?.simpleName ?: "unknown")
                        )
                    )
                    val nextIndex = index + 1
                    imageView.post {
                        if (imageView.getTag(R.id.book_cover_request_key) != requestKey) return@post
                        if (nextIndex < candidates.size) {
                            loadCandidate(requestManager, candidates, nextIndex, imageView, placeholderResId, requestKey, circle, onLoaded)
                        } else {
                            imageView.setTag(R.id.book_cover_request_url, null)
                            imageView.setImageResource(placeholderResId)
                            AiBridgeTrace.state(
                                "book_cover_all_candidates_failed",
                                traceTarget(imageView),
                                AiBridgeTrace.fields("count" to candidates.size)
                            )
                        }
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
                    if (imageView.getTag(R.id.book_cover_request_key) != requestKey) return true
                    imageView.setTag(R.id.book_cover_request_url, url)
                    AiBridgeTrace.state(
                        "book_cover_candidate_loaded",
                        traceTarget(imageView),
                        AiBridgeTrace.fields(
                            "index" to index,
                            "count" to candidates.size,
                            "host" to urlHost(url),
                            "likely" to BookCoverUrl.isLikelyImage(url),
                            "source" to dataSource.name
                        )
                    )
                    onLoaded?.invoke(url)
                    return false
                }
            })
            .dontAnimate()
        if (circle) {
            request.transform(CenterCrop(), CircleTransform())
        } else {
            request.centerCrop()
        }
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

    private fun traceTarget(target: ImageView): String {
        val viewName = runCatching {
            target.resources.getResourceEntryName(target.id)
        }.getOrElse {
            "imageView"
        }
        return "${target.context.javaClass.simpleName}.$viewName"
    }

    private fun urlHost(url: String): String {
        return runCatching {
            Uri.parse(url).host ?: "local"
        }.getOrElse {
            "unknown"
        }
    }
}
