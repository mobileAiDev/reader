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
        String entity = readFile("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookRecordEntity.kt");

        assertTrue(entity.contains("@Entity"));
        assertTrue(entity.contains("@Id"));
        assertTrue(entity.contains("var id: Long"));
        assertTrue(entity.contains("@Index"));
        assertTrue(entity.contains("var bookId: String?"));
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
        String store = readFile("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookRecordStore.kt");

        assertTrue(store.contains("boxStore.boxFor(ObjectBoxBookRecordEntity::class.java)"));
        assertTrue(store.contains("ObjectBoxBookRecordEntity_.bookId.equal(bookId)"));
        assertTrue(store.contains("entity.chapter = record.chapter"));
        assertTrue(store.contains("entity.pagePos = record.pagePos"));
        assertTrue(store.contains("recordBox.put(entity)"));
        assertTrue(store.contains("recordBox.remove(entity)"));
    }

    @Test
    public void productionObjectBoxStoreIsAppContextBacked() throws IOException {
        String helper = readFile("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxDbHelper.kt");

        assertTrue(helper.contains("MyObjectBox.builder()"));
        assertTrue(helper.contains(".androidContext(App.getContext())"));
        assertTrue(helper.contains(".build()"));
    }

    @Test
    public void bookshelfAndChapterEntitiesUseObjectBoxIdsAndBusinessKeys() throws IOException {
        String collBook = readFile("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxCollBookEntity.kt");
        String chapter = readFile("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookChapterEntity.kt");

        assertTrue(collBook.contains("@Entity"));
        assertTrue(collBook.contains("@Id"));
        assertTrue(collBook.contains("var objectBoxId: Long"));
        assertTrue(collBook.contains("@Index"));
        assertTrue(collBook.contains("var bookId: String?"));

        assertTrue(chapter.contains("@Entity"));
        assertTrue(chapter.contains("@Id"));
        assertTrue(chapter.contains("var objectBoxId: Long"));
        assertTrue(chapter.contains("var chapterBusinessId: String?"));
        assertTrue(chapter.contains("var bookId: String?"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
