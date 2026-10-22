package com.ldp.reader.ui.activity

import androidx.lifecycle.ViewModelProvider
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityReadingStatsBinding
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.theme.EdgeToEdge

class ReadingStatsActivity : BaseActivity<ActivityReadingStatsBinding>() {
    private val viewModel by lazy {
        ViewModelProvider(this)[ReadingStatsViewModel::class.java]
    }

    override fun getViewBinding(): ActivityReadingStatsBinding {
        return ActivityReadingStatsBinding.inflate(layoutInflater)
    }

    override fun initWidget() {
        super.initWidget()
        EdgeToEdge.apply(this)
        EdgeToEdge.padTop(binding.readingStatsHeader.root)
        EdgeToEdge.padBottom(binding.readingStatsScroll, 0)
        binding.readingStatsHeader.inkHeaderTitle.setText(R.string.reading_stats_title)
        viewModel.stats.observe(this) { state ->
            binding.readingStatsTotalValue.text = state.totalLabel
            binding.readingStatsTodayValue.text = state.todayLabel
            binding.readingStatsWeekValue.text = state.weekLabel
        }
        viewModel.recentDays.observe(this) { days ->
            binding.readingStatsWeekChart.setDays(days, System.currentTimeMillis())
        }
        viewModel.refresh()
    }

    override fun initClick() {
        super.initClick()
        binding.readingStatsHeader.inkHeaderBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
