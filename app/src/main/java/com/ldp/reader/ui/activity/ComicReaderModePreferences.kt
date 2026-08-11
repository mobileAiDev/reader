package com.ldp.reader.ui.activity

import com.tencent.mmkv.MMKV

internal interface ComicReaderModeStorage {
    fun readBoolean(key: String): Boolean?

    fun readInt(key: String): Int?

    fun writeBoolean(key: String, value: Boolean)

    fun writeInt(key: String, value: Int)
}

internal class OnlineComicReaderModeStore(
    private val storage: ComicReaderModeStorage
) {
    fun load(defaultLandscape: Boolean): ComicReaderModeState {
        return restoredComicReaderModeState(
            horizontalPages = storage.readBoolean(KEY_HORIZONTAL) ?: false,
            night = storage.readBoolean(KEY_NIGHT) ?: false,
            eyeProtection = storage.readBoolean(KEY_EYE_PROTECTION) ?: false,
            forcedLandscape = storage.readBoolean(KEY_LANDSCAPE) ?: defaultLandscape,
            savedBrightnessPercent = storage.readInt(KEY_BRIGHTNESS_PERCENT)
                ?: DEFAULT_COMIC_BRIGHTNESS_PERCENT,
            savedBrightnessOverridden = storage.readBoolean(KEY_BRIGHTNESS_OVERRIDDEN) ?: false
        )
    }

    fun save(state: ComicReaderModeState) {
        storage.writeBoolean(KEY_HORIZONTAL, state.horizontalPages)
        storage.writeBoolean(KEY_NIGHT, state.night)
        storage.writeBoolean(KEY_EYE_PROTECTION, state.eyeProtection)
        storage.writeBoolean(KEY_LANDSCAPE, state.forcedLandscape)
        storage.writeInt(
            KEY_BRIGHTNESS_PERCENT,
            restoredComicBrightnessPercent(state.brightnessPercent)
        )
        storage.writeBoolean(
            KEY_BRIGHTNESS_OVERRIDDEN,
            state.brightnessOverridden && state.brightnessPercent in
                MIN_COMIC_BRIGHTNESS_PERCENT..MAX_COMIC_BRIGHTNESS_PERCENT
        )
    }

    private companion object {
        const val KEY_HORIZONTAL = "online.horizontal"
        const val KEY_NIGHT = "online.night"
        const val KEY_EYE_PROTECTION = "online.eye_protection"
        const val KEY_LANDSCAPE = "online.landscape"
        const val KEY_BRIGHTNESS_PERCENT = "online.brightness_percent"
        const val KEY_BRIGHTNESS_OVERRIDDEN = "online.brightness_overridden"
    }
}

internal class LocalComicReaderDirectionStore(
    private val storage: ComicReaderModeStorage
) {
    fun load(documentId: String): Boolean {
        if (documentId.isBlank()) return false
        return storage.readBoolean(key(documentId)) ?: false
    }

    fun save(documentId: String, horizontal: Boolean) {
        if (documentId.isBlank()) return
        storage.writeBoolean(key(documentId), horizontal)
    }

    private fun key(documentId: String): String = "local.horizontal.$documentId"
}

internal object ComicReaderModePreferences {
    private const val STORE_NAME = "reader_comic_modes"
    private val onlineStore by lazy { OnlineComicReaderModeStore(MmkvComicReaderModeStorage) }
    private val localStore by lazy { LocalComicReaderDirectionStore(MmkvComicReaderModeStorage) }

    fun loadOnline(defaultLandscape: Boolean): ComicReaderModeState {
        return onlineStore.load(defaultLandscape)
    }

    fun saveOnline(state: ComicReaderModeState) {
        onlineStore.save(state)
    }

    fun loadLocalHorizontal(documentId: String): Boolean = localStore.load(documentId)

    fun saveLocalHorizontal(documentId: String, horizontal: Boolean) {
        localStore.save(documentId, horizontal)
    }

    private object MmkvComicReaderModeStorage : ComicReaderModeStorage {
        private fun mmkv(): MMKV = MMKV.mmkvWithID(STORE_NAME)

        override fun readBoolean(key: String): Boolean? {
            val store = mmkv()
            return store.decodeBool(key).takeIf { store.containsKey(key) }
        }

        override fun readInt(key: String): Int? {
            val store = mmkv()
            return store.decodeInt(key).takeIf { store.containsKey(key) }
        }

        override fun writeBoolean(key: String, value: Boolean) {
            mmkv().encode(key, value)
        }

        override fun writeInt(key: String, value: Int) {
            mmkv().encode(key, value)
        }
    }
}
