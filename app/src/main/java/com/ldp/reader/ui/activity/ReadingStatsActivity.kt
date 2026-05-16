package com.ldp.reader.ui.activity

import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.ldp.reader.databinding.ActivityReadingStatsBinding
import com.ldp.reader.ui.base.BaseActivity

class ReadingStatsActivity : BaseActivity<ActivityReadingStatsBinding>() {
    private val viewModel by lazy {
        ViewModelProvider(this)[ReadingStatsViewModel::class.java]
    }

    override fun getViewBinding(): ActivityReadingStatsBinding {
        return ActivityReadingStatsBinding.inflate(layoutInflater)
    }

    override fun initWidget() {
        super.initWidget()
        setLightStatusBar()
        viewModel.stats.observe(this) { state ->
            binding.readingStatsTotalValue.text = state.totalLabel
            binding.readingStatsTodayValue.text = state.todayLabel
            binding.readingStatsWeekValue.text = state.weekLabel
        }
        viewModel.refresh()
    }

    override fun initClick() {
        super.initClick()
        binding.readingStatsBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun setLightStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.WHITE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }
}
