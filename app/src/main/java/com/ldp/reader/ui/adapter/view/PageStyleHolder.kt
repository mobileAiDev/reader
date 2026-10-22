package com.ldp.reader.ui.adapter.view

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemReadBgBinding
import com.ldp.reader.ui.base.adapter.ViewHolderImpl
import com.ldp.reader.ui.theme.ReaderPalette
import com.ldp.reader.widget.page.PageStyle

/** One round paper swatch with its name; the selected one sits inside a fine ink ring. */
class PageStyleHolder(
    private val paletteProvider: () -> ReaderPalette,
    private val isChecked: (PageStyle) -> Boolean
) : ViewHolderImpl<PageStyle>() {
    private lateinit var mReadBg: View
    private lateinit var mIvChecked: ImageView
    private lateinit var mLabel: TextView

    override fun initView() {
        val binding = ItemReadBgBinding.bind(getItemView())
        mReadBg = binding.readBgView
        mIvChecked = binding.readBgIvChecked
        mLabel = binding.readBgTvLabel
    }

    override fun onBind(data: PageStyle, pos: Int) {
        val palette = paletteProvider()
        val checked = isChecked(data)
        val density = getContext().resources.displayMetrics.density
        val paper = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(getContext(), data.bgColor))
            // An edge strong enough to outline a paper of the panel's own tone (黛蓝 on a night panel).
            setStroke((0.75f * density).toInt().coerceAtLeast(1), ColorUtils.blendARGB(palette.panel, palette.panelText, SWATCH_EDGE))
        }
        // The ring stands a hair away from the paper, so even a pale paper reads as chosen.
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((1.5f * density).toInt(), if (checked) palette.panelText else Color.TRANSPARENT)
        }
        val gap = (4 * density).toInt()
        mReadBg.background = LayerDrawable(arrayOf(ring, paper)).apply { setLayerInset(1, gap, gap, gap, gap) }
        mIvChecked.visibility = if (checked) View.VISIBLE else View.GONE
        mIvChecked.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(getContext(), data.fontColor))
        mLabel.setText(labelRes(data))
        mLabel.setTextColor(if (checked) palette.panelText else palette.panelTextSecondary)
    }

    override fun getItemLayoutId(): Int {
        return R.layout.item_read_bg
    }

    private fun labelRes(style: PageStyle): Int {
        return when (style) {
            PageStyle.BG_0 -> R.string.reader_style_paper
            PageStyle.BG_1 -> R.string.reader_style_parchment
            PageStyle.BG_2 -> R.string.reader_style_celadon
            PageStyle.BG_3 -> R.string.reader_style_mist
            PageStyle.BG_4 -> R.string.reader_style_indigo
            PageStyle.NIGHT -> R.string.nb_mode_night
        }
    }

    private companion object {
        const val SWATCH_EDGE = 0.3f
    }
}
