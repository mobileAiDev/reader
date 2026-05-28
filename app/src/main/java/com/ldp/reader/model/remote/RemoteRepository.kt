package com.ldp.reader.model.remote

import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.BookIdBean
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.model.bean.ChapterBean
import com.ldp.reader.model.bean.ContentBean
import com.ldp.reader.model.bean.DirectLoginResultBean
import com.ldp.reader.model.bean.LoginResultBean
import com.ldp.reader.model.bean.SmsLoginBean
import com.ldp.reader.model.bean.SyncBookShelfBean
import com.ldp.reader.source.SourceNetworkDispatchers
import com.ldp.reader.source.SourceNetworkForegroundPriority
import com.ldp.reader.utils.Constant.APP_KEY
import com.ldp.reader.utils.Constant.APP_SECRET
import com.mob.secverify.datatype.VerifyResult
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.HttpException
import retrofit2.Retrofit

class RemoteRepository private constructor() {
    private val mRetrofit: Retrofit
    private val mRetrofitByOwn: Retrofit
    private val mBookApi: BookApi
    private val mBookApiOwn: BookApiOwn

    init {
        mRetrofit = RemoteHelper.getInstance().getRetrofit()
        mBookApi = mRetrofit.create(BookApi::class.java)

        mRetrofitByOwn = RemoteHelper.getInstance().getRetrofitByOwn()
        mBookApiOwn = mRetrofitByOwn.create(BookApiOwn::class.java)
    }

    suspend fun getSearchResult(bookName: String?): List<BookSearchResult> {
        return execute(mBookApiOwn.getSearchResult(bookName))
    }

    suspend fun getBookInfo(bookId: String?): BookDetailBeanInOwn {
        return execute(mBookApiOwn.getBookInfo(bookId))
    }

    suspend fun getBookInfoBatch(body: RequestBody?): List<BookDetailBeanInOwn> {
        return execute(mBookApiOwn.getBookInfoBatch(body))
    }

    suspend fun getBookFolder(bookId: String?): List<ChapterBean> {
        return execute(mBookApiOwn.getBookFolder(bookId))
    }

    suspend fun getBookContent(bookId: String?, chapterId: String?, sourceIndex: Int): ContentBean {
        return execute(mBookApiOwn.getBookContent(bookId, chapterId, sourceIndex))
    }

    suspend fun userLogin(userNameInput: String?, passwordInput: String?): LoginResultBean {
        return execute(mBookApiOwn.userLogin(userNameInput, passwordInput))
    }

    suspend fun smsLogin(phoneNumber: String?, smsCode: String?, registrationId: String?): SmsLoginBean {
        return execute(mBookApiOwn.smsLogin(phoneNumber, smsCode, registrationId))
    }

    suspend fun userDirectLogin(verifyResult: VerifyResult?, registrationId: String?): DirectLoginResultBean {
        return execute(mBookApiOwn.userDirectLogin(
            APP_KEY,
            APP_SECRET,
            verifyResult!!.token,
            verifyResult.opToken,
            verifyResult.operator,
            registrationId
        ))
    }

    suspend fun getBookShelf(header: String?): List<BookIdBean> {
        return execute(mBookApiOwn.getBookShelf(header))
    }

    suspend fun getBookShelfByMobile(mobile: String?, token: String?): List<BookIdBean> {
        return execute(mBookApiOwn.getBookShelfByMobile(mobile, token))
    }

    suspend fun setBookShelf(token: String?, body: RequestBody?): SyncBookShelfBean {
        return execute(mBookApiOwn.setBookShelf(token, body))
    }

    suspend fun setBookShelfByMobile(body: RequestBody?): SyncBookShelfBean {
        return execute(mBookApiOwn.setBookShelfByMobile(body))
    }

    suspend fun getHotWords(): List<String> {
        return execute(mBookApi.getHotWordPackage()).hotWords!!
    }

    suspend fun getKeyWords(query: String?): List<String> {
        return execute(mBookApi.getKeyWordPacakge(query)).keywords!!
    }

    private suspend fun <T> execute(call: Call<T>): T {
        val request = call.request()
        return SourceNetworkForegroundPriority.entered(
            operation = "remote",
            key = "${request.method} ${request.url.encodedPath}"
        ) {
            withContext(SourceNetworkDispatchers.foreground) {
                val response = call.execute()
                if (!response.isSuccessful) {
                    throw HttpException(response)
                }
                response.body()!!
            }
        }
    }

    companion object {
        @Volatile
        private var sInstance: RemoteRepository? = null

        @JvmStatic
        fun getInstance(): RemoteRepository {
            if (sInstance == null) {
                synchronized(RemoteHelper::class.java) {
                    if (sInstance == null) {
                        sInstance = RemoteRepository()
                    }
                }
            }
            return sInstance!!
        }
    }
}
