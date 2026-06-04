package com.ldp.reader.media

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object MediaShelfStore {
    private const val STORE_NAME = "reader_media_shelf"
    private const val KEY_ITEMS = "items_json"
    private val gson = Gson()
    private val itemListType = object : TypeToken<List<MediaShelfItem>>() {}.type

    fun items(context: Context): List<MediaShelfItem> {
        return loadItems(context).sortedByDescending { it.updatedAtMs }
    }

    fun isAdded(context: Context, bookRouteId: String): Boolean {
        if (bookRouteId.isBlank()) return false
        val snapshotId = MediaRouteRegistry.snapshotBookRoute(bookRouteId)?.let { stableId(it.kind, it.book) }
        return loadItems(context).any { item ->
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
        val snapshot = MediaRouteRegistry.snapshotBookRoute(bookRouteId) ?: return null
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
            comicPageIndex = previous?.comicPageIndex ?: 0,
            audioPositionMs = previous?.audioPositionMs ?: 0L,
            audioDurationMs = previous?.audioDurationMs ?: 0L,
            comicPageIndexByChapter = previous?.comicPageIndexByChapter,
            audioPositionMsByChapter = previous?.audioPositionMsByChapter,
            audioDurationMsByChapter = previous?.audioDurationMsByChapter,
            routeSnapshot = snapshot,
            updatedAtMs = now
        )
        saveItems(context, currentItems.upsert(item))
        return item
    }

    fun restoreForBook(context: Context, bookRouteId: String): Boolean {
        if (bookRouteId.isBlank()) return false
        if (MediaRouteRegistry.book(bookRouteId) != null) return true
        val item = loadItems(context).firstOrNull { it.bookRouteId == bookRouteId || it.routeSnapshot.bookRouteId == bookRouteId }
            ?: return false
        MediaRouteRegistry.restore(item.routeSnapshot)
        return true
    }

    fun restoreForChapter(context: Context, chapterRouteId: String): Boolean {
        if (chapterRouteId.isBlank()) return false
        if (MediaRouteRegistry.chapter(chapterRouteId) != null) return true
        val item = itemForChapter(loadItems(context), chapterRouteId) ?: return false
        MediaRouteRegistry.restore(item.routeSnapshot)
        return true
    }

    fun restoreItemRoutes(item: MediaShelfItem) {
        MediaRouteRegistry.restore(item.routeSnapshot)
    }

    fun updateAudioChapter(context: Context, chapterRouteId: String, title: String) {
        updateChapter(context, ReaderMediaKind.AUDIO, chapterRouteId, title)
    }

    fun updateAudioProgress(context: Context, chapterRouteId: String, positionMs: Long, durationMs: Long) {
        if (chapterRouteId.isBlank()) return
        val currentItems = loadItems(context)
        val item = itemForChapter(currentItems, chapterRouteId) ?: return
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.takeIf { it > 0L } ?: item.audioDurationMs
        val updated = item.copy(
            currentChapterRouteId = chapterRouteId,
            currentChapterTitle = chapterTitle(item, chapterRouteId).ifBlank { item.currentChapterTitle },
            currentChapterIndex = chapterIndex(item, chapterRouteId),
            audioPositionMs = position,
            audioDurationMs = duration,
            audioPositionMsByChapter = item.audioPositionMsByChapter.orEmpty() + (chapterRouteId to position),
            audioDurationMsByChapter = item.audioDurationMsByChapter.orEmpty() + (chapterRouteId to duration),
            updatedAtMs = System.currentTimeMillis()
        )
        saveItems(context, currentItems.upsert(updated))
    }

    fun updateComicProgress(context: Context, chapterRouteId: String, pageIndex: Int) {
        if (chapterRouteId.isBlank()) return
        val currentItems = loadItems(context)
        val item = itemForChapter(currentItems, chapterRouteId) ?: return
        val page = pageIndex.coerceAtLeast(0)
        val updated = item.copy(
            currentChapterRouteId = chapterRouteId,
            currentChapterTitle = chapterTitle(item, chapterRouteId).ifBlank { item.currentChapterTitle },
            currentChapterIndex = chapterIndex(item, chapterRouteId),
            comicPageIndex = page,
            comicPageIndexByChapter = item.comicPageIndexByChapter.orEmpty() + (chapterRouteId to page),
            updatedAtMs = System.currentTimeMillis()
        )
        saveItems(context, currentItems.upsert(updated))
    }

    fun comicPageIndex(context: Context, chapterRouteId: String): Int {
        if (chapterRouteId.isBlank()) return 0
        val item = itemForChapter(loadItems(context), chapterRouteId) ?: return 0
        return item.comicPageIndexByChapter.orEmpty()[chapterRouteId]
            ?: item.comicPageIndex.takeIf { item.currentChapterRouteId == chapterRouteId }
            ?: 0
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
        val item = itemForChapter(currentItems, chapterRouteId) ?: return
        if (item.kindKey != kind.seedKey) return
        val updated = item.copy(
            currentChapterRouteId = chapterRouteId,
            currentChapterTitle = title.ifBlank { chapterTitle(item, chapterRouteId) }.ifBlank { item.currentChapterTitle },
            currentChapterIndex = chapterIndex(item, chapterRouteId),
            updatedAtMs = System.currentTimeMillis()
        )
        saveItems(context, currentItems.upsert(updated))
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
        return items.firstOrNull { item ->
            item.currentChapterRouteId == chapterRouteId ||
                item.routeSnapshot.chapters.any { it.routeId == chapterRouteId }
        }
    }

    private fun chapterTitle(item: MediaShelfItem, chapterRouteId: String): String {
        return item.routeSnapshot.chapters.firstOrNull { it.routeId == chapterRouteId }?.chapter?.name.orEmpty()
    }

    private fun chapterIndex(item: MediaShelfItem, chapterRouteId: String): Int {
        return item.routeSnapshot.chapters.firstOrNull { it.routeId == chapterRouteId }?.chapter?.index
            ?: item.currentChapterIndex
    }

    private fun loadItems(context: Context): List<MediaShelfItem> {
        val json = context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "")
            .orEmpty()
        if (json.isBlank()) return emptyList()
        return runCatching { gson.fromJson<List<MediaShelfItem>>(json, itemListType).orEmpty() }
            .getOrDefault(emptyList())
    }

    private fun saveItems(context: Context, items: List<MediaShelfItem>) {
        context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, gson.toJson(items.sortedByDescending { it.updatedAtMs }))
            .commit()
    }

    private fun List<MediaShelfItem>.upsert(item: MediaShelfItem): List<MediaShelfItem> {
        return filterNot { it.id == item.id } + item
    }

    private fun stableId(kind: ReaderMediaKind, book: MediaSourceBook): String {
        return listOf(kind.seedKey, book.source.sourceUrl, book.bookUrl).joinToString("|")
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
    val comicPageIndex: Int,
    val audioPositionMs: Long,
    val audioDurationMs: Long,
    val comicPageIndexByChapter: Map<String, Int>? = emptyMap(),
    val audioPositionMsByChapter: Map<String, Long>? = emptyMap(),
    val audioDurationMsByChapter: Map<String, Long>? = emptyMap(),
    val routeSnapshot: MediaRouteSnapshot,
    val updatedAtMs: Long
) {
    val mediaKind: ReaderMediaKind?
        get() = ReaderMediaKind.fromSeedKey(kindKey)
}
