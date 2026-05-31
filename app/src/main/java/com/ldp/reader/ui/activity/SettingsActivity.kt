package com.ldp.reader.ui.activity

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.ldp.reader.databinding.ActivitySettingsBinding
import com.ldp.reader.source.BookContentProviderRouter
import com.ldp.reader.source.ReaderFeatureSwitches
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.utils.CacheUtils
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils

class SettingsActivity : BaseActivity<ActivitySettingsBinding>() {
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
            window.statusBarColor = Color.WHITE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        refreshCacheSize()
        binding.settingsCleanContentSwitch.isChecked = ReaderFeatureSwitches.isCleanContentEnabled()
        binding.settingsCleanIntroSwitch.isChecked = ReaderFeatureSwitches.isCleanIntroEnabled()
        binding.settingsSmartWrongChapterSwitch.isChecked = ReaderFeatureSwitches.isSmartWrongChapterAnalysisEnabled()
    }

    override fun initClick() {
        super.initClick()
        binding.settingsBack.setOnClickListener { finish() }
        binding.settingsAccountEntry.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
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
            clearCache()
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

    private fun refreshCacheSize() {
        binding.settingsCacheSummary.text = CacheUtils.getAppCacheSizeLabel(this)
    }

    private fun clearCache() {
        Glide.get(this).clearMemory()
        Thread {
            CacheUtils.clearAppCache(this)
            runOnUiThread {
                refreshCacheSize()
                ToastUtils.show("缓存已清理")
            }
        }.start()
    }

    private fun requestBookShelfSyncAndFinish() {
        setResult(Activity.RESULT_OK, BookshelfSyncRequest.resultIntent())
        ToastUtils.show("已发起书架同步")
        finish()
    }
}
