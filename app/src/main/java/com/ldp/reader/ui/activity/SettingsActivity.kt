package com.ldp.reader.ui.activity

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import com.bumptech.glide.Glide
import com.ldp.reader.RxBus
import com.ldp.reader.databinding.ActivitySettingsBinding
import com.ldp.reader.event.BookSyncEvent
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.utils.CacheUtils
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils

class SettingsActivity : BaseActivity<ActivitySettingsBinding>() {
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
    }

    override fun initClick() {
        super.initClick()
        binding.settingsBack.setOnClickListener { finish() }
        binding.settingsAccountEntry.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.settingsSyncBookshelf.setOnClickListener {
            if (SharedPreUtils.getInstance().getString("token").isEmpty()) {
                startActivity(LoginActivity.syncIntent(this))
                return@setOnClickListener
            }
            RxBus.getInstance().post(BookSyncEvent())
            ToastUtils.show("已发起书架同步")
        }
        binding.settingsLocalImport.setOnClickListener {
            startActivity(Intent(this, FileSystemActivity::class.java))
        }
        binding.settingsClearCache.setOnClickListener {
            clearCache()
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
}
