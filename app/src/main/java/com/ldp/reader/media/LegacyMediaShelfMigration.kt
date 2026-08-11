package com.ldp.reader.media

import android.content.Context
import android.util.Log

internal object LegacyShelfMigrationTransaction {
    fun <T> migrate(
        legacyPayload: String,
        decode: (String) -> List<T>?,
        currentItems: () -> List<T>,
        merge: (List<T>, List<T>) -> List<T>,
        persistAndVerify: (List<T>) -> Boolean,
        deleteLegacy: () -> Boolean
    ): Boolean {
        if (legacyPayload.isBlank()) return false
        return runCatching {
            val legacyItems = decode(legacyPayload) ?: return@runCatching false
            val merged = merge(currentItems(), legacyItems)
            if (!persistAndVerify(merged)) return@runCatching false
            deleteLegacy()
        }.getOrDefault(false)
    }
}

object LegacyMediaShelfMigration {
    private const val TAG = "LegacyMediaShelf"
    private const val STORE_NAME = "reader_media_shelf"
    private const val KEY_ITEMS = "items_json"

    fun migrate(context: Context) {
        runCatching { MediaShelfStore.migrateEmbeddedLegacyItems() }
            .onFailure { error -> Log.w(TAG, "embedded legacy shelf migration deferred", error) }
        runCatching { migrateSharedPreferences(context) }
            .onFailure { error -> Log.w(TAG, "shared-preferences shelf migration deferred", error) }
    }

    private fun migrateSharedPreferences(context: Context) {
        val preferences = context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
        val legacyPayload = preferences.getString(KEY_ITEMS, "").orEmpty()
        val migrated = LegacyShelfMigrationTransaction.migrate(
            legacyPayload = legacyPayload,
            decode = MediaShelfStore::decodeLegacyItems,
            currentItems = MediaShelfStore::persistedItemsForMigration,
            merge = MediaShelfStore::mergeForMigration,
            persistAndVerify = MediaShelfStore::persistAndVerifyMigration,
            deleteLegacy = { preferences.edit().remove(KEY_ITEMS).commit() }
        )
        if (legacyPayload.isNotBlank()) {
            Log.i(TAG, "legacy shared-preferences shelf migrated=$migrated")
        }
    }
}
