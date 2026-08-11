package com.ldp.reader.media

import java.io.IOException
import java.net.ProtocolException
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal object AudioPlaybackRedirectPolicy {
    const val MAX_REDIRECTS = 5

    fun isRedirect(code: Int): Boolean = code in REDIRECT_STATUS_CODES

    fun isAllowed(from: HttpUrl, to: HttpUrl): Boolean {
        return from.scheme != "https" || to.scheme == "https"
    }

    fun headersForRedirect(
        headers: Map<String, String>,
        from: HttpUrl,
        to: HttpUrl
    ): Map<String, String> {
        if (from.sameOriginAs(to)) return headers.toMap()
        return headers.filterKeys { name ->
            name.lowercase(Locale.US) in CROSS_ORIGIN_SAFE_HEADERS
        }
    }

    fun redirectedRequest(request: Request, to: HttpUrl): Request {
        val redirectedHeaders = headersForRedirect(
            headers = request.headers.names().associateWith { name -> request.header(name).orEmpty() },
            from = request.url,
            to = to
        )
        val builder = request.newBuilder()
            .url(to)
            .headers(okhttp3.Headers.Builder().apply {
                redirectedHeaders.forEach { (name, value) -> add(name, value) }
            }.build())
        return builder.build()
    }

    private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean {
        return scheme == other.scheme && host == other.host && port == other.port
    }

    private val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
    private val CROSS_ORIGIN_SAFE_HEADERS = setOf(
        "accept",
        "accept-encoding",
        "range",
        "user-agent"
    )
}

internal class AudioPlaybackRedirectInterceptor(
    private val maxRedirects: Int = AudioPlaybackRedirectPolicy.MAX_REDIRECTS
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return follow(chain.request(), chain::proceed)
    }

    internal fun follow(
        initialRequest: Request,
        proceed: (Request) -> Response
    ): Response {
        var currentRequest = initialRequest
        for (redirectCount in 0..maxRedirects) {
            val response = proceed(currentRequest)
            if (!AudioPlaybackRedirectPolicy.isRedirect(response.code)) return response
            val location = response.header("Location") ?: return response
            if (redirectCount == maxRedirects) {
                response.close()
                throw ProtocolException("audio_playback_redirect_limit")
            }
            val nextUrl = currentRequest.url.resolve(location)
            if (nextUrl == null || !AudioPlaybackRedirectPolicy.isAllowed(currentRequest.url, nextUrl)) {
                response.close()
                throw IOException("audio_playback_redirect_rejected")
            }
            response.close()
            currentRequest = AudioPlaybackRedirectPolicy.redirectedRequest(currentRequest, nextUrl)
        }
        throw ProtocolException("audio_playback_redirect_limit")
    }
}
