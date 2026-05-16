package com.ldp.reader.ui.activity

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.ChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.bean.DirectSycBookShelfBean
import com.ldp.reader.model.bean.SyncBookShelfBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.remote.RemoteRepository
import com.ldp.reader.presenter.BookShelfPresenter
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.utils.RxUtils
import com.ldp.reader.utils.SharedPreUtils
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class BookDetailViewModel : ViewModel() {
    private val disposable = CompositeDisposable()
    private val _bookDetails = MutableLiveData<BookDetailBeanInOwn>()
    private val _refreshErrors = MutableLiveData<Int>()
    private val _bookShelfAddWaitEvents = MutableLiveData<Int>()
    private val _bookShelfAddErrorEvents = MutableLiveData<Int>()
    private val _bookShelfAddSuccessEvents = MutableLiveData<Int>()
    private var refreshErrorVersion = 0
    private var bookShelfAddWaitVersion = 0
    private var bookShelfAddErrorVersion = 0
    private var bookShelfAddSuccessVersion = 0
    private var bookId: String? = null

    val bookDetails: LiveData<BookDetailBeanInOwn> = _bookDetails
    val refreshErrors: LiveData<Int> = _refreshErrors
    val bookShelfAddWaitEvents: LiveData<Int> = _bookShelfAddWaitEvents
    val bookShelfAddErrorEvents: LiveData<Int> = _bookShelfAddErrorEvents
    val bookShelfAddSuccessEvents: LiveData<Int> = _bookShelfAddSuccessEvents

    fun refreshBookDetail(bookId: String?) {
        this.bookId = bookId
        refreshBook()
    }

    fun addToBookShelf(collBook: CollBookBean?) {
        val collBookBean = collBook!!
        val bookChapterBeans: MutableList<BookChapterBean> = ArrayList()
        BookRepository.getInstance()
            .saveCollBookWithAsync(collBookBean)
        Log.d(TAG, "addToBookShelf: $bookId")
        _bookShelfAddWaitEvents.value = ++bookShelfAddWaitVersion
        val disp = RemoteRepository.getInstance()
            .getBookFolder(bookId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { chapterBeans: List<ChapterBean> ->
                    for (chapterBean in chapterBeans) {
                        val bookChapterBeanTemp = BookChapterBean()
                        bookChapterBeanTemp.link = chapterBean.chapterId.toString()
                        bookChapterBeanTemp.title = chapterBean.title
                        bookChapterBeanTemp.id = MD5Utils.strToMd5By16(bookChapterBeanTemp.link!!)
                        bookChapterBeanTemp.bookId = collBookBean.get_id()
                        bookChapterBeanTemp.start = bookChapterBeans.size.toLong()
                        bookChapterBeans.add(bookChapterBeanTemp)
                    }
                    collBookBean.bookChapters = bookChapterBeans
                    collBookBean.chaptersCount = bookChapterBeans.size
                    BookRepository.getInstance()
                        .saveCollBookWithAsync(collBookBean)
                    _bookShelfAddSuccessEvents.value = ++bookShelfAddSuccessVersion
                    val collBookBeanResult = BookRepository.getInstance().getCollBook(bookId)
                    Log.d(TAG, "addToBookShelf:collBookBeanResult $collBookBeanResult")

                    synBookShelf()
                },
                {
                    _bookShelfAddErrorEvents.value = ++bookShelfAddErrorVersion
                }
            )

        disposable.add(disp)
    }

    private fun synBookShelf() {
        val collBooks = BookRepository.getInstance().collBooks
        val bookIds = BookShelfPresenter.onlineBookIdsFrom(collBooks)
        if ("password" == SharedPreUtils.getInstance().getString("loginType")) {
            setBookShelf(bookIds)
        } else {
            val mobile = SharedPreUtils.getInstance().getString("userName")
            val mobileToken = SharedPreUtils.getInstance().getString("token")
            setBookShelfByMobile(bookIds, mobile, mobileToken)
        }
    }

    private fun setBookShelf(bookIds: List<String>) {
        val body = Gson().toJson(BookShelfPresenter.normalizeServerBookIds(bookIds)).toRequestBody(JSON)
        val token = SharedPreUtils.getInstance().getString("token")
        val disp = RemoteRepository.getInstance().setBookShelf(token, body)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { _: SyncBookShelfBean -> },
                {}
            )
        disposable.add(disp)
    }

    private fun setBookShelfByMobile(bookIds: List<String>, mobile: String?, mobileToken: String?) {
        val directSycBookShelfBean = DirectSycBookShelfBean()
        directSycBookShelfBean.bookIds = BookShelfPresenter.normalizeServerBookIds(bookIds)
        directSycBookShelfBean.mobile = mobile
        directSycBookShelfBean.mobileToken = mobileToken
        val body = Gson().toJson(directSycBookShelfBean).toRequestBody(JSON)
        val disp = RemoteRepository.getInstance().setBookShelfByMobile(body)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { _: SyncBookShelfBean -> },
                {}
            )
        disposable.add(disp)
    }

    private fun refreshBook() {
        val disp = RemoteRepository
            .getInstance()
            .getBookInfo(bookId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { bookDetailBeanInOwn: BookDetailBeanInOwn ->
                    _bookDetails.value = bookDetailBeanInOwn
                },
                {
                    _refreshErrors.value = ++refreshErrorVersion
                }
            )

        disposable.add(disp)
    }

    override fun onCleared() {
        disposable.clear()
        super.onCleared()
    }

    companion object {
        private val TAG = BookDetailViewModel::class.java.simpleName
        private val JSON: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
