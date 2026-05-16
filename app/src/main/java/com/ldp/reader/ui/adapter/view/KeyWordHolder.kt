package com.ldp.reader.ui.adapter.view

import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemKeywordBinding
import com.ldp.reader.ui.base.adapter.ViewHolderImpl

/**
 * Created by ldp on 17-6-2.
 */
class KeyWordHolder : ViewHolderImpl<String>() {
    private lateinit var mTvName: TextView

    override fun initView() {
        val binding = ItemKeywordBinding.bind(getItemView())
        mTvName = binding.keywordTvName
    }

    override fun onBind(data: String, pos: Int) {
        mTvName.text = data
    }

    override fun getItemLayoutId(): Int {
        return R.layout.item_keyword
    }
}
