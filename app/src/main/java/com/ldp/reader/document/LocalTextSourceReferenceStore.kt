package com.ldp.reader.document

import com.tencent.mmkv.MMKV

internal interface LocalTextSourceReferenceStorage {
    fun read(bookId: String): String?

    fun write(bookId: String, sourceUri: String)

    fun remove(bookId: String)
}

internal class LocalTextSourceReferenceStore(
    private val storage: LocalTextSourceReferenceStorage
) {
    fun save(bookId: String?, sourceUri: String?) {
        if (bookId.isNullOrBlank() || sourceUri.isNullOrBlank()) return
        storage.write(bookId, sourceUri)
    }

    fun sourceFor(bookId: String?): String? {
        if (bookId.isNullOrBlank()) return null
        return storage.read(bookId)?.takeIf { it.isNotBlank() }
    }

    fun clear(bookId: String?) {
        if (bookId.isNullOrBlank()) return
        storage.remove(bookId)
    }
}

internal object LocalTextSourceReferencePersistence {
    private const val STORE_NAME = "reader_local_text_sources"
    private const val KEY_PREFIX = "source_"

    val store: LocalTextSourceReferenceStore by lazy {
        LocalTextSourceReferenceStore(
            object : LocalTextSourceReferenceStorage {
                override fun read(bookId: String): String? {
                    return storage().decodeString(KEY_PREFIX + bookId)
                }

                override fun write(bookId: String, sourceUri: String) {
                    storage().encode(KEY_PREFIX + bookId, sourceUri)
                }

                override fun remove(bookId: String) {
                    storage().removeValueForKey(KEY_PREFIX + bookId)
                }
            }
        )
    }

    private fun storage(): MMKV = MMKV.mmkvWithID(STORE_NAME)
}
