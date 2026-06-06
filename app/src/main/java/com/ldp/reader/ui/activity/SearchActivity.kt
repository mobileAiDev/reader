package com.ldp.reader.ui.activity

import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivitySearchBinding
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.ui.activity.BookDetailActivity.Companion.startActivity
import com.ldp.reader.ui.adapter.KeyWordAdapter
import com.ldp.reader.ui.adapter.MediaSearchAdapter
import com.ldp.reader.ui.adapter.SearchBookAdapter
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.utils.SystemBarUtils
import com.ldp.reader.widget.RefreshLayout
import com.ldp.reader.widget.itemdecoration.DividerItemDecoration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gujun.android.taggroup.TagGroup
import kotlin.coroutines.cancellation.CancellationException

/**
 * Created by ldp on 17-4-24.
 */
class SearchActivity : BaseActivity<ActivitySearchBinding>() {
    var mIvBack: ImageView? = null
    var mEtInput: EditText? = null
    var mIvDelete: ImageView? = null
    var mIvSearch: TextView? = null
    var mTvRefreshHot: TextView? = null
    var mTgHot: TagGroup? = null
    var mRlRefresh: RefreshLayout? = null
    var mRvSearch: RecyclerView? = null
    private var mKeyWordAdapter: KeyWordAdapter? = null
    private var mSearchAdapter: SearchBookAdapter? = null
    private var mMediaSearchAdapter: MediaSearchAdapter? = null
    private var isTag = false
    private var mHotTagList: List<String> = emptyList()
    private var mTagStart = 0
    private var activeBookSearchQuery = ""
    private var activeBookSearchStartedAtMs = 0L
    private var activeSearchKind = ReaderMediaKind.NOVEL
    private var mediaSearchToken = 0
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSearchJob: Job? = null
    private var searchLoadingProgressJob: Job? = null
    private lateinit var viewModel: SearchViewModel

    override fun initWidget() {
        super.initWidget()
        viewModel = ViewModelProvider(this)[SearchViewModel::class.java]
        observeSearchState()
        mIvBack = binding!!.searchIvBack
        mEtInput = binding!!.searchEtInput
        mIvDelete = binding!!.searchIvDelete
        mIvSearch = binding!!.searchIvSearch
        mTvRefreshHot = binding!!.searchBookTvRefreshHot
        mTgHot = binding!!.searchTgHot
        mRlRefresh = binding!!.searchRefreshList.refreshLayout
        mRvSearch = binding!!.searchRefreshList.refreshRvContent
        setUpAdapter()
        mRlRefresh?.setBackground(ContextCompat.getDrawable(this, R.color.white))
    }

    private fun setUpAdapter() {
        mKeyWordAdapter = KeyWordAdapter()
        mSearchAdapter = SearchBookAdapter()
        mMediaSearchAdapter = MediaSearchAdapter()
        mRvSearch!!.layoutManager = LinearLayoutManager(this)
        mRvSearch!!.addItemDecoration(DividerItemDecoration(this))
    }

