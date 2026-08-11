package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalTxtEncodingPreferenceStoreTest {

    @Test
    fun selectedEncodingPersistsPerBookAndAutoClearsTheOverride() {
        val storage = InMemoryEncodingPreferenceStorage()
        val firstSession = LocalTxtEncodingPreferenceStore(storage)

        assertEquals(LocalTxtEncodingPreference.AUTO, firstSession.load("book-a"))
        firstSession.save("book-a", LocalTxtEncodingPreference.GBK)

        val reopenedSession = LocalTxtEncodingPreferenceStore(storage)
        assertEquals(LocalTxtEncodingPreference.GBK, reopenedSession.load("book-a"))
        assertEquals(LocalTxtEncodingPreference.AUTO, reopenedSession.load("book-b"))

        reopenedSession.save("book-a", LocalTxtEncodingPreference.AUTO)
        assertEquals(LocalTxtEncodingPreference.AUTO, reopenedSession.load("book-a"))
        assertFalse(storage.contains("book-a"))
    }

    @Test
    fun removedBookReimportedWithTheSameStableIdStartsAtAutoWithoutTouchingOtherBooks() {
        val storage = InMemoryEncodingPreferenceStorage()
        val beforeRemoval = LocalTxtEncodingPreferenceStore(storage)
        val samePathBookId = "md5-of-stable-absolute-path"
        beforeRemoval.save(samePathBookId, LocalTxtEncodingPreference.GBK)
        beforeRemoval.save("another-book", LocalTxtEncodingPreference.UTF16LE)

        beforeRemoval.clear(samePathBookId)

        val reimportedSession = LocalTxtEncodingPreferenceStore(storage)
        assertEquals(
            LocalTxtEncodingPreference.AUTO,
            reimportedSession.load(samePathBookId)
        )
        assertEquals(
            LocalTxtEncodingPreference.UTF16LE,
            reimportedSession.load("another-book")
        )
        assertFalse(storage.contains(samePathBookId))
    }

    @Test
    fun blankOrUnrelatedLifecycleEventsPreserveTheExistingOverride() {
        val storage = InMemoryEncodingPreferenceStorage()
        val store = LocalTxtEncodingPreferenceStore(storage)
        store.save("book-kept", LocalTxtEncodingPreference.GBK)

        store.clear(null)
        store.clear("")
        store.clear("different-book")

        assertEquals(LocalTxtEncodingPreference.GBK, store.load("book-kept"))
    }

    private class InMemoryEncodingPreferenceStorage : LocalTxtEncodingPreferenceStorage {
        private val values = LinkedHashMap<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        fun contains(key: String): Boolean = values.containsKey(key)
    }
}
