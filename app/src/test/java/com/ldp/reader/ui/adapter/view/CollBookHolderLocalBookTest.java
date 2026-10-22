package com.ldp.reader.ui.adapter.view;

import static org.junit.Assert.assertEquals;

import com.ldp.reader.model.bean.BookRecordBean;
import com.ldp.reader.model.bean.CollBookBean;
import com.ldp.reader.widget.page.PageLoader;

import org.junit.Test;

public class CollBookHolderLocalBookTest {

    @Test
    public void coverTitleFallsBackWhenFileNameIsEmpty() {
        assertEquals("本地书", CollBookHolder.coverTitle("  "));
        assertEquals("codex-local-import-probe", CollBookHolder.coverTitle(" codex-local-import-probe "));
    }

    @Test
    public void fileTypeUsesUppercaseExtensionWithReaderStyleDashes() {
        assertEquals("TXT", CollBookHolder.fileTypeLabel("/sdcard/Download/a.txt"));
        assertEquals("PDF", CollBookHolder.fileTypeLabel("/sdcard/Download/a.PDF"));
        assertEquals("TXT", CollBookHolder.fileTypeLabel(""));
    }

    @Test
    public void unreadLocalBookShowsUnreadState() {
        CollBookBean book = new CollBookBean();
        book.setLocal(true);
        book.setLastChapter("开始阅读");

        assertEquals("未读", CollBookHolder.progressLabel(book, null));
    }

    @Test
    public void storedLocalProgressShowsPercentWithOneDecimalWhenUseful() {
        assertEquals("已读4.4%", CollBookHolder.progressLabel(localBook(), null, 44));
        assertEquals("已读20%", CollBookHolder.progressLabel(localBook(), null, 200));
    }

    @Test
    public void localBookWithOnlyOldPageRecordStillShowsApproximatePercent() {
        CollBookBean book = localBook();
        BookRecordBean record = new BookRecordBean();
        record.setChapter(0);
        record.setPagePos(3);

        assertEquals("已读0.1%", CollBookHolder.progressLabel(book, record));
    }

    @Test
    public void localProgressCalculationTreatsLastTwoPagesAsNearlyFinished() {
        assertEquals(44, PageLoader.calculateProgressTenths(1, 0, 3, 90));
        assertEquals(999, PageLoader.calculateProgressTenths(10, 9, 18, 20));
        assertEquals(999, PageLoader.calculateProgressTenths(10, 9, 19, 20));
        assertEquals(905, PageLoader.calculateProgressTenths(10, 9, 0, 20));
    }

    private static CollBookBean localBook() {
        CollBookBean book = new CollBookBean();
        book.setLocal(true);
        book.setLastChapter("第1章");
        return book;
    }
}
