package com.ldp.reader.model.remote;

import com.ldp.reader.model.bean.BookDetailBeanInOwn;
import com.ldp.reader.model.bean.BookIdBean;
import com.ldp.reader.model.bean.BookSearchResult;
import com.ldp.reader.model.bean.ChapterBean;
import com.ldp.reader.model.bean.ChapterInfoBean;
import com.ldp.reader.model.bean.ContentBean;
import com.ldp.reader.model.bean.DirectLoginResultBean;
import com.ldp.reader.model.bean.LoginResultBean;
import com.ldp.reader.model.bean.SmsLoginBean;
import com.ldp.reader.model.bean.SyncBookShelfBean;
import com.mob.secverify.datatype.VerifyResult;

import java.util.List;

import io.reactivex.Single;
import okhttp3.RequestBody;
import retrofit2.Retrofit;

import static com.ldp.reader.utils.Constant.APP_KEY;
import static com.ldp.reader.utils.Constant.APP_SECRET;

/**
 * Created by ldp on 17-4-20.
 */

public class RemoteRepository {
    private static RemoteRepository sInstance;
    private Retrofit mRetrofit, mRetrofitByOwn;
    private BookApi mBookApi;
    private BookApiOwn mBookApiOwn;


    private RemoteRepository() {
        mRetrofit = RemoteHelper.getInstance()
                .getRetrofit();
        mBookApi = mRetrofit.create(BookApi.class);


        mRetrofitByOwn = RemoteHelper.getInstance()
                .getRetrofitByOwn();
        mBookApiOwn = mRetrofitByOwn.create(BookApiOwn.class);
    }

    public static RemoteRepository getInstance() {
        if (sInstance == null) {
            synchronized (RemoteHelper.class) {
                if (sInstance == null) {
                    sInstance = new RemoteRepository();
                }
            }
        }
        return sInstance;
    }



    public Single<List<BookSearchResult>> getSearchResult(String bookName) {
        return mBookApiOwn.getSearchResult(bookName);

    }


    public Single<BookDetailBeanInOwn> getBookInfo(String bookId) {
        return mBookApiOwn.getBookInfo(bookId);
    }

    public Single<List<BookDetailBeanInOwn>> getBookInfoBatch( RequestBody body) {
        return mBookApiOwn.getBookInfoBatch(body);
    }



    public Single<List<ChapterBean>> getBookFolder(String bookId) {
        return mBookApiOwn.getBookFolder(bookId);
    }

    public Single<ContentBean> getBookContent(String bookId,String chapterId ,int sourceIndex) {
        return mBookApiOwn.getBookContent(bookId,chapterId,sourceIndex);
    }

    public Single<LoginResultBean> userLogin(String userNameInput, String passwordInput) {
        return mBookApiOwn.userLogin(userNameInput,passwordInput);
    }

    public Single<SmsLoginBean> smsLogin(String phoneNumber , String smsCode , String registrationId) {
        return mBookApiOwn.smsLogin( phoneNumber , smsCode , registrationId) ;
    }

    public Single<DirectLoginResultBean> userDirectLogin(VerifyResult verifyResult,String registrationId) {
        return mBookApiOwn.userDirectLogin(APP_KEY,APP_SECRET,verifyResult.getToken() ,verifyResult.getOpToken(),verifyResult.getOperator(),registrationId) ;
    }

    public Single<List<BookIdBean>> getBookShelf(String header) {
        return mBookApiOwn.getBookShelf(header);
    }

    public Single<List<BookIdBean>> getBookShelfByMobile(String mobile ,String token) {
        return mBookApiOwn.getBookShelfByMobile(mobile,token);
    }

    public Single<SyncBookShelfBean> setBookShelf(String token, RequestBody body) {
        return mBookApiOwn.setBookShelf(token,body);
    }


    public Single<SyncBookShelfBean> setBookShelfByMobile(RequestBody body) {
        return mBookApiOwn.setBookShelfByMobile(body);
    }


    /**
     * 注意这里用的是同步请求
     *
     * @param url
     * @return
     */
    public Single<ChapterInfoBean> getChapterInfo(String url) {
        return mBookApi.getChapterInfoPackage(url)
                .map(bean -> bean.getChapter());
    }

    /********************************书籍搜索*********************************************/
    /**
     * 搜索热词
     *
     * @return
     */
    public Single<List<String>> getHotWords() {
        return mBookApi.getHotWordPackage()
                .map(bean -> bean.getHotWords());
    }

    /**
     * 搜索关键字
     *
     * @param query
     * @return
     */
    public Single<List<String>> getKeyWords(String query) {
        return mBookApi.getKeyWordPacakge(query)
                .map(bean -> bean.getKeywords());

    }



}
