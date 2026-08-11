package com.ldp.reader.media

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ldp.reader.source.AiBridgeTrace
import com.tencent.mmkv.MMKV

object MediaShelfStore {
    private const val STORE_NAME = "reader_media_shelf"
    private const val KEY_IDS = "ids_json"
    private const val KEY_LEGACY_ITEMS = "items_json"
    private const val KEY_ITEM_PREFIX = "item:"
    private const val MAX_STORE_ACTUAL_SIZE_BYTES = 1_000_000L
    private const val MAX_ITEM_JSON_BYTES = 32_000
    private const val MAX_PERSISTED_INTRO_CHARS = 800
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    fun items(context: Context): List<MediaShelfItem> {
        return loadItems(context).sortedByDescending { it.updatedAtMs }
    }

    fun isAdded(context: Context, bookRouteId: String): Boolean {
        return itemForBook(context, bookRouteId) != null
    }

    fun itemForBook(context: Context, bookRouteId: String): MediaShelfItem? {
        if (bookRouteId.isBlank()) return null
        val snapshotId = MediaRouteRegistry.snapshotBookRoute(bookRouteId)?.let { stableId(it.kind, it.book) }
        return loadItems(context).firstOrNull { item ->
            item.bookRouteId == bookRouteId || (snapshotId != null && item.id == snapshotId)
        }
    }

    fun addOrUpdate(
        context: Context,
        kind: ReaderMediaKind,
        bookRouteId: String,
        currentChapterRouteId: String = "",
        currentChapterTitle: String = "",
        currentChapterIndex: Int = -1
    ): MediaShelfItem? {
        val snapshot = MediaRouteRegistry.snapshotBookRoute(bookRouteId) ?: run {
            traceShelfEvent(
                "media_shelf_add_skipped",
                bookRouteId,
                "kind" to kind.seedKey,
                "reason" to "missing_route_snapshot"
            )
            return null
        }
        val id = stableId(kind, snapshot.book)
        val now = System.currentTimeMillis()
        val currentItems = loadItems(context)
        val previous = currentItems.firstOrNull { it.id == id }
        val selectedChapter = selectedChapter(snapshot, previous, currentChapterRouteId)
        val nextChapterRouteId = currentChapterRouteId
            .ifBlank { previous?.currentChapterRouteId.orEmpty() }
            .ifBlank { selectedChapter?.routeId.orEmpty() }
        val nextChapterTitle = currentChapterTitle
            .ifBlank { previous?.currentChapterTitle.orEmpty() }
            .ifBlank { selectedChapter?.chapter?.name.orEmpty() }
        val nextChapterIndex = if (currentChapterIndex >= 0) {
            currentChapterIndex
        } else {
            previous?.currentChapterIndex?.takeIf { it >= 0 }
                ?: selectedChapter?.chapter?.index
                ?: -1
        }
        val detail = snapshot.detail
        val item = MediaShelfItem(
            id = id,
            kindKey = kind.seedKey,
            bookRouteId = snapshot.bookRouteId,
            title = detail?.name?.ifBlank { snapshot.book.name } ?: snapshot.book.name,
            author = detail?.author?.ifBlank { snapshot.book.author } ?: snapshot.book.author,
            coverUrl = detail?.coverUrl?.ifBlank { snapshot.book.coverUrl } ?: snapshot.book.coverUrl,
            intro = detail?.intro?.ifBlank { snapshot.book.intro } ?: snapshot.book.intro,
            latest = detail?.lastChapter?.ifBlank { snapshot.book.lastChapter } ?: snapshot.book.lastChapter,
            sourceName = snapshot.book.source.sourceName,
            currentChapterRouteId = nextChapterRouteId,
            currentChapterTitle = nextChapterTitle,
            currentChapterIndex = nextChapterIndex,
            routeSnapshot = MediaRouteRegistry.compactSnapshot(snapshot, nextChapterRouteId),
            updatedAtMs = now
        )
        saveItems(context, currentItems.upsert(item))
        traceShelfEvent(
            "media_shelf_item_saved",
            item.id,
            "kind" to kind.seedKey,
            "mode" to if (previous == null) "added" else "updated",
            "title" to item.title,
            "chapter" to item.currentChapterTitle,
            "chapterIndex" to item.currentChapterIndex,
            "chapters" to snapshot.chapters.size,
            "storage" to "mmkv_multi_key"
        )
        return item
    }

