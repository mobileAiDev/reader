package com.ldp.reader.utils

import java.util.Locale

object BookCoverUrl {
    fun clean(value: String?): String {
        return value?.trim().orEmpty()
    }

    fun best(primary: String?, fallback: String?): String {
        val primaryUrl = clean(primary)
        if (isUsable(primaryUrl)) return primaryUrl
        val fallbackUrl = clean(fallback)
        if (isUsable(fallbackUrl)) return fallbackUrl
        return ""
    }

    fun bestLikelyImage(primary: String?, fallback: String?): String {
        val primaryUrl = clean(primary)
        if (isLikelyImage(primaryUrl)) return primaryUrl
        val fallbackUrl = clean(fallback)
        if (isLikelyImage(fallbackUrl)) return fallbackUrl
        return best(primary, fallback)
    }

    fun isUsable(value: String?): Boolean {
        val url = clean(value)
        if (url.isBlank()) return false
        val normalized = url.lowercase(Locale.ROOT)
        return NO_COVER_MARKERS.none { marker -> normalized.contains(marker) }
    }

    fun isLikelyImage(value: String?): Boolean {
        val url = clean(value)
        if (!isUsable(url)) return false
        val normalized = url.lowercase(Locale.ROOT)
        if (IMAGE_EXTENSIONS.any { normalized.substringBefore('?').substringBefore('#').endsWith(it) }) {
            return true
        }
        if (IMAGE_PATH_MARKERS.any { marker -> normalized.contains(marker) }) {
            return true
        }
        return !normalized.endsWith("/") && IMAGE_NAME_MARKERS.any { marker -> normalized.contains(marker) }
    }

    private val NO_COVER_MARKERS = listOf(
        "nocover",
        "no-cover",
        "no_cover",
        "noimg",
        "no-img",
        "no_img",
        "noimage",
        "no-image",
        "no_image",
        "nopic",
        "no-pic",
        "no_pic",
        "defaultcover",
        "default-cover",
        "default_cover",
        "cover-default",
        "cover_default",
        "xbiquge.la/files/article/image/",
        "lazy.gif",
        "loading.gif",
        "loader.gif",
        "/lazy.",
        "/loading.",
        "/loader."
    )

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
    private val IMAGE_PATH_MARKERS = listOf(
        "/image/",
        "/images/",
        "/img/",
        "bookimage",
        "bookimages",
        "coverimg",
        "coverimage"
    )
    private val IMAGE_NAME_MARKERS = listOf("cover", "poster")
}
