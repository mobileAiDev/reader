package com.ldp.reader.ui.home

import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.utils.BookIdentity
import com.tencent.mmkv.MMKV

/**
 * Persists shelf presentation state separately from reading history.
 *
 * A pin must never be represented by [CollBookBean.lastRead]: opening another book legitimately
 * changes that field and would both lose the pin and falsify the user's reading timeline.
 */
internal object BookshelfPinStore {
    private const val STORE_NAME = "reader_bookshelf_pins"
    private const val KEY_PINNED_BOOKS = "pinned_book_keys"

    fun pinnedKeys(): Set<String> {
        return LinkedHashSet(
            store().decodeStringSet(KEY_PINNED_BOOKS, emptySet()).orEmpty()
        )
    }

    fun isPinned(book: CollBookBean): Boolean {
        val key = BookshelfPinIdentity.key(book)
        return key.isNotBlank() && pinnedKeys().contains(key)
    }

    @Synchronized
    fun pin(book: CollBookBean): Boolean {
        val key = BookshelfPinIdentity.key(book)
        if (key.isBlank()) return false
        val keys = LinkedHashSet(pinnedKeys())
        if (!keys.add(key)) return false
        return store().encode(KEY_PINNED_BOOKS, keys)
    }

    @Synchronized
    fun unpin(book: CollBookBean): Boolean {
        val key = BookshelfPinIdentity.key(book)
        if (key.isBlank()) return false
        val keys = LinkedHashSet(pinnedKeys())
        if (!keys.remove(key)) return false
        return store().encode(KEY_PINNED_BOOKS, keys)
    }

    private fun store(): MMKV = MMKV.mmkvWithID(STORE_NAME)
}

/** Stable across refreshed bean instances while keeping local and online namespaces separate. */
internal object BookshelfPinIdentity {
    fun key(book: CollBookBean?): String {
        book ?: return ""
        val bookId = clean(book.get_id())
        if (book.isLocal()) {
            val sourcePath = clean(book.cover)
            return when {
                sourcePath.isNotBlank() -> "local:path:$sourcePath"
                bookId.isNotBlank() -> "local:id:$bookId"
                else -> ""
            }
        }

        val sourceRouteId = clean(book.bookIdInBiquge)
        if (
            SourceEngineBookRoute.isBookId(bookId) ||
            SourceEngineBookRoute.isShelfBookId(bookId) ||
            SourceEngineBookRoute.isBookId(sourceRouteId) ||
            SourceEngineBookRoute.isShelfBookId(sourceRouteId)
        ) {
            val shelfId = when {
                SourceEngineBookRoute.isShelfBookId(bookId) -> bookId
                SourceEngineBookRoute.isShelfBookId(sourceRouteId) -> sourceRouteId
                else -> BookIdentity.sourceEngineShelfId(book.title, book.author)
            }
            return "online:source-engine:$shelfId"
        }
        return when {
            bookId.isNotBlank() -> "online:id:$bookId"
            sourceRouteId.isNotBlank() -> "online:route:$sourceRouteId"
            else -> BookIdentity.sourceEngineIdentityKey(book.title, book.author)
                .takeIf { it.isNotBlank() }
                ?.let { "online:metadata:$it" }
                .orEmpty()
        }
    }

    private fun clean(value: String?): String = value.orEmpty().trim()
}

internal data class BookshelfPinPartition<T>(
    val pinned: List<T>,
    val regular: List<T>
)

internal object BookshelfPinOrdering {
    fun partition(
        books: List<CollBookBean>,
        pinnedKeys: Set<String>
    ): BookshelfPinPartition<CollBookBean> {
        val (pinned, regular) = books.partition { book ->
            pinnedKeys.contains(BookshelfPinIdentity.key(book))
        }
        return BookshelfPinPartition(pinned = pinned, regular = regular)
    }
}
