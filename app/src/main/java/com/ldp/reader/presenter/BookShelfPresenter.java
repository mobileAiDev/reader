package com.ldp.reader.presenter;

import android.util.Log;

import com.ldp.reader.App;
import com.ldp.reader.RxBus;
import com.ldp.reader.model.bean.BookChapterBean;
import com.ldp.reader.model.bean.BookDetailBeanInOwn;
import com.ldp.reader.model.bean.BookIdBean;
import com.ldp.reader.model.bean.BookRecordBean;
import com.ldp.reader.model.bean.ChapterBean;
import com.ldp.reader.model.bean.CollBookBean;
import com.ldp.reader.model.bean.DirectSycBookShelfBean;
import com.ldp.reader.model.local.BookRepository;
import com.ldp.reader.model.remote.RemoteRepository;
import com.ldp.reader.presenter.contract.BookShelfContract;
import com.ldp.reader.ui.base.RxPresenter;
import com.ldp.reader.utils.Constant;
import com.ldp.reader.utils.LogUtils;
import com.ldp.reader.utils.MD5Utils;
import com.ldp.reader.utils.RxUtils;
import com.ldp.reader.utils.SharedPreUtils;
import com.ldp.reader.utils.StringUtils;
import com.google.gson.Gson;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/**
 * Created by ldp on 17-5-8.
 */

