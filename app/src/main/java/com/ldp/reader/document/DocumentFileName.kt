package com.ldp.reader.document

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.CancellationException

object DocumentFileName {
    fun displayName(
        context: Context,
        uri: Uri,
        cancellationSignal: CancellationSignal? = null
    ): String {
        if (uri.scheme.equals(FILE_SCHEME, ignoreCase = true)) {
            return trustedFilePathDisplayName(uri.scheme, uri.path)
        }
        if (!uri.scheme.equals(CONTENT_SCHEME, ignoreCase = true)) {
            return ""
        }
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
                cancellationSignal
            )
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) return cursor.getString(index).orEmpty()
                    }
                }
        } catch (error: OperationCanceledException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            // The reader screens will report the actual open failure; keep routing deterministic.
        } catch (_: RuntimeException) {
            // Broken document providers must not crash the exported VIEW entry point.
        }
        return ""
    }

    fun extension(
        context: Context,
        uri: Uri,
        cancellationSignal: CancellationSignal? = null
    ): String {
        return displayName(context, uri, cancellationSignal).substringAfterLast('.', "").lowercase()
    }

    internal fun trustedFilePathDisplayName(scheme: String?, path: String?): String {
        if (!scheme.equals(FILE_SCHEME, ignoreCase = true) || path.isNullOrBlank()) {
            return ""
        }
        return File(path).name
    }

    private const val FILE_SCHEME = "file"
    private const val CONTENT_SCHEME = "content"
}
