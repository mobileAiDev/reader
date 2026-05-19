package com.ldp.reader.sourceengine.legado

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

class JdkHttpFetcher(
    private val connectTimeoutMillis: Int = 10000,
    private val readTimeoutMillis: Int = 15000
) : HttpFetcher {
    override fun fetch(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = request.method.uppercase(Locale.ROOT)
        connection.setRequestProperty(
            "User-Agent",
            request.headers["User-Agent"]
                ?: request.headers["user-agent"]
                ?: DEFAULT_USER_AGENT
        )
        connection.setRequestProperty("Accept", "*/*")
        request.headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                connection.setRequestProperty(name, value)
            }
        }
        request.body?.let { body ->
            connection.doOutput = true
            if (connection.getRequestProperty("Content-Type").isNullOrBlank()) {
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=${request.charset ?: "UTF-8"}"
                )
            }
            val bytes = body.toByteArray(Charset.forName(request.charset ?: "UTF-8"))
            connection.outputStream.use { it.write(bytes) }
        }

        val status = connection.responseCode
        val stream = if (status in 200..399) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            output.toByteArray()
        }
        if (status !in 200..399) {
            throw IllegalStateException("HTTP $status ${connection.url}")
        }
        val charset = request.charset
            ?: connection.contentType?.let { contentTypeCharset(it) }
            ?: htmlCharset(bytes)
            ?: "UTF-8"
        return HttpResponse(connection.url.toString(), String(bytes, Charset.forName(charset)))
    }

    private fun contentTypeCharset(contentType: String): String? {
        return contentType
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
            ?.ifBlank { null }
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

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"
    }
}
