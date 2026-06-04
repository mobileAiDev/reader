package com.ldp.reader.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object DocumentFileName {
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index).orEmpty()
                }
            }
        return uri.lastPathSegment.orEmpty()
    }

    fun extension(context: Context, uri: Uri): String {
        return displayName(context, uri).substringAfterLast('.', "").lowercase()
    }
}
