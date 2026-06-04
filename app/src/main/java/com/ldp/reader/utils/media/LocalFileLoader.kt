package com.ldp.reader.utils.media

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.text.TextUtils
import androidx.loader.content.CursorLoader
import java.io.File
import java.sql.Blob
import java.util.ArrayList

class LocalFileLoader(context: Context) : CursorLoader(context) {
    init {
        initLoader()
    }

    private fun initLoader() {
        uri = FILE_URI
        projection = FILE_PROJECTION
        selection = SELECTION
        selectionArgs = SEARCH_TYPES
        sortOrder = SORT_ORDER
    }

    fun parseData(cursor: Cursor?, resultCallback: MediaStoreHelper.MediaResultCallback?) {
        val files: MutableList<File> = ArrayList()
        if (cursor == null) {
            resultCallback!!.onResultCallback(files)
            return
        }
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) {
            val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA))
            if (TextUtils.isEmpty(path)) {
                continue
            } else {
                val file = File(path)
                if (file.isDirectory || !file.exists()) {
                    continue
                } else {
                    files.add(file)
                }
            }
        }
        if (resultCallback != null) {
            resultCallback.onResultCallback(files)
        }
    }

    protected fun getValueFromCursor(cursor: Cursor, columnName: String?, defaultValue: Any?): Any? {
        try {
            val index = cursor.getColumnIndexOrThrow(columnName)
            val type = cursor.getType(index)
            if (type == Cursor.FIELD_TYPE_STRING) {
                val value = cursor.getString(index)
                try {
                    if (defaultValue is String) {
                        return value
                    } else if (defaultValue is Long) {
                        return value.toLong()
                    } else if (defaultValue is Int) {
                        return value.toInt()
                    } else if (defaultValue is Double) {
                        return value.toDouble()
                    } else if (defaultValue is Float) {
                        return value.toFloat()
                    }
                } catch (e: NumberFormatException) {
                    return defaultValue
                }
            }
            if (type == Cursor.FIELD_TYPE_STRING || type == Cursor.FIELD_TYPE_INTEGER) {
                if (defaultValue is Long) {
                    return cursor.getLong(index)
                } else if (defaultValue is Int) {
                    return cursor.getInt(index)
                }
            }
            if (type == Cursor.FIELD_TYPE_STRING || type == Cursor.FIELD_TYPE_INTEGER || type == Cursor.FIELD_TYPE_FLOAT) {
                if (defaultValue is Float) {
                    return cursor.getFloat(index)
                } else if (defaultValue is Double) {
                    return cursor.getDouble(index)
                }
            }
            if (
                type == Cursor.FIELD_TYPE_STRING ||
                type == Cursor.FIELD_TYPE_INTEGER ||
                type == Cursor.FIELD_TYPE_FLOAT ||
                type == Cursor.FIELD_TYPE_BLOB
            ) {
                if (defaultValue is Blob) {
                    return cursor.getBlob(index)
                }
            }
            return defaultValue
        } catch (e: IllegalArgumentException) {
            return defaultValue
        }
    }

    companion object {
        private val FILE_URI = Uri.parse("content://media/external/file")
        private val SEARCH_TYPES = arrayOf("%.txt", "%.epub", "%.pdf", "%.cbz", "%.zip")
        private val SELECTION = SEARCH_TYPES.joinToString(" OR ") {
            MediaStore.Files.FileColumns.DATA + " like ?"
        }
        private val SORT_ORDER = MediaStore.Files.FileColumns.DISPLAY_NAME + " DESC"
        private val FILE_PROJECTION = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
    }
}
