package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityThemeBinding
import com.ldp.reader.databinding.ItemThemeCardBinding
import com.ldp.reader.databinding.ViewThemePreviewBinding
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.motion.Haptics
import com.ldp.reader.ui.motion.Press
import com.ldp.reader.ui.theme.AppearanceMode
import com.ldp.reader.ui.theme.EdgeToEdge
import com.ldp.reader.ui.theme.InkTheme
import com.ldp.reader.ui.theme.InkThemes
import com.ldp.reader.ui.widget.CoverFrameLayout

/** Themes and appearance: every theme as a live preview, and light or dark below. */
class ThemeActivity : BaseActivity<ActivityThemeBinding>() {
    override fun getViewBinding(): ActivityThemeBinding = ActivityThemeBinding.inflate(layoutInflater)

    override fun initWidget() {
        super.initWidget()
        EdgeToEdge.apply(this)
        EdgeToEdge.padTop(binding.themeHeader.root)
        EdgeToEdge.padBottom(binding.themeScroll, 0)
        binding.themeHeader.inkHeaderTitle.setText(R.string.theme_title)
        renderThemes()
        renderMode()
    }

    override fun initClick() {
        super.initClick()
        binding.themeHeader.inkHeaderBack.setOnClickListener { finish() }
        binding.themeModeGroup.setOnCheckedChangeListener { group, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_mode_light -> AppearanceMode.LIGHT
                R.id.theme_mode_dark -> AppearanceMode.DARK
                else -> AppearanceMode.FOLLOW_SYSTEM
            }
            if (mode == AppearanceMode.current()) return@setOnCheckedChangeListener
            Haptics.tick(group)
            AppearanceMode.select(mode)
        }
    }

    private fun renderThemes() {
        val current = InkThemes.current()
        for (theme in InkThemes.all) {
            val card = ItemThemeCardBinding.inflate(layoutInflater, binding.themeList, false)
            bindPreview(card.themeCardPreview, theme)
            card.themeCardName.setText(theme.name)
            card.themeCardSummary.setText(theme.summary)
            card.themeCard.isSelected = theme == current
            card.themeCardCheck.visibility = if (theme == current) View.VISIBLE else View.INVISIBLE
            card.themeCard.contentDescription = getString(theme.name) + "，" + getString(theme.summary)
            Press.attach(card.themeCard, CARD_PRESS)
            card.themeCard.setOnClickListener { view ->
                Haptics.confirm(view)
                if (theme != InkThemes.current()) switchTo(theme)
            }
            binding.themeList.addView(card.root)
        }
    }

    /** The preview is inflated in the theme it shows, so it is built from that theme's own resources. */
    private fun bindPreview(host: FrameLayout, theme: InkTheme) {
        val themed: Context = ContextThemeWrapper(this, R.style.AppTheme).apply {
            this.theme.applyStyle(theme.overlay, true)
        }
        val preview = ViewThemePreviewBinding.inflate(LayoutInflater.from(themed), host, true)
        val density = resources.displayMetrics.density
        BOOK_CLOTHS.forEachIndexed { index, cloth ->
            val book = CoverFrameLayout(themed)
            book.addView(View(themed).apply { setBackgroundColor(cloth) })
            val width = (BOOK_WIDTHS_DP[index] * density).toInt()
            book.layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (10 * density).toInt()
            }
            preview.themePreviewBooks.addView(book)
        }
    }

    /** Reopens this page in the new theme with a cross-fade; pages behind rebuild when shown again. */
    private fun switchTo(theme: InkTheme) {
        InkThemes.select(application, theme)
        startActivity(Intent(this, ThemeActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun renderMode() {
        binding.themeModeGroup.check(
            when (AppearanceMode.current()) {
                AppearanceMode.FOLLOW_SYSTEM -> R.id.theme_mode_system
                AppearanceMode.LIGHT -> R.id.theme_mode_light
                AppearanceMode.DARK -> R.id.theme_mode_dark
            }
        )
    }

    companion object {
        private const val CARD_PRESS = 0.98f
        /** Book-cloth colours of the preview's little books. */
        private val BOOK_CLOTHS = intArrayOf(0xFF7A2F2B.toInt(), 0xFF2E3B4E.toInt(), 0xFFD9CDB5.toInt(), 0xFF9A7446.toInt(), 0xFF3E3C3A.toInt())
        private val BOOK_WIDTHS_DP = floatArrayOf(38f, 42f, 36f, 40f, 34f)

        fun start(context: Context) {
            context.startActivity(Intent(context, ThemeActivity::class.java))
        }
    }
}
