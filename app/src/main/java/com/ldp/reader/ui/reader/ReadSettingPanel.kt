package com.ldp.reader.ui.reader

import android.app.Activity
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ldp.reader.R
import com.ldp.reader.databinding.ViewReadSettingPanelBinding
import com.ldp.reader.model.local.ReaderTextSize
import com.ldp.reader.model.local.ReadSettingManager
import com.ldp.reader.ui.adapter.PageStyleAdapter
import com.ldp.reader.ui.glass.GlassStyle
import com.ldp.reader.ui.motion.Haptics
import com.ldp.reader.ui.theme.ReaderChrome
import com.ldp.reader.ui.theme.ReaderPalette
import com.ldp.reader.utils.BrightnessUtils
import com.ldp.reader.widget.page.LocalPageLoader
import com.ldp.reader.widget.page.LocalTxtEncodingPreference
import com.ldp.reader.widget.page.PageLoader
import com.ldp.reader.widget.page.PageMode
import com.ldp.reader.widget.page.PageStyle

/**
 * Created by ldp on 17-5-18.
 *
 * Reader settings as a glass sheet over the page, inside the reader's own window, so the page
 * shows through it and reflows live behind it as the type size changes. Every colour is taken
 * from the current [ReaderPalette] (parchment on parchment, dim on the night page).
 */
