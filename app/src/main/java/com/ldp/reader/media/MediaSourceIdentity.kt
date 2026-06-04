package com.ldp.reader.media

import com.ldp.reader.media.MediaSourceDefinition
import java.util.Locale

internal object MediaSourceIdentity {
    fun sourceKey(source: MediaSourceDefinition): String {
        val searchHost = hostOf(source.searchUrl.orEmpty())
        val sourceHost = hostOf(source.sourceUrl)
        return (searchHost ?: sourceHost ?: source.sourceUrl.ifBlank { source.sourceName })
            .normalizeKey()
    }

    private fun hostOf(value: String): String? {
        val url = value.substringBefore("##").trim()
        return Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.removePrefix("www.")
            ?.removePrefix("m.")
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.normalizeKey(): String {
        return trim()
            .trimEnd('/')
            .lowercase(Locale.ROOT)
    }
}
