package com.ldp.reader.ui.adapter.view

import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemMediaChapterBinding
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.ui.base.adapter.ViewHolderImpl

class MediaChapterHolder(
    private val selectedIndexProvider: () -> Int = { -1 },
    private val accentColorProvider: () -> Int = { R.color.ink_highlight },
    private val textColorProvider: () -> Int = { R.color.ink_text_primary },
    private val ordinalColorProvider: () -> Int = { R.color.ink_text_tertiary },
    private val displayOrdinalProvider: (MediaChapterItem) -> Int = { chapter -> chapter.index + 1 }
) : ViewHolderImpl<MediaChapterItem>() {
    private lateinit var title: TextView
    private lateinit var meta: TextView

    override fun initView() {
        val binding = ItemMediaChapterBinding.bind(getItemView())
        title = binding.mediaChapterTitle
        meta = binding.mediaChapterMeta
    }

    override fun onBind(chapter: MediaChapterItem, pos: Int) {
        val displayOrdinal = displayOrdinalProvider(chapter)
        title.text = chapter.title.ifBlank { "第 $displayOrdinal 章" }
        meta.text = "$displayOrdinal"
        val selected = chapter.index == selectedIndexProvider()
        title.setTextColor(getContext().getColor(if (selected) accentColorProvider() else textColorProvider()))
        meta.setTextColor(getContext().getColor(if (selected) accentColorProvider() else ordinalColorProvider()))
    }

    override fun getItemLayoutId(): Int = R.layout.item_media_chapter
}
