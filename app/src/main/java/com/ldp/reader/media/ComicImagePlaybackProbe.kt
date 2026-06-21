package com.ldp.reader.media

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.InterruptedIOException
import java.io.InputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object ComicImagePlaybackProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()
    private val http1Client = client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    fun probe(request: MediaRequest): ComicImageProbeResult {
        if (!request.url.startsWith("http://", ignoreCase = true) &&
            !request.url.startsWith("https://", ignoreCase = true)
        ) {
            return ComicImageProbeResult(ok = true, statusCode = 0, contentType = "local", bytesRead = 0)
        }
        repeat(PROBE_ATTEMPTS) { attempt ->
            val result = executeProbe(request)
            if (result.shouldRetryWithoutRange()) {
                val noRange = executeProbe(request, useRange = false)
                if (noRange.ok || noRange.cancelled) return noRange
                val http1 = executeProbe(request, client = http1Client, useRange = false)
                if (http1.ok || http1.cancelled) return http1
                if (attempt == PROBE_ATTEMPTS - 1 || !http1.retryableTimeout()) return http1
                return@repeat
            }
            if (result.ok || result.cancelled || attempt == PROBE_ATTEMPTS - 1 || !result.retryableTimeout()) {
                return result
            }
        }
        return ComicImageProbeResult(
            ok = false,
            statusCode = 0,
            contentType = "",
            bytesRead = 0,
            finalUrl = request.url,
            error = "probe_empty"
        )
    }

    private fun executeProbe(
        request: MediaRequest,
        client: OkHttpClient = this.client,
        useRange: Boolean = true
    ): ComicImageProbeResult {
        return runCatching {
            val httpRequestBuilder = Request.Builder()
                .url(request.url)
                .headers(playbackHeaders(request))
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            if (useRange) httpRequestBuilder.header("Range", "bytes=0-2047")
            val httpRequest = httpRequestBuilder.build()
            val response = client.newCall(httpRequest).execute()
            val bodyRead = try {
                readProbeBytes(response.body)
            } finally {
                runCatching { response.close() }
            }
            val buffer = bodyRead.buffer
            val bytesRead = bodyRead.bytesRead
            run {
                val contentType = response.header("Content-Type").orEmpty()
                val ok = response.code in 200..299 &&
                    bytesRead > 0 &&
                    (contentType.startsWith("image/", ignoreCase = true) || looksLikeImage(buffer, bytesRead))
                ComicImageProbeResult(
                    ok = ok,
                    statusCode = response.code,
                    contentType = contentType,
                    bytesRead = bytesRead,
                    finalUrl = response.request.url.toString(),
                    error = when {
                        ok -> ""
                        bodyRead.error.isNotBlank() -> bodyRead.error
                        else -> "http_${response.code}:${contentType.ifBlank { "unknown_type" }}"
                    }
                )
            }
        }.getOrElse { throwable ->
            val cancelled = throwable.isCancellation()
            ComicImageProbeResult(
                ok = false,
                statusCode = 0,
                contentType = "",
                bytesRead = 0,
                finalUrl = request.url,
                error = if (cancelled) {
                    "probe_cancelled:${throwable.javaClass.simpleName}"
                } else {
                    "${throwable.javaClass.simpleName}:${throwable.message.orEmpty().take(80)}"
                },
                cancelled = cancelled
            )
        }
    }

    private fun readProbeBytes(body: ResponseBody?): ProbeBodyRead {
        return readProbeBytes(body?.byteStream())
    }

    internal fun readProbeBytes(stream: InputStream?): ProbeBodyRead {
        if (stream == null) return ProbeBodyRead(ByteArray(PROBE_BYTES), 0, "")
        val buffer = ByteArray(PROBE_BYTES)
        var total = 0
        while (total < buffer.size) {
            val read = runCatching {
                stream.read(buffer, total, buffer.size - total)
            }.getOrElse { throwable ->
                val cancelled = throwable.isCancellation()
                return ProbeBodyRead(
                    buffer = buffer,
                    bytesRead = total,
                    error = if (cancelled) {
                        "probe_cancelled:${throwable.javaClass.simpleName}"
                    } else {
                        "${throwable.javaClass.simpleName}:${throwable.message.orEmpty().take(80)}"
                    },
                    cancelled = cancelled
                )
            }
            if (read <= 0) break
            total += read
        }
        return ProbeBodyRead(buffer, total, "")
    }

    private fun ComicImageProbeResult.retryableTimeout(): Boolean {
        return error.contains("timeout", ignoreCase = true)
    }

    private fun ComicImageProbeResult.shouldRetryWithoutRange(): Boolean {
        if (ok || cancelled) return false
        return error.contains("StreamResetException", ignoreCase = true) ||
            error.contains("stream was reset", ignoreCase = true) ||
            error.contains("CANCEL", ignoreCase = true)
    }

    private fun Throwable.isCancellation(): Boolean {
        return generateSequence(this) { it.cause }.any { cause ->
            cause is InterruptedException || cause is InterruptedIOException
        } || Thread.currentThread().isInterrupted
    }

    private fun playbackHeaders(request: MediaRequest): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        val headers = request.headers.toMutableMap()
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = DEFAULT_USER_AGENT
        }
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            headers["Referer"] = refererFor(request.url)
        }
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) builder[name] = value
        }
        return builder.build()
    }

    private fun refererFor(url: String): String {
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: return url
            val host = uri.host ?: return url
            "$scheme://$host/"
        }.getOrDefault(url)
    }

    private fun looksLikeImage(buffer: ByteArray, bytesRead: Int): Boolean {
        if (bytesRead < 4) return false
        val first = buffer[0].toInt() and 0xff
        val second = buffer[1].toInt() and 0xff
        if (first == 0xff && second == 0xd8) return true
        if (
            first == 0x89 &&
            buffer[1] == 'P'.code.toByte() &&
            buffer[2] == 'N'.code.toByte() &&
            buffer[3] == 'G'.code.toByte()
        ) return true
        if (
            buffer[0] == 'G'.code.toByte() &&
            buffer[1] == 'I'.code.toByte() &&
            buffer[2] == 'F'.code.toByte()
        ) return true
        if (
            bytesRead >= 12 &&
            buffer[0] == 'R'.code.toByte() &&
            buffer[1] == 'I'.code.toByte() &&
            buffer[2] == 'F'.code.toByte() &&
            buffer[3] == 'F'.code.toByte() &&
            buffer[8] == 'W'.code.toByte() &&
            buffer[9] == 'E'.code.toByte() &&
            buffer[10] == 'B'.code.toByte() &&
            buffer[11] == 'P'.code.toByte()
        ) return true
        if (
            bytesRead >= 12 &&
            buffer[4] == 'f'.code.toByte() &&
            buffer[5] == 't'.code.toByte() &&
            buffer[6] == 'y'.code.toByte() &&
            buffer[7] == 'p'.code.toByte()
        ) {
            val brand = String(buffer, 8, 4).lowercase(Locale.ROOT)
            return brand.contains("avif") || brand.contains("avis")
        }
        return false
    }

    private const val CONNECT_TIMEOUT_MS = 2_000L
    private const val READ_TIMEOUT_MS = 4_000L
    private const val CALL_TIMEOUT_MS = 5_000L
    private const val PROBE_ATTEMPTS = 2
    private const val PROBE_BYTES = 2_048
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"
}

internal data class ProbeBodyRead(
    val buffer: ByteArray,
    val bytesRead: Int,
    val error: String,
    val cancelled: Boolean = false
)

internal data class ComicImageProbeResult(
    val ok: Boolean,
    val statusCode: Int,
    val contentType: String,
    val bytesRead: Int,
    val finalUrl: String = "",
    val error: String = "",
    val cancelled: Boolean = false
)
