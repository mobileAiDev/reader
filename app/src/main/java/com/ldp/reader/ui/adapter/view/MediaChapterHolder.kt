package com.ldp.reader.ui.adapter.view

import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemMediaChapterBinding
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.ui.base.adapter.ViewHolderImpl

class MediaChapterHolder(
    private val selectedIndexProvider: () -> Int = { -1 },
    private val accentColorProvider: () -> Int = { R.color.media_comic_accent },
    private val metaPrefixProvider: () -> String = { "序号" }
) : ViewHolderImpl<MediaChapterItem>() {
    private lateinit var title: TextView
    private lateinit var meta: TextView

    override fun initView() {
        val binding = ItemMediaChapterBinding.bind(getItemView())
        title = binding.mediaChapterTitle
        meta = binding.mediaChapterMeta
    }

    override fun onBind(chapter: MediaChapterItem, pos: Int) {
        title.text = chapter.title.ifBlank { "第 ${chapter.index + 1} 章" }
        val prefix = metaPrefixProvider().trim()
        meta.text = if (prefix.isBlank()) {
            "${chapter.index + 1}"
        } else {
            "$prefix ${chapter.index + 1}"
        }
        val selected = chapter.index == selectedIndexProvider()
        title.setTextColor(
            getContext().resources.getColor(
                if (selected) accentColorProvider() else R.color.media_text_primary
            )
        )
    }

    override fun getItemLayoutId(): Int = R.layout.item_media_chapter
}
