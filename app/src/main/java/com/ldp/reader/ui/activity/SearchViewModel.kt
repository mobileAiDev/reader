package com.ldp.reader.ui.activity

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.model.remote.RemoteRepository
import com.ldp.reader.utils.LogUtils
import com.ldp.reader.utils.RxUtils
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers

class SearchViewModel : ViewModel() {
    private val disposable = CompositeDisposable()
    private val _hotWords = MutableLiveData<List<String>>()
    private val _keyWords = MutableLiveData<List<String>>()
    private val _books = MutableLiveData<List<BookSearchResult>>()
    private val _bookSearchErrors = MutableLiveData<Int>()
    private var bookSearchErrorVersion = 0

    val hotWords: LiveData<List<String>> = _hotWords
    val keyWords: LiveData<List<String>> = _keyWords
    val books: LiveData<List<BookSearchResult>> = _books
    val bookSearchErrors: LiveData<Int> = _bookSearchErrors

    fun searchHotWord() {
        val disp = RemoteRepository.getInstance()
            .getHotWords()
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { bean ->
                    _hotWords.value = bean
                    Log.d("+bean", bean.toString())
                    LogUtils.e(bean)
                },
                { e -> LogUtils.e(e) }
            )
        disposable.add(disp)
    }

    fun searchKeyWord(query: String?) {
        val disp = RemoteRepository.getInstance()
            .getKeyWords(query)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { bean ->
                    Log.d("+bean", bean.toString())
                    _keyWords.value = bean
                    LogUtils.d("+bean", bean)
                },
                { e -> LogUtils.e(e) }
            )
        disposable.add(disp)
    }

    fun searchBook(query: String?) {
        Log.d(TAG, "searchBook: $query")
        val disp = RemoteRepository.getInstance()
            .getSearchResult(query)
            .compose { upstream -> RxUtils.toSimpleSingle(upstream) }
            .subscribe(
                { bookSearchResults -> _books.value = bookSearchResults },
                { throwable ->
                    LogUtils.e(throwable)
                    _bookSearchErrors.value = ++bookSearchErrorVersion
                }
            )
        disposable.add(disp)
    }

    override fun onCleared() {
        disposable.clear()
        super.onCleared()
    }

    companion object {
        private val TAG = SearchViewModel::class.java.simpleName
    }
}
