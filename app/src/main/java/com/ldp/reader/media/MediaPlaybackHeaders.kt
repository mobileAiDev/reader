package com.ldp.reader.media

object MediaPlaybackHeaders {
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"

    fun audio(headers: Map<String, String>, refererUrl: String = ""): Map<String, String> {
        val output = linkedMapOf<String, String>()
        headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .forEach { output[it.key] = it.value }
        if (!output.containsHeader("User-Agent") || output.headerValue("User-Agent").isBlockedForPlayback()) {
            output.removeHeader("User-Agent")
            output["User-Agent"] = MOBILE_USER_AGENT
        }
        if (!output.containsHeader("Referer") && refererUrl.startsWith("http", ignoreCase = true)) {
            output["Referer"] = refererUrl
        }
        return output
    }

    fun userAgent(headers: Map<String, String>): String {
        return headers.headerValue("User-Agent") ?: MOBILE_USER_AGENT
    }

    fun defaultRequestProperties(headers: Map<String, String>): Map<String, String> {
        return headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
    }

    private fun String?.isBlockedForPlayback(): Boolean {
        if (isNullOrBlank()) return true
        val lower = lowercase()
        return lower == "okhttp" ||
            lower.startsWith("okhttp/") ||
            lower.startsWith("dart/") ||
            lower == "mobile"
    }

    private fun MutableMap<String, String>.removeHeader(name: String) {
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { remove(it) }
    }

    private fun Map<String, String>.containsHeader(name: String): Boolean {
        return keys.any { it.equals(name, ignoreCase = true) }
    }

    private fun Map<String, String>.headerValue(name: String): String? {
        return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
    }
}
