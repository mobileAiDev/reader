package com.ldp.reader.ui.activity

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.utils.LogUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    private val _hotWords = MutableLiveData<List<String>>()
    private val _keyWords = MutableLiveData<List<String>>()
    private val _books = MutableLiveData<List<BookSearchResult>>()
    private val _bookSearchErrors = MutableLiveData<Int>()
    private var bookSearchErrorVersion = 0
    private var keywordRequestVersion = 0
    private var bookRequestVersion = 0
    private var activeBookQuery: String? = null
    private var keywordJob: Job? = null
    private var bookJob: Job? = null

    val hotWords: LiveData<List<String>> = _hotWords
    val keyWords: LiveData<List<String>> = _keyWords
    val books: LiveData<List<BookSearchResult>> = _books
    val bookSearchErrors: LiveData<Int> = _bookSearchErrors

    fun searchHotWord() {
        viewModelScope.launch {
            try {
                val bean = BookContentProviderRouter.searchHotWords()
                _hotWords.value = bean
                Log.d("+bean", bean.toString())
                LogUtils.e(bean)
            } catch (e: Throwable) {
                LogUtils.e(e)
            }
        }
    }

    fun searchKeyWord(query: String?) {
        val trimmedQuery = query?.trim().orEmpty()
        activeBookQuery = null
        val requestVersion = ++keywordRequestVersion
        keywordJob?.cancel()
        keywordJob = viewModelScope.launch {
            try {
                val bean = BookContentProviderRouter.searchKeyWords(trimmedQuery)
                Log.d("+bean", bean.toString())
                if (requestVersion == keywordRequestVersion && activeBookQuery == null) {
                    _keyWords.value = bean
                }
                LogUtils.d("+bean", bean)
            } catch (e: Throwable) {
                LogUtils.e(e)
            }
        }
    }

    fun searchBook(query: String?) {
        val trimmedQuery = query?.trim().orEmpty()
        Log.d(TAG, "searchBook: $trimmedQuery")
        activeBookQuery = trimmedQuery
        keywordJob?.cancel()
        keywordRequestVersion++
        val requestVersion = ++bookRequestVersion
        bookJob?.cancel()
        bookJob = viewModelScope.launch {
            try {
                val books = BookContentProviderRouter.searchBooks(trimmedQuery)
                if (requestVersion == bookRequestVersion && activeBookQuery == trimmedQuery) {
                    _books.value = books
                }
            } catch (throwable: Throwable) {
                LogUtils.e(throwable)
                if (requestVersion == bookRequestVersion && activeBookQuery == trimmedQuery) {
                    _bookSearchErrors.value = ++bookSearchErrorVersion
                }
            }
        }
    }

    companion object {
        private val TAG = SearchViewModel::class.java.simpleName
    }
}
