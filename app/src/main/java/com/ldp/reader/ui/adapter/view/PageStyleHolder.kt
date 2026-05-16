package com.ldp.reader.ui.adapter.view

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemReadBgBinding
import com.ldp.reader.ui.base.adapter.ViewHolderImpl

/**
 * Created by ldp on 17-5-19.
 */
class PageStyleHolder : ViewHolderImpl<Drawable>() {
    private lateinit var mReadBg: View
    private lateinit var mIvChecked: ImageView

    override fun initView() {
        val binding = ItemReadBgBinding.bind(getItemView())
        mReadBg = binding.readBgView
        mIvChecked = binding.readBgIvChecked
    }

    override fun onBind(data: Drawable, pos: Int) {
        mReadBg.background = data
        mIvChecked.visibility = View.GONE
    }

    override fun getItemLayoutId(): Int {
        return R.layout.item_read_bg
    }

    fun setChecked() {
        mIvChecked.visibility = View.VISIBLE
    }
}
