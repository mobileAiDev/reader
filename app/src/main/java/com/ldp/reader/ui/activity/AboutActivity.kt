package com.ldp.reader.ui.activity

import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityAboutBinding
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.theme.InkSystemBars

class AboutActivity : BaseActivity<ActivityAboutBinding>() {
    override fun getViewBinding(): ActivityAboutBinding {
        return ActivityAboutBinding.inflate(layoutInflater)
    }

    override fun initWidget() {
        super.initWidget()
        InkSystemBars.apply(this)
        binding.aboutHeader.inkHeaderTitle.setText(R.string.mine_about_title)
        binding.aboutVersion.text = packageManager.getPackageInfo(packageName, 0).versionName
    }

    override fun initClick() {
        super.initClick()
        binding.aboutHeader.inkHeaderBack.setOnClickListener { finish() }
        // Developer diagnostics stay reachable without cluttering the reader-facing UI.
        binding.aboutVersionRow.setOnLongClickListener {
            SourceEngineActivity.start(this)
            true
        }
    }
}
