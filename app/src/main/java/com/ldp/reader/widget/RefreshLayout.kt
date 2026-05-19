package com.ldp.reader.widget

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ldp.reader.R

class RefreshLayout @JvmOverloads constructor(
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
    private var mContentView: View? = null

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
        typedArray.recycle()
    }

    private fun initView() {
        mEmptyView = inflateView(mEmptyViewId)
        mErrorView = inflateView(mErrorViewId)
        mLoadingView = inflateView(mLoadingViewId)

        addView(mEmptyView)
        addView(mErrorView)
        addView(mLoadingView)

        mErrorView.setOnClickListener {
            if (mListener != null) {
                toggleStatus(STATUS_LOADING)
                mListener!!.onReload()
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        toggleStatus(STATUS_LOADING)
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (childCount == 4) {
            mContentView = child
        }
    }

    override fun addView(child: View?) {
        if (childCount > 4) {
            throw IllegalStateException("RefreshLayout can host only one direct child")
        }
        super.addView(child)
    }

    override fun addView(child: View?, index: Int) {
        if (childCount > 4) {
            throw IllegalStateException("RefreshLayout can host only one direct child")
        }
        super.addView(child, index)
    }

    override fun addView(child: View?, params: ViewGroup.LayoutParams?) {
        if (childCount > 4) {
            throw IllegalStateException("RefreshLayout can host only one direct child")
        }
        super.addView(child, params)
    }

    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        if (childCount > 4) {
            throw IllegalStateException("RefreshLayout can host only one direct child")
        }
        super.addView(child, index, params)
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
                if (mContentView != null) {
                    mContentView!!.visibility = GONE
                }
            }
            STATUS_FINISH -> {
                if (mContentView != null) {
                    mContentView!!.visibility = VISIBLE
                }
                mLoadingView.visibility = GONE
                mEmptyView.visibility = GONE
                mErrorView.visibility = GONE
            }
            STATUS_ERROR -> {
                mErrorView.visibility = VISIBLE
                mLoadingView.visibility = GONE
                mEmptyView.visibility = GONE
                if (mContentView != null) {
                    mContentView!!.visibility = GONE
                }
            }
            STATUS_EMPTY -> {
                mEmptyView.visibility = VISIBLE
                mErrorView.visibility = GONE
                mLoadingView.visibility = GONE
                if (mContentView != null) {
                    mContentView!!.visibility = GONE
                }
            }
        }
        mStatus = status
    }

    protected fun getStatus(): Int {
        return mStatus
    }

    fun setOnReloadingListener(listener: OnReloadingListener?) {
        mListener = listener
    }

    private fun inflateView(id: Int): View {
        return LayoutInflater.from(mContext).inflate(id, this, false)
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superParcel = super.onSaveInstanceState()
        val savedState = SavedState(superParcel)
        savedState.status = mStatus
        return savedState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val savedState = state as SavedState
        super.onRestoreInstanceState(savedState.superState)
        toggleStatus(savedState.status)
    }

    internal class SavedState : BaseSavedState {
        var status = 0

        constructor(superState: Parcelable?) : super(superState)

        private constructor(parcel: Parcel) : super(parcel) {
            status = parcel.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(status)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(parcel: Parcel): SavedState {
                    return SavedState(parcel)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
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
