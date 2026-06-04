package com.ldp.reader.media.engine

import com.ldp.reader.media.legado.MediaHttpFetcher
import com.ldp.reader.media.legado.MediaHttpRequest
import com.ldp.reader.media.legado.MediaHttpResponse
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class MediaOkHttpFetcher(
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int
) : MediaHttpFetcher {
    private val dispatcher = Dispatcher().apply {
        maxRequests = MAX_REQUESTS
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }
    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((connectTimeoutMillis + readTimeoutMillis).toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
        val builder = Request.Builder()
            .url(request.url)
            .header(
                "User-Agent",
                request.headers["User-Agent"]
                    ?: request.headers["user-agent"]
                    ?: DEFAULT_USER_AGENT
            )
            .header("Accept", "*/*")
        request.headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                builder.header(name, value)
            }
        }

        val method = request.method.uppercase(Locale.ROOT)
        val body = request.body?.toRequestBody(
            "application/x-www-form-urlencoded; charset=${request.charset ?: "UTF-8"}".toMediaType()
        )
        builder.method(method, body)

        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body ?: error("empty response body ${request.url}")
            val bytes = responseBody.bytes()
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ${response.request.url}")
            }
            val charset = request.charset
                ?: responseBody.contentType()?.charset()?.name()
                ?: htmlCharset(bytes)
                ?: "UTF-8"
            return MediaHttpResponse(
                finalUrl = response.request.url.toString(),
                body = String(bytes, Charset.forName(charset)),
                headers = response.headerMap(),
                statusCode = response.code
            )
        }
    }

    private fun okhttp3.Response.headerMap(): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val chain = ArrayDeque<okhttp3.Response>()
        var cursor: okhttp3.Response? = this
        while (cursor != null) {
            chain.addFirst(cursor)
            cursor = cursor.priorResponse
        }
        chain.forEach { response ->
            response.headers.forEach { pair ->
                val name = pair.first
                val value = pair.second
                values[name] = value
                values[name.lowercase(Locale.ROOT)] = value
            }
        }
        return values
    }

    private fun htmlCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return Regex("""(?i)<meta[^>]+charset=["']?\s*([A-Za-z0-9_\-]+)""")
            .find(head)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
    }

    private companion object {
        private const val MAX_REQUESTS = 64
        private const val MAX_REQUESTS_PER_HOST = 16
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"
    }
}
