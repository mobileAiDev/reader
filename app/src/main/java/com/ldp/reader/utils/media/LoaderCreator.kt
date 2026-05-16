package com.ldp.reader.utils.media

import android.content.Context
import android.os.Bundle
import androidx.loader.content.CursorLoader

object LoaderCreator {
    const val ALL_BOOK_FILE: Int = 1

    @JvmStatic
    fun create(context: Context, id: Int, bundle: Bundle?): CursorLoader {
        val loader = when (id) {
            ALL_BOOK_FILE -> LocalFileLoader(context)
            else -> null
        }
        if (loader != null) {
            return loader
        }
        throw IllegalArgumentException("The id of Loader is invalid!")
    }
}
