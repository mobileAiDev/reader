package com.ldp.reader.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.DialogReadSettingBinding
import com.ldp.reader.model.local.ReadSettingManager
import com.ldp.reader.ui.adapter.PageStyleAdapter
import com.ldp.reader.utils.BrightnessUtils
import com.ldp.reader.utils.ScreenUtils
import com.ldp.reader.widget.page.PageLoader
import com.ldp.reader.widget.page.PageMode
import com.ldp.reader.widget.page.PageStyle
import java.util.Arrays

/**
 * Created by ldp on 17-5-18.
 */
class ReadSettingDialog(
    private val mActivity: Activity,
    private val mPageLoader: PageLoader?
) : Dialog(mActivity, R.style.ReadSettingDialog) {
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
    private lateinit var binding: DialogReadSettingBinding

    private lateinit var mPageMode: PageMode
    private lateinit var mPageStyle: PageStyle

    private var mBrightness = 0
    private var mTextSize = 0

    private var isBrightnessAuto = false
    private var isTextDefault = false

    val isBrightFollowSystem: Boolean
        get() {
            if (!::mCbBrightnessAuto.isInitialized) {
                return false
            }
            return mCbBrightnessAuto.isChecked
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogReadSettingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpWindow()
        initData()
        initWidget()
        initClick()
    }

    private fun setUpWindow() {
        val window = window!!
        val lp = window.attributes
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.BOTTOM
        window.attributes = lp
    }

    private fun initData() {
        mSettingManager = ReadSettingManager.getInstance()

        isBrightnessAuto = mSettingManager.isBrightnessAuto
        mBrightness = mSettingManager.brightness
        mTextSize = mSettingManager.textSize
        isTextDefault = mSettingManager.isDefaultTextSize
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

        mSbBrightness.progress = mBrightness
        mTvFont.text = mTextSize.toString()
        mCbBrightnessAuto.isChecked = isBrightnessAuto
        mCbFontDefault.isChecked = isTextDefault
        initPageMode()
        setUpAdapter()
    }

    private fun setUpAdapter() {
        val drawables = arrayOf(
            getDrawable(R.color.nb_read_bg_1),
            getDrawable(R.color.nb_read_bg_2),
            getDrawable(R.color.nb_read_bg_3),
            getDrawable(R.color.nb_read_bg_4),
            getDrawable(R.color.nb_read_bg_5)
        )

        mPageStyleAdapter = PageStyleAdapter()
        mRvBg.layoutManager = GridLayoutManager(context, 5)
        mRvBg.adapter = mPageStyleAdapter
        mPageStyleAdapter.refreshItems(Arrays.asList(*drawables))

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

    private fun getDrawable(drawRes: Int): Drawable {
        return ContextCompat.getDrawable(context, drawRes)!!
    }

    private fun initClick() {
        mIvBrightnessMinus.setOnClickListener {
            if (mCbBrightnessAuto.isChecked) {
                mCbBrightnessAuto.isChecked = false
            }
            val progress = mSbBrightness.progress - 1
            if (progress < 0) return@setOnClickListener
            mSbBrightness.progress = progress
            BrightnessUtils.setBrightness(mActivity, progress)
        }
        mIvBrightnessPlus.setOnClickListener {
            if (mCbBrightnessAuto.isChecked) {
                mCbBrightnessAuto.isChecked = false
            }
            val progress = mSbBrightness.progress + 1
            if (progress > mSbBrightness.max) return@setOnClickListener
            mSbBrightness.progress = progress
            BrightnessUtils.setBrightness(mActivity, progress)
            ReadSettingManager.getInstance().brightness = progress
        }

        mSbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val progress = seekBar.progress
                if (mCbBrightnessAuto.isChecked) {
                    mCbBrightnessAuto.isChecked = false
                }
                BrightnessUtils.setBrightness(mActivity, progress)
                ReadSettingManager.getInstance().brightness = progress
            }
        })

        mCbBrightnessAuto.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                BrightnessUtils.setBrightness(
                    mActivity,
                    BrightnessUtils.getScreenBrightness(mActivity)
                )
            } else {
                BrightnessUtils.setBrightness(mActivity, mSbBrightness.progress)
            }
            ReadSettingManager.getInstance().setAutoBrightness(isChecked)
        }

        mTvFontMinus.setOnClickListener {
            if (mCbFontDefault.isChecked) {
                mCbFontDefault.isChecked = false
            }
            val fontSize = mTvFont.text.toString().toInt() - 1
            if (fontSize < 0) return@setOnClickListener
            mTvFont.text = fontSize.toString()
            mPageLoader!!.setTextSize(fontSize)
        }

        mTvFontPlus.setOnClickListener {
            if (mCbFontDefault.isChecked) {
                mCbFontDefault.isChecked = false
            }
            val fontSize = mTvFont.text.toString().toInt() + 1
            mTvFont.text = fontSize.toString()
            mPageLoader!!.setTextSize(fontSize)
        }

        mCbFontDefault.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val fontSize = ScreenUtils.dpToPx(DEFAULT_TEXT_SIZE)
                mTvFont.text = fontSize.toString()
                mPageLoader!!.setTextSize(fontSize)
            }
        }

        mRgPageMode.setOnCheckedChangeListener { _, checkedId ->
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
            mPageLoader!!.setPageStyle(PageStyle.values()[pos])
        }
        mTvMore.visibility = View.INVISIBLE
    }

    companion object {
        private const val DEFAULT_TEXT_SIZE = 16
    }
}
