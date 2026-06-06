package com.ldp.reader.media

import java.net.URI

object MediaPlaybackSignature {
    private val audioFileName = Regex("""(?i)[^/?#]+\.(mp3|m4a|aac|ogg|wav|flac|m3u8|mp4)$""")

    fun repeatedAudioUrlAcrossChapters(samples: List<Pair<String, String>>): Boolean {
        val routeToSignature = samples
            .mapNotNull { (routeId, url) ->
                val route = routeId.trim()
                val signature = audioUrl(url)
                if (route.isBlank() || signature.isBlank()) null else route to signature
            }
            .distinct()
        return routeToSignature
            .groupBy(keySelector = { it.second }, valueTransform = { it.first })
            .any { (_, routes) -> routes.distinct().size > 1 }
    }

    fun audioUrl(url: String): String {
        val clean = url.trim()
            .substringBefore("#")
            .trim()
        if (clean.isBlank()) return ""
        val withoutQuery = clean.substringBefore("?").trimEnd('/')
        val uri = runCatching { URI(withoutQuery) }.getOrNull()
        val fileName = uri?.path
            ?.substringAfterLast('/')
            ?.takeIf { audioFileName.matches(it) }
        if (!fileName.isNullOrBlank()) {
            val host = uri.host.orEmpty().lowercase()
            return if (host.isNotBlank()) {
                "$host/${fileName.lowercase()}"
            } else {
                fileName.lowercase()
            }
        }
        return clean
    }
}
