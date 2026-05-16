package com.ldp.reader.model.objectbox

import com.ldp.reader.model.bean.BookRecordBean
import io.objectbox.Box
import io.objectbox.BoxStore

class ObjectBoxBookRecordStore(boxStore: BoxStore) {
    private val recordBox: Box<ObjectBoxBookRecordEntity> =
        boxStore.boxFor(ObjectBoxBookRecordEntity::class.java)

    fun saveBookRecord(record: BookRecordBean) {
        var entity = findEntity(record.bookId)
        if (entity == null) {
            entity = ObjectBoxBookRecordEntity.from(record)
        } else {
            entity.chapter = record.chapter
            entity.pagePos = record.pagePos
        }
        recordBox.put(entity)
    }

    fun getBookRecord(bookId: String?): BookRecordBean? {
        val entity = findEntity(bookId) ?: return null
        return entity.toBookRecord()
    }

    fun deleteBookRecord(bookId: String?) {
        val entity = findEntity(bookId)
        if (entity != null) {
            recordBox.remove(entity)
        }
    }

    private fun findEntity(bookId: String?): ObjectBoxBookRecordEntity? {
        val query = recordBox
            .query(ObjectBoxBookRecordEntity_.bookId.equal(bookId))
            .build()
        return try {
            query.findFirst()
        } finally {
            query.close()
        }
    }
}
