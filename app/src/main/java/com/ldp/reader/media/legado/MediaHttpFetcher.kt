package com.ldp.reader.media.legado

interface MediaHttpFetcher {
    fun fetch(request: MediaHttpRequest): MediaHttpResponse
}

data class MediaHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val charset: String? = null
)

data class MediaHttpResponse(
    val finalUrl: String,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val statusCode: Int = 200
)