class ReadSettingPanel(
    private val mActivity: Activity,
    host: ViewGroup,
    private val mPageLoader: PageLoader?,
    private val onPageStyleChanged: (PageStyle) -> Unit
) {
    private lateinit var mIvBrightnessMinus: ImageView
    private lateinit var mSbBrightness: SeekBar
    private lateinit var mIvBrightnessPlus: ImageView
    private lateinit var mCbBrightnessAuto: CheckBox
    private lateinit var mTvFontMinus: TextView
    private lateinit var mTvFont: TextView
    private lateinit var mTvFontPlus: TextView
    private lateinit var mCbFontDefault: CheckBox
    private lateinit var mRgPageMode: RadioGroup

    private lateinit var mRbSimulation: RadioButton
    private lateinit var mRbCover: RadioButton
    private lateinit var mRbSlide: RadioButton
    private lateinit var mRbScroll: RadioButton
    private lateinit var mRbNone: RadioButton
    private lateinit var mRvBg: RecyclerView
    private lateinit var mTvMore: TextView

    private lateinit var mPageStyleAdapter: PageStyleAdapter
    private lateinit var mSettingManager: ReadSettingManager
    private val binding = ViewReadSettingPanelBinding.inflate(LayoutInflater.from(mActivity), host, true)
    private val context = mActivity

    private lateinit var mPageMode: PageMode
    private lateinit var mPageStyle: PageStyle

    private var mBrightness = 0
    private var mTextSizeSp = 0

    private var isBrightnessAuto = false
    private var isTextDefault = false
    private var palette: ReaderPalette? = null

    /** Called once the sheet has left the screen, however it was put away. */
    var onDismiss: (() -> Unit)? = null

    val isBrightFollowSystem: Boolean
        get() = mCbBrightnessAuto.isChecked

    /** True while the sheet is open or opening. */
    val isShowing: Boolean
        get() = binding.readSettingLlMenu.isOpen

    init {
        // Floats above the page footer, like the reader's bottom bar.
        FloatingReaderChrome.keepClear(
            binding.readSettingLlMenu,
            atTop = false,
            clearancePx = binding.root.resources.getDimensionPixelSize(R.dimen.reader_footer_height)
        )
        binding.readSettingLlMenu.scrollContent = binding.readSettingScroll
        binding.readSettingLlMenu.onHidden = {
            binding.readSettingWindowRoot.visibility = View.GONE
            onDismiss?.invoke()
        }
        initData()
        initWidget()
        initClick()
    }

    fun show() {
        // Values may have changed elsewhere (system brightness, a restored session).
        initData()
        renderValues()
        binding.readSettingWindowRoot.visibility = View.VISIBLE
        binding.readSettingLlMenu.open()
    }

    fun dismiss() {
        binding.readSettingLlMenu.close()
    }

    /** Recolours the sheet for the page style currently on screen. */
    fun applyPalette(newPalette: ReaderPalette) {
        palette = newPalette
        renderPalette(newPalette)
    }


    private fun initData() {
        mSettingManager = ReadSettingManager.getInstance()

        isBrightnessAuto = mSettingManager.isBrightnessAuto
        mBrightness = mSettingManager.brightness
        isTextDefault = mSettingManager.isDefaultTextSize
        mTextSizeSp = ReaderTextSize.displaySp(
            storedPx = mSettingManager.textSize,
            scaledDensity = context.resources.displayMetrics.scaledDensity,
            isDefault = isTextDefault
        )
        mPageMode = mSettingManager.pageMode
        mPageStyle = mSettingManager.pageStyle
    }

    private fun initWidget() {
        mIvBrightnessMinus = binding.readSettingIvBrightnessMinus
        mSbBrightness = binding.readSettingSbBrightness
        mIvBrightnessPlus = binding.readSettingIvBrightnessPlus
        mCbBrightnessAuto = binding.readSettingCbBrightnessAuto
        mTvFontMinus = binding.readSettingTvFontMinus
        mTvFont = binding.readSettingTvFont
        mTvFontPlus = binding.readSettingTvFontPlus
        mCbFontDefault = binding.readSettingCbFontDefault
        mRgPageMode = binding.readSettingRgPageMode
        mRbSimulation = binding.readSettingRbSimulation
        mRbCover = binding.readSettingRbCover
        mRbSlide = binding.readSettingRbSlide
        mRbScroll = binding.readSettingRbScroll
        mRbNone = binding.readSettingRbNone
        mRvBg = binding.readSettingRvBg
        mTvMore = binding.readSettingTvMore

        renderValues()
        val localTxtLoader = mPageLoader as? LocalPageLoader
        binding.readSettingEncodingRow.visibility = if (localTxtLoader == null) {
            View.GONE
        } else {
            binding.readSettingEncodingValue.text = encodingLabel(
                localTxtLoader.textEncodingPreference
            )
            View.VISIBLE
        }
        initPageMode()
        setUpAdapter()
    }

    private fun renderValues() {
        mSbBrightness.progress = mBrightness
        mTvFont.text = mTextSizeSp.toString()
        updateFontStepperState(mTextSizeSp)
        mCbBrightnessAuto.isChecked = isBrightnessAuto
        mCbFontDefault.isChecked = isTextDefault
    }

    private fun setUpAdapter() {
        mPageStyleAdapter = PageStyleAdapter { checkNotNull(palette) }
        mRvBg.layoutManager = GridLayoutManager(context, SWATCH_COLUMNS)
        mRvBg.adapter = mPageStyleAdapter
        mPageStyleAdapter.refreshItems(SWATCH_STYLES)
        mPageStyleAdapter.setPageStyleChecked(mPageStyle)
    }

    private fun initPageMode() {
        when (mPageMode) {
            PageMode.SIMULATION -> mRbSimulation.isChecked = true
            PageMode.COVER -> mRbCover.isChecked = true
            PageMode.SLIDE -> mRbSlide.isChecked = true
            PageMode.NONE -> mRbNone.isChecked = true
            PageMode.SCROLL -> mRbScroll.isChecked = true
        }
    }

    private fun initClick() {
        binding.readSettingWindowRoot.setOnClickListener { dismiss() }
        mIvBrightnessMinus.setOnClickListener { stepBrightness(-BRIGHTNESS_STEP) }
        mIvBrightnessPlus.setOnClickListener { stepBrightness(BRIGHTNESS_STEP) }

        mSbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Preview brightness live while dragging; persist once the finger lifts.
                if (mCbBrightnessAuto.isChecked) {
                    mCbBrightnessAuto.isChecked = false
                }
                BrightnessUtils.setBrightness(mActivity, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                mSettingManager.brightness = seekBar.progress
            }
        })

        mCbBrightnessAuto.setOnCheckedChangeListener { chip, isChecked ->
            if (chip.isPressed) Haptics.toggle(chip, isChecked)
            if (isChecked) {
                // Release the per-window override so Android can keep following system/auto
                // brightness changes after the dialog is closed.
                BrightnessUtils.setDefaultBrightness(mActivity)
            } else {
                BrightnessUtils.setBrightness(mActivity, mSbBrightness.progress)
            }
            ReadSettingManager.getInstance().setAutoBrightness(isChecked)
        }

        mTvFontMinus.setOnClickListener { stepFont(-1) }
        mTvFontPlus.setOnClickListener { stepFont(1) }

        mCbFontDefault.setOnCheckedChangeListener { chip, isChecked ->
            if (chip.isPressed) Haptics.toggle(chip, isChecked)
            mSettingManager.isDefaultTextSize = isChecked
            if (isChecked) {
                applyTextSizeSp(ReaderTextSize.DEFAULT_SP)
            }
        }

        mRgPageMode.setOnCheckedChangeListener { group, checkedId ->
            Haptics.tick(group)
            val pageMode = when (checkedId) {
                R.id.read_setting_rb_simulation -> PageMode.SIMULATION
                R.id.read_setting_rb_cover -> PageMode.COVER
                R.id.read_setting_rb_slide -> PageMode.SLIDE
                R.id.read_setting_rb_scroll -> PageMode.SCROLL
                R.id.read_setting_rb_none -> PageMode.NONE
                else -> PageMode.SIMULATION
            }
            mPageLoader!!.setPageMode(pageMode)
        }

        mPageStyleAdapter.setOnItemClickListener { _, pos ->
            Haptics.tick(mRvBg)
            val style = SWATCH_STYLES[pos]
            mPageLoader!!.setPageStyle(style)
            onPageStyleChanged(style)
        }
        binding.readSettingEncodingRow.setOnClickListener {
            val localTxtLoader = mPageLoader as? LocalPageLoader ?: return@setOnClickListener
            showTextEncodingPicker(localTxtLoader)
        }
        mTvMore.visibility = View.GONE
    }

    private fun stepBrightness(delta: Int) {
        Haptics.tick(mSbBrightness)
        if (mCbBrightnessAuto.isChecked) {
            mCbBrightnessAuto.isChecked = false
        }
        val progress = (mSbBrightness.progress + delta).coerceIn(0, mSbBrightness.max)
        mSbBrightness.progress = progress
        BrightnessUtils.setBrightness(mActivity, progress)
        mSettingManager.brightness = progress
    }

    private fun stepFont(delta: Int) {
        if (mCbFontDefault.isChecked) {
            mCbFontDefault.isChecked = false
        }
        val fontSize = mTvFont.text.toString().toInt() + delta
        if (fontSize < ReaderTextSize.STEPPER_MIN_SP || fontSize > ReaderTextSize.STEPPER_MAX_SP) {
            return
        }
        Haptics.tick(mTvFont)
        applyTextSizeSp(fontSize)
    }

    private fun updateFontStepperState(fontSizeSp: Int) {
        val canShrink = fontSizeSp > ReaderTextSize.STEPPER_MIN_SP
        val canGrow = fontSizeSp < ReaderTextSize.STEPPER_MAX_SP
        mTvFontMinus.isEnabled = canShrink
        mTvFontMinus.alpha = if (canShrink) 1f else DISABLED_ALPHA
        mTvFontPlus.isEnabled = canGrow
        mTvFontPlus.alpha = if (canGrow) 1f else DISABLED_ALPHA
    }

    private fun renderPalette(palette: ReaderPalette) {
        val density = context.resources.displayMetrics.density
        val pillRadius = 18 * density
        binding.readSettingLlMenu.glass.style = GlassStyle.readerPanel(palette.panel, palette.isDark)
        binding.readSettingHandle.background = ReaderChrome.rounded(palette.panelDivider, 2 * density)
        listOf(
            binding.readSettingLabelBrightness,
            binding.readSettingLabelFont,
            binding.readSettingLabelPageMode,
            binding.readSettingLabelBackground,
            binding.readSettingEncodingHint
        ).forEach { it.setTextColor(palette.panelTextSecondary) }
        binding.readSettingEncodingTitle.setTextColor(palette.panelText)
        val iconTint = ColorStateList.valueOf(palette.panelText)
        mIvBrightnessMinus.imageTintList = iconTint
        mIvBrightnessPlus.imageTintList = iconTint
        ReaderChrome.tintSeekBar(mSbBrightness, palette)

        listOf<CompoundButton>(mCbBrightnessAuto, mCbFontDefault).forEach { chip ->
            chip.background = ReaderChrome.checkable(
                ReaderChrome.inkWash(palette),
                palette.panelControl,
                16 * density
            )
            chip.setTextColor(ReaderChrome.checkableText(palette.panelText, palette.panelTextSecondary))
        }
        listOf(mTvFontMinus, mTvFontPlus).forEach { stepper ->
            stepper.background = ReaderChrome.pressable(palette, palette.panelControl, pillRadius)
            stepper.setTextColor(palette.panelText)
        }
        mTvFont.setTextColor(palette.panelText)

        mRgPageMode.background = ReaderChrome.rounded(palette.panelControl, 20 * density)
        listOf(mRbSimulation, mRbCover, mRbSlide, mRbScroll, mRbNone).forEach { segment ->
            segment.background = ReaderChrome.checkable(
                ReaderChrome.raised(palette),
                0x00000000,
                17 * density
            )
            segment.setTextColor(ReaderChrome.checkableText(palette.panelText, palette.panelTextSecondary))
        }

        binding.readSettingEncodingValue.background =
            ReaderChrome.pressable(palette, palette.panelControl, pillRadius)
        binding.readSettingEncodingValue.setTextColor(palette.panelText)
        mPageStyleAdapter.notifyDataSetChanged()
    }

    private fun showTextEncodingPicker(localTxtLoader: LocalPageLoader) {
        val preferences = LocalTxtEncodingPreference.entries
        val labels = preferences.map(::encodingLabel).toTypedArray()
        val selected = preferences.indexOf(localTxtLoader.textEncodingPreference)
        MaterialAlertDialogBuilder(mActivity)
            .setTitle(R.string.read_text_encoding_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val preference = preferences[which]
                localTxtLoader.setTextEncodingPreference(preference)
                binding.readSettingEncodingValue.text = encodingLabel(preference)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.nb_common_cancel, null)
            .show()
    }

    private fun encodingLabel(preference: LocalTxtEncodingPreference): String {
        val label = when (preference) {
            LocalTxtEncodingPreference.AUTO -> R.string.read_text_encoding_auto
            LocalTxtEncodingPreference.UTF8 -> R.string.read_text_encoding_utf8
            LocalTxtEncodingPreference.GBK -> R.string.read_text_encoding_gbk
            LocalTxtEncodingPreference.UTF16LE -> R.string.read_text_encoding_utf16le
            LocalTxtEncodingPreference.UTF16BE -> R.string.read_text_encoding_utf16be
        }
        return context.getString(label)
    }

    private fun applyTextSizeSp(fontSizeSp: Int) {
        mTvFont.text = fontSizeSp.toString()
        updateFontStepperState(fontSizeSp)
        mPageLoader!!.setTextSize(
            ReaderTextSize.toStoredPx(
                displaySp = fontSizeSp,
                scaledDensity = context.resources.displayMetrics.scaledDensity
            )
        )
    }

    private companion object {
        const val SWATCH_COLUMNS = 5
        const val BRIGHTNESS_STEP = 8
        const val DISABLED_ALPHA = 0.35f
        val SWATCH_STYLES = listOf(
            PageStyle.BG_0,
            PageStyle.BG_1,
            PageStyle.BG_2,
            PageStyle.BG_3,
            PageStyle.BG_4
        )
    }
}