public class BookShelfPresenter extends RxPresenter<BookShelfContract.View>
        implements BookShelfContract.Presenter<BookShelfContract.View> {
    private static final String TAG = "BookShelfPresenter";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final int FINISHED_PROGRESS_TENTHS = 999;
    private static final int STALE_RELATION_TAIL_TOLERANCE = 3;
    private static final String START_READING = "开始阅读";
    private static final FilterKey[] VISIBLE_FILTER_KEYS = {
            FilterKey.ALL,
            FilterKey.UPDATED_3_DAYS,
            FilterKey.UPDATED_7_DAYS,
            FilterKey.UNREAD,
            FilterKey.READING,
            FilterKey.FINISHED,
            FilterKey.LOCAL
    };
    private static final String[] FILTER_SECTION_LABELS = {"全部书籍", "更新状态", "阅读进度", "本地文件"};
    private static final String[] STATUS_OPTION_LABELS = {"3日内更新", "7日内更新"};
    private static final String[] PROGRESS_OPTION_LABELS = {"尚未阅读", "正在阅读", "已读完"};

    public enum FilterKey {
        ALL,
        UPDATED_3_DAYS,
        UPDATED_7_DAYS,
        UNREAD,
        READING,
        FINISHED,
        LOCAL
    }

    private enum ProgressState {
        UNREAD,
        READING,
        FINISHED
    }

    public static FilterKey[] visibleFilterKeys() {
        return VISIBLE_FILTER_KEYS.clone();
    }

    public static String[] filterSectionLabels() {
        return FILTER_SECTION_LABELS.clone();
    }

    public static String[] statusOptionLabels() {
        return STATUS_OPTION_LABELS.clone();
    }

    public static String[] progressOptionLabels() {
        return PROGRESS_OPTION_LABELS.clone();
    }

    public static String filterToolbarLabel(FilterKey key) {
        if (key == null || key == FilterKey.ALL) {
            return "筛选";
        }
        return filterOptionLabel(key);
    }

    public static String filterOptionLabel(FilterKey key) {
        if (key == null) {
            return "全部书籍";
        }
        switch (key) {
            case UPDATED_3_DAYS:
                return "3日内更新";
            case UPDATED_7_DAYS:
                return "7日内更新";
            case UNREAD:
                return "尚未阅读";
            case READING:
                return "正在阅读";
            case FINISHED:
                return "已读完";
            case LOCAL:
                return "本地书";
            case ALL:
            default:
                return "全部书籍";
        }
    }

    public static String localFilterSectionLabel() {
        return "本地文件";
    }

    public static boolean matchesFilter(
            FilterKey key,
            CollBookBean book,
            BookRecordBean record,
            int storedProgressTenths,
            long nowMillis
    ) {
        if (book == null) {
            return false;
        }
        if (key == null || key == FilterKey.ALL) {
            return true;
        }
        switch (key) {
            case UPDATED_3_DAYS:
                return isUpdatedWithin(book, 3, nowMillis);
            case UPDATED_7_DAYS:
                return isUpdatedWithin(book, 7, nowMillis);
            case UNREAD:
                return progressState(book, record, storedProgressTenths) == ProgressState.UNREAD;
            case READING:
                return progressState(book, record, storedProgressTenths) == ProgressState.READING;
            case FINISHED:
                return progressState(book, record, storedProgressTenths) == ProgressState.FINISHED;
            case LOCAL:
                return book.isLocal();
            default:
                return true;
        }
    }

    public static boolean shouldShowFilterEmpty(FilterKey key, int visibleBookCount) {
        return key != null && key != FilterKey.ALL && visibleBookCount == 0;
    }

    public static String emptyShelfTitle() {
        return "重拾阅读习惯，从添加一本书开始";
    }

    public static String filterEmptyTitle(FilterKey key) {
        return emptyShelfTitle();
    }

    public static String filterEmptyResetText() {
        return "全部书籍";
    }

    public static String emptyImportText() {
        return "去导入";
    }

    public static List<String> onlineBookIdsFrom(List<CollBookBean> books) {
        if (books == null || books.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (CollBookBean book : books) {
            if (book == null || book.isLocal()) {
                continue;
            }
            String id = book.get_id();
            if (isServerBookId(id)) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    public static List<Long> onlineBookLongIdsFrom(List<CollBookBean> books) {
        List<String> ids = onlineBookIdsFrom(books);
        List<Long> longIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            longIds.add(Long.valueOf(id));
        }
        return longIds;
    }

    public static List<String> mergeServerAndLocalOnlineIds(
            Collection<Long> serverBookIds,
            List<CollBookBean> localBooks
    ) {
        Set<String> ids = new LinkedHashSet<>();
        if (serverBookIds != null) {
            for (Long serverBookId : serverBookIds) {
                if (serverBookId != null && serverBookId != 0L) {
                    ids.add(String.valueOf(serverBookId));
                }
            }
        }
        ids.addAll(onlineBookIdsFrom(localBooks));
        return new ArrayList<>(ids);
    }

    public static List<String> normalizeServerBookIds(Collection<String> bookIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (bookIds == null) {
            return new ArrayList<>();
        }
        for (String bookId : bookIds) {
            if (isServerBookId(bookId)) {
                ids.add(bookId);
            }
        }
        return new ArrayList<>(ids);
    }

    private static boolean isUpdatedWithin(CollBookBean book, int days, long nowMillis) {
        long updatedMillis = parseBookDateMillis(book.getUpdated());
        if (updatedMillis <= 0L) {
            return false;
        }
        long diffMillis = Math.max(0L, nowMillis - updatedMillis);
        return diffMillis <= days * DAY_MS;
    }

    private static ProgressState progressState(
            CollBookBean book,
            BookRecordBean record,
            int storedProgressTenths
    ) {
        if (storedProgressTenths >= FINISHED_PROGRESS_TENTHS) {
            return ProgressState.FINISHED;
        }
        if (storedProgressTenths > 0) {
            return ProgressState.READING;
        }
        if (isFinishedByRecord(book, record)) {
            return ProgressState.FINISHED;
        }
        if (record != null && (record.getChapter() > 0 || record.getPagePos() > 0)) {
            return ProgressState.READING;
        }
        if (book.isLocal()) {
            String lastChapter = book.getLastChapter();
            if (lastChapter != null
                    && lastChapter.trim().length() > 0
                    && !START_READING.equals(lastChapter.trim())) {
                return ProgressState.READING;
            }
        }
        return ProgressState.UNREAD;
    }

    private static boolean isFinishedByRecord(CollBookBean book, BookRecordBean record) {
        if (book == null || record == null || record.getPagePos() <= 0) {
            return false;
        }
        int chapterCount = resolveChapterCount(book, record);
        if (chapterCount <= 0) {
            return false;
        }
        return record.getChapter() >= chapterCount - 1;
    }

    private static int resolveChapterCount(CollBookBean book, BookRecordBean record) {
        int storedChapterCount = Math.max(0, book.getChaptersCount());
        int relationChapterCount = 0;
        try {
            List<?> chapters = book.getBookChapters();
            if (chapters != null) {
                relationChapterCount = chapters.size();
            }
        } catch (RuntimeException ignored) {
        }
        if (storedChapterCount > 0
                && relationChapterCount > storedChapterCount
                && relationChapterCount - storedChapterCount <= STALE_RELATION_TAIL_TOLERANCE
                && record != null
                && record.getChapter() >= storedChapterCount - 1) {
            return storedChapterCount;
        }
        return Math.max(storedChapterCount, relationChapterCount);
    }

    private static long parseBookDateMillis(String source) {
        if (source == null || source.trim().length() == 0) {
            return 0L;
        }
        String value = source.trim();
        try {
            long numeric = Long.parseLong(value);
            if (numeric > 0L && numeric < 10_000_000_000L) {
                return numeric * 1000L;
            }
            return numeric;
        } catch (NumberFormatException ignored) {
        }
        long standardMillis = parseWithFormat(value, new SimpleDateFormat(Constant.FORMAT_BOOK_DATE, Locale.CHINA));
        if (standardMillis > 0L) {
            return standardMillis;
        }
        SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.CHINA);
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return parseWithFormat(value, utcFormat);
    }

    private static long parseWithFormat(String value, DateFormat format) {
        try {
            Date date = format.parse(value);
            return date == null ? 0L : date.getTime();
        } catch (ParseException ignored) {
        }
        return 0L;
    }

    private static boolean isServerBookId(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        int start = id.charAt(0) == '-' ? 1 : 0;
        if (start == id.length()) {
            return false;
        }
        for (int index = start; index < id.length(); index++) {
            if (!Character.isDigit(id.charAt(index))) {
                return false;
            }
        }
        try {
            return Long.parseLong(id) != 0L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void refreshCollBooks() {
        List<CollBookBean> collBooks = BookRepository
                .getInstance().getCollBooks();
        for (CollBookBean bookBean : collBooks) {
            Log.d("+书名", bookBean.getTitle());
        }
        mView.finishRefresh(collBooks);
    }


    @Deprecated
    @Override
    public void getBookShelf(String token) {
        Disposable disposable = RemoteRepository.getInstance()
                .getBookShelf(token)
                .compose(RxUtils::toSimpleSingle)
                .subscribe(
                        bookIdBeans -> {
                            List<String> bookIdList = new ArrayList<>();
                            if (bookIdBeans != null) {
                                for (BookIdBean bookIdBean : bookIdBeans) {
                                    if (bookIdBean != null) {
                                        bookIdList.add(bookIdBean.getBookId() + "");
                                    }
                                }
                            }
                            getBookInfo(bookIdList);
                        },
                        e -> {
                            //提示没有网络
                            LogUtils.e(e);
                            mView.showErrorTip(e.toString());
                            mView.complete();
                        }
                );
        addDisposable(disposable);
    }

    @Override
    public void getBookShelfByMobile(String mobile, String mobileToken) {
        Disposable disposable = RemoteRepository.getInstance()
                .getBookShelfByMobile(mobile, mobileToken)
                .compose(RxUtils::toSimpleSingle)
                .subscribe(
                        bookIdBeans -> {
                            List<Long> bookIdList = new ArrayList<>();
                            if (bookIdBeans != null) {
                                for (BookIdBean bookIdBean : bookIdBeans) {
                                    if (bookIdBean != null && bookIdBean.getBookId() != 0) {
                                        bookIdList.add((long) bookIdBean.getBookId());
                                    }
                                }
                            }
                            getBookInfoBatch(bookIdList);
                        },
                        e -> {
                            //提示没有网络
                            LogUtils.e(e);
                            mView.showErrorTip(e.toString());
                            mView.complete();
                        }
                );
        addDisposable(disposable);
    }

    @Deprecated
    public void getBookInfo(List<String> bookIdList) {
        if (bookIdList == null || bookIdList.isEmpty()) {
            List<CollBookBean> collBooks = BookRepository.getInstance().getCollBooks();
            List<String> bookIds = onlineBookIdsFrom(collBooks);
            if ("password".equals(SharedPreUtils.getInstance().getString("loginType"))) {
                setBookShelf(bookIds);
            } else {
                String mobile = SharedPreUtils.getInstance().getString("userName");
                String mobileToken = SharedPreUtils.getInstance().getString("token");
                setBookShelfByMobile(bookIds, mobile, mobileToken);
            }
            return;
        }
        List<Single<BookDetailBeanInOwn>> bookDetailSingleList = new ArrayList<>();
        for (String bookId : bookIdList) {
            CollBookBean mCollBookBean = BookRepository.getInstance().getCollBook(bookId);
            if (null == mCollBookBean) {
                bookDetailSingleList.add(RemoteRepository.getInstance().getBookInfo(bookId));
            }
        }
        Disposable disposable = Single.concat(bookDetailSingleList)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::addToBookShelf, throwable -> mView.showErrorTip(throwable.getMessage()), () -> {
                    List<CollBookBean> collBooks = BookRepository.getInstance().getCollBooks();
                    List<String> bookIds = new ArrayList<>();
                    for (CollBookBean collBookBean : collBooks) {
                        bookIds.add(collBookBean.get_id());
                    }
                    if ("password".equals(SharedPreUtils.getInstance().getString("loginType"))) {
                        setBookShelf(bookIds);
                    } else {
                        String mobile = SharedPreUtils.getInstance().getString("userName");
                        String mobileToken = SharedPreUtils.getInstance().getString("token");
                        setBookShelfByMobile(bookIds, mobile, mobileToken);
                    }
                });
        addDisposable(disposable);
    }


    private void addToBookShelf(BookDetailBeanInOwn bookDetailBeanInOwn) {
        final CollBookBean collBookBean = bookDetailBeanInOwn.getCollBookBean();
        collBookBean.setUpdated(bookDetailBeanInOwn.getUpdateTime() + "");
        List<BookChapterBean> bookChapterBeans = new ArrayList<>();
        BookRepository.getInstance()
                .saveCollBookWithAsync(collBookBean);
        Disposable disposable = RemoteRepository.getInstance()
                .getBookFolder(collBookBean.get_id())
                .compose(RxUtils::toSimpleSingle)
                .subscribe(chapterBeans -> {
                    for (ChapterBean chapterBean : chapterBeans) {
                        BookChapterBean bookChapterBeanTemp = new BookChapterBean();
                        bookChapterBeanTemp.setLink(chapterBean.getChapterId() + "");
                        bookChapterBeanTemp.setTitle(chapterBean.getTitle());
                        bookChapterBeanTemp.setId(MD5Utils.strToMd5By16(bookChapterBeanTemp.getLink()));
                        bookChapterBeanTemp.setBookId(collBookBean.get_id());
                        bookChapterBeanTemp.setStart(bookChapterBeans.size());
                        bookChapterBeans.add(bookChapterBeanTemp);
                    }
                    collBookBean.setBookChapters(bookChapterBeans);
                    collBookBean.setChaptersCount(bookChapterBeans.size());
                    //存储收藏
                    BookRepository.getInstance().saveCollBookWithAsync(collBookBean);
                    CollBookBean collBookBeanResult = BookRepository.getInstance().getCollBook(collBookBean.get_id());
                    Log.d(TAG, "addToBookShelf:collBookBeanResult " + collBookBeanResult);

                }, throwable -> mView.showErrorTip(throwable.getMessage()));


        addDisposable(disposable);
    }


    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Override
    public void setBookShelf(List<String> bookIds) {
        List<String> syncBookIds = normalizeServerBookIds(bookIds);
        RequestBody body = RequestBody.create(JSON, new Gson().toJson(syncBookIds));
        String token = SharedPreUtils.getInstance().getString("token");
        Disposable disposable = RemoteRepository.getInstance().setBookShelf(token, body)
                .compose(RxUtils::toSimpleSingle)
                .subscribe(syncBookShelfBean -> {
                    mView.finishSyncBook();
                    mView.finishUpdate();
                }, throwable -> mView.showErrorTip(throwable.getMessage()));
        addDisposable(disposable);
    }

    @Override
    public void setBookShelfByMobile(List<String> bookIds, String mobile, String mobileToken) {
        DirectSycBookShelfBean directSycBookShelfBean = new DirectSycBookShelfBean();
        directSycBookShelfBean.setBookIds(normalizeServerBookIds(bookIds));
        directSycBookShelfBean.setMobile(mobile);
        directSycBookShelfBean.setMobileToken(mobileToken);
        RequestBody body = RequestBody.create(JSON, new Gson().toJson(directSycBookShelfBean));
        Disposable disposable = RemoteRepository.getInstance().setBookShelfByMobile(body)
                .compose(RxUtils::toSimpleSingle)
                .subscribe(syncBookShelfBean -> {
                    mView.finishSyncBook();
                    mView.finishUpdate();
                }, throwable -> mView.showErrorTip(throwable.getMessage()));
        addDisposable(disposable);
    }


    @Override
    public void updateCollBooks(List<CollBookBean> collBookBeans) {
        if (collBookBeans == null || collBookBeans.isEmpty()) {
            return;
        }
        List<Long> bookIdList = onlineBookLongIdsFrom(collBookBeans);
        updateBookInfoBatch(bookIdList);
    }

    private void updateBookInfoBatch(List<Long> bookIdList) {
        if (bookIdList == null || bookIdList.isEmpty()) {
            mView.complete();
            mView.finishUpdate();
            return;
        }
        List<CollBookBean> newCollBooksMerge = new ArrayList<>();
        RequestBody body = RequestBody.create(JSON, new Gson().toJson(bookIdList));
        Disposable disposable = RemoteRepository.getInstance().getBookInfoBatch(body)
                .compose(RxUtils::toSimpleSingle)
                .subscribe(bookDetailBeanInOwns -> {
                    for (BookDetailBeanInOwn bookDetailBeanInOwn : bookDetailBeanInOwns) {
                        CollBookBean oldCollBook = BookRepository.getInstance().getCollBook(String.valueOf(bookDetailBeanInOwn.getBookId()));
                        if (oldCollBook == null) {
                            continue;
                        }
                        boolean lastChapterChanged = null == oldCollBook.getLastChapter()
                                || !oldCollBook.getLastChapter().equals(bookDetailBeanInOwn.getLastChapter());
                        if (lastChapterChanged) {
                            updateBookInfo(bookDetailBeanInOwn, oldCollBook);
                            newCollBooksMerge.add(oldCollBook);
                        } else if (isReadableFolderStale(oldCollBook)) {
                            updateCategory(oldCollBook);
                        }
                        Log.d(TAG, "+检查更新");
                    }
                    BookRepository.getInstance().saveCollBooks(newCollBooksMerge);
                    mView.complete();
                    mView.finishUpdate();
                }, throwable -> {
                    mView.complete();
                    mView.showErrorTip(throwable.getMessage());
                });
        addDisposable(disposable);
    }

    private void getBookInfoBatch(List<Long> bookIdList) {
        if (bookIdList == null || bookIdList.isEmpty()) {
            updateShelf(new ArrayList<>());
            mView.complete();
            return;
        }
        RequestBody body = RequestBody.create(JSON, new Gson().toJson(bookIdList));
        Disposable disposable = RemoteRepository.getInstance().getBookInfoBatch(body)
                .compose(RxUtils::toSimpleSingle)
                .subscribe(bookDetailBeanInOwns -> {
                    for (BookDetailBeanInOwn bookDetailBeanInOwn : bookDetailBeanInOwns) {
                        addToBookShelf(bookDetailBeanInOwn);
                    }

                    updateShelf(bookIdList);
                }, throwable -> mView.showErrorTip(throwable.getMessage()));
        addDisposable(disposable);
    }

    /**
     * 更新书架,将未登录时的添加书籍同步给服务器
     */
    private void updateShelf(List<Long> bookIdList) {
        List<CollBookBean> collBooks = BookRepository.getInstance().getCollBooks();
        List<String> bookIds = mergeServerAndLocalOnlineIds(bookIdList, collBooks);

        String mobile = SharedPreUtils.getInstance().getString("userName");
        String mobileToken = SharedPreUtils.getInstance().getString("token");
        setBookShelfByMobile(bookIds, mobile, mobileToken);
    }

    private void updateBookInfo(BookDetailBeanInOwn bookDetailBeanInOwn, CollBookBean oldCollBook) {
        oldCollBook.setLastChapter(bookDetailBeanInOwn.getLastChapter());
        Log.d(TAG, "+更新书籍 " + oldCollBook.getTitle() + oldCollBook.getLastChapter());
        oldCollBook.setUpdate(true);
        updateCategory(oldCollBook);
        oldCollBook.setUpdated(bookDetailBeanInOwn.getUpdateTime() + "");
    }

    private boolean isReadableFolderStale(CollBookBean collBookBean) {
        if (collBookBean == null || collBookBean.isLocal()) {
            return false;
        }
        BookRecordBean record = BookRepository.getInstance().getBookRecord(collBookBean.get_id());
        if (record == null || record.getChapter() <= 0) {
            return false;
        }
        int chaptersCount = collBookBean.getChaptersCount();
        int storedChapterCount = Math.max(0, chaptersCount);
        int relationChapterCount = 0;
        try {
            if (collBookBean.getBookChapters() != null) {
                relationChapterCount = collBookBean.getBookChapters().size();
            }
        } catch (RuntimeException ignored) {
        }
        return storedChapterCount <= 0
                || record.getChapter() >= storedChapterCount
                || relationChapterCount > storedChapterCount;
    }


    //更新Book的目录
    private void updateCategory(CollBookBean collBookBean) {
        List<BookChapterBean> bookChapterBeans = new ArrayList<>();
        Log.d(TAG, "loadCategory: " + collBookBean.get_id() + "" + collBookBean);
        Disposable disposable = RemoteRepository.getInstance()
                .getBookFolder(collBookBean.get_id())
                .compose(RxUtils::toSimpleSingle)
                .subscribe(chapterBeans -> {
                    for (ChapterBean chapterBean : chapterBeans) {
                        BookChapterBean bookChapterBeanTemp = new BookChapterBean();
                        bookChapterBeanTemp.setLink(chapterBean.getChapterId() + "");
                        bookChapterBeanTemp.setTitle(chapterBean.getTitle());
                        bookChapterBeanTemp.setId(MD5Utils.strToMd5By16(bookChapterBeanTemp.getLink()));
                        Log.d(TAG, "+章节名  " + chapterBean.getTitle());
                        bookChapterBeanTemp.setBookId(collBookBean.get_id());
                        bookChapterBeanTemp.setStart(bookChapterBeans.size());
                        bookChapterBeans.add(bookChapterBeanTemp);
                    }
                    collBookBean.setBookChapters(bookChapterBeans);
                    collBookBean.setChaptersCount(bookChapterBeans.size());
                    Log.d(TAG, "accept: " + bookChapterBeans);
                    //存储收藏
                    BookRepository.getInstance()
                            .saveCollBookWithAsync(collBookBean);

                }, Throwable::printStackTrace);
        addDisposable(disposable);
    }

}
