package com.ldp.reader.ui.activity

import com.ldp.reader.media.ReaderMediaKind

internal object MediaCatalogLabels {
    fun title(kind: ReaderMediaKind?): String = when (kind) {
        ReaderMediaKind.COMIC -> "漫画目录"
        ReaderMediaKind.AUDIO -> "剧集目录"
        ReaderMediaKind.NOVEL -> "书籍目录"
        null -> "目录"
    }

    fun unit(kind: ReaderMediaKind?): String = when (kind) {
        ReaderMediaKind.AUDIO -> "集"
        ReaderMediaKind.COMIC -> "话"
        ReaderMediaKind.NOVEL, null -> "章"
    }
}
