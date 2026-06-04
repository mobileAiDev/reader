package com.ldp.reader.media

data class MediaRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)