    fun addOrUpdateForChapter(
        context: Context,
        kind: ReaderMediaKind,
        chapterRouteId: String,
        currentChapterTitle: String = ""
    ): MediaShelfItem? {
        val target = MediaShelfChapterLink.resolve(kind, chapterRouteId) ?: run {
            traceShelfEvent(
                "media_shelf_add_skipped",
                chapterRouteId,
                "kind" to kind.seedKey,
                "reason" to "missing_chapter_link"
            )
            return null
        }
        return addOrUpdate(
            context = context,
            kind = kind,
            bookRouteId = target.bookRouteId,
            currentChapterRouteId = target.chapterRouteId,
            currentChapterTitle = currentChapterTitle.ifBlank { target.chapterTitle },
            currentChapterIndex = target.chapterIndex
        )
    }

    fun restoreForBook(context: Context, bookRouteId: String): Boolean {
        if (bookRouteId.isBlank()) return false
        if (MediaRouteRegistry.book(bookRouteId) != null) return true
        val item = loadItems(context).firstOrNull { it.bookRouteId == bookRouteId || it.routeSnapshot.bookRouteId == bookRouteId }
            ?: run {
                traceShelfEvent("media_shelf_restore_missed", bookRouteId, "scope" to "book")
                return false
            }
        MediaRouteRegistry.restore(item.routeSnapshot)
        traceShelfEvent("media_shelf_restore_succeeded", bookRouteId, "scope" to "book", "title" to item.title)
        return true
    }

    fun restoreForChapter(context: Context, chapterRouteId: String): Boolean {
        if (chapterRouteId.isBlank()) return false
        if (MediaRouteRegistry.chapter(chapterRouteId) != null) return true
        val item = itemForChapter(loadItems(context), chapterRouteId) ?: run {
            traceShelfEvent("media_shelf_restore_missed", chapterRouteId, "scope" to "chapter")
            return false
        }
        MediaRouteRegistry.restore(item.routeSnapshot)
        traceShelfEvent("media_shelf_restore_succeeded", chapterRouteId, "scope" to "chapter", "title" to item.title)
        return true
    }

    fun restoreItemRoutes(item: MediaShelfItem) {
        MediaRouteRegistry.restore(item.routeSnapshot)
    }

    fun updateAudioChapter(context: Context, chapterRouteId: String, title: String) {
        updateChapter(context, ReaderMediaKind.AUDIO, chapterRouteId, title)
    }

    fun updateComicChapter(context: Context, chapterRouteId: String, title: String) {
        updateChapter(context, ReaderMediaKind.COMIC, chapterRouteId, title)
    }

    fun hasItemForChapter(context: Context, chapterRouteId: String): Boolean {
        if (chapterRouteId.isBlank()) return false
        return itemForChapter(loadItems(context), chapterRouteId) != null
    }

    fun updateComicProgress(context: Context, chapterRouteId: String, pageIndex: Int) {
        if (chapterRouteId.isBlank()) return
        val page = pageIndex.coerceAtLeast(0)
        ComicReadingProgressStore.save(context, chapterRouteId, page)
        traceShelfEvent(
            "media_comic_progress_saved",
            chapterRouteId,
            "page" to page,
            "storage" to "reader_comic_progress"
        )
    }

    fun comicPageIndex(context: Context, chapterRouteId: String): Int {
        if (chapterRouteId.isBlank()) return 0
        return ComicReadingProgressStore.page(context, chapterRouteId).takeIf { it >= 0 } ?: 0
    }

    fun remove(context: Context, itemId: String) {
        if (itemId.isBlank()) return
        saveItems(context, loadItems(context).filterNot { it.id == itemId })
    }

    private fun updateChapter(
        context: Context,
        kind: ReaderMediaKind,
        chapterRouteId: String,
        title: String
    ) {
        if (chapterRouteId.isBlank()) return
        val currentItems = loadItems(context)
        val item = itemForChapter(currentItems, chapterRouteId) ?: run {
            traceShelfEvent("media_shelf_update_skipped", chapterRouteId, "kind" to kind.seedKey, "reason" to "missing_item")
            return
        }
        if (item.kindKey != kind.seedKey) return
        val updated = item.copy(
            currentChapterRouteId = chapterRouteId,
            currentChapterTitle = title.ifBlank { chapterTitle(item, chapterRouteId) }.ifBlank { item.currentChapterTitle },
            currentChapterIndex = chapterIndex(item, chapterRouteId),
            routeSnapshot = snapshotForChapter(item, chapterRouteId),
            updatedAtMs = System.currentTimeMillis()
        )
        saveItems(context, currentItems.upsert(updated))
    }

