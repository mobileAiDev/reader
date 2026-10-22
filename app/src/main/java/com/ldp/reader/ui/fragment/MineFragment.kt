package com.ldp.reader.ui.fragment

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.ldp.reader.R
import com.ldp.reader.databinding.FragmentMineBinding
import com.ldp.reader.ui.activity.AboutActivity
import com.ldp.reader.ui.activity.LoginActivity
import com.ldp.reader.ui.activity.MainActivity
import com.ldp.reader.ui.activity.ReadingStatsActivity
import com.ldp.reader.ui.activity.SettingsActivity
import com.ldp.reader.ui.base.BaseFragment
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.ui.theme.AppearanceMode
import com.ldp.reader.ui.activity.ThemeActivity
import com.ldp.reader.ui.theme.InkThemes
import com.ldp.reader.ui.theme.EdgeToEdge
import com.ldp.reader.utils.ReadingStatsUtils
import com.ldp.reader.utils.SharedPreUtils

class MineFragment : BaseFragment<FragmentMineBinding>() {
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            refreshProfile()
            handleBookShelfSyncResult(result.resultCode, result.data)
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            refreshProfile()
            handleBookShelfSyncResult(result.resultCode, result.data)
        }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMineBinding {
        return FragmentMineBinding.inflate(inflater, container, false)
    }

    override fun initWidget(savedInstanceState: Bundle?) {
        super.initWidget(savedInstanceState)
        val currentBinding = binding ?: return
        // The page scrolls under the status bar and ends clear of the floating tab capsule.
        EdgeToEdge.padTop(currentBinding.mineContent)
        EdgeToEdge.padBottom(currentBinding.mineScroll, resources.getDimensionPixelSize(R.dimen.home_list_bottom_room))
    }

    override fun initClick() {
        super.initClick()
        binding?.mineLoginEntry?.setOnClickListener {
            loginLauncher.launch(Intent(requireContext(), LoginActivity::class.java))
        }
        binding?.mineProfileCard?.setOnClickListener {
            loginLauncher.launch(Intent(requireContext(), LoginActivity::class.java))
        }
        binding?.mineReadingEntry?.setOnClickListener {
            startActivity(Intent(requireContext(), ReadingStatsActivity::class.java))
        }
        binding?.mineBooksEntry?.setOnClickListener {
            (activity as? MainActivity)?.selectHomeTab(MainActivity.HomeTabKey.BOOKSHELF)
        }
        binding?.mineAppearanceEntry?.setOnClickListener {
            ThemeActivity.start(requireContext())
        }
        binding?.mineSettingsEntry?.setOnClickListener {
            settingsLauncher.launch(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding?.mineAboutEntry?.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }
    }

    override fun processLogic() {
        super.processLogic()
        refreshProfile()
    }

    override fun onResume() {
        super.onResume()
        refreshProfile()
    }

    private fun refreshProfile() {
        val currentBinding = binding ?: return
        val userName = SharedPreUtils.getInstance().getString("userName")
        if (!TextUtils.isEmpty(userName)) {
            currentBinding.mineProfileName.text = userName
            currentBinding.mineProfileReading.setText(R.string.mine_profile_hint_member)
            currentBinding.mineLoginEntry.text = "账号"
        } else {
            currentBinding.mineProfileName.text = "未登录"
            currentBinding.mineProfileReading.setText(R.string.mine_profile_hint_guest)
            currentBinding.mineLoginEntry.text = "去登录"
        }
        val nowMs = System.currentTimeMillis()
        bindStat(
            currentBinding.mineStatTotalValue,
            currentBinding.mineStatTotalUnit,
            ReadingStatsUtils.getTotalReadingMillis()
        )
        bindStat(
            currentBinding.mineStatWeekValue,
            currentBinding.mineStatWeekUnit,
            ReadingStatsUtils.getWeeklyReadingMillis(nowMs)
        )
        bindStat(
            currentBinding.mineStatTodayValue,
            currentBinding.mineStatTodayUnit,
            ReadingStatsUtils.getTodayReadingMillis(nowMs)
        )
        currentBinding.mineAppearanceValue.text =
            getString(InkThemes.current().name) + " · " + AppearanceMode.current().label
    }

    private fun bindStat(value: TextView, unit: TextView, durationMs: Long) {
        val (number, unitLabel) = ReadingStatsUtils.formatDurationParts(durationMs)
        value.text = number
        unit.text = unitLabel
    }

    private fun handleBookShelfSyncResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && BookshelfSyncRequest.isRequested(data)) {
            (activity as? MainActivity)?.requestBookShelfSync()
        }
    }
}
