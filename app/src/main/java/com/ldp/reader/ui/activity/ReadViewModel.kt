package com.ldp.reader.ui.activity

import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.utils.LogUtils
import com.ldp.reader.widget.page.TxtChapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Arrays

/**
 * Created by ldp on 17-5-16.
 */
class ReadViewModel : ViewModel() {
    data class CategoryResult(
        val bookChapterList: List<BookChapterBean>,
        val bookId: String,
        val isBiqugeLoaded: Boolean
    )

    private val _categories = MutableLiveData<CategoryResult>()
    private val _chapterFinishedEvents = MutableLiveData<Boolean>()
    private val _chapterErrorEvents = MutableLiveData<Int>()
    private var chapterErrorVersion = 0
    private var chapterJob: Job? = null
    private var bookIdInBiquge: String? = ""

    val categories: LiveData<CategoryResult> = _categories
    val chapterFinishedEvents: LiveData<Boolean> = _chapterFinishedEvents
    val chapterErrorEvents: LiveData<Int> = _chapterErrorEvents

    fun loadCategory(bookId: String?, collBookBean: CollBookBean) {
        Log.d(TAG, "loadCategory: $bookId$collBookBean")
        viewModelScope.launch {
            try {
                val bookChapterBeans = BookContentProviderRouter.getBookFolder(bookId, collBookBean)
                collBookBean.bookChapters = bookChapterBeans
                collBookBean.chaptersCount = bookChapterBeans.size
                collBookBean.lastChapter = bookChapterBeans.lastOrNull()?.title ?: collBookBean.lastChapter
                Log.d(TAG, "accept: $bookChapterBeans")
                _categories.value = CategoryResult(bookChapterBeans, bookId!!, true)

                BookRepository.getInstance()
                    .saveCollBookWithAsync(collBookBean)
            } catch (e: Throwable) {
                _chapterErrorEvents.value = ++chapterErrorVersion
            }
        }
    }

    @Synchronized
    fun loadChapter(bookId: String?, sourceBook: CollBookBean, bookChapterList: List<TxtChapter>) {
        val size = bookChapterList.size
        Log.e(TAG, "loadChapter  列表大小" + size + Arrays.asList(bookChapterList).toString())

        chapterJob?.cancel()

        val titlesInBiquge = ArrayDeque<String>()
        val readableChapters = ArrayList<TxtChapter>(bookChapterList.size)

        for (i in 0 until size) {
            val bookChapter = bookChapterList[i]
            if (bookChapter.title == null || TextUtils.isEmpty(bookChapter.title)) {
                continue
            }
            bookIdInBiquge = sourceBook.get_id()
            Log.d("+收到的章节笔趣阁Id", bookIdInBiquge!!)
            Log.d("+收到的章节ID", bookChapter.link!!)
            readableChapters.add(bookChapter)
            titlesInBiquge.add(bookChapter.title!!)
        }

        chapterJob = viewModelScope.launch {
            var titleInBiquge: String? = titlesInBiquge.poll()
            try {
                for (bookChapter in readableChapters) {
                    val content = BookContentProviderRouter.getBookContent(
                        bookId,
                        sourceBook,
                        bookChapter,
                        0
                    )
                    BookRepository.getInstance().saveChapterInfo(
                        bookId,
                        titleInBiquge,
                        content
                    )
                    Log.e(
                        "+chapterBody",
                        "title" + titleInBiquge + titlesInBiquge + " " + content
                    )
                    _chapterFinishedEvents.value = false
                    titleInBiquge = titlesInBiquge.poll()
                }
            } catch (t: Throwable) {
                if (bookChapterList[0].title == titleInBiquge) {
                    _chapterErrorEvents.value = ++chapterErrorVersion
                }
                LogUtils.e(t)
            }
        }
    }

    @Synchronized
    fun refreshChapter(bookId: String?, sourceBook: CollBookBean, bookChapter: TxtChapter?, sourceIndex: Int) {
        requireNotNull(bookChapter)
        bookIdInBiquge = sourceBook.get_id()
        viewModelScope.launch {
            try {
                val content = BookContentProviderRouter.getBookContent(
                    bookId,
                    sourceBook,
                    bookChapter,
                    sourceIndex
                )
                BookRepository.getInstance().saveChapterInfo(
                    bookId,
                    bookChapter.title,
                    content
                )
                _chapterFinishedEvents.value = true
            } catch (e: Throwable) {
                _chapterErrorEvents.value = ++chapterErrorVersion
            }
        }
    }

    override fun onCleared() {
        chapterJob?.cancel()
        super.onCleared()
    }

    companion object {
        private val TAG = ReadViewModel::class.java.simpleName
    }
}
