package com.ldp.reader.model.local;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class BookRepositoryStorageContractTest {

    @Test
    public void repositoryKeepsBookshelfChapterAndRecordStorageSemantics() throws IOException {
        String repository = readFile("src/main/java/com/ldp/reader/model/local/BookRepository.java");

        assertInOrder(repository,
                "public  List<CollBookBean> getCollBooks()",
                ".orderDesc(CollBookBeanDao.Properties.LastRead)",
                ".list()");

        assertInOrder(repository,
                "public Single<List<BookChapterBean>> getBookChaptersInRx(String bookId)",
                ".where(BookChapterBeanDao.Properties.BookId.eq(bookId))",
                ".orderAsc(BookChapterBeanDao.Properties.Start)",
                ".list()");

        assertInOrder(repository,
                "public void saveBookRecord(BookRecordBean bean)",
                "mBookRecordStore.saveBookRecord(bean)");

        assertInOrder(repository,
                "public BookRecordBean getBookRecord(String bookId)",
                "return mBookRecordStore.getBookRecord(bookId)");

        assertInOrder(repository,
                "public void deleteBookRecord(String id)",
                "mBookRecordStore.deleteBookRecord(id)");

        assertInOrder(repository,
                "private void replaceBookChaptersInTx(String bookId, List<BookChapterBean> beans)",
                "deleteBookChapterInTx(bookId);",
                ".insertOrReplaceInTx(beans)");
    }

    @Test
    public void persistedEntitiesKeepCurrentBusinessIdsAndRelationFields() throws IOException {
        String collBook = readFile("src/main/java/com/ldp/reader/model/bean/CollBookBean.java");
        String chapter = readFile("src/main/java/com/ldp/reader/model/bean/BookChapterBean.java");
        String record = readFile("src/main/java/com/ldp/reader/model/bean/BookRecordBean.java");

        assertInOrder(collBook,
                "@Id",
                "private String _id",
                "@ToMany(referencedJoinProperty = \"bookId\")",
                "private List<BookChapterBean> bookChapterList");

        assertInOrder(chapter,
                "@Id",
                "private String id",
                "@Index",
                "private String bookId",
                "private long start",
                "private long end");

        assertTrue("BookRecordBean should no longer be a GreenDAO entity",
                !record.contains("org.greenrobot.greendao.annotation"));
        assertInOrder(record,
                "private String bookId",
                "private int chapter",
                "private int pagePos");
        assertTrue("BookRecordBeanDao should not remain after BookRecord moves to ObjectBox",
                !new File("src/main/java/com/ldp/reader/model/gen/BookRecordBeanDao.java").exists());
    }

    private static void assertInOrder(String source, String... parts) {
        int cursor = 0;
        for (String part : parts) {
            int index = source.indexOf(part, cursor);
            assertTrue("Missing or out of order: " + part, index >= 0);
            cursor = index + part.length();
        }
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
