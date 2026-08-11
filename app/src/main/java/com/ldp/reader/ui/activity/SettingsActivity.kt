package com.ldp.reader.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.bumptech.glide.Glide
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivitySettingsBinding
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.utils.CacheUtils
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseActivity<ActivitySettingsBinding>() {
    private val cacheSizeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cacheSizeRefreshJob: Job? = null
    private var cacheClearJob: Job? = null
    private val loginSyncLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK &&
                BookshelfSyncRequest.isRequested(result.data)
            ) {
                requestBookShelfSyncAndFinish()
            }
        }

    override fun getViewBinding(): ActivitySettingsBinding {
        return ActivitySettingsBinding.inflate(layoutInflater)
    }

    override fun initWidget() {
        super.initWidget()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val background = ContextCompat.getColor(this, R.color.mine_bg)
            window.statusBarColor = background
            window.navigationBarColor = ContextCompat.getColor(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    R.color.mine_bg
                } else {
                    R.color.media_reader_dark
                }
            )
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        }
        binding.settingsCleanContentSwitch.isChecked = ReaderFeatureSwitches.isCleanContentEnabled()
        binding.settingsCleanIntroSwitch.isChecked = ReaderFeatureSwitches.isCleanIntroEnabled()
        binding.settingsSmartWrongChapterSwitch.isChecked = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()
    }

    override fun initClick() {
        super.initClick()
        binding.settingsBack.setOnClickListener { finish() }
        binding.settingsAccountEntry.setOnClickListener {
            loginSyncLauncher.launch(LoginActivity.syncIntent(this))
        }
        binding.settingsSyncBookshelf.setOnClickListener {
            if (SharedPreUtils.getInstance().getString("token").isEmpty()) {
                loginSyncLauncher.launch(LoginActivity.syncIntent(this))
                return@setOnClickListener
            }
            requestBookShelfSyncAndFinish()
        }
        binding.settingsLocalImport.setOnClickListener {
            startActivity(Intent(this, FileSystemActivity::class.java))
        }
        binding.settingsClearCache.setOnClickListener {
            showClearCacheConfirmation()
        }
        binding.settingsCleanContent.setOnClickListener {
            binding.settingsCleanContentSwitch.toggle()
        }
        binding.settingsCleanContentSwitch.setOnCheckedChangeListener { _, isChecked ->
            ReaderFeatureSwitches.setCleanContentEnabled(isChecked)
        }
        binding.settingsCleanIntro.setOnClickListener {
            binding.settingsCleanIntroSwitch.toggle()
        }
        binding.settingsCleanIntroSwitch.setOnCheckedChangeListener { _, isChecked ->
            ReaderFeatureSwitches.setCleanIntroEnabled(isChecked)
        }
        binding.settingsSmartWrongChapter.setOnClickListener {
            binding.settingsSmartWrongChapterSwitch.toggle()
        }
        binding.settingsSmartWrongChapterSwitch.setOnCheckedChangeListener { _, isChecked ->
            ReaderFeatureSwitches.setSmartWrongChapterAnalysisEnabled(isChecked)
            if (isChecked) {
                BookContentProviderRouter.startLowPriorityV8Maintenance()
            } else {
                BookContentProviderRouter.stopLowPriorityV8Maintenance("settings-disabled")
            }
        }
        binding.settingsAboutEntry.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCacheSize()
    }

    override fun onDestroy() {
        cacheSizeScope.cancel()
        super.onDestroy()
    }

    private fun refreshCacheSize() {
        cacheSizeRefreshJob?.cancel()
        cacheSizeRefreshJob = cacheSizeScope.launch {
            val label = withContext(Dispatchers.IO) {
                CacheUtils.getAppCacheSizeLabel(applicationContext)
            }
            binding.settingsCacheSummary.text = label
        }
    }

    private fun showClearCacheConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("确认清除缓存？")
            .setMessage(
                "缓存中的章节、图片和音频会被删除，正在阅读或播放的内容可能需要重新加载。" +
                    "不会删除书架或本机书籍。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ -> clearCache() }
            .show()
    }

    private fun clearCache() {
        if (cacheClearJob?.isActive == true) return
        Glide.get(this).clearMemory()
        cacheClearJob = cacheSizeScope.launch {
            val cleared = withContext(Dispatchers.IO) {
                CacheUtils.clearAppCache(applicationContext)
            }
            refreshCacheSize()
            ToastUtils.show(if (cleared) "缓存已清理" else "部分缓存清理失败，请重试")
        }
    }

    private fun requestBookShelfSyncAndFinish() {
        setResult(Activity.RESULT_OK, BookshelfSyncRequest.resultIntent())
        ToastUtils.show("正在同步书架…")
        finish()
    }
}
