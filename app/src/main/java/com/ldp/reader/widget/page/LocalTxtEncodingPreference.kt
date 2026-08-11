package com.ldp.reader.widget.page

import com.ldp.reader.utils.Charset as ReaderCharset
import com.tencent.mmkv.MMKV

internal enum class LocalTxtEncodingPreference(val persistedValue: String) {
    AUTO("auto"),
    UTF8("utf8"),
    GBK("gbk"),
    UTF16LE("utf16le"),
    UTF16BE("utf16be");

    companion object {
        fun fromPersistedValue(value: String?): LocalTxtEncodingPreference {
            return entries.firstOrNull { it.persistedValue == value } ?: AUTO
        }
    }
}

internal interface LocalTxtEncodingPreferenceStorage {
    fun read(key: String): String?

    fun write(key: String, value: String)

    fun remove(key: String)
}

internal class LocalTxtEncodingPreferenceStore(
    private val storage: LocalTxtEncodingPreferenceStorage
) {
    fun load(bookId: String?): LocalTxtEncodingPreference {
        if (bookId.isNullOrBlank()) return LocalTxtEncodingPreference.AUTO
        return LocalTxtEncodingPreference.fromPersistedValue(storage.read(bookId))
    }

    fun save(bookId: String?, preference: LocalTxtEncodingPreference) {
        if (bookId.isNullOrBlank()) return
        if (preference == LocalTxtEncodingPreference.AUTO) {
            storage.remove(bookId)
        } else {
            storage.write(bookId, preference.persistedValue)
        }
    }

    fun clear(bookId: String?) {
        if (bookId.isNullOrBlank()) return
        storage.remove(bookId)
    }
}

internal object LocalTxtEncodingPreferences {
    private const val STORE_NAME = "local_txt_encoding_preferences"

    fun load(bookId: String?): LocalTxtEncodingPreference = store().load(bookId)

    fun save(bookId: String?, preference: LocalTxtEncodingPreference) {
        store().save(bookId, preference)
    }

    fun clear(bookId: String?) {
        store().clear(bookId)
    }

    private fun store(): LocalTxtEncodingPreferenceStore {
        return LocalTxtEncodingPreferenceStore(MmkvLocalTxtEncodingPreferenceStorage)
    }

    private object MmkvLocalTxtEncodingPreferenceStorage : LocalTxtEncodingPreferenceStorage {
        private fun mmkv(): MMKV = MMKV.mmkvWithID(STORE_NAME)

        override fun read(key: String): String? = mmkv().decodeString(key, null)

        override fun write(key: String, value: String) {
            mmkv().encode(key, value)
        }

        override fun remove(key: String) {
            mmkv().removeValueForKey(key)
        }
    }
}

internal object LocalTxtCharsetResolver {
    fun resolve(
        snapshot: LocalTxtFileSnapshot,
        preference: LocalTxtEncodingPreference,
        cancellationCheck: () -> Unit = {}
    ): ReaderCharset {
        cancellationCheck()
        val charset = when (preference) {
            LocalTxtEncodingPreference.AUTO -> {
                LocalTxtSnapshotCharsetDetector.detect(snapshot, cancellationCheck)
            }

            LocalTxtEncodingPreference.UTF8 -> ReaderCharset.UTF8
            LocalTxtEncodingPreference.GBK -> ReaderCharset.GBK
            LocalTxtEncodingPreference.UTF16LE -> ReaderCharset.UTF16LE
            LocalTxtEncodingPreference.UTF16BE -> ReaderCharset.UTF16BE
        }
        cancellationCheck()
        return charset
    }
}
