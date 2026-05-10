package com.ldp.reader.presenter;

import android.util.Log;

import com.google.gson.Gson;
import com.ldp.reader.model.bean.BookChapterBean;
import com.ldp.reader.model.bean.BookDetailBeanInOwn;
import com.ldp.reader.model.bean.ChapterBean;
import com.ldp.reader.model.bean.CollBookBean;
import com.ldp.reader.model.bean.DirectSycBookShelfBean;
import com.ldp.reader.model.bean.SyncBookShelfBean;
import com.ldp.reader.model.local.BookRepository;
import com.ldp.reader.model.remote.RemoteRepository;
import com.ldp.reader.presenter.contract.BookDetailContract;
import com.ldp.reader.ui.base.BaseContract;
import com.ldp.reader.ui.base.RxPresenter;
import com.ldp.reader.utils.MD5Utils;
import com.ldp.reader.utils.RxUtils;
import com.ldp.reader.utils.SharedPreUtils;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/**
 * Created by ldp on 17-5-4.
 */

public class BookDetailPresenter extends RxPresenter<BookDetailContract.View>
        implements BookDetailContract.Presenter<BookDetailContract.View> {
    private static final String TAG = "BookDetailPresenter";
    private String bookId;

    @Override
    public void refreshBookDetail(String bookId) {
        this.bookId = bookId;
        refreshBook();
    }

    @Override
    public void addToBookShelf(CollBookBean collBookBean) {
        List<BookChapterBean> bookChapterBeans = new ArrayList<>();
        BookRepository.getInstance()
                .saveCollBookWithAsync(collBookBean);
        Log.d(TAG, "addToBookShelf: " + bookId);
        Disposable disposable = RemoteRepository.getInstance()
                .getBookFolder(bookId)
                .subscribeOn(Schedulers.io())
                .doOnSubscribe(
                        (d) -> mView.waitToBookShelf()
                        //等待加载
                )
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<List<ChapterBean>>() {
                    @Override
                    public void accept(List<ChapterBean> chapterBeans) throws Exception {
                        for (ChapterBean chapterBean : chapterBeans) {
                            BookChapterBean bookChapterBeanTemp = new BookChapterBean();
                            bookChapterBeanTemp.setLink(chapterBean.getChapterId() + "");
                            bookChapterBeanTemp.setTitle(chapterBean.getTitle());
                            bookChapterBeanTemp.setId(MD5Utils.strToMd5By16(bookChapterBeanTemp.getLink()));
                            bookChapterBeanTemp.setBookId(collBookBean.get_id());
                            bookChapterBeans.add(bookChapterBeanTemp);
                        }
                        collBookBean.setBookChapters(bookChapterBeans);
                        //存储收藏
                        BookRepository.getInstance()
                                .saveCollBookWithAsync(collBookBean);
                        mView.succeedToBookShelf();
                        CollBookBean collBookBeanResult = BookRepository.getInstance().getCollBook(bookId);
                        Log.d(TAG, "addToBookShelf:collBookBeanResult " + collBookBeanResult);

                        synBookShelf();

                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        mView.errorToBookShelf();

                    }
                });


        addDisposable(disposable);
    }

    private void synBookShelf() {
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
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private void setBookShelf(List<String> bookIds) {
        RequestBody body = RequestBody.create(JSON, new Gson().toJson(bookIds));
        String token = SharedPreUtils.getInstance().getString("token");
        Disposable disposable = RemoteRepository.getInstance().setBookShelf(token, body)
                .compose(RxUtils::toSimpleSingle)
                .subscribe(new Consumer<SyncBookShelfBean>() {
                    @Override
                    public void accept(SyncBookShelfBean syncBookShelfBean) throws Exception {
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                    }
                });
        addDisposable(disposable);
    }

    private void setBookShelfByMobile(List<String> bookIds, String mobile, String mobileToken) {
        DirectSycBookShelfBean directSycBookShelfBean = new DirectSycBookShelfBean();
        directSycBookShelfBean.setBookIds(bookIds);
        directSycBookShelfBean.setMobile(mobile);
        directSycBookShelfBean.setMobileToken(mobileToken);
        RequestBody body = RequestBody.create(JSON, new Gson().toJson(directSycBookShelfBean));
        Disposable disposable = RemoteRepository.getInstance().setBookShelfByMobile(body)
                .compose(RxUtils::toSimpleSingle)
                .subscribe(new Consumer<SyncBookShelfBean>() {
                    @Override
                    public void accept(SyncBookShelfBean syncBookShelfBean) throws Exception {
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                    }
                });
        addDisposable(disposable);
    }

    private void refreshBook() {
        Disposable disposable = RemoteRepository
                .getInstance()
                .getBookInfo(bookId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<BookDetailBeanInOwn>() {
                    @Override
                    public void accept(BookDetailBeanInOwn bookDetailBeanInOwn) throws Exception {
                        mView.finishRefresh(bookDetailBeanInOwn);
                        mView.complete();
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        mView.showError();
                    }
                });

        addDisposable(disposable);


    }
}
