package com.ldp.reader.model.objectbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.model.bean.BookRecordBean;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ObjectBoxBookRecordEntityTest {

    @Test
    public void bookRecordUsesObjectBoxLongIdAndBusinessBookId() throws IOException {
        String entity = readFile("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookRecordEntity.java");

        assertTrue(entity.contains("@Entity"));
        assertTrue(entity.contains("@Id"));
        assertTrue(entity.contains("private long id;"));
        assertTrue(entity.contains("@Index"));
        assertTrue(entity.contains("private String bookId;"));
    }

    @Test
    public void mapperKeepsCurrentBookRecordFields() {
        BookRecordBean record = new BookRecordBean("book-1", 12, 34);

        ObjectBoxBookRecordEntity entity = ObjectBoxBookRecordEntity.from(record);
        BookRecordBean restored = entity.toBookRecord();

        assertEquals("book-1", entity.getBookId());
        assertEquals(12, entity.getChapter());
        assertEquals(34, entity.getPagePos());
        assertEquals("book-1", restored.getBookId());
        assertEquals(12, restored.getChapter());
        assertEquals(34, restored.getPagePos());
    }

    @Test
    public void productionStoreKeepsBookRecordReadWriteDeleteContract() throws IOException {
        String store = readFile("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookRecordStore.java");

        assertTrue(store.contains("boxStore.boxFor(ObjectBoxBookRecordEntity.class)"));
        assertTrue(store.contains("ObjectBoxBookRecordEntity_.bookId.equal(bookId)"));
        assertTrue(store.contains("entity.setChapter(record.getChapter())"));
        assertTrue(store.contains("entity.setPagePos(record.getPagePos())"));
        assertTrue(store.contains("recordBox.put(entity)"));
        assertTrue(store.contains("recordBox.remove(entity)"));
    }

    @Test
    public void productionObjectBoxStoreIsAppContextBacked() throws IOException {
        String helper = readFile("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxDbHelper.java");

        assertTrue(helper.contains("MyObjectBox.builder()"));
        assertTrue(helper.contains(".androidContext(App.getContext())"));
        assertTrue(helper.contains(".build()"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
