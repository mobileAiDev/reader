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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivitySearchBinding
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.source.AiBridgeTrace
import com.ldp.reader.ui.activity.BookDetailActivity.Companion.startActivity
import com.ldp.reader.ui.adapter.KeyWordAdapter
import com.ldp.reader.ui.adapter.SearchBookAdapter
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.utils.SystemBarUtils
import com.ldp.reader.widget.RefreshLayout
import com.ldp.reader.widget.itemdecoration.DividerItemDecoration
import me.gujun.android.taggroup.TagGroup

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
    private var isTag = false
    private var mHotTagList: List<String> = emptyList()
    private var mTagStart = 0
    private var activeBookSearchQuery = ""
    private var activeBookSearchStartedAtMs = 0L
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
        mRvSearch!!.layoutManager = LinearLayoutManager(this)
        mRvSearch!!.addItemDecoration(DividerItemDecoration(this))
    }

    override fun initClick() {
        super.initClick()

        //退出
        mIvBack!!.setOnClickListener { v: View? -> onBackPressed() }

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
                        mRvSearch!!.removeAllViews()
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
                } else {
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
            toggleKeyboard()
        }

        //点击查书
        mKeyWordAdapter!!.setOnItemClickListener { view: View?, pos: Int ->
            val book = mKeyWordAdapter!!.getItem(pos)
            beginBookSearch(book)
            toggleKeyboard()
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
            toggleKeyboard()
        }

        //Tag的刷新事件
        mTvRefreshHot!!.setOnClickListener { v: View? -> refreshTag() }

        //书本的点击事件
        mSearchAdapter!!.setOnItemClickListener { view: View?, pos: Int ->
            val bookId = mSearchAdapter!!.getItem(pos).id
            viewModel.cancelActiveBookWork()
            startActivity(this, bookId)
        }
    }

    private fun searchBook() {
        val query = mEtInput!!.text.toString().trim { it <= ' ' }
        if (query != "") {
            beginBookSearch(query)
            toggleKeyboard()
        }
    }

    private fun beginBookSearch(query: String) {
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
        traceSearchUi("source_search_ui_loading", query, "reason_begin")
        mSearchAdapter!!.refreshItems(emptyList())
        traceSearchUi("source_search_ui_adapter_cleared", query, "count_0")
        viewModel.searchBook(query)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(0, InputMethodManager.HIDE_NOT_ALWAYS)
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
        setSearchPanelsVisible(false)
        mRlRefresh!!.showEmpty()
        val query = activeBookSearchQuery.ifBlank { mEtInput?.text?.toString()?.trim().orEmpty() }
        traceSearchUi("source_search_ui_empty", query, "reason_error")
    }

    private fun setSearchPanelsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding?.searchAssistantEntry?.visibility = visibility
        binding?.searchHotPanel?.visibility = visibility
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
        if (mRlRefresh!!.visibility == View.VISIBLE) {
            mEtInput!!.setText("")
        } else {
            super.onBackPressed()
        }
    }

    override fun getViewBinding(): ActivitySearchBinding {
        return ActivitySearchBinding.inflate(layoutInflater)
    }

    companion object {
        private const val TAG = "SearchActivity"
        private const val TAG_LIMIT = 8
    }
}
