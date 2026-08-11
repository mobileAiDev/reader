package com.ldp.reader.ui.activity

import android.os.Bundle
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivitySearchBinding
import com.ldp.reader.media.MediaSearchCancellationHandle
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
import com.ldp.reader.utils.ToastUtils
import com.ldp.reader.widget.RefreshLayout
import com.ldp.reader.widget.itemdecoration.DividerItemDecoration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
    var mTgHot: ChipGroup? = null
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
    private var hasSubmittedSearch = false
    private var restoredFromInstanceState = false
    private var restoredInputQuery = ""
    private var restoredSubmittedQuery = ""
    private var restoringSearchInput = false
    private var mediaSearchToken = 0
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSearchJob: Job? = null
    private var mediaSearchCancellationHandle: MediaSearchCancellationHandle? = null
    private var searchLoadingProgressJob: Job? = null
    private var searchLoadingResultCount = 0
    private var bookProgressHideJob: Job? = null
    private lateinit var viewModel: SearchViewModel

    override fun initData(savedInstanceState: Bundle?) {
        super.initData(savedInstanceState)
        if (savedInstanceState == null) return
        restoredFromInstanceState = true
        activeSearchKind = savedInstanceState.getString(STATE_SEARCH_KIND)
            ?.let { ReaderMediaKind.fromSeedKey(it) }
            ?: ReaderMediaKind.NOVEL
        restoredInputQuery = savedInstanceState.getString(STATE_INPUT_QUERY).orEmpty()
        hasSubmittedSearch = savedInstanceState.getBoolean(STATE_SEARCH_SUBMITTED)
        restoredSubmittedQuery = savedInstanceState.getString(STATE_SUBMITTED_QUERY).orEmpty()
        activeBookSearchQuery = restoredSubmittedQuery.takeIf { hasSubmittedSearch }.orEmpty()
    }

    override fun initWidget() {
        super.initWidget()
        viewModel = ViewModelProvider(this)[SearchViewModel::class.java]
        observeSearchState()
        mIvBack = binding!!.searchIvBack
        mEtInput = binding!!.searchEtInput
        mEtInput?.isSaveEnabled = false
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
        mRlRefresh!!.setOnReloadingListener(object : RefreshLayout.OnReloadingListener {
            override fun onReload() {
                retryActiveSearch()
            }
        })
        binding.searchAssistantEntry.setOnClickListener { focusSearchInput() }

        //输入框
        mEtInput!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (restoringSearchInput) return
                val inputChange = SearchFlowPolicy.inputChange(activeSearchKind, s.toString())
                if (inputChange.normalizedQuery.isEmpty()) {
                    cancelActiveSearchForInputChange()
                    activeBookSearchQuery = inputChange.activeSubmittedQuery
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
                        hideBookSearchProgressBar()
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
                val query = inputChange.normalizedQuery
                if (isTag) {
                    mRlRefresh!!.showLoading()
                    isTag = false
                } else {
                    cancelActiveSearchForInputChange()
                    activeBookSearchQuery = inputChange.activeSubmittedQuery
                    val keywordQuery = inputChange.keywordLookupQuery
                    if (keywordQuery != null) {
                        setSearchPanelsVisible(false)
                        mKeyWordAdapter!!.clear()
                        if (mRvSearch!!.adapter !is KeyWordAdapter) {
                            mRvSearch!!.adapter = mKeyWordAdapter
                        }
                        mRlRefresh!!.visibility = View.VISIBLE
                        mRlRefresh!!.showLoading()
                        viewModel.searchKeyWord(keywordQuery)
                    } else {
                        mMediaSearchAdapter!!.clear()
                        mRlRefresh!!.showFinish()
                    }
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
            val book = mKeyWordAdapter!!.getItem(pos).trim()
            if (book.isEmpty()) return@setOnItemClickListener
            isTag = true
            mEtInput!!.setText(book)
            isTag = false
            mEtInput!!.setSelection(mEtInput!!.text.length)
            beginBookSearch(book)
            hideKeyboard()
        }

        //Tag的刷新事件
        mTvRefreshHot!!.setOnClickListener { v: View? -> refreshTag() }

        //书本的点击事件
        mSearchAdapter!!.setOnItemClickListener { view: View?, pos: Int ->
            val book = mSearchAdapter!!.getItem(pos)
            val bookId = book.id
            if (SearchFlowPolicy.novelResultClickAction(book.readingReady) ==
                NovelResultClickAction.WAIT_FOR_READING_CONTENT
            ) {
                traceSearchUi(
                    "source_search_ui_result_waiting",
                    activeBookSearchQuery.ifBlank { mEtInput?.text?.toString()?.trim().orEmpty() },
                    AiBridgeTrace.fields(
                        "pos" to pos,
                        "title" to book.title.orEmpty(),
                        "author" to book.author.orEmpty(),
                        "route" to bookId.orEmpty()
                    )
                )
                ToastUtils.show("这本书还在加载，请稍后再打开")
                return@setOnItemClickListener
            }
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
            hideBookSearchProgressBar()
            startActivity(this, bookId)
        }
        mMediaSearchAdapter!!.setOnItemClickListener { _, pos ->
            val book = mMediaSearchAdapter!!.getItem(pos)
            stopSearchLoadingProgress()
            hideBookSearchProgressBar()
            when (activeSearchKind) {
                ReaderMediaKind.COMIC -> ComicDetailActivity.start(this, book.routeId)
                ReaderMediaKind.AUDIO -> AudioDetailActivity.start(this, book.routeId)
                ReaderMediaKind.NOVEL -> Unit
            }
        }
        restoreSearchChrome()
    }

    private fun searchBook() {
        val query = mEtInput!!.text.toString().trim { it <= ' ' }
        AiBridgeTrace.event(
            "media_search_ui_search_click",
            query,
            AiBridgeTrace.fields(
                "kind" to activeSearchKind.seedKey,
                "empty" to query.isEmpty()
            )
        )
        Log.i(TAG, "operation=search_click query=$query kind=${activeSearchKind.seedKey} empty=${query.isEmpty()}")
        if (query != "") {
            beginBookSearch(query)
            hideKeyboard()
        }
    }

    private fun beginBookSearch(query: String) {
        hasSubmittedSearch = true
        if (activeSearchKind != ReaderMediaKind.NOVEL) {
            beginMediaSearch(query, activeSearchKind)
            return
        }
        activeBookSearchQuery = query
        activeBookSearchStartedAtMs = System.currentTimeMillis()
        hideBookSearchProgressBar()
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
        hideBookSearchProgressBar()
        traceSearchUi("media_search_ui_begin", query, "kind_${kind.seedKey}")
        val token = ++mediaSearchToken
        cancelMediaSearch()
        val cancellationHandle = MediaSearchCancellationHandle()
        mediaSearchCancellationHandle = cancellationHandle
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
            try {
                val books = runInterruptible(Dispatchers.IO) {
                    MediaSourceRepository.search(
                        kind = kind,
                        query = query,
                        cancellationHandle = cancellationHandle
                    ) { partial ->
                        searchScope.launch {
                            if (
                                SearchFlowPolicy.isCurrentMediaResponse(
                                    activeSearchKind,
                                    kind,
                                    mediaSearchToken,
                                    token
                                )
                            ) {
                                traceSearchUi(
                                    "media_search_ui_partial",
                                    query,
                                    "kind_${kind.seedKey}_count_${partial.size}"
                                )
                                updateSearchLoadingProgress(query, kind, partial.size, finished = false)
                                renderMediaSearchResults(
                                    query = query,
                                    kind = kind,
                                    books = partial,
                                    finished = false
                                )
                            }
                        }
                    }
                }
                if (
                    !SearchFlowPolicy.isCurrentMediaResponse(
                        activeSearchKind,
                        kind,
                        mediaSearchToken,
                        token
                    )
                ) return@launch
                traceSearchUi("media_search_ui_final", query, "kind_${kind.seedKey}_count_${books.size}")
                updateSearchLoadingProgress(query, kind, books.size, finished = true)
                renderMediaSearchResults(
                    query = query,
                    kind = kind,
                    books = books,
                    finished = true
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "media search failed: kind=$kind query=$query", error)
                if (
                    SearchFlowPolicy.isCurrentMediaResponse(
                        activeSearchKind,
                        kind,
                        mediaSearchToken,
                        token
                    )
                ) {
                    renderMediaSearchError(query, kind, error)
                }
            } finally {
                if (mediaSearchCancellationHandle === cancellationHandle) {
                    mediaSearchCancellationHandle = null
                }
            }
        }
    }

    private fun renderMediaSearchResults(
        query: String,
        kind: ReaderMediaKind,
        books: List<com.ldp.reader.media.MediaSearchBook>,
        finished: Boolean
    ) {
        mMediaSearchAdapter!!.refreshItems(books)
        when {
            books.isNotEmpty() && !finished -> {
                // Partial results are already useful, but the repository is still validating
                // more sources. Keep the list interactive without pretending the search ended.
                mRlRefresh!!.showFinish()
                showMediaSearchProgressBar(
                    query = query,
                    kind = kind,
                    resultCount = books.size,
                    finished = false
                )
            }
            books.isNotEmpty() -> {
                stopSearchLoadingProgress()
                mRlRefresh!!.showFinish()
                showMediaSearchProgressBar(
                    query = query,
                    kind = kind,
                    resultCount = books.size,
                    finished = true
                )
            }
            finished -> {
                stopSearchLoadingProgress()
                hideBookSearchProgressBar()
                mRlRefresh!!.showEmpty()
            }
            else -> {
                hideBookSearchProgressBar()
                mRlRefresh!!.showLoading()
            }
        }
    }

    private fun showMediaSearchProgressBar(
        query: String,
        kind: ReaderMediaKind,
        resultCount: Int,
        finished: Boolean
    ) {
        bookProgressHideJob?.cancel()
        bookProgressHideJob = null
        val elapsedMs = (System.currentTimeMillis() - activeBookSearchStartedAtMs)
            .coerceAtLeast(0)
        val presentation = SearchFlowPolicy.loadingPresentation(
            kind = kind,
            elapsedMs = elapsedMs,
            resultCount = resultCount,
            finished = finished
        )
        binding.searchBookProgressText.text = presentation.message
        binding.searchBookProgressCount.text = SearchFlowPolicy.resultCountText(kind, resultCount)
        binding.searchBookProgressSpinner.visibility = if (finished) View.GONE else View.VISIBLE
        binding.searchBookProgressBar.visibility = View.VISIBLE
        AiBridgeTrace.state(
            "media_search_ui_progress_bar",
            "${kind.seedKey}:$query",
            AiBridgeTrace.fields(
                "visible" to true,
                "count" to resultCount,
                "finished" to finished,
                "message" to presentation.message.traceToken()
            )
        )
        if (finished) {
            bookProgressHideJob = searchScope.launch {
                delay(1_500)
                if (activeSearchKind == kind && activeBookSearchQuery == query) {
                    binding.searchBookProgressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun renderMediaSearchError(query: String, kind: ReaderMediaKind, error: Throwable) {
        stopSearchLoadingProgress()
        hideBookSearchProgressBar()
        mMediaSearchAdapter!!.clear()
        mRlRefresh!!.showError()
        traceSearchUi(
            "media_search_ui_error",
            query,
            "kind_${kind.seedKey}_error_${error.javaClass.simpleName.traceToken()}"
        )
    }

    private fun startSearchLoadingProgress(query: String, kind: ReaderMediaKind) {
        searchLoadingProgressJob?.cancel()
        searchLoadingResultCount = 0
        updateSearchLoadingProgress(query, kind, resultCount = 0, finished = false)
        searchLoadingProgressJob = searchScope.launch {
            while (true) {
                delay(1_000)
                if (activeBookSearchQuery == query && activeSearchKind == kind) {
                    updateSearchLoadingProgress(
                        query,
                        kind,
                        resultCount = searchLoadingResultCount,
                        finished = false
                    )
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
        searchLoadingResultCount = resultCount.coerceAtLeast(0)
        val presentation = SearchFlowPolicy.loadingPresentation(
            kind,
            elapsedMs,
            searchLoadingResultCount,
            finished
        )
        mRlRefresh?.updateLoadingProgress(
            title = "正在搜索",
            message = presentation.message,
            percent = presentation.percent
        )
        AiBridgeTrace.state(
            "media_search_loading",
            "${kind.seedKey}:$query",
            AiBridgeTrace.fields(
                "percent" to (presentation.percent ?: "indeterminate"),
                "step" to presentation.message.traceToken(),
                "count" to searchLoadingResultCount,
                "finished" to finished
            )
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val token = mEtInput?.windowToken ?: binding.root.windowToken
        imm.hideSoftInputFromWindow(token, 0)
        mEtInput?.clearFocus()
    }

    private fun focusSearchInput() {
        val input = mEtInput ?: return
        input.requestFocus()
        input.setSelection(input.text.length)
        input.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        ToastUtils.show("输入书名或作者后，点击搜索")
        AiBridgeTrace.event("search_assistant_input_focused", activeSearchKind.seedKey, "query_${input.text.toString().trim().traceToken()}")
    }

    override fun processLogic() {
        super.processLogic()
        SystemBarUtils.showStableStatusBar(this)
        SystemBarUtils.transparentStatusBar(this)
        //默认隐藏
        if (!restoredFromInstanceState) {
            mRlRefresh!!.visibility = View.GONE
        }
        //获取热词
        viewModel.searchHotWord()
        if (!restoreSavedSearchState()) {
            handleInitialSearchIntent()
        }
    }

    private fun restoreSearchChrome() {
        mEtInput?.hint = activeSearchKind.searchHint
        updateSearchKindTabs()
        if (!restoredFromInstanceState) {
            setSearchPanelsVisible(true)
            return
        }
        restoringSearchInput = true
        mEtInput?.setText(restoredInputQuery)
        mEtInput?.setSelection(mEtInput?.text?.length ?: 0)
        restoringSearchInput = false
        val hasInput = restoredInputQuery.isNotBlank()
        mIvDelete?.visibility = if (hasInput) View.VISIBLE else View.INVISIBLE
        mRlRefresh?.visibility = if (hasInput) View.VISIBLE else View.GONE
        if (hasInput) mRlRefresh?.showFinish()
        setSearchPanelsVisible(!hasInput)
    }

    private fun restoreSavedSearchState(): Boolean {
        if (!restoredFromInstanceState) return false
        val retainedNovelState = if (
            activeSearchKind == ReaderMediaKind.NOVEL && hasSubmittedSearch
        ) {
            viewModel.retainedBookSearchState(restoredSubmittedQuery)
        } else {
            null
        }
        val plan = SearchFlowPolicy.restorationPlan(
            savedKind = activeSearchKind.seedKey,
            rawInputQuery = restoredInputQuery,
            rawSubmittedQuery = restoredSubmittedQuery,
            searchWasSubmitted = hasSubmittedSearch,
            hasRetainedNovelSession = retainedNovelState != null
        )
        activeSearchKind = plan.kind
        activeBookSearchQuery = plan.submittedQuery
        hasSubmittedSearch = plan.submittedQuery.isNotEmpty()
        if (!hasSubmittedSearch) {
            if (plan.kind == ReaderMediaKind.NOVEL && plan.inputQuery.isNotEmpty()) {
                mRlRefresh!!.visibility = View.VISIBLE
                mRlRefresh!!.showLoading()
                viewModel.searchKeyWord(plan.inputQuery)
            }
            return true
        }

        if (retainedNovelState != null) {
            restoreRetainedNovelSearch(plan.submittedQuery, retainedNovelState)
        } else if (plan.shouldRestartSearch) {
            beginBookSearch(plan.submittedQuery)
        }
        return true
    }

    private fun restoreRetainedNovelSearch(query: String, state: RetainedBookSearchState) {
        activeBookSearchStartedAtMs = System.currentTimeMillis()
        mRlRefresh!!.visibility = View.VISIBLE
        setSearchPanelsVisible(false)
        if (mRvSearch!!.adapter !is SearchBookAdapter) {
            mRvSearch!!.adapter = mSearchAdapter
        }
        when {
            state.results.isNotEmpty() -> finishBooks(state.results)
            state.progress?.phase == BookSearchProgressPhase.ERROR -> errorBooks()
            state.progress?.phase == BookSearchProgressPhase.EMPTY ||
                state.progress?.phase == BookSearchProgressPhase.FINISHED -> mRlRefresh!!.showEmpty()
            else -> {
                mRlRefresh!!.showLoading()
                startSearchLoadingProgress(query, ReaderMediaKind.NOVEL)
            }
        }
        state.progress?.let(::renderBookSearchProgress)
    }

    private fun handleInitialSearchIntent() {
        val query = intent.getStringExtra(EXTRA_SEARCH_QUERY)
            ?: intent.getStringExtra(EXTRA_QUERY)
            ?: intent.data?.getQueryParameter(EXTRA_QUERY)
            ?: intent.data?.getQueryParameter(EXTRA_SEARCH_QUERY)
            ?: return
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        mEtInput?.setText(trimmed)
        mEtInput?.setSelection(mEtInput?.text?.length ?: 0)
        beginBookSearch(trimmed)
        hideKeyboard()
    }

    private fun observeSearchState() {
        viewModel.hotWords.observe(this) { hotWords -> finishHotWords(hotWords) }
        viewModel.keyWords.observe(this) { state -> finishKeyWords(state) }
        viewModel.books.observe(this) { books -> finishBooks(books) }
        viewModel.bookSearchProgress.observe(this) { progress -> renderBookSearchProgress(progress) }
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
        renderHotTerms(tags)
        mTagStart += TAG_LIMIT
    }

    private fun renderHotTerms(tags: List<String>) {
        val hotTermGroup = mTgHot ?: return
        hotTermGroup.removeAllViews()
        tags.forEach { rawTag ->
            val tag = rawTag.trim { it <= ' ' }
            if (tag.isEmpty()) return@forEach
            val chip = layoutInflater.inflate(
                R.layout.item_search_hot_term,
                hotTermGroup,
                false
            ) as Chip
            chip.text = tag
            chip.contentDescription = tag
            chip.setOnClickListener { submitHotTerm(tag) }
            hotTermGroup.addView(chip)
        }
    }

    private fun submitHotTerm(tag: String) {
        isTag = true
        mEtInput!!.setText(tag)
        isTag = false
        mEtInput!!.setSelection(mEtInput!!.text.length)
        beginBookSearch(tag)
        hideKeyboard()
    }

    private fun finishKeyWords(state: KeywordSearchState) {
        if (
            !SearchFlowPolicy.isCurrentKeywordResponse(
                activeKind = activeSearchKind,
                activeSubmittedQuery = activeBookSearchQuery,
                rawInputQuery = mEtInput?.text?.toString(),
                responseQuery = state.query
            )
        ) return
        hideBookSearchProgressBar()
        setSearchPanelsVisible(false)
        mRlRefresh!!.visibility = View.VISIBLE
        mKeyWordAdapter!!.refreshItems(state.suggestions)
        if (mRvSearch!!.adapter !is KeyWordAdapter) {
            mRvSearch!!.adapter = mKeyWordAdapter
        }
        if (state.suggestions.isEmpty()) {
            mRlRefresh!!.showEmpty()
        } else {
            mRlRefresh!!.showFinish()
        }
    }

    private fun finishBooks(books: List<BookSearchResult>) {
        if (activeSearchKind != ReaderMediaKind.NOVEL || activeBookSearchQuery.isBlank()) return
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
            hideBookSearchProgressBar()
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
        if (activeSearchKind != ReaderMediaKind.NOVEL || activeBookSearchQuery.isBlank()) return
        setSearchPanelsVisible(false)
        mRlRefresh!!.showError()
        stopSearchLoadingProgress()
        hideBookSearchProgressBar()
        val query = activeBookSearchQuery.ifBlank { mEtInput?.text?.toString()?.trim().orEmpty() }
        traceSearchUi("source_search_ui_error", query, "reason_request_failed")
    }

    private fun renderBookSearchProgress(progress: BookSearchProgressState) {
        if (activeSearchKind != ReaderMediaKind.NOVEL || progress.query != activeBookSearchQuery) return
        updateSearchLoadingProgress(
            query = progress.query,
            kind = ReaderMediaKind.NOVEL,
            resultCount = progress.resultCount,
            finished = progress.phase == BookSearchProgressPhase.FINISHED ||
                progress.phase == BookSearchProgressPhase.EMPTY
        )
        when (progress.phase) {
            BookSearchProgressPhase.SEARCHING,
            BookSearchProgressPhase.EMPTY,
            BookSearchProgressPhase.ERROR -> hideBookSearchProgressBar()
            BookSearchProgressPhase.FOUND_SEARCHING -> showBookSearchProgressBar(
                message = SearchFlowPolicy.novelProgressMessage(progress),
                resultCount = progress.resultCount,
                spinning = true,
                autoHide = false,
                progress = progress
            )
            BookSearchProgressPhase.PREPARING_READING -> showBookSearchProgressBar(
                message = SearchFlowPolicy.novelProgressMessage(progress),
                resultCount = progress.resultCount,
                spinning = true,
                autoHide = false,
                progress = progress
            )
            BookSearchProgressPhase.CHECKING_READING_SOURCE -> showBookSearchProgressBar(
                message = SearchFlowPolicy.novelProgressMessage(progress),
                resultCount = progress.resultCount,
                spinning = true,
                autoHide = false,
                progress = progress
            )
            BookSearchProgressPhase.WAITING_READING_SOURCE -> showBookSearchProgressBar(
                message = SearchFlowPolicy.novelProgressMessage(progress),
                resultCount = progress.resultCount,
                spinning = true,
                autoHide = false,
                progress = progress
            )
            BookSearchProgressPhase.READY -> showBookSearchProgressBar(
                message = SearchFlowPolicy.novelProgressMessage(progress),
                resultCount = progress.resultCount,
                spinning = false,
                autoHide = false,
                progress = progress
            )
            BookSearchProgressPhase.FINISHED -> showBookSearchProgressBar(
                message = SearchFlowPolicy.novelProgressMessage(progress),
                resultCount = progress.resultCount,
                spinning = false,
                autoHide = true,
                progress = progress
            )
        }
    }

    private fun showBookSearchProgressBar(
        message: String,
        resultCount: Int,
        spinning: Boolean,
        autoHide: Boolean,
        progress: BookSearchProgressState
    ) {
        bookProgressHideJob?.cancel()
        bookProgressHideJob = null
        binding.searchBookProgressText.text = message
        binding.searchBookProgressCount.text = "${resultCount} 本"
        binding.searchBookProgressSpinner.visibility = if (spinning) View.VISIBLE else View.GONE
        binding.searchBookProgressBar.visibility = View.VISIBLE
        AiBridgeTrace.state(
            "source_search_ui_progress_bar",
            progress.query,
            AiBridgeTrace.fields(
                "phase" to progress.phase.name.lowercase(),
                "visible" to true,
                "count" to resultCount,
                "message" to message.traceToken(),
                "elapsedMs" to progress.elapsedMs,
                "attempt" to progress.attempt,
                "nextDelayMs" to progress.nextDelayMs,
                "checkedCount" to progress.checkedCount,
                "targetCount" to progress.targetCount
            )
        )
        if (autoHide) {
            bookProgressHideJob = searchScope.launch {
                delay(1_500)
                if (activeSearchKind == ReaderMediaKind.NOVEL && activeBookSearchQuery == progress.query) {
                    binding.searchBookProgressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun hideBookSearchProgressBar() {
        bookProgressHideJob?.cancel()
        bookProgressHideJob = null
        binding.searchBookProgressBar.visibility = View.GONE
    }

    private fun setSearchPanelsVisible(visible: Boolean) {
        val visibility = if (visible && activeSearchKind == ReaderMediaKind.NOVEL) View.VISIBLE else View.GONE
        binding?.searchAssistantEntry?.visibility = visibility
        binding?.searchHotPanel?.visibility = visibility
    }

    private fun selectSearchKind(kind: ReaderMediaKind) {
        val previousKind = activeSearchKind
        val restartQuery = SearchFlowPolicy.queryToRestart(
            currentKind = previousKind,
            selectedKind = kind,
            rawQuery = mEtInput?.text?.toString()
        )
        activeSearchKind = kind
        AiBridgeTrace.event("media_search_ui_kind_selected", kind.seedKey, mEtInput?.text?.toString().orEmpty())
        mEtInput?.hint = kind.searchHint
        updateSearchKindTabs()
        if (previousKind == kind) {
            setSearchPanelsVisible(mEtInput?.text.isNullOrBlank())
            return
        }
        hasSubmittedSearch = false
        mediaSearchToken += 1
        cancelMediaSearch()
        stopSearchLoadingProgress()
        hideBookSearchProgressBar()
        viewModel.cancelActiveBookWork()
        mKeyWordAdapter?.clear()
        mSearchAdapter?.clear()
        mMediaSearchAdapter?.clear()
        mRvSearch?.removeAllViews()
        mRlRefresh?.visibility = View.GONE
        setSearchPanelsVisible(mEtInput?.text.isNullOrBlank())
        if (restartQuery != null) {
            beginBookSearch(restartQuery)
        }
    }

    private fun retryActiveSearch() {
        val query = mEtInput?.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            setSearchPanelsVisible(true)
            mRlRefresh?.visibility = View.GONE
            focusSearchInput()
            return
        }
        beginBookSearch(query)
    }

    private fun cancelActiveSearchForInputChange() {
        hasSubmittedSearch = false
        mediaSearchToken += 1
        cancelMediaSearch()
        stopSearchLoadingProgress()
        hideBookSearchProgressBar()
        viewModel.cancelActiveBookWork()
    }

    private fun updateSearchKindTabs() {
        val tabs = mapOf(
            ReaderMediaKind.NOVEL to binding.searchTabNovel,
            ReaderMediaKind.COMIC to binding.searchTabComic,
            ReaderMediaKind.AUDIO to binding.searchTabAudio
        )
        SearchFlowPolicy.tabStates(activeSearchKind).forEach { state ->
            val tab = tabs.getValue(state.kind)
            tab.isSelected = state.selected
            tab.setBackgroundResource(if (state.selected) R.drawable.bg_search_tab_selected else R.drawable.bg_search_tab_normal)
            tab.setTextColor(resources.getColor(if (state.selected) R.color.home_text_on_primary else R.color.home_text_primary))
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
        Log.i(
            TAG,
            "operation=search_back query=${activeBookSearchQuery.ifBlank { mEtInput?.text?.toString()?.trim().orEmpty() }} kind=${activeSearchKind.seedKey}"
        )
        cancelSearchWorkForBack()
        startActivity(MainActivity.bookshelfIntent(this))
        finish()
    }

    private fun cancelSearchWorkForBack() {
        val mediaActive = mediaSearchJob?.isActive == true
        stopSearchLoadingProgress()
        hideBookSearchProgressBar()
        mediaSearchToken += 1
        cancelMediaSearch()
        viewModel.cancelActiveBookWork()
        AiBridgeTrace.event(
            "source_search_ui_back",
            activeBookSearchQuery.ifBlank { mEtInput?.text?.toString()?.trim().orEmpty() },
            AiBridgeTrace.fields(
                "target" to "bookshelf",
                "mediaActive" to mediaActive
            )
        )
    }

    override fun onDestroy() {
        stopSearchLoadingProgress()
        cancelMediaSearch()
        if (!isChangingConfigurations) {
            viewModel.cancelActiveBookWork()
        }
        searchScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SEARCH_KIND, activeSearchKind.seedKey)
        outState.putString(STATE_INPUT_QUERY, mEtInput?.text?.toString().orEmpty())
        outState.putBoolean(STATE_SEARCH_SUBMITTED, hasSubmittedSearch)
        outState.putString(
            STATE_SUBMITTED_QUERY,
            activeBookSearchQuery.takeIf { hasSubmittedSearch }.orEmpty()
        )
        super.onSaveInstanceState(outState)
    }

    private fun cancelMediaSearch() {
        mediaSearchCancellationHandle?.cancel()
        mediaSearchCancellationHandle = null
        mediaSearchJob?.cancel()
        mediaSearchJob = null
    }

    override fun getViewBinding(): ActivitySearchBinding {
        return ActivitySearchBinding.inflate(layoutInflater)
    }

    companion object {
        private const val TAG = "SearchActivity"
        private const val TAG_LIMIT = 8
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_SEARCH_QUERY = "searchQuery"
        private const val STATE_SEARCH_KIND = "search.kind"
        private const val STATE_INPUT_QUERY = "search.inputQuery"
        private const val STATE_SEARCH_SUBMITTED = "search.submitted"
        private const val STATE_SUBMITTED_QUERY = "search.submittedQuery"
    }
}
