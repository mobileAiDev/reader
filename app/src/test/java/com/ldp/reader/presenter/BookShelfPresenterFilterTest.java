package com.ldp.reader.presenter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.model.bean.BookChapterBean;
import com.ldp.reader.model.bean.BookRecordBean;
import com.ldp.reader.model.bean.CollBookBean;
import com.ldp.reader.utils.Constant;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BookShelfPresenterFilterTest {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    @Test
    public void allFilterKeepsGenericToolbarLabel() {
        assertEquals("筛选", BookShelfPresenter.filterToolbarLabel(BookShelfPresenter.FilterKey.ALL));
        assertEquals("全部书籍", BookShelfPresenter.filterOptionLabel(BookShelfPresenter.FilterKey.ALL));
    }

    @Test
    public void localFilterUsesReaderVisibleLabel() {
        assertEquals("本地书", BookShelfPresenter.filterToolbarLabel(BookShelfPresenter.FilterKey.LOCAL));
        assertEquals("本地书", BookShelfPresenter.filterOptionLabel(BookShelfPresenter.FilterKey.LOCAL));
        assertEquals("本地文件", BookShelfPresenter.localFilterSectionLabel());
    }

    @Test
    public void readerStyleSectionsAreShownExceptUnsupportedPurchaseDiscount() {
        assertArrayEquals(
                new String[]{"全部书籍", "更新状态", "阅读进度", "本地文件"},
                BookShelfPresenter.filterSectionLabels()
        );
    }

    @Test
    public void onlySupportedShelfFiltersAreVisible() {
        assertArrayEquals(
                new BookShelfPresenter.FilterKey[]{
                        BookShelfPresenter.FilterKey.ALL,
                        BookShelfPresenter.FilterKey.UPDATED_3_DAYS,
                        BookShelfPresenter.FilterKey.UPDATED_7_DAYS,
                        BookShelfPresenter.FilterKey.UNREAD,
                        BookShelfPresenter.FilterKey.READING,
                        BookShelfPresenter.FilterKey.FINISHED,
                        BookShelfPresenter.FilterKey.LOCAL
                },
                BookShelfPresenter.visibleFilterKeys()
        );
    }

    @Test
    public void readerStyleOptionsMatchWithoutPurchaseDiscount() {
        assertArrayEquals(
                new String[]{"3日内更新", "7日内更新"},
                BookShelfPresenter.statusOptionLabels()
        );
        assertArrayEquals(
                new String[]{"尚未阅读", "正在阅读", "已读完"},
                BookShelfPresenter.progressOptionLabels()
        );
        assertEquals("3日内更新", BookShelfPresenter.filterToolbarLabel(BookShelfPresenter.FilterKey.UPDATED_3_DAYS));
        assertEquals("7日内更新", BookShelfPresenter.filterToolbarLabel(BookShelfPresenter.FilterKey.UPDATED_7_DAYS));
        assertEquals("尚未阅读", BookShelfPresenter.filterToolbarLabel(BookShelfPresenter.FilterKey.UNREAD));
        assertEquals("正在阅读", BookShelfPresenter.filterToolbarLabel(BookShelfPresenter.FilterKey.READING));
        assertEquals("已读完", BookShelfPresenter.filterToolbarLabel(BookShelfPresenter.FilterKey.FINISHED));
    }

    @Test
    public void updateFiltersUseBookUpdatedTime() {
        long now = 1_775_000_000_000L;
        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.UPDATED_3_DAYS,
                book(false, date(now - 2L * DAY_MS), "第2章", 10),
                null,
                -1,
                now
        ));
        assertFalse(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.UPDATED_3_DAYS,
                book(false, date(now - 4L * DAY_MS), "第2章", 10),
                null,
                -1,
                now
        ));
        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.UPDATED_7_DAYS,
                book(false, date(now - 6L * DAY_MS), "第2章", 10),
                null,
                -1,
                now
        ));
    }

    @Test
    public void progressFiltersUseStoredOrRecordProgress() {
        long now = 1_775_000_000_000L;
        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.UNREAD,
                book(false, date(now), "开始阅读", 10),
                null,
                -1,
                now
        ));
        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.READING,
                book(false, date(now), "第2章", 10),
                new BookRecordBean("id", 1, 0),
                -1,
                now
        ));
        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book(true, date(now), "正文", 1),
                null,
                999,
                now
        ));
        assertFalse(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book(true, date(now), "正文", 1),
                null,
                998,
                now
        ));
    }

    @Test
    public void finishedFilterAcceptsOldRecordOnFinalChapter() {
        long now = 1_775_000_000_000L;
        CollBookBean book = book(false, date(now), "第10章", 10);

        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book,
                new BookRecordBean("id", 9, 1),
                -1,
                now
        ));
        assertFalse(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.READING,
                book,
                new BookRecordBean("id", 9, 1),
                -1,
                now
        ));
        assertFalse(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book,
                new BookRecordBean("id", 9, 0),
                -1,
                now
        ));
    }

    @Test
    public void finishedFilterUsesRealChapterListWhenStoredChapterCountIsStale() {
        long now = 1_775_000_000_000L;
        CollBookBean book = book(false, date(now), "第500章", 100);
        book.setBookChapters(chapters(500));

        assertFalse(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book,
                new BookRecordBean("id", 120, 1),
                -1,
                now
        ));
        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book,
                new BookRecordBean("id", 499, 1),
                -1,
                now
        ));
    }

    @Test
    public void finishedFilterTreatsFilteredReadableFolderEndAsFinished() {
        long now = 1_775_000_000_000L;
        CollBookBean book = book(false, date(now), "第521章", 100);
        book.setBookChapters(chapters(537));

        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book,
                new BookRecordBean("id", 536, 49),
                -1,
                now
        ));
    }

    @Test
    public void finishedFilterTrustsStoredReadableCountOverSmallStaleRelationTail() {
        long now = 1_775_000_000_000L;
        CollBookBean book = book(false, date(now), "第521章", 537);
        book.setBookChapters(chapters(539));

        assertTrue(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book,
                new BookRecordBean("id", 536, 39),
                -1,
                now
        ));
        assertFalse(BookShelfPresenter.matchesFilter(
                BookShelfPresenter.FilterKey.FINISHED,
                book,
                new BookRecordBean("id", 535, 39),
                -1,
                now
        ));
    }

    @Test
    public void filterEmptyStateOnlyAppearsWhenAnActiveFilterHasNoVisibleBooks() {
        assertFalse(BookShelfPresenter.shouldShowFilterEmpty(BookShelfPresenter.FilterKey.ALL, 0));
        assertFalse(BookShelfPresenter.shouldShowFilterEmpty(BookShelfPresenter.FilterKey.ALL, 3));
        assertFalse(BookShelfPresenter.shouldShowFilterEmpty(BookShelfPresenter.FilterKey.LOCAL, 1));
        assertTrue(BookShelfPresenter.shouldShowFilterEmpty(BookShelfPresenter.FilterKey.FINISHED, 0));
        assertTrue(BookShelfPresenter.shouldShowFilterEmpty(BookShelfPresenter.FilterKey.LOCAL, 0));
    }

    @Test
    public void emptyStateCopyMatchesShelfAndFilterActions() {
        assertEquals("重拾阅读习惯，从添加一本书开始", BookShelfPresenter.emptyShelfTitle());
        assertEquals("重拾阅读习惯，从添加一本书开始",
                BookShelfPresenter.filterEmptyTitle(BookShelfPresenter.FilterKey.FINISHED));
        assertEquals("全部书籍", BookShelfPresenter.filterEmptyResetText());
        assertEquals("去导入", BookShelfPresenter.emptyImportText());
    }

    @Test
    public void newChapterUpdateDoesNotUseDeviceVibration() throws IOException {
        String presenter = readFile("src/main/java/com/ldp/reader/presenter/BookShelfPresenter.java");

        assertFalse(presenter.contains("android.os.Vibrator"));
        assertFalse(presenter.contains("VIBRATOR_SERVICE"));
        assertFalse(presenter.contains(".vibrate("));
    }

    private static CollBookBean book(boolean local, String updated, String lastChapter, int chaptersCount) {
        CollBookBean book = new CollBookBean();
        book.set_id("id");
        book.setLocal(local);
        book.setUpdated(updated);
        book.setLastChapter(lastChapter);
        book.setChaptersCount(chaptersCount);
        return book;
    }

    private static String date(long millis) {
        return new SimpleDateFormat(Constant.FORMAT_BOOK_DATE, Locale.CHINA).format(new Date(millis));
    }

    private static List<BookChapterBean> chapters(int count) {
        List<BookChapterBean> chapters = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BookChapterBean chapter = new BookChapterBean();
            chapter.setId("chapter-" + i);
            chapter.setTitle("第" + (i + 1) + "章");
            chapters.add(chapter);
        }
        return chapters;
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
