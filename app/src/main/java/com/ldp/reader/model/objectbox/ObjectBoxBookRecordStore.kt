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

    fun getBookRecords(bookIds: Collection<String?>): Map<String, BookRecordBean> {
        val ids = bookIds.filterNotNull().distinct()
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val query = recordBox
            .query(ObjectBoxBookRecordEntity_.bookId.oneOf(ids.toTypedArray()))
            .build()
        return try {
            val entities = query.find()
            val records = LinkedHashMap<String, BookRecordBean>(entities.size)
            for (entity in entities) {
                val bookId = entity.bookId ?: continue
                records[bookId] = entity.toBookRecord()
            }
            records
        } finally {
            query.close()
        }
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
