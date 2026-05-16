package com.ldp.reader.model.objectbox;

import com.ldp.reader.model.bean.BookRecordBean;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.Query;

public class ObjectBoxBookRecordStore {
    private final Box<ObjectBoxBookRecordEntity> recordBox;

    public ObjectBoxBookRecordStore(BoxStore boxStore) {
        recordBox = boxStore.boxFor(ObjectBoxBookRecordEntity.class);
    }

    public void saveBookRecord(BookRecordBean record) {
        ObjectBoxBookRecordEntity entity = findEntity(record.getBookId());
        if (entity == null) {
            entity = ObjectBoxBookRecordEntity.from(record);
        } else {
            entity.setChapter(record.getChapter());
            entity.setPagePos(record.getPagePos());
        }
        recordBox.put(entity);
    }

    public BookRecordBean getBookRecord(String bookId) {
        ObjectBoxBookRecordEntity entity = findEntity(bookId);
        if (entity == null) {
            return null;
        }
        return entity.toBookRecord();
    }

    public void deleteBookRecord(String bookId) {
        ObjectBoxBookRecordEntity entity = findEntity(bookId);
        if (entity != null) {
            recordBox.remove(entity);
        }
    }

    private ObjectBoxBookRecordEntity findEntity(String bookId) {
        Query<ObjectBoxBookRecordEntity> query = recordBox
                .query(ObjectBoxBookRecordEntity_.bookId.equal(bookId))
                .build();
        try {
            return query.findFirst();
        } finally {
            query.close();
        }
    }
}
