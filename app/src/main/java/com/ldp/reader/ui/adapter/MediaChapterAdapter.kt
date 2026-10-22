package com.ldp.reader.ui.adapter

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.R
import com.ldp.reader.ui.adapter.view.MediaChapterHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder

class MediaChapterAdapter : BaseListAdapter<MediaChapterItem>() {
    private var displayOrdinals = MediaChapterDisplayOrdinals.sourceIndexes()

    var selectedIndex: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    /** Colour of the chapter being read or played. */
    var accentColorRes: Int = R.color.ink_highlight
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    /** Title and number colours; pages on a dark backdrop (the audio player) pass light ones. */
    var textColorRes: Int = R.color.ink_text_primary
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var ordinalColorRes: Int = R.color.ink_text_tertiary
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun useChronologicalDisplayOrder(chapters: List<MediaChapterItem>) {
        displayOrdinals = MediaChapterDisplayOrdinals.chronological(chapters)
        notifyDataSetChanged()
    }

    fun useSourceIndexDisplayOrder() {
        displayOrdinals = MediaChapterDisplayOrdinals.sourceIndexes()
        notifyDataSetChanged()
    }

    override fun createViewHolder(viewType: Int): IViewHolder<MediaChapterItem> {
        return MediaChapterHolder(
            selectedIndexProvider = { selectedIndex },
            accentColorProvider = { accentColorRes },
            textColorProvider = { textColorRes },
            ordinalColorProvider = { ordinalColorRes },
            displayOrdinalProvider = { chapter -> displayOrdinals.forChapter(chapter) }
        )
    }
}
