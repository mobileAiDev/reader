package com.ldp.reader.document

import com.ldp.reader.utils.SharedPreUtils
import java.security.MessageDigest

internal object LocalDocumentPageProgressPersistence {
    private const val KEY_PREFIX = "local_document_page_progress_v1_"

    val store: LocalDocumentPageProgressStore by lazy {
        LocalDocumentPageProgressStore(
            object : LocalDocumentPageProgressStorage {
                override fun read(key: String): String? {
                    return SharedPreUtils.getInstance()
                        .getString(KEY_PREFIX + stableKey(key))
                        .takeIf { it.isNotBlank() }
                }

                override fun write(key: String, value: String) {
                    SharedPreUtils.getInstance().putString(KEY_PREFIX + stableKey(key), value)
                }
            }
        )
    }

    private fun stableKey(documentId: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(documentId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
