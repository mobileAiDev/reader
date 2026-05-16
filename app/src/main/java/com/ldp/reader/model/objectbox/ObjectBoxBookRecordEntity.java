package com.ldp.reader.model.objectbox;

import com.ldp.reader.model.bean.BookRecordBean;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;

@Entity
public class ObjectBoxBookRecordEntity {
    @Id
    private long id;
    @Index
    private String bookId;
    private int chapter;
    private int pagePos;

    public ObjectBoxBookRecordEntity() {
    }

    public ObjectBoxBookRecordEntity(String bookId, int chapter, int pagePos) {
        this.bookId = bookId;
        this.chapter = chapter;
        this.pagePos = pagePos;
    }

    public static ObjectBoxBookRecordEntity from(BookRecordBean record) {
        return new ObjectBoxBookRecordEntity(record.getBookId(), record.getChapter(), record.getPagePos());
    }

    public BookRecordBean toBookRecord() {
        return new BookRecordBean(bookId, chapter, pagePos);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
    }

    public int getChapter() {
        return chapter;
    }

    public void setChapter(int chapter) {
        this.chapter = chapter;
    }

    public int getPagePos() {
        return pagePos;
    }

    public void setPagePos(int pagePos) {
        this.pagePos = pagePos;
    }
}
