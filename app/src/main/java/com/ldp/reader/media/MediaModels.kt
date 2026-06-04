package com.ldp.reader.media

import com.ldp.reader.media.MediaSourceType

enum class ReaderMediaKind(
    val sourceType: Int,
    val displayName: String,
    val searchHint: String,
    val seedKey: String
) {
    NOVEL(MediaSourceType.TEXT, "小说", "搜索小说", "novel"),
    COMIC(MediaSourceType.COMIC, "漫画", "搜索漫画", "comic"),
    AUDIO(MediaSourceType.AUDIO, "听书", "搜索听书", "audio");

    companion object {
        fun fromSeedKey(value: String): ReaderMediaKind? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.seedKey == normalized }
        }
    }
}

data class MediaSearchBook(
    val routeId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val intro: String,
    val latest: String,
    val sourceName: String,
    val sourceCount: Int = 1
)

data class MediaBookDetail(
    val routeId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val intro: String,
    val kind: String,
    val latest: String,
    val sourceName: String
)

data class MediaChapterItem(
    val routeId: String,
    val title: String,
    val index: Int
)
