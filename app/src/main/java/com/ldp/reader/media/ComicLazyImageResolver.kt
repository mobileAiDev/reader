package com.ldp.reader.media

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ldp.reader.media.legado.MediaHttpFetcher
import com.ldp.reader.media.legado.MediaHttpRequest
import java.net.URI
import java.net.URLEncoder

internal object ComicLazyImageResolver {
    private const val DEFAULT_BATCH_SIZE = 50
    private const val MAX_BATCHES = 20
    private val readObject = Regex("""(?s)\bread\s*=\s*\{(.*?)\}\s*</script>""")
    private val stringField = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:\s*['"]([^'"]*)['"]""")
    private val numberField = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(\d+)""")

    fun resolveRequests(
        pageHtml: String,
        pageUrl: String,
        fetcher: MediaHttpFetcher,
        defaultHeaders: Map<String, String>,
        maxPages: Int? = null
    ): List<MediaRequest> {
        val meta = parseReaderPicsMeta(pageHtml) ?: return emptyList()
        val targetPages = maxPages?.coerceAtLeast(1)?.let { minOf(it, meta.picCount) } ?: meta.picCount
        if (targetPages <= 0) return emptyList()
        val endpoint = URI(pageUrl).resolve("/api/comic/read/pics").toString()
        val requestHeaders = defaultHeaders + ("Referer" to pageUrl)
        val pages = ArrayList<MediaRequest>()
        var offset = 0
        var batches = 0
        while (offset < targetPages && batches < MAX_BATCHES) {
            val limit = minOf(DEFAULT_BATCH_SIZE, targetPages - offset)
            val response = runCatching {
                fetcher.fetch(
                    MediaHttpRequest(
                        url = endpoint,
                        method = "POST",
                        headers = requestHeaders,
                        body = formBody(
                            "id" to meta.chapterId,
                            "aid" to meta.bookId,
                            "offset" to offset.toString(),
                            "limit" to limit.toString()
                        )
                    )
                )
            }.getOrNull() ?: break
            val batch = parsePicBatch(response.body, response.finalUrl, requestHeaders)
            if (batch.isEmpty()) break
            pages.addAll(batch)
            offset += batch.size
            batches += 1
            if (pages.size >= targetPages) break
        }
        return pages.distinctBy { it.url }.take(targetPages)
    }

    private fun parseReaderPicsMeta(pageHtml: String): ReaderPicsMeta? {
        if (!pageHtml.contains("reader-pic-slot", ignoreCase = true)) return null
        if (!pageHtml.contains("picCount", ignoreCase = true)) return null
        val body = readObject.find(pageHtml)?.groupValues?.getOrNull(1) ?: return null
        val strings = stringField.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        val numbers = numberField.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        val chapterId = strings["apiCid"] ?: strings["cid"] ?: numbers["apiCid"] ?: numbers["cid"] ?: return null
        val bookId = strings["aid"] ?: numbers["aid"] ?: return null
        val picCount = (numbers["picCount"] ?: strings["picCount"])?.toIntOrNull() ?: return null
        if (chapterId.isBlank() || bookId.isBlank() || picCount <= 0) return null
        return ReaderPicsMeta(bookId = bookId, chapterId = chapterId, picCount = picCount)
    }

    private fun parsePicBatch(
        body: String,
        baseUrl: String,
        defaultHeaders: Map<String, String>
    ): List<MediaRequest> {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        if (root.int("code") != 1) return emptyList()
        val picArray = root.getAsJsonObject("data")?.get("pic")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return picArray.toRequests(baseUrl, defaultHeaders)
    }

    private fun JsonArray.toRequests(
        baseUrl: String,
        defaultHeaders: Map<String, String>
    ): List<MediaRequest> {
        return asSequence()
            .mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject?.string("pic") }
            .mapNotNull { MediaRequestParser.parse(it, baseUrl, defaultHeaders) }
            .toList()
    }

    private fun JsonObject.string(name: String): String? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.int(name: String): Int? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.asInt
    }

    private fun formBody(vararg values: Pair<String, String>): String {
        return values.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private data class ReaderPicsMeta(
        val bookId: String,
        val chapterId: String,
        val picCount: Int
    )
}
