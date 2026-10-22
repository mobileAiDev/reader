package com.ldp.reader.widget.tab

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ldp.reader.R
import com.ldp.reader.databinding.LibPubViewTabBinding

class TabViewGroup @JvmOverloads constructor(
    private val mContext: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(mContext, attrs, defStyleAttr), TabView {
    private lateinit var mTvTitle: TextView
    private lateinit var mTvNumber: TextView

    init {
        init(mContext)
    }

    private fun init(context: Context) {
        val binding = LibPubViewTabBinding.inflate(LayoutInflater.from(context), this)
        mTvTitle = binding.tvTabTitle
        mTvNumber = binding.tvTabNumber
    }

    override fun setText(text: String?) {
        mTvTitle.text = text
    }

    override fun setPadding(padding: Int) {
        setPadding(padding, 0, padding, 0)
    }

    override fun setNumber(text: String?, visibility: Int) {
        mTvNumber.text = text
        mTvNumber.visibility = visibility
    }

    override fun notifyData(focus: Boolean) {
        mTvTitle.setTextColor(
            ContextCompat.getColor(
                mContext,
                if (focus) R.color.ink_primary else R.color.ink_text_tertiary
            )
        )
    }

    override fun onScroll(factor: Float) {
    }
}
