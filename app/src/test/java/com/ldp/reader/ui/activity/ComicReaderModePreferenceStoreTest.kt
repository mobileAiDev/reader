package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicReaderModePreferenceStoreTest {
    @Test
    fun `online reader modes survive a new activity instance`() {
        val storage = FakeComicReaderModeStorage()
        val writer = OnlineComicReaderModeStore(storage)
        writer.save(
            ComicReaderModeState(
                horizontalPages = true,
                night = true,
                eyeProtection = true,
                forcedLandscape = true,
                brightnessPercent = 37,
                brightnessOverridden = true
            )
        )

        val restored = OnlineComicReaderModeStore(storage).load(defaultLandscape = false)

        assertTrue(restored.horizontalPages)
        assertTrue(restored.night)
        assertTrue(restored.eyeProtection)
        assertTrue(restored.forcedLandscape)
        assertEquals(37, restored.brightnessPercent)
        assertTrue(restored.brightnessOverridden)
    }

    @Test
    fun `invalid stored brightness cannot force an invalid window brightness`() {
        val storage = FakeComicReaderModeStorage().apply {
            writeInt("online.brightness_percent", 1000)
            writeBoolean("online.brightness_overridden", true)
        }

        val restored = OnlineComicReaderModeStore(storage).load(defaultLandscape = false)

        assertEquals(DEFAULT_COMIC_BRIGHTNESS_PERCENT, restored.brightnessPercent)
        assertFalse(restored.brightnessOverridden)
    }

    @Test
    fun `local archive direction is isolated per document`() {
        val storage = FakeComicReaderModeStorage()
        val store = LocalComicReaderDirectionStore(storage)

        store.save("local_comic|content://books/a.cbz", horizontal = true)
        store.save("local_comic|content://books/b.cbz", horizontal = false)

        assertTrue(store.load("local_comic|content://books/a.cbz"))
        assertFalse(store.load("local_comic|content://books/b.cbz"))
        assertFalse(store.load("local_comic|content://books/unknown.cbz"))
    }

    @Test
    fun `blank local document identity is never shared`() {
        val storage = FakeComicReaderModeStorage()
        val store = LocalComicReaderDirectionStore(storage)

        store.save("", horizontal = true)

        assertFalse(store.load(""))
        assertTrue(storage.booleans.isEmpty())
    }

    private class FakeComicReaderModeStorage : ComicReaderModeStorage {
        val booleans = mutableMapOf<String, Boolean>()
        private val ints = mutableMapOf<String, Int>()

        override fun readBoolean(key: String): Boolean? = booleans[key]

        override fun readInt(key: String): Int? = ints[key]

        override fun writeBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }

        override fun writeInt(key: String, value: Int) {
            ints[key] = value
        }
    }
}
