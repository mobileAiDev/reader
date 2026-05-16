package com.ldp.reader.model.objectbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.ldp.reader.model.bean.BookRecordBean;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import io.objectbox.BoxStore;

public class ObjectBoxBookRecordStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private BoxStore boxStore;

    @After
    public void tearDown() {
        if (boxStore != null) {
            boxStore.close();
        }
    }

    @Test
    public void saveReadUpdateAndDeleteBookRecordByBusinessBookId() throws IOException {
        boxStore = MyObjectBox.builder()
                .directory(temporaryFolder.newFolder("objectbox"))
                .build();
        ObjectBoxBookRecordStore recordStore = new ObjectBoxBookRecordStore(boxStore);

        recordStore.saveBookRecord(new BookRecordBean("book-1", 3, 18));
        BookRecordBean saved = recordStore.getBookRecord("book-1");

        assertEquals("book-1", saved.getBookId());
        assertEquals(3, saved.getChapter());
        assertEquals(18, saved.getPagePos());

        recordStore.saveBookRecord(new BookRecordBean("book-1", 4, 2));
        BookRecordBean updated = recordStore.getBookRecord("book-1");

        assertEquals("book-1", updated.getBookId());
        assertEquals(4, updated.getChapter());
        assertEquals(2, updated.getPagePos());

        recordStore.deleteBookRecord("book-1");

        assertNull(recordStore.getBookRecord("book-1"));
    }
}
