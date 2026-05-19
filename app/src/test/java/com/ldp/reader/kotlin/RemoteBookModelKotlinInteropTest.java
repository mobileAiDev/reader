package com.ldp.reader.kotlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.model.bean.BookDetailBeanInOwn;
import com.ldp.reader.model.bean.BookSearchResult;
import com.ldp.reader.model.bean.CollBookBean;

import org.junit.Test;

public class RemoteBookModelKotlinInteropTest {

    @Test
    public void searchResultKeepsJavaIdentityContract() {
        BookSearchResult first = new BookSearchResult();
        first.setTitle("title");
        first.setAuthor("author");

        BookSearchResult second = new BookSearchResult();
        second.setTitle("title");
        second.setAuthor("author");

        assertEquals(first.getId(), second.getId());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void searchResultEqualsStillExposesMissingCurrentTitle() {
        BookSearchResult first = new BookSearchResult();
        first.setAuthor("author");
        BookSearchResult second = new BookSearchResult();
        second.setTitle("title");
        second.setAuthor("author");

        try {
            first.equals(second);
            assertTrue("Missing current title should still fail loudly", false);
        } catch (NullPointerException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void bookDetailKeepsCollBookCreationContract() {
        BookDetailBeanInOwn detail = new BookDetailBeanInOwn();
        detail.setBookId(12);
        detail.setTitle("title");
        detail.setAuthor("author");
        detail.setDesc("desc");
        detail.setCover("cover");
        detail.setLastChapter("last");

        CollBookBean collBook = detail.getCollBookBean();

        assertSame(collBook, detail.getCollBookBean());
        assertEquals("12", collBook.get_id());
        assertEquals("title", collBook.getTitle());
        assertEquals("author", collBook.getAuthor());
        assertEquals("desc", collBook.getShortIntro());
        assertEquals("cover", collBook.getCover());
        assertEquals("连载中", collBook.getBookStatus());
        assertEquals(100, collBook.getChaptersCount());
        assertEquals("last", collBook.getLastChapter());
    }

    @Test
    public void sourceEngineBookDetailSeparatesShelfIdFromReadableRouteId() {
        BookDetailBeanInOwn detail = new BookDetailBeanInOwn();
        detail.setBookId(12);
        detail.setRouteId("source_engine_book_readable_route");
        detail.setShelfBookId("source_engine_shelf_stable");
        detail.setTitle("斗破苍穹");
        detail.setAuthor("天蚕土豆");

        CollBookBean collBook = detail.getCollBookBean();

        assertEquals("source_engine_shelf_stable", collBook.get_id());
        assertEquals("source_engine_book_readable_route", collBook.getBookIdInBiquge());
    }
}
