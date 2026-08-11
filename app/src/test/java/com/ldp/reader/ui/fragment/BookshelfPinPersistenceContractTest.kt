package com.ldp.reader.ui.fragment

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfPinPersistenceContractTest {
    @Test
    fun pinActionUsesDedicatedPersistenceWithoutOverwritingReadingHistory() {
        val fragment = File(
            "src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt"
        ).readText()
        val store = File(
            "src/main/java/com/ldp/reader/ui/home/BookshelfPinStore.kt"
        ).readText()

        assertFalse(fragment.contains("collBook.lastRead = BookshelfPinTimestamp"))
        assertTrue(fragment.contains("BookshelfPinStore.pin("))
        assertTrue(fragment.contains("BookshelfPinStore.unpin("))
        assertTrue(fragment.contains("setPinnedBookKeys(BookshelfPinStore.pinnedKeys())"))
        assertTrue(fragment.contains("R.string.home_bookshelf_unpin"))
        assertTrue(store.contains("MMKV.mmkvWithID(STORE_NAME)"))
        assertTrue(store.contains("reader_bookshelf_pins"))
        assertTrue(store.contains("decodeStringSet(KEY_PINNED_BOOKS"))
    }

    @Test
    fun removingABookAlsoRemovesItsPersistentPin() {
        val fragment = File(
            "src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt"
        ).readText()
        val removalStart = fragment.indexOf("private fun removeShelfItems(")
        val removalEnd = fragment.indexOf("private fun updateEditUi()", removalStart)
        val removalBody = fragment.substring(removalStart, removalEnd)

        assertTrue(removalBody.contains("BookshelfPinStore.unpin(book)"))
    }

    @Test
    fun pinnedBooksRenderAheadOfMediaAndRegularBooks() {
        val adapter = File(
            "src/main/java/com/ldp/reader/ui/adapter/HomeShelfAdapter.kt"
        ).readText()
        val pinned = adapter.indexOf(
            "shelfItems.addAll(pinPartition.pinned.map { HomeShelfItem.Book(it) })"
        )
        val media = adapter.indexOf(
            "shelfItems.addAll(mediaItems.map { HomeShelfItem.Media(it) })"
        )
        val regular = adapter.indexOf(
            "shelfItems.addAll(pinPartition.regular.map { HomeShelfItem.Book(it) })"
        )

        assertTrue(pinned >= 0)
        assertTrue(pinned < media)
        assertTrue(media < regular)
    }
}
