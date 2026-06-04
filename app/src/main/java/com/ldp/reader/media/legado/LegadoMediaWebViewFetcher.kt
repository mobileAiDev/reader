package com.ldp.reader.media.legado

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonParser
import com.ldp.reader.App
import com.ldp.reader.media.legado.MediaHttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object LegadoMediaWebViewFetcher {
    private const val DEFAULT_TIMEOUT_MS = 10_000L

    fun fetch(
        request: LegadoMediaUrlRequest,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        sourceRegex: String? = null,
        javaScript: String? = null
    ): MediaHttpResponse? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val effectiveTimeoutMillis = if (sourceRegex.isNullOrBlank()) {
            timeoutMillis
        } else {
            maxOf(timeoutMillis, request.webViewDelayMillis + SOURCE_SNIFF_TOTAL_WAIT_MS)
        }
        val latch = CountDownLatch(1)
        var result: MediaHttpResponse? = null
        var loadedUrl = request.url
        Handler(Looper.getMainLooper()).post {
            runCatching {
                loadOnMain(request, effectiveTimeoutMillis, sourceRegex, javaScript) { finalUrl, body ->
                    loadedUrl = finalUrl.ifBlank { loadedUrl }
                    result = MediaHttpResponse(loadedUrl, body)
                    latch.countDown()
                }
            }.onFailure {
                latch.countDown()
            }
        }
        latch.await(effectiveTimeoutMillis + request.webViewDelayMillis + 1_000L, TimeUnit.MILLISECONDS)
        return result
    }

    fun evaluate(
        html: String,
        request: LegadoMediaUrlRequest?,
        javaScript: String?,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        if (html.isBlank() && request == null) return ""
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val delayMillis = request?.webViewDelayMillis ?: DEFAULT_WEBVIEW_DELAY_MS
        val latch = CountDownLatch(1)
        var result: String? = null
        Handler(Looper.getMainLooper()).post {
            runCatching {
                evaluateOnMain(html, request, javaScript, timeoutMillis) { value ->
                    result = value
                    latch.countDown()
                }
            }.onFailure {
                latch.countDown()
            }
        }
        latch.await(timeoutMillis + delayMillis + 1_000L, TimeUnit.MILLISECONDS)
        return result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadOnMain(
        request: LegadoMediaUrlRequest,
        timeoutMillis: Long,
        sourceRegex: String?,
        javaScript: String?,
        finish: (String, String) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(App.getContext())
        var finished = false
        val sourcePattern = sourceRegex
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Regex(it) }.getOrNull() }

        fun complete(finalUrl: String, body: String) {
            if (finished) return
            finished = true
            finish(finalUrl, body)
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }

        fun completeWithPageBody(view: WebView, url: String) {
            if (finished) return
            view.evaluateJavascript(
                "(function(){return document.documentElement ? document.documentElement.outerHTML : document.body.innerHTML;})()"
            ) { encoded ->
                complete(view.url ?: url, decodeJavascriptString(encoded))
            }
        }

        fun sniffSourceElement(view: WebView, url: String, pattern: Regex, attempt: Int = 0) {
            if (finished) return
            view.evaluateJavascript(SOURCE_ELEMENT_JS) { encoded ->
                if (finished) return@evaluateJavascript
                val candidate = decodeJavascriptString(encoded).trim()
                if (candidate.isNotBlank() && pattern.matches(candidate)) {
                    complete(view.url ?: url, candidate)
                    return@evaluateJavascript
                }
                if (attempt < SOURCE_SNIFF_RETRY_COUNT) {
                    handler.postDelayed({
                        sniffSourceElement(view, url, pattern, attempt + 1)
                    }, SOURCE_SNIFF_RETRY_DELAY_MS)
                } else {
                    completeWithPageBody(view, url)
                }
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        request.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { webView.settings.userAgentString = it }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, resourceRequest: WebResourceRequest?): Boolean {
                val url = resourceRequest?.url?.toString().orEmpty()
                if (sourcePattern != null && sourcePattern.matches(url)) {
                    complete(view?.url ?: request.url, url)
                    return true
                }
                return false
            }

            override fun onLoadResource(view: WebView, url: String) {
                if (sourcePattern != null && sourcePattern.matches(url)) {
                    complete(view.url ?: request.url, url)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                handler.postDelayed({
                    if (finished) return@postDelayed
                    if (sourcePattern != null) {
                        if (!javaScript.isNullOrBlank()) {
                            view.loadUrl("javascript:$javaScript")
                        }
                        sniffSourceElement(view, url, sourcePattern)
                        return@postDelayed
                    }
                    view.evaluateJavascript(javaScript?.takeIf { it.isNotBlank() } ?: PAGE_BODY_JS) { encoded ->
                        val body = decodeJavascriptString(encoded)
                        complete(view.url ?: url, body)
                    }
                }, request.webViewDelayMillis.coerceAtLeast(0L))
            }
        }
        handler.postDelayed({ completeWithPageBody(webView, request.url) }, timeoutMillis)
        if (request.method.equals("POST", ignoreCase = true) && request.body != null) {
            webView.postUrl(request.url, request.body.toByteArray(Charsets.UTF_8))
        } else {
            webView.loadUrl(request.url, request.headers)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun evaluateOnMain(
        html: String,
        request: LegadoMediaUrlRequest?,
        javaScript: String?,
        timeoutMillis: Long,
        finish: (String) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(App.getContext())
        var finished = false
        val delayMillis = request?.webViewDelayMillis ?: DEFAULT_WEBVIEW_DELAY_MS

        fun complete(value: String) {
            if (finished) return
            finished = true
            finish(value)
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }

        fun evaluatePage(view: WebView) {
            view.evaluateJavascript(javaScript?.takeIf { it.isNotBlank() } ?: PAGE_BODY_JS) { encoded ->
                complete(decodeJavascriptString(encoded))
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        request?.headers
            ?.entries
            ?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { webView.settings.userAgentString = it }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                handler.postDelayed({
                    if (!finished) evaluatePage(view)
                }, delayMillis.coerceAtLeast(0L))
            }
        }
        handler.postDelayed({ if (!finished) evaluatePage(webView) }, timeoutMillis)
        if (html.isNotBlank()) {
            webView.loadDataWithBaseURL(
                request?.url ?: "https://reader.media.local/",
                html,
                "text/html",
                "UTF-8",
                null
            )
        } else if (request != null) {
            if (request.method.equals("POST", ignoreCase = true) && request.body != null) {
                webView.postUrl(request.url, request.body.toByteArray(Charsets.UTF_8))
            } else {
                webView.loadUrl(request.url, request.headers)
            }
        }
    }

    private fun decodeJavascriptString(value: String?): String {
        if (value.isNullOrBlank() || value == "null") return ""
        return runCatching { JsonParser.parseString(value).asString }.getOrDefault(value)
    }

    private const val SOURCE_SNIFF_RETRY_COUNT = 10
    private const val SOURCE_SNIFF_RETRY_DELAY_MS = 1_000L
    private const val SOURCE_SNIFF_TOTAL_WAIT_MS =
        (SOURCE_SNIFF_RETRY_COUNT + 1) * SOURCE_SNIFF_RETRY_DELAY_MS
    private const val DEFAULT_WEBVIEW_DELAY_MS = 1_200L
    private const val PAGE_BODY_JS =
        "(function(){return document.documentElement ? document.documentElement.outerHTML : document.body.innerHTML;})()"
    private const val SOURCE_ELEMENT_JS =
        "(function(){var e=document.querySelector('audio,source,video');" +
            "if(e){var u=e.currentSrc||e.src||e.getAttribute('src')||'';if(u)return u;}" +
            "var h=document.documentElement?document.documentElement.outerHTML:'';" +
            "var m=h.match(/https?:\\/\\/[^\"'\\s<>]+?\\.(?:mp3|m4a|aac|ogg|wav|flac|m3u8|mp4)(?:\\?[^\"'\\s<>]*)?/i);" +
            "return m?m[0]:'';})()"
}
