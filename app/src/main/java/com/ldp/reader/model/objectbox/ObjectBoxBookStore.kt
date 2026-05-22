package com.ldp.reader.model.objectbox

import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import io.objectbox.Box
import io.objectbox.BoxStore

class ObjectBoxBookStore(private val boxStore: BoxStore) {
    private val collBookBox: Box<ObjectBoxCollBookEntity> =
        boxStore.boxFor(ObjectBoxCollBookEntity::class.java)
    private val chapterBox: Box<ObjectBoxBookChapterEntity> =
        boxStore.boxFor(ObjectBoxBookChapterEntity::class.java)

    fun runInTxAsync(runnable: Runnable) {
        boxStore.runInTxAsync(runnable) { _, error ->
            if (error != null) {
                Log.e(TAG, "ObjectBox transaction failed", error)
            }
        }
    }

    fun saveCollBook(book: CollBookBean) {
        val entity = ObjectBoxCollBookEntity.from(book)
        val existing = findCollBookEntity(book.get_id())
        if (existing != null) {
            entity.objectBoxId = existing.objectBoxId
        }
        collBookBox.put(entity)
    }

    fun saveCollBooks(books: List<CollBookBean>) {
        for (book in books) {
            saveCollBook(book)
        }
    }

    fun getCollBook(bookId: String?): CollBookBean? {
        val entity = findCollBookEntity(bookId) ?: return null
        val book = entity.toCollBook()
        book.setBookChapters(getBookChapters(bookId))
        return book
    }

    fun getCollBooks(): List<CollBookBean> {
        val query = collBookBox
            .query()
            .orderDesc(ObjectBoxCollBookEntity_.lastRead)
            .build()
        return try {
            val entities = query.find()
            val books = ArrayList<CollBookBean>(entities.size)
            for (entity in entities) {
                books.add(entity.toCollBook())
            }
            books
        } finally {
            query.close()
        }
    }

    fun getBookChapters(bookId: String?): List<BookChapterBean> {
        val query = chapterBox
            .query(ObjectBoxBookChapterEntity_.bookId.equal(bookId))
            .order(ObjectBoxBookChapterEntity_.start)
            .build()
        return try {
            val entities = query.find()
            val chapters = ArrayList<BookChapterBean>(entities.size)
            for (entity in entities) {
                chapters.add(entity.toBookChapter())
            }
            chapters
        } finally {
            query.close()
        }
    }

    fun replaceBookChapters(bookId: String?, chapters: List<BookChapterBean>) {
        deleteBookChapters(bookId)
        val entities = ArrayList<ObjectBoxBookChapterEntity>(chapters.size)
        for (chapter in chapters) {
            chapter.bookId = bookId
            entities.add(ObjectBoxBookChapterEntity.from(chapter))
        }
        chapterBox.put(entities)
    }

    fun deleteBookChapters(bookId: String?) {
        val query = chapterBox
            .query(ObjectBoxBookChapterEntity_.bookId.equal(bookId))
            .build()
        try {
            query.remove()
        } finally {
            query.close()
        }
    }

    fun deleteCollBook(book: CollBookBean) {
        val entity = findCollBookEntity(book.get_id())
        if (entity != null) {
            collBookBox.remove(entity)
        }
    }

    private fun findCollBookEntity(bookId: String?): ObjectBoxCollBookEntity? {
        val query = collBookBox
            .query(ObjectBoxCollBookEntity_.bookId.equal(bookId))
            .build()
        return try {
            query.findFirst()
        } finally {
            query.close()
        }
    }

    companion object {
        private const val TAG = "ObjectBoxBookStore"
    }
}
