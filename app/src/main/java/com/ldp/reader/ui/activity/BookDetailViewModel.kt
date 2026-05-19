package com.ldp.reader.ui.activity

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.ldp.reader.model.bean.BookDetailBeanInOwn
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.bean.DirectSycBookShelfBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.remote.RemoteRepository
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.ui.fragment.BookShelfViewModel
import com.ldp.reader.utils.SharedPreUtils
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class BookDetailViewModel : ViewModel() {
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
        BookRepository.getInstance()
            .saveCollBookWithAsync(collBookBean)
        Log.d(TAG, "addToBookShelf: $bookId")
        _bookShelfAddWaitEvents.value = ++bookShelfAddWaitVersion
        viewModelScope.launch {
            try {
                val bookChapterBeans = BookContentProviderRouter.getBookFolder(bookId, collBookBean)
                collBookBean.bookChapters = bookChapterBeans
                collBookBean.chaptersCount = bookChapterBeans.size
                BookRepository.getInstance()
                    .saveCollBookWithAsync(collBookBean)
                _bookShelfAddSuccessEvents.value = ++bookShelfAddSuccessVersion
                val collBookBeanResult = BookRepository.getInstance().getCollBook(collBookBean.get_id())
                Log.d(TAG, "addToBookShelf:collBookBeanResult $collBookBeanResult")

                synBookShelf()
            } catch (e: Throwable) {
                _bookShelfAddErrorEvents.value = ++bookShelfAddErrorVersion
            }
        }
    }

    private fun synBookShelf() {
        val collBooks = BookRepository.getInstance().collBooks
        val bookIds = BookShelfViewModel.onlineBookIdsFrom(collBooks)
        if ("password" == SharedPreUtils.getInstance().getString("loginType")) {
            setBookShelf(bookIds)
        } else {
            val mobile = SharedPreUtils.getInstance().getString("userName")
            val mobileToken = SharedPreUtils.getInstance().getString("token")
            setBookShelfByMobile(bookIds, mobile, mobileToken)
        }
    }

    private fun setBookShelf(bookIds: List<String>) {
        val body = Gson().toJson(BookShelfViewModel.normalizeServerBookIds(bookIds)).toRequestBody(JSON)
        val token = SharedPreUtils.getInstance().getString("token")
        viewModelScope.launch {
            try {
                RemoteRepository.getInstance().setBookShelf(token, body)
            } catch (e: Throwable) {
            }
        }
    }

    private fun setBookShelfByMobile(bookIds: List<String>, mobile: String?, mobileToken: String?) {
        val directSycBookShelfBean = DirectSycBookShelfBean()
        directSycBookShelfBean.bookIds = BookShelfViewModel.normalizeServerBookIds(bookIds)
        directSycBookShelfBean.mobile = mobile
        directSycBookShelfBean.mobileToken = mobileToken
        val body = Gson().toJson(directSycBookShelfBean).toRequestBody(JSON)
        viewModelScope.launch {
            try {
                RemoteRepository.getInstance().setBookShelfByMobile(body)
            } catch (e: Throwable) {
            }
        }
    }

    private fun refreshBook() {
        viewModelScope.launch {
            try {
                val detail = BookContentProviderRouter.getBookInfo(bookId)
                bookId = detail.routeId ?: bookId
                _bookDetails.value = detail
            } catch (e: Throwable) {
                _refreshErrors.value = ++refreshErrorVersion
            }
        }
    }

    companion object {
        private val TAG = BookDetailViewModel::class.java.simpleName
        private val JSON: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