    override fun initClick() {
        super.initClick()

        //退出
        mIvBack!!.setOnClickListener { v: View? -> onBackPressed() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleSearchBack()
                }
            }
        )
        binding.searchTabNovel.setOnClickListener { selectSearchKind(ReaderMediaKind.NOVEL) }
        binding.searchTabComic.setOnClickListener { selectSearchKind(ReaderMediaKind.COMIC) }
        binding.searchTabAudio.setOnClickListener { selectSearchKind(ReaderMediaKind.AUDIO) }

        //输入框
        mEtInput!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (s.toString().trim { it <= ' ' } == "") {
                    //隐藏delete按钮和关键字显示内容
                    if (mIvDelete!!.visibility == View.VISIBLE) {
                        mIvDelete!!.visibility = View.INVISIBLE
                        mRlRefresh!!.visibility = View.INVISIBLE
                        setSearchPanelsVisible(true)
                        //删除全部视图
                        mKeyWordAdapter!!.clear()
                        mSearchAdapter!!.clear()
                        mMediaSearchAdapter!!.clear()
                        mRvSearch!!.removeAllViews()
                        stopSearchLoadingProgress()
                    }
                    return
                }
                //显示delete按钮
                if (mIvDelete!!.visibility == View.INVISIBLE) {
                    mIvDelete!!.visibility = View.VISIBLE
                    mRlRefresh!!.visibility = View.VISIBLE
                    setSearchPanelsVisible(false)
                    //默认是显示完成状态
                    mRlRefresh!!.showFinish()
                }
                //搜索
                val query = s.toString().trim { it <= ' ' }
                if (isTag) {
                    mRlRefresh!!.showLoading()
                    isTag = false
                } else if (activeSearchKind == ReaderMediaKind.NOVEL) {
                    //传递
                    viewModel.searchKeyWord(query)
                }
            }

            override fun afterTextChanged(s: Editable) {}
        })

        //键盘的搜索
        mEtInput!!.setOnKeyListener { v: View?, keyCode: Int, event: KeyEvent ->
            //修改回车键功能
            if (event.action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_SEARCH)) {
                searchBook()
                return@setOnKeyListener true
            }
            false
        }

        //进行搜索
        mIvSearch!!.setOnClickListener { v: View? -> searchBook() }

        //删除字
        mIvDelete!!.setOnClickListener { v: View? ->
            mEtInput!!.setText("")
            hideKeyboard()
        }

        //点击查书
        mKeyWordAdapter!!.setOnItemClickListener { view: View?, pos: Int ->
            val book = mKeyWordAdapter!!.getItem(pos)
            beginBookSearch(book)
            hideKeyboard()
        }

        //Tag的点击事件
        mTgHot!!.setOnTagClickListener { tag: String? ->
            val query = tag?.trim { it <= ' ' }.orEmpty()
            if (query.isEmpty()) {
                return@setOnTagClickListener
            }
            isTag = true
            mEtInput!!.setText(query)
            mEtInput!!.setSelection(mEtInput!!.text.length)
            beginBookSearch(query)
            hideKeyboard()
        }

        //Tag的刷新事件
        mTvRefreshHot!!.setOnClickListener { v: View? -> refreshTag() }

        //书本的点击事件
        mSearchAdapter!!.setOnItemClickListener { view: View?, pos: Int ->
            val book = mSearchAdapter!!.getItem(pos)
            val bookId = book.id
            traceSearchUi(
                "source_search_ui_result_clicked",
                activeBookSearchQuery.ifBlank { mEtInput?.text?.toString()?.trim().orEmpty() },
                AiBridgeTrace.fields(
                    "pos" to pos,
                    "title" to book.title.orEmpty(),
                    "author" to book.author.orEmpty(),
                    "route" to bookId.orEmpty()
                )
            )
            viewModel.cancelActiveBookWork()
            startActivity(this, bookId)
        }
        mMediaSearchAdapter!!.setOnItemClickListener { _, pos ->
            val book = mMediaSearchAdapter!!.getItem(pos)
            stopSearchLoadingProgress()
            when (activeSearchKind) {
                ReaderMediaKind.COMIC -> ComicDetailActivity.start(this, book.routeId)
                ReaderMediaKind.AUDIO -> AudioDetailActivity.start(this, book.routeId)
                ReaderMediaKind.NOVEL -> Unit
            }
        }
        selectSearchKind(ReaderMediaKind.NOVEL)
    }

    private fun searchBook() {
        val query = mEtInput!!.text.toString().trim { it <= ' ' }
        if (query != "") {
            beginBookSearch(query)
            hideKeyboard()
        }
    }

    private fun beginBookSearch(query: String) {
        if (activeSearchKind != ReaderMediaKind.NOVEL) {
            beginMediaSearch(query, activeSearchKind)
            return
        }
        activeBookSearchQuery = query
        activeBookSearchStartedAtMs = System.currentTimeMillis()
        traceSearchUi(
            "source_search_ui_activity_begin",
            query,
            "adapter_${mRvSearch!!.adapter?.javaClass?.simpleName.orEmpty()}_oldCount_${mSearchAdapter!!.itemCount}"
        )
        mRlRefresh!!.visibility = View.VISIBLE
        setSearchPanelsVisible(false)
        if (mRvSearch!!.adapter !is SearchBookAdapter) {
            mRvSearch!!.adapter = mSearchAdapter
        }
        mRlRefresh!!.showLoading()
        startSearchLoadingProgress(query, ReaderMediaKind.NOVEL)
        traceSearchUi("source_search_ui_loading", query, "reason_begin")
        mSearchAdapter!!.refreshItems(emptyList())
        traceSearchUi("source_search_ui_adapter_cleared", query, "count_0")
        viewModel.searchBook(query)
    }

    private fun beginMediaSearch(query: String, kind: ReaderMediaKind) {
        activeBookSearchQuery = query
        activeBookSearchStartedAtMs = System.currentTimeMillis()
        traceSearchUi("media_search_ui_begin", query, "kind_${kind.seedKey}")
        val token = ++mediaSearchToken
        mediaSearchJob?.cancel()
        viewModel.cancelActiveBookWork()
        mRlRefresh!!.visibility = View.VISIBLE
        setSearchPanelsVisible(false)
        if (mRvSearch!!.adapter !is MediaSearchAdapter) {
            mRvSearch!!.adapter = mMediaSearchAdapter
        }
        mMediaSearchAdapter!!.refreshItems(emptyList())
        mRlRefresh!!.showLoading()
        startSearchLoadingProgress(query, kind)
        mediaSearchJob = searchScope.launch {
            val books = try {
                withContext(Dispatchers.IO) {
                    MediaSourceRepository.search(kind, query) { partial ->
                        searchScope.launch {
                            if (activeSearchKind == kind && token == mediaSearchToken) {
                                traceSearchUi(
                                    "media_search_ui_partial",
                                    query,
                                    "kind_${kind.seedKey}_count_${partial.size}"
                                )
                                updateSearchLoadingProgress(query, kind, partial.size, finished = false)
                                renderMediaSearchResults(partial, finished = false)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "media search failed: kind=$kind query=$query", error)
                emptyList()
            }
            if (activeSearchKind != kind || token != mediaSearchToken) return@launch
            traceSearchUi("media_search_ui_final", query, "kind_${kind.seedKey}_count_${books.size}")
            updateSearchLoadingProgress(query, kind, books.size, finished = true)
            renderMediaSearchResults(books, finished = true)
        }
    }

    private fun renderMediaSearchResults(books: List<com.ldp.reader.media.MediaSearchBook>, finished: Boolean) {
        mMediaSearchAdapter!!.refreshItems(books)
        when {
            books.isNotEmpty() -> {
                stopSearchLoadingProgress()
                mRlRefresh!!.showFinish()
            }
            finished -> {
                stopSearchLoadingProgress()
                mRlRefresh!!.showEmpty()
            }
            else -> mRlRefresh!!.showLoading()
        }
    }

    private fun startSearchLoadingProgress(query: String, kind: ReaderMediaKind) {
        searchLoadingProgressJob?.cancel()
        updateSearchLoadingProgress(query, kind, resultCount = 0, finished = false)
        searchLoadingProgressJob = searchScope.launch {
            while (true) {
                delay(1_000)
                if (activeBookSearchQuery == query && activeSearchKind == kind) {
                    updateSearchLoadingProgress(query, kind, resultCount = 0, finished = false)
                }
            }
        }
    }

    private fun stopSearchLoadingProgress() {
        searchLoadingProgressJob?.cancel()
        searchLoadingProgressJob = null
    }

    private fun updateSearchLoadingProgress(
        query: String,
        kind: ReaderMediaKind,
        resultCount: Int,
        finished: Boolean
    ) {
        val elapsedMs = (System.currentTimeMillis() - activeBookSearchStartedAtMs).coerceAtLeast(0)
        val percent = searchLoadingPercent(elapsedMs, resultCount, finished)
        val message = searchLoadingMessage(kind, resultCount, finished, elapsedMs)
        mRlRefresh?.updateLoadingProgress(
            title = "智能引擎分析中",
            message = message,
            percent = percent
        )
        AiBridgeTrace.state(
            "media_search_loading",
            "${kind.seedKey}:$query",
            AiBridgeTrace.fields(
                "percent" to percent,
                "step" to message.traceToken(),
                "count" to resultCount,
                "finished" to finished
            )
        )
    }

    private fun searchLoadingPercent(elapsedMs: Long, resultCount: Int, finished: Boolean): Int {
        if (finished) return 100
        val timePercent = when {
            elapsedMs < 1_200 -> 12
            elapsedMs < 4_000 -> 24
            elapsedMs < 10_000 -> 38
            elapsedMs < 20_000 -> 55
            elapsedMs < 45_000 -> 70
            elapsedMs < 90_000 -> 84
            else -> 92
        }
        val resultPercent = when {
            resultCount >= 8 -> 94
            resultCount >= 3 -> 88
            resultCount > 0 -> 80
            else -> timePercent
        }
        return maxOf(timePercent, resultPercent).coerceIn(8, 96)
    }

    private fun searchLoadingMessage(
        kind: ReaderMediaKind,
        resultCount: Int,
        finished: Boolean,
        elapsedMs: Long
    ): String {
        if (finished) {
            return if (resultCount > 0) "结果整理完成" else "暂未找到可用结果"
        }
        if (resultCount > 0) {
            return "已找到 ${resultCount} 个可用结果，正在优化排序"
        }
        return when {
            elapsedMs < 4_000 -> "正在理解关键词"
            elapsedMs < 15_000 -> when (kind) {
                ReaderMediaKind.COMIC -> "正在筛选漫画目录"
                ReaderMediaKind.AUDIO -> "正在筛选可播放分集"
                ReaderMediaKind.NOVEL -> "正在筛选可读书籍"
            }
            elapsedMs < 45_000 -> "正在比对可用结果"
            else -> "结果较多，正在继续整理"
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val token = mEtInput?.windowToken ?: binding.root.windowToken
        imm.hideSoftInputFromWindow(token, 0)
        mEtInput?.clearFocus()
    }

    override fun processLogic() {
        super.processLogic()
        SystemBarUtils.showStableStatusBar(this)
        SystemBarUtils.transparentStatusBar(this)
        //默认隐藏
        mRlRefresh!!.visibility = View.GONE
        //获取热词
        viewModel.searchHotWord()
    }

    private fun observeSearchState() {
        viewModel.hotWords.observe(this) { hotWords -> finishHotWords(hotWords) }
        viewModel.keyWords.observe(this) { keyWords -> finishKeyWords(keyWords) }
        viewModel.books.observe(this) { books -> finishBooks(books) }
        viewModel.bookSearchErrors.observe(this) { errorBooks() }
    }

    private fun finishHotWords(hotWords: List<String>) {
        mHotTagList = hotWords
        Log.d(TAG, "finishHotWords: $hotWords")
        refreshTag()
    }

    private fun refreshTag() {
        if (mEtInput?.text.isNullOrBlank()) {
            setSearchPanelsVisible(true)
        }
        var last = mTagStart + TAG_LIMIT
        if (mHotTagList.size <= last) {
            mTagStart = 0
            last = TAG_LIMIT
        }
        if (mHotTagList.size <= TAG_LIMIT) {
            last = mHotTagList.size
        }
        Log.d(TAG, "refreshTag: mHotTagList$mHotTagList")
        Log.d(TAG, "refreshTag: mTagStart $mTagStart")
        Log.d(TAG, "refreshTag: last$last")
        val tags = mHotTagList.subList(mTagStart, last)
        mTgHot!!.setTags(tags)
        mTagStart += TAG_LIMIT
    }

    private fun finishKeyWords(keyWords: List<String>) {
        if (activeSearchKind != ReaderMediaKind.NOVEL) return
        if (keyWords.size == 0) {
            mRlRefresh!!.visibility = View.INVISIBLE
            setSearchPanelsVisible(false)
        }
        mKeyWordAdapter!!.refreshItems(keyWords)
        if (mRvSearch!!.adapter !is KeyWordAdapter) {
            mRvSearch!!.adapter = mKeyWordAdapter
        }
    }

    private fun finishBooks(books: List<BookSearchResult>) {
        if (activeSearchKind != ReaderMediaKind.NOVEL) return
        setSearchPanelsVisible(false)
        val query = activeBookSearchQuery.ifBlank { mEtInput?.text?.toString()?.trim().orEmpty() }
        traceSearchUi(
            "source_search_ui_books_observed",
            query,
            "count_${books.size}_top_${books.take(3).joinToString("_") { book ->
                "${book.title.orEmpty()}/${book.author.orEmpty()}".traceToken()
            }}"
        )
        mSearchAdapter!!.refreshItems(books)
        stopSearchLoadingProgress()
        if (books.size == 0) {
            mRlRefresh!!.showEmpty()
            traceSearchUi("source_search_ui_empty", query, "reason_books_empty")
        } else {
            //显示完成
            mRlRefresh!!.showFinish()
            traceSearchUi(
                "source_search_ui_result",
                query,
                "count_${books.size}_first_${books.first().title.orEmpty().traceToken()}" +
                    "_author_${books.first().author.orEmpty().traceToken()}"
            )
        }
        //加载
        if (mRvSearch!!.adapter !is SearchBookAdapter) {
            mRvSearch!!.adapter = mSearchAdapter
        }
    }

    private fun errorBooks() {
        if (activeSearchKind != ReaderMediaKind.NOVEL) return
        setSearchPanelsVisible(false)
        mRlRefresh!!.showEmpty()
        stopSearchLoadingProgress()
        val query = activeBookSearchQuery.ifBlank { mEtInput?.text?.toString()?.trim().orEmpty() }
        traceSearchUi("source_search_ui_empty", query, "reason_error")
    }

    private fun setSearchPanelsVisible(visible: Boolean) {
        val visibility = if (visible && activeSearchKind == ReaderMediaKind.NOVEL) View.VISIBLE else View.GONE
        binding?.searchAssistantEntry?.visibility = visibility
        binding?.searchHotPanel?.visibility = visibility
    }

    private fun selectSearchKind(kind: ReaderMediaKind) {
        activeSearchKind = kind
        AiBridgeTrace.event("media_search_ui_kind_selected", kind.seedKey, mEtInput?.text?.toString().orEmpty())
        mediaSearchToken += 1
        mediaSearchJob?.cancel()
        stopSearchLoadingProgress()
        viewModel.cancelActiveBookWork()
        mEtInput?.hint = kind.searchHint
        updateSearchKindTabs()
        mKeyWordAdapter?.clear()
        mSearchAdapter?.clear()
        mMediaSearchAdapter?.clear()
        mRvSearch?.removeAllViews()
        mRlRefresh?.visibility = View.GONE
        setSearchPanelsVisible(mEtInput?.text.isNullOrBlank())
    }

    private fun updateSearchKindTabs() {
        val tabs = listOf(
            binding.searchTabNovel to ReaderMediaKind.NOVEL,
            binding.searchTabComic to ReaderMediaKind.COMIC,
            binding.searchTabAudio to ReaderMediaKind.AUDIO
        )
        tabs.forEach { (tab, kind) ->
            val selected = activeSearchKind == kind
            tab.setBackgroundResource(if (selected) R.drawable.bg_search_tab_selected else R.drawable.bg_search_tab_normal)
            tab.setTextColor(resources.getColor(if (selected) R.color.home_text_on_primary else R.color.home_text_primary))
        }
    }

    private fun traceSearchUi(name: String, query: String, value: String) {
        val elapsedMs = (System.currentTimeMillis() - activeBookSearchStartedAtMs).coerceAtLeast(0)
        AiBridgeTrace.event(name, query, "${value}_elapsedMs_$elapsedMs")
        Log.i(TAG, "operation=$name query=$query $value elapsedMs=$elapsedMs")
    }

    private fun String.traceToken(): String {
        return replace(Regex("""[\s=:/\\#]+"""), "_").take(80)
    }

    override fun onBackPressed() {
        handleSearchBack()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleSearchBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleSearchBack() {
        if (!mEtInput?.text.isNullOrBlank()) {
            stopSearchLoadingProgress()
            mediaSearchToken += 1
            mediaSearchJob?.cancel()
            viewModel.cancelActiveBookWork()
            mEtInput!!.setText("")
            mRlRefresh!!.visibility = View.GONE
            mKeyWordAdapter?.clear()
            mSearchAdapter?.clear()
            mMediaSearchAdapter?.clear()
            mRvSearch?.removeAllViews()
            setSearchPanelsVisible(true)
            return
        }
        finish()
    }

    override fun onDestroy() {
        stopSearchLoadingProgress()
        searchScope.cancel()
        super.onDestroy()
    }

    override fun getViewBinding(): ActivitySearchBinding {
        return ActivitySearchBinding.inflate(layoutInflater)
    }

    companion object {
        private const val TAG = "SearchActivity"
        private const val TAG_LIMIT = 8
    }
}
