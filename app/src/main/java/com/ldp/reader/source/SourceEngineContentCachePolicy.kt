package com.ldp.reader.source

import android.util.Log
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.utils.BookManager
import com.ldp.reader.utils.FileUtils
import java.io.File

object SourceEngineContentCachePolicy {
    private const val CACHE_VERSION = "source-engine-content-v3"
    private const val MARKER_FILE = ".source_engine_content_cache_version"
    private const val TAG = "SourceEngineCache"

    fun ensureFresh(book: CollBookBean?) {
        if (book == null || !isSourceEngineBook(book)) {
            return
        }

        val folder = File(BookManager.cacheFolderPath(book.get_id()))
        val marker = File(folder, MARKER_FILE)
        if (marker.exists() && marker.readText() == CACHE_VERSION) {
            return
        }

        if (folder.exists()) {
            FileUtils.deleteFile(folder.absolutePath)
        }
        folder.mkdirs()
        marker.writeText(CACHE_VERSION)
        BookManager.getInstance().clear()
        Log.i(TAG, "sourceEngineContentCacheInvalidated book=${book.title} version=$CACHE_VERSION")
    }

    private fun isSourceEngineBook(book: CollBookBean): Boolean {
        return SourceEngineBookRoute.isBookId(book.get_id()) ||
            SourceEngineBookRoute.isShelfBookId(book.get_id()) ||
            SourceEngineBookRoute.isBookId(book.bookIdInBiquge)
    }
}
