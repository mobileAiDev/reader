package com.ldp.reader.media

import com.google.gson.JsonParser
import com.ldp.reader.media.legado.MediaHttpFetcher
import com.ldp.reader.media.legado.MediaHttpRequest
import java.net.URI
import java.net.URLEncoder

object AudioPlaybackUrlResolver {
    private val dataCode = Regex("""data-code=["']([^"']+)["']""")

    fun resolve(
        rawContent: String,
        pageUrl: String,
        fetcher: MediaHttpFetcher,
        headers: Map<String, String>,
        timestampSeconds: Long = System.currentTimeMillis() / 1_000L
    ): String? {
        return resolveRequest(rawContent, pageUrl, fetcher, headers, timestampSeconds)?.url
    }

    fun resolveRequest(
        rawContent: String,
        pageUrl: String,
        fetcher: MediaHttpFetcher,
        headers: Map<String, String>,
        timestampSeconds: Long = System.currentTimeMillis() / 1_000L
    ): MediaRequest? {
        AudioUrlExtractor.extractRequest(rawContent, pageUrl, headers)
            ?.withAudioPlaybackHeaders(pageUrl)
            ?.let { return it }
        return resolveSignedDataCodePlayer(rawContent, pageUrl, fetcher, headers, timestampSeconds)
    }

    fun resolveFromPage(
        pageUrl: String,
        fetcher: MediaHttpFetcher,
        headers: Map<String, String>,
        timestampSeconds: Long = System.currentTimeMillis() / 1_000L
    ): String? {
        return resolveRequestFromPage(pageUrl, fetcher, headers, timestampSeconds)?.url
    }

    fun resolveRequestFromPage(
        pageUrl: String,
        fetcher: MediaHttpFetcher,
        headers: Map<String, String>,
        timestampSeconds: Long = System.currentTimeMillis() / 1_000L
    ): MediaRequest? {
        if (pageUrl.isBlank()) return null
        val response = runCatching {
            fetcher.fetch(MediaHttpRequest(pageUrl, headers = headers))
        }.getOrNull() ?: return null
        return resolveRequest(response.body, response.finalUrl, fetcher, headers, timestampSeconds)
    }

    private fun resolveSignedDataCodePlayer(
        html: String,
        pageUrl: String,
        fetcher: MediaHttpFetcher,
        headers: Map<String, String>,
        timestampSeconds: Long
    ): MediaRequest? {
        val code = dataCode.find(html)?.groupValues?.getOrNull(1)?.ifBlank { null } ?: return null
        val timestamp = timestampSeconds.toString()
        val endpoint = URI(pageUrl).resolve("/e/extend/url.php").toString()
        val formBody = "code=${urlEncode(code)}&timestamp=$timestamp"
        val requestHeaders = headers + mapOf(
            "Referer" to pageUrl,
            "Origin" to URI(pageUrl).let { "${it.scheme}://${it.host}" },
            "X-Requested-With" to "XMLHttpRequest"
        )
        val signatureResponse = runCatching {
            fetcher.fetch(
                MediaHttpRequest(
                    url = endpoint,
                    method = "POST",
                    headers = requestHeaders,
                    body = formBody
                )
            )
        }.getOrNull() ?: return null
        val signatureJson = runCatching {
            JsonParser.parseString(signatureResponse.body).asJsonObject
        }.getOrNull() ?: return null
        val signature = signatureJson.string("signature") ?: return null
        val uuid = signatureJson.string("uuid") ?: return null
        val playbackResponse = runCatching {
            fetcher.fetch(
                MediaHttpRequest(
                    url = "$endpoint?code=${urlEncode(code)}&timestamp=$timestamp&signature=${urlEncode(signature)}&uuid=${urlEncode(uuid)}",
                    headers = requestHeaders
                )
            )
        }.getOrNull() ?: return null
        return AudioUrlExtractor.extractRequest(playbackResponse.body, playbackResponse.finalUrl, requestHeaders)
            ?.withAudioPlaybackHeaders(playbackResponse.finalUrl)
    }

    private fun MediaRequest.withAudioPlaybackHeaders(pageUrl: String): MediaRequest {
        return copy(headers = MediaPlaybackHeaders.audio(headers, pageUrl))
    }

    private fun com.google.gson.JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        return value.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }
}
