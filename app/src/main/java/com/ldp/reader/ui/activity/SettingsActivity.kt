package com.ldp.reader.ui.activity

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivitySettingsBinding
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.ui.theme.InkThemes
import com.ldp.reader.ui.theme.InkDialogs
import com.ldp.reader.ui.theme.EdgeToEdge
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
        EdgeToEdge.apply(this)
        EdgeToEdge.padTop(binding.settingsHeader.root)
        EdgeToEdge.padBottom(binding.settingsScroll, 0)
        binding.settingsHeader.inkHeaderTitle.setText(R.string.settings_title)
        binding.settingsCleanContentSwitch.isChecked = ReaderFeatureSwitches.isCleanContentEnabled()
        binding.settingsCleanIntroSwitch.isChecked = ReaderFeatureSwitches.isCleanIntroEnabled()
        binding.settingsSmartWrongChapterSwitch.isChecked = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()
    }

    override fun initClick() {
        super.initClick()
        binding.settingsHeader.inkHeaderBack.setOnClickListener { finish() }
        binding.settingsAppearanceEntry.setOnClickListener {
            ThemeActivity.start(this)
        }
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
        binding.settingsAppearanceValue.text = getString(InkThemes.current().name)
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
        InkDialogs.confirm(
            context = this,
            title = "确认清除缓存？",
            message = "缓存中的章节、图片和音频会被删除，正在阅读或播放的内容可能需要重新加载。" +
                "不会删除书架或本机书籍。",
            confirmText = "清除",
            destructive = true
        ) { clearCache() }
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
