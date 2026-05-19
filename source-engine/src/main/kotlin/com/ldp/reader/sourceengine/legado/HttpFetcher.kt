package com.ldp.reader.sourceengine.legado

interface HttpFetcher {
    fun fetch(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val charset: String? = null
)

data class HttpResponse(
    val finalUrl: String,
    val body: String
)
