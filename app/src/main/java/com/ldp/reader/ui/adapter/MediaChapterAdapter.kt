package com.ldp.reader.ui.adapter

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.R
import com.ldp.reader.ui.adapter.view.MediaChapterHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder

class MediaChapterAdapter : BaseListAdapter<MediaChapterItem>() {
    var selectedIndex: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var accentColorRes: Int = R.color.media_comic_accent
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var metaPrefix: String = "序号"
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun createViewHolder(viewType: Int): IViewHolder<MediaChapterItem> {
        return MediaChapterHolder(
            selectedIndexProvider = { selectedIndex },
            accentColorProvider = { accentColorRes },
            metaPrefixProvider = { metaPrefix }
        )
    }
}
