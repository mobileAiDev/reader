package com.ldp.reader.widget.refresh

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ldp.reader.R

abstract class RefreshLayout @JvmOverloads constructor(
    private val mContext: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(mContext, attrs, defStyleAttr) {
    private var mEmptyViewId = 0
    private var mErrorViewId = 0
    private var mLoadingViewId = 0

    private lateinit var mEmptyView: View
    private lateinit var mErrorView: View
    private lateinit var mLoadingView: View
    private lateinit var mContentView: View

    private var mListener: OnReloadingListener? = null
    private var mStatus = 0

    init {
        initAttrs(attrs)
        initView()
    }

    private fun initAttrs(attrs: AttributeSet?) {
        val typedArray = mContext.obtainStyledAttributes(attrs, R.styleable.RefreshLayout)
        mEmptyViewId = typedArray.getResourceId(R.styleable.RefreshLayout_layout_refresh_empty, R.layout.view_empty)
        mErrorViewId = typedArray.getResourceId(R.styleable.RefreshLayout_layout_refresh_error, R.layout.view_net_error)
        mLoadingViewId = typedArray.getResourceId(R.styleable.RefreshLayout_layout_refresh_loading, R.layout.view_loading)
    }

    private fun initView() {
        mEmptyView = inflateView(mEmptyViewId)
        mErrorView = inflateView(mErrorViewId)
        mLoadingView = inflateView(mLoadingViewId)

        mContentView = createContentView(this)
        val params = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        mContentView.layoutParams = params

        toggleStatus(STATUS_LOADING)

        addView(mEmptyView)
        addView(mErrorView)
        addView(mLoadingView)
        addView(mContentView)

        mErrorView.setOnClickListener {
            if (mListener != null) {
                toggleStatus(STATUS_LOADING)
                mListener!!.onReload()
            }
        }
    }

    fun showLoading() {
        if (mStatus != STATUS_LOADING) {
            toggleStatus(STATUS_LOADING)
        }
    }

    fun showFinish() {
        if (mStatus != STATUS_FINISH) {
            toggleStatus(STATUS_FINISH)
        }
    }

    fun showError() {
        if (mStatus != STATUS_ERROR) {
            toggleStatus(STATUS_ERROR)
        }
    }

    fun showEmpty() {
        if (mStatus != STATUS_EMPTY) {
            toggleStatus(STATUS_EMPTY)
        }
    }

    private fun toggleStatus(status: Int) {
        when (status) {
            STATUS_LOADING -> {
                mLoadingView.visibility = VISIBLE
                mEmptyView.visibility = GONE
                mErrorView.visibility = GONE
                mContentView.visibility = GONE
            }
            STATUS_FINISH -> {
                mContentView.visibility = VISIBLE
                mLoadingView.visibility = GONE
                mEmptyView.visibility = GONE
                mErrorView.visibility = GONE
            }
            STATUS_ERROR -> {
                mErrorView.visibility = VISIBLE
                mContentView.visibility = GONE
                mLoadingView.visibility = GONE
                mEmptyView.visibility = GONE
            }
            STATUS_EMPTY -> {
                mEmptyView.visibility = VISIBLE
                mErrorView.visibility = GONE
                mContentView.visibility = GONE
                mLoadingView.visibility = GONE
            }
        }
        mStatus = status
    }

    protected abstract fun createContentView(parent: ViewGroup): View

    protected fun getStatus(): Int {
        return mStatus
    }

    fun setOnReloadingListener(listener: OnReloadingListener?) {
        mListener = listener
    }

    private fun inflateView(id: Int): View {
        return LayoutInflater.from(mContext).inflate(id, this, false)
    }

    interface OnReloadingListener {
        fun onReload()
    }

    companion object {
        private const val STATUS_LOADING = 0
        private const val STATUS_FINISH = 1
        private const val STATUS_ERROR = 2
        private const val STATUS_EMPTY = 3
    }
}