    private fun traceShelfEvent(name: String, key: String, vararg fields: Pair<String, Any?>) {
        AiBridgeTrace.event(name, key, AiBridgeTrace.fields(*fields))
    }

    private fun selectedChapter(
        snapshot: MediaRouteSnapshot,
        previous: MediaShelfItem?,
        currentChapterRouteId: String
    ): MediaRouteChapterSnapshot? {
        return snapshot.chapters.firstOrNull { it.routeId == currentChapterRouteId }
            ?: previous?.currentChapterRouteId?.let { routeId -> snapshot.chapters.firstOrNull { it.routeId == routeId } }
            ?: snapshot.chapters.firstOrNull()
    }

    private fun itemForChapter(items: List<MediaShelfItem>, chapterRouteId: String): MediaShelfItem? {
        val bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId)
        return items.firstOrNull { item ->
            item.currentChapterRouteId == chapterRouteId ||
                item.routeSnapshot.chapters.any { it.routeId == chapterRouteId } ||
                (bookRouteId != null && item.bookRouteId == bookRouteId)
        }
    }

    private fun chapterTitle(item: MediaShelfItem, chapterRouteId: String): String {
        return MediaRouteRegistry.chapter(chapterRouteId)?.name
            ?: item.routeSnapshot.chapters.firstOrNull { it.routeId == chapterRouteId }?.chapter?.name
            ?: item.currentChapterTitle
    }

    private fun chapterIndex(item: MediaShelfItem, chapterRouteId: String): Int {
        return MediaRouteRegistry.chapter(chapterRouteId)?.index
            ?: item.routeSnapshot.chapters.firstOrNull { it.routeId == chapterRouteId }?.chapter?.index
            ?: item.currentChapterIndex
    }

    private fun snapshotForChapter(item: MediaShelfItem, chapterRouteId: String): MediaRouteSnapshot {
        val bookRouteId = MediaRouteRegistry.bookRouteForChapter(chapterRouteId)
            ?: item.bookRouteId
        return MediaShelfRouteSnapshotPolicy.forChapter(
            currentSnapshot = MediaRouteRegistry.snapshotBookRoute(bookRouteId),
            persistedSnapshot = item.routeSnapshot,
            chapterRouteId = chapterRouteId
        )
    }

    private fun loadItems(context: Context): List<MediaShelfItem> {
        val mmkv = store()
        val sizeBefore = mmkv.actualSize()
        if (sizeBefore > MAX_STORE_ACTUAL_SIZE_BYTES) {
            traceShelfEvent(
                "media_shelf_store_oversized",
                STORE_NAME,
                "beforeBytes" to sizeBefore,
                "action" to "preserved"
            )
        }
        val currentItems = readMultiKeyItems(mmkv)
        val embeddedLegacyItems = decodeLegacyItems(
            mmkv.decodeString(KEY_LEGACY_ITEMS, "").orEmpty()
        ).orEmpty()
        return mergeForMigration(currentItems, embeddedLegacyItems)
    }

    private fun readMultiKeyItems(mmkv: MMKV): List<MediaShelfItem> {
        val ids = decodeIds(mmkv)
        return ids.mapNotNull { id ->
            val json = mmkv.decodeString(itemKey(id), "").orEmpty()
            if (json.isBlank()) return@mapNotNull null
            if (json.toByteArray(Charsets.UTF_8).size > MAX_ITEM_JSON_BYTES) {
                traceShelfEvent("media_shelf_item_preserved", id, "reason" to "oversized_item")
                return@mapNotNull null
            }
            val persisted = runCatching { gson.fromJson(json, PersistedMediaShelfItem::class.java) }.getOrNull()
                ?: run {
                    traceShelfEvent("media_shelf_item_preserved", id, "reason" to "decode_failed")
                    return@mapNotNull null
                }
            val item = runCatching { persisted.toItem() }.getOrNull()
            if (item == null) {
                traceShelfEvent("media_shelf_item_preserved", id, "reason" to "invalid_item")
            }
            item
        }
    }

    private fun saveItems(context: Context, items: List<MediaShelfItem>) {
        writeItems(store(), items)
    }

    private fun writeItems(mmkv: MMKV, items: List<MediaShelfItem>) {
        if (mmkv.actualSize() > MAX_STORE_ACTUAL_SIZE_BYTES) {
            traceShelfEvent(
                "media_shelf_store_oversized",
                STORE_NAME,
                "beforeBytes" to mmkv.actualSize(),
                "action" to "write_without_clear"
            )
        }
        val oldIds = decodeIds(mmkv).toSet()
        val readableOldIds = readMultiKeyItems(mmkv).mapTo(linkedSetOf()) { it.id }
        val requestedIds = items.mapTo(linkedSetOf()) { it.id }
        val persistedItems = items
            .map { compactItemForPersistence(it) }
            .mapNotNull { item ->
                val persisted = PersistedMediaShelfItem.from(item)
                val json = gson.toJson(persisted)
                if (json.toByteArray(Charsets.UTF_8).size > MAX_ITEM_JSON_BYTES) {
                    traceShelfEvent(
                        "media_shelf_item_write_rejected",
                        item.id,
                        "reason" to "oversized_item",
                        "bytes" to json.toByteArray(Charsets.UTF_8).size
                    )
                    null
                } else {
                    persisted to json
                }
            }
        val acceptedIds = persistedItems.mapTo(linkedSetOf()) { it.first.id }
        val unreadableOldIds = oldIds - readableOldIds
        val rejectedExistingIds = (requestedIds - acceptedIds).intersect(oldIds)
        val nextIds = (unreadableOldIds + rejectedExistingIds + acceptedIds).toList()
        (readableOldIds - requestedIds).forEach { id -> mmkv.removeValueForKey(itemKey(id)) }
        persistedItems.forEach { (item, json) -> mmkv.encode(itemKey(item.id), json) }
        mmkv.encode(KEY_IDS, gson.toJson(nextIds))
        mmkv.trim()
    }

    private fun decodeIds(mmkv: MMKV): List<String> {
        val json = mmkv.decodeString(KEY_IDS, "").orEmpty()
        if (json.isBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(json, stringListType).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .distinct()
    }

    internal fun decodeLegacyItems(payload: String): List<MediaShelfItem>? {
        if (payload.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(payload, Array<MediaShelfItem>::class.java)
                .orEmpty()
                .toList()
                .takeIf { items -> items.all { it.id.isNotBlank() } }
        }.getOrNull()
    }

    internal fun persistedItemsForMigration(): List<MediaShelfItem> {
        return readMultiKeyItems(store())
    }

    internal fun mergeForMigration(
        currentItems: List<MediaShelfItem>,
        legacyItems: List<MediaShelfItem>
    ): List<MediaShelfItem> {
        return (currentItems + legacyItems)
            .groupBy { it.id }
            .map { (_, candidates) -> candidates.maxBy { it.updatedAtMs } }
    }

    internal fun persistAndVerifyMigration(items: List<MediaShelfItem>): Boolean {
        val mmkv = store()
        return runCatching {
            writeItems(mmkv, items)
            val persistedIds = readMultiKeyItems(mmkv).mapTo(hashSetOf()) { it.id }
            items.all { it.id in persistedIds }
        }.getOrDefault(false)
    }

    internal fun migrateEmbeddedLegacyItems() {
        val mmkv = store()
        val payload = mmkv.decodeString(KEY_LEGACY_ITEMS, "").orEmpty()
        LegacyShelfMigrationTransaction.migrate(
            legacyPayload = payload,
            decode = ::decodeLegacyItems,
            currentItems = ::persistedItemsForMigration,
            merge = ::mergeForMigration,
            persistAndVerify = ::persistAndVerifyMigration,
            deleteLegacy = {
                mmkv.removeValueForKey(KEY_LEGACY_ITEMS)
                !mmkv.containsKey(KEY_LEGACY_ITEMS)
            }
        )
    }

    private fun store(): MMKV {
        return MMKV.mmkvWithID(STORE_NAME)
    }

    private fun itemKey(id: String): String = KEY_ITEM_PREFIX + id

    private fun compactItemForPersistence(item: MediaShelfItem): MediaShelfItem {
        return item.copy(
            intro = item.intro.take(MAX_PERSISTED_INTRO_CHARS),
            routeSnapshot = MediaRouteRegistry.compactSnapshot(item.routeSnapshot, item.currentChapterRouteId)
        )
    }

    private fun List<MediaShelfItem>.upsert(item: MediaShelfItem): List<MediaShelfItem> {
        return filterNot { it.id == item.id } + item
    }

    private fun stableId(kind: ReaderMediaKind, book: MediaSourceBook): String {
        return listOf(kind.seedKey, book.source.sourceUrl, book.bookUrl).joinToString("|")
    }

    private data class PersistedMediaShelfItem(
        val id: String,
        val kindKey: String,
        val bookRouteId: String,
        val title: String,
        val author: String,
        val coverUrl: String,
        val intro: String,
        val latest: String,
        val sourceName: String,
        val sourceUrl: String,
        val sourceType: Int,
        val sourceGroup: String?,
        val bookUrl: String,
        val bookKind: String,
        val currentChapterRouteId: String,
        val currentChapterTitle: String,
        val currentChapterIndex: Int,
        val currentChapterUrl: String,
        val updatedAtMs: Long
    ) {
        fun toItem(): MediaShelfItem {
            val kind = ReaderMediaKind.fromSeedKey(kindKey) ?: ReaderMediaKind.AUDIO
            val source = compactSource()
            val book = MediaSourceBook(
                source = source,
                name = title,
                author = author,
                bookUrl = bookUrl,
                coverUrl = coverUrl,
                intro = intro.take(MAX_PERSISTED_INTRO_CHARS),
                kind = bookKind.ifBlank { kind.seedKey },
                lastChapter = latest
            )
            val chapter = MediaSourceChapter(
                source = source,
                book = book,
                index = currentChapterIndex,
                name = currentChapterTitle.ifBlank { title },
                chapterUrl = currentChapterUrl
            )
            val snapshot = MediaRouteSnapshot(
                kind = kind,
                bookRouteId = bookRouteId,
                book = book,
                detail = null,
                chapters = listOfNotNull(
                    currentChapterRouteId.takeIf { it.isNotBlank() }?.let { routeId ->
                        MediaRouteChapterSnapshot(routeId = routeId, chapter = chapter)
                    }
                )
            )
            return MediaShelfItem(
                id = id,
                kindKey = kind.seedKey,
                bookRouteId = bookRouteId,
                title = title,
                author = author,
                coverUrl = coverUrl,
                intro = intro.take(MAX_PERSISTED_INTRO_CHARS),
                latest = latest,
                sourceName = sourceName,
                currentChapterRouteId = currentChapterRouteId,
                currentChapterTitle = currentChapterTitle,
                currentChapterIndex = currentChapterIndex,
                routeSnapshot = snapshot,
                updatedAtMs = updatedAtMs
            )
        }

        private fun compactSource(): MediaSourceDefinition {
            val emptyRules = MediaLegadoRuleSet("", emptyMap())
            return MediaSourceDefinition(
                sourceName = sourceName,
                sourceUrl = sourceUrl,
                sourceType = sourceType,
                sourceGroup = sourceGroup,
                sourceComment = null,
                enabled = true,
                headers = emptyMap(),
                searchUrl = null,
                ruleSearch = emptyRules,
                ruleBookInfo = emptyRules,
                ruleToc = emptyRules,
                ruleContent = emptyRules,
                diagnostics = emptyList()
            )
        }

        companion object {
            fun from(item: MediaShelfItem): PersistedMediaShelfItem {
                val snapshot = MediaRouteRegistry.compactSnapshot(item.routeSnapshot, item.currentChapterRouteId)
                val chapter = snapshot.chapters.firstOrNull { it.routeId == item.currentChapterRouteId }
                    ?: snapshot.chapters.firstOrNull()
                return PersistedMediaShelfItem(
                    id = item.id,
                    kindKey = item.kindKey,
                    bookRouteId = item.bookRouteId,
                    title = item.title,
                    author = item.author,
                    coverUrl = item.coverUrl,
                    intro = item.intro.take(MAX_PERSISTED_INTRO_CHARS),
                    latest = item.latest,
                    sourceName = snapshot.book.source.sourceName,
                    sourceUrl = snapshot.book.source.sourceUrl,
                    sourceType = snapshot.book.source.sourceType,
                    sourceGroup = snapshot.book.source.sourceGroup,
                    bookUrl = snapshot.book.bookUrl,
                    bookKind = snapshot.book.kind,
                    currentChapterRouteId = item.currentChapterRouteId,
                    currentChapterTitle = item.currentChapterTitle,
                    currentChapterIndex = item.currentChapterIndex,
                    currentChapterUrl = chapter?.chapter?.chapterUrl.orEmpty(),
                    updatedAtMs = item.updatedAtMs
                )
            }
        }
    }
}

data class MediaShelfItem(
    val id: String,
    val kindKey: String,
    val bookRouteId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val intro: String,
    val latest: String,
    val sourceName: String,
    val currentChapterRouteId: String,
    val currentChapterTitle: String,
    val currentChapterIndex: Int,
    val routeSnapshot: MediaRouteSnapshot,
    val updatedAtMs: Long
) {
    val mediaKind: ReaderMediaKind?
        get() = ReaderMediaKind.fromSeedKey(kindKey)
}
