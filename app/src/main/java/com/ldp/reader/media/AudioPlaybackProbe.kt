package com.ldp.reader.media

import android.util.Log
import com.ldp.reader.source.AiBridgeTrace
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object AudioPlaybackProbe {
    private val contentRangeTotal = Regex("""(?i)bytes\s+\d+-\d+/(\d+)""")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .hostnameVerifier { host, session -> verifyAudioHostname(host, session) }
            .build()
    }

    fun isPlayable(request: MediaRequest): Boolean {
        if (!request.url.startsWith("http://", ignoreCase = true) &&
            !request.url.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        return runCatching {
            val headers = MediaPlaybackHeaders.audio(request.headers)
            val builder = Request.Builder()
                .url(request.url)
                .get()
                .header("Range", "bytes=0-1023")
                .header("User-Agent", MediaPlaybackHeaders.userAgent(headers))
            MediaPlaybackHeaders.defaultRequestProperties(headers).forEach { (name, value) ->
                builder.header(name, value)
            }
            client.newCall(builder.build()).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val basicPlayable = response.code == 206 ||
                    (response.code in 200..299 && isAudioLike(contentType, request.url))
                val lengthBytes = response.probedAudioLengthBytes()
                val lengthOk = !requiresFullAudioLengthCheck(contentType, request.url) ||
                    lengthBytes <= 0L ||
                    lengthBytes >= MIN_FULL_AUDIO_BYTES
                val playable = basicPlayable && lengthOk
                val error = if (!lengthOk) "too_short_bytes_$lengthBytes" else ""
                traceProbe(request, response.code, contentType, lengthBytes, playable, error)
                playable
            }
        }.getOrElse { throwable ->
            traceProbe(request, -1, "", -1L, false, throwable.javaClass.simpleName)
            Log.w(TAG, "Audio probe failed url=${request.url.safeUrlForLog()}", throwable)
            false
        }
    }

    private fun isAudioLike(contentType: String, url: String): Boolean {
        if (contentType.startsWith("audio/")) return true
        if (contentType.contains("mpegurl") || contentType.contains("x-mpegurl")) return true
        val cleanUrl = url.substringBefore('?').lowercase()
        return cleanUrl.endsWith(".mp3") ||
            cleanUrl.endsWith(".m4a") ||
            cleanUrl.endsWith(".aac") ||
            cleanUrl.endsWith(".mp4") ||
            cleanUrl.endsWith(".m3u8")
    }

    private fun requiresFullAudioLengthCheck(contentType: String, url: String): Boolean {
        if (contentType.contains("mpegurl") || contentType.contains("x-mpegurl")) return false
        val cleanUrl = url.substringBefore('?').lowercase()
        return contentType.startsWith("audio/") ||
            cleanUrl.endsWith(".mp3") ||
            cleanUrl.endsWith(".m4a") ||
            cleanUrl.endsWith(".aac") ||
            cleanUrl.endsWith(".ogg") ||
            cleanUrl.endsWith(".wav") ||
            cleanUrl.endsWith(".flac") ||
            cleanUrl.endsWith(".mp4")
    }

    private fun Response.probedAudioLengthBytes(): Long {
        val rangeTotal = header("Content-Range")
            ?.let { range -> contentRangeTotal.find(range)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        if (rangeTotal != null) return rangeTotal
        return header("Content-Length")?.toLongOrNull() ?: -1L
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
    private const val MIN_FULL_AUDIO_BYTES = 512L * 1024L
    private val DEFAULT_HOSTNAME_VERIFIER = HttpsURLConnection.getDefaultHostnameVerifier()
}
