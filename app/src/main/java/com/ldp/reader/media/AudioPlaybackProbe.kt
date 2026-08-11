package com.ldp.reader.media

import android.util.Log
import com.ldp.reader.source.AiBridgeTrace
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object AudioPlaybackProbe {
    private val contentRangeTotal = Regex("""(?i)bytes\s+\d+-\d+/(\d+)""")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .hostnameVerifier { host, session -> verifyAudioHostname(host, session) }
            .build()
    }

    fun isPlayable(request: MediaRequest): Boolean {
        return resolvePlayableRequest(request) != null
    }

    fun resolvePlayableRequest(request: MediaRequest): MediaRequest? {
        if (!request.url.startsWith("http://", ignoreCase = true) &&
            !request.url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return runCatching {
            val outcome = executeProbe(
                request = request,
                execute = { probeRequest, remainingNanos ->
                    val call = client.newCall(probeRequest)
                    call.timeout().timeout(remainingNanos, TimeUnit.NANOSECONDS)
                    call.execute()
                },
                nanoTime = System::nanoTime
            )
            traceProbe(
                request,
                outcome.code,
                outcome.contentType,
                outcome.lengthBytes,
                outcome.playableRequest != null,
                outcome.error
            )
            outcome.playableRequest
        }.getOrElse { throwable ->
            traceProbe(request, -1, "", -1L, false, throwable.javaClass.simpleName)
            Log.w(TAG, "Audio probe failed url=${request.url.safeUrlForLog()}", throwable)
            null
        }
    }

    internal fun resolvePlayableRequest(
        request: MediaRequest,
        execute: (Request) -> Response
    ): MediaRequest? {
        if (!request.url.startsWith("http://", ignoreCase = true) &&
            !request.url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return runCatching {
            executeProbe(
                request = request,
                execute = { probeRequest, _ -> execute(probeRequest) },
                nanoTime = { 0L }
            ).playableRequest
        }.getOrNull()
    }

    internal fun isPlayable(
        @Suppress("UNUSED_PARAMETER") request: MediaRequest,
        response: Response
    ): Boolean {
        return evaluate(response).playable
    }

    private fun executeProbe(
        request: MediaRequest,
        execute: (Request, Long) -> Response,
        nanoTime: () -> Long
    ): ProbeOutcome {
        var currentUrl = request.url.toHttpUrlOrNull()
            ?: throw IOException("invalid_audio_probe_url")
        var currentHeaders = initialRequestHeaders(request.headers)
        val deadlineNanos = nanoTime() + CALL_TIMEOUT_NANOS
        for (redirectCount in 0..AudioPlaybackRedirectPolicy.MAX_REDIRECTS) {
            val remainingNanos = deadlineNanos - nanoTime()
            if (remainingNanos <= 0L) throw IOException("audio_probe_timeout")
            val response = execute(buildRequest(currentUrl, currentHeaders), remainingNanos)
            if (!AudioPlaybackRedirectPolicy.isRedirect(response.code)) {
                return evaluateOutcome(response, currentUrl, currentHeaders)
            }
            val location = response.header("Location")
                ?: return evaluateOutcome(response, currentUrl, currentHeaders)
            if (redirectCount == AudioPlaybackRedirectPolicy.MAX_REDIRECTS) {
                response.close()
                throw IOException("audio_probe_redirect_limit")
            }
            val nextUrl = currentUrl.resolve(location)
            if (nextUrl == null || !AudioPlaybackRedirectPolicy.isAllowed(currentUrl, nextUrl)) {
                response.close()
                throw IOException("audio_probe_redirect_rejected")
            }
            currentHeaders = headersForRedirect(currentHeaders, currentUrl, nextUrl)
            currentUrl = nextUrl
            response.close()
        }
        throw IOException("audio_probe_redirect_limit")
    }

    private fun evaluateOutcome(
        response: Response,
        finalUrl: HttpUrl,
        finalHeaders: Map<String, String>
    ): ProbeOutcome {
        response.use {
            val decision = evaluate(response)
            return ProbeOutcome(
                code = response.code,
                contentType = decision.contentType,
                lengthBytes = decision.lengthBytes,
                playableRequest = if (decision.playable) {
                    MediaRequest(finalUrl.toString(), finalHeaders)
                } else {
                    null
                },
                error = decision.error
            )
        }
    }

    private fun buildRequest(url: HttpUrl, headers: Map<String, String>): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
            .header("Range", PROBE_RANGE)
            .build()
    }

    internal fun initialRequestHeaders(headers: Map<String, String>): Map<String, String> {
        val normalized = MediaPlaybackHeaders.audio(headers)
        val filtered = linkedMapOf<String, String>()
        MediaPlaybackHeaders.defaultRequestProperties(normalized).forEach { (name, value) ->
            if (name.lowercase(Locale.US) !in BLOCKED_OR_CONTROLLED_HEADERS) {
                filtered[name] = value
            }
        }
        filtered["User-Agent"] = MediaPlaybackHeaders.userAgent(normalized)
        return filtered
    }

    internal fun isRedirectAllowed(from: HttpUrl, to: HttpUrl): Boolean {
        return AudioPlaybackRedirectPolicy.isAllowed(from, to)
    }

    internal fun headersForRedirect(
        headers: Map<String, String>,
        from: HttpUrl,
        to: HttpUrl
    ): Map<String, String> {
        return AudioPlaybackRedirectPolicy.headersForRedirect(headers, from, to)
    }

    private fun evaluate(response: Response): ProbeDecision {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val basicPlayable = response.code in 200..299 &&
            isAudioLike(contentType, response.request.url.encodedPath) &&
            !isExplicitNonAudioType(contentType)
        val lengthBytes = response.probedAudioLengthBytes()
        val hasContent = lengthBytes != 0L && response.body?.source()?.request(1L) == true
        val explicitNonAudioBody = hasContent && response.hasExplicitNonAudioBody()
        return ProbeDecision(
            contentType = contentType,
            lengthBytes = lengthBytes,
            playable = basicPlayable && hasContent && !explicitNonAudioBody,
            error = when {
                !hasContent -> "empty_response"
                explicitNonAudioBody -> "non_audio_body"
                else -> ""
            }
        )
    }

    private fun isAudioLike(contentType: String, urlPath: String): Boolean {
        if (contentType.startsWith("audio/")) return true
        if (contentType.contains("mpegurl") || contentType.contains("x-mpegurl")) return true
        val path = urlPath.lowercase()
        return path.endsWith(".mp3") ||
            path.endsWith(".m4a") ||
            path.endsWith(".aac") ||
            path.endsWith(".ogg") ||
            path.endsWith(".wav") ||
            path.endsWith(".flac") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m3u8")
    }

    private fun isExplicitNonAudioType(contentType: String): Boolean {
        val mimeType = contentType.substringBefore(';').trim()
        return mimeType == "text/html" ||
            mimeType == "application/xhtml+xml" ||
            mimeType == "application/json" ||
            mimeType == "text/json" ||
            mimeType.endsWith("+json")
    }

    private fun Response.probedAudioLengthBytes(): Long {
        val rangeTotal = header("Content-Range")
            ?.let { range -> contentRangeTotal.find(range)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        if (rangeTotal != null) return rangeTotal
        return header("Content-Length")?.toLongOrNull() ?: -1L
    }

    private fun Response.hasExplicitNonAudioBody(): Boolean {
        val prefix = peekBody(BODY_SNIFF_BYTES)
            .string()
            .trimStart { character -> character.isWhitespace() || character == '\uFEFF' }
            .lowercase()
        return prefix.startsWith("<!doctype html") ||
            prefix.startsWith("<html") ||
            prefix.startsWith("<head") ||
            prefix.startsWith("<body") ||
            prefix.startsWith("{") ||
            prefix.startsWith("[")
    }

    private fun verifyAudioHostname(host: String, session: SSLSession): Boolean {
        if (DEFAULT_HOSTNAME_VERIFIER.verify(host, session)) return true
        val dnsNames = runCatching {
            session.peerCertificates
                .filterIsInstance<X509Certificate>()
                .flatMap { MediaPlaybackTlsPolicy.dnsSubjectAlternativeNames(it) }
        }.getOrDefault(emptyList())
        return MediaPlaybackTlsPolicy.acceptsKnownAudioCdnAlias(host, dnsNames)
    }

    private fun traceProbe(
        request: MediaRequest,
        code: Int,
        contentType: String,
        bytes: Long,
        playable: Boolean,
        error: String
    ) {
        AiBridgeTrace.event(
            "media_audio_probe",
            request.url.safeUrlForTrace(),
            AiBridgeTrace.fields(
                "code" to code,
                "type" to contentType,
                "bytes" to bytes,
                "playable" to playable,
                "error" to error
            )
        )
    }

    private fun String.safeUrlForLog(): String {
        return substringBefore('?').take(180)
    }

    private fun String.safeUrlForTrace(): String {
        return substringBefore('?')
            .replace(Regex("""[\s=:/\\#]+"""), "_")
            .take(160)
    }

    private const val TAG = "AudioPlaybackProbe"
    private const val BODY_SNIFF_BYTES = 128L
    private const val CALL_TIMEOUT_SECONDS = 5L
    private const val PROBE_RANGE = "bytes=0-1023"
    private val CALL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(CALL_TIMEOUT_SECONDS)
    private val BLOCKED_OR_CONTROLLED_HEADERS = setOf(
        "accept-encoding",
        "connection",
        "content-length",
        "host",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "proxy-connection",
        "range",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade"
    )
    private val DEFAULT_HOSTNAME_VERIFIER = HttpsURLConnection.getDefaultHostnameVerifier()

    private data class ProbeDecision(
        val contentType: String,
        val lengthBytes: Long,
        val playable: Boolean,
        val error: String
    )

    private data class ProbeOutcome(
        val code: Int,
        val contentType: String,
        val lengthBytes: Long,
        val playableRequest: MediaRequest?,
        val error: String
    )
}
