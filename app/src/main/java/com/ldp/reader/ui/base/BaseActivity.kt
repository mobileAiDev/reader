package com.ldp.reader.ui.base

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.viewbinding.ViewBinding
import com.ldp.reader.R

/**
 * Created by PC on 2016/9/8.
 */
abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {
    private var mToolbar: Toolbar? = null
    protected lateinit var binding: VB
    /****************************abstract area */
    /************************init area */
    /**
     * 配置Toolbar
     *
     * @param toolbar
     */
    protected open fun setUpToolbar(toolbar: Toolbar?) {}
    protected open fun toolbarView(): Toolbar? = null
    protected open fun initData(savedInstanceState: Bundle?) {}

    /**
     * 初始化零件
     */
    protected open fun initWidget() {}

    /**
     * 初始化点击事件
     */
    protected open fun initClick() {}

    /**
     * 逻辑使用区
     */
    protected open fun processLogic() {}

    abstract fun getViewBinding(): VB


    /*************************lifecycle area */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = getViewBinding()
        setContentView(binding.root)
        initData(savedInstanceState)
        initToolbar()
        initWidget()
        initClick()
        processLogic()
    }

    private fun initToolbar() {
        mToolbar = toolbarView()
        if (mToolbar != null) {
            supportActionBar(mToolbar)
            setUpToolbar(mToolbar)
        }
    }

    /**************************used method area */
    protected fun startActivity(activity: Class<out AppCompatActivity?>?) {
        val intent = Intent(this, activity)
        startActivity(intent)
    }

    protected fun supportActionBar(toolbar: Toolbar?): ActionBar? {
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowHomeEnabled(true)
        }
        mToolbar!!.setNavigationContentDescription(R.string.common_navigate_back)
        mToolbar!!.setNavigationOnClickListener { v: View? -> finish() }
        return actionBar
    }

    companion object {
        private const val INVALID_VAL = -1
    }
}
