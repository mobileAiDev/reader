package com.ldp.reader.media

import android.content.Context
import com.tencent.mmkv.MMKV

object ComicReadingProgressStore {
    private const val STORE_NAME = "reader_comic_progress"
    private const val PAGE_SUFFIX = ":page"

    fun save(context: Context, chapterRouteId: String, pageIndex: Int) {
        if (chapterRouteId.isBlank()) return
        store(context).encode(chapterRouteId + PAGE_SUFFIX, pageIndex.coerceAtLeast(0))
    }

    fun page(context: Context, chapterRouteId: String): Int {
        if (chapterRouteId.isBlank()) return -1
        return store(context).decodeInt(chapterRouteId + PAGE_SUFFIX, -1)
    }

    private fun store(context: Context): MMKV {
        return MMKV.mmkvWithID(STORE_NAME)
    }
}
