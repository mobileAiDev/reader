package com.ldp.reader.model.objectbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.ldp.reader.model.bean.BookChapterBean;
import com.ldp.reader.model.bean.CollBookBean;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import io.objectbox.BoxStore;

public class ObjectBoxBookStoreTest {
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
    public void saveReadOrderReplaceAndDeleteBooksAndChapters() throws IOException {
        boxStore = MyObjectBox.builder()
                .directory(temporaryFolder.newFolder("objectbox-books"))
                .build();
        ObjectBoxBookStore store = new ObjectBoxBookStore(boxStore);

        CollBookBean older = book("book-old", "Old", "2026-05-01");
        CollBookBean newer = book("book-new", "New", "2026-05-16");

        store.saveCollBooks(Arrays.asList(older, newer));
        store.replaceBookChapters("book-new", Arrays.asList(
                chapter("chapter-2", "book-new", 20),
                chapter("chapter-1", "book-new", 10)
        ));

        List<CollBookBean> books = store.getCollBooks();

        assertEquals("book-new", books.get(0).get_id());
        assertEquals("book-old", books.get(1).get_id());
        assertNull(books.get(0).getBookChapters());

        CollBookBean hydrated = store.getCollBook("book-new");
        assertEquals(2, hydrated.getBookChapters().size());
        assertEquals("chapter-1", hydrated.getBookChapters().get(0).getId());
        assertEquals("chapter-2", hydrated.getBookChapters().get(1).getId());

        store.replaceBookChapters("book-new", Arrays.asList(chapter("chapter-3", "book-new", 30)));

        List<BookChapterBean> replaced = store.getBookChapters("book-new");
        assertEquals(1, replaced.size());
        assertEquals("chapter-3", replaced.get(0).getId());

        store.deleteBookChapters("book-new");
        assertEquals(0, store.getBookChapters("book-new").size());

        store.deleteCollBook(newer);
        assertNull(store.getCollBook("book-new"));
    }

    private static CollBookBean book(String id, String title, String lastRead) {
        CollBookBean book = new CollBookBean();
        book.set_id(id);
        book.setTitle(title);
        book.setAuthor("author");
        book.setShortIntro("intro");
        book.setCover("cover");
        book.setBookStatus("status");
        book.setUpdated("updated");
        book.setLastRead(lastRead);
        book.setChaptersCount(1);
        book.setLastChapter("last");
        book.setIsUpdate(false);
        book.setIsLocal(false);
        book.setBookIdInBiquge("biquge-" + id);
        return book;
    }

    private static BookChapterBean chapter(String id, String bookId, long start) {
        return new BookChapterBean(id, "link-" + id, "title-" + id, null, false, true, bookId, start, start + 5);
    }
}
