package com.ldp.reader.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import com.blankj.utilcode.util.BarUtils
import com.ldp.reader.R
import com.ldp.reader.RxBus
import com.ldp.reader.databinding.ActivityMainBinding
import com.ldp.reader.event.BookSyncEvent
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.fragment.BookShelfFragment
import com.ldp.reader.utils.PermissionsChecker
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils
import java.util.Arrays

/**
 * @author ldp
 */
class MainActivity : BaseActivity<ActivityMainBinding>(), ViewPager.OnPageChangeListener {
    /***************Object */
    private val mFragmentList = ArrayList<Fragment>()

    private var mTitleList: List<String>? = null
    private var mPermissionsChecker: PermissionsChecker? = null

    /*****************Params */
    private var isPrepareFinish = false

    /**************init method */
    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        toolbar?.subtitle = "iReader"
        supportActionBar!!.setDisplayHomeAsUpEnabled(false)
        supportActionBar!!.title = ""
        setUpTabLayout()
        BarUtils.transparentStatusBar(this)
    }

    protected fun createTabFragments(): List<Fragment> {
        initFragment()
        return mFragmentList
    }

    private fun initFragment() {
        val bookShelfFragment: Fragment = BookShelfFragment()
//                Fragment communityFragment = new CommunityFragment();
//        Fragment discoveryFragment = new FindFragment();
        mFragmentList.add(bookShelfFragment)
//                mFragmentList.add(communityFragment);
//        mFragmentList.add(discoveryFragment
    }

    private fun setUpTabLayout() {
        createTabFragments()
        mTitleList = createTabTitles()
        val adapter: TabFragmentPageAdapter = TabFragmentPageAdapter(
            supportFragmentManager
        )
        binding?.apply {


            mainVp.adapter = adapter;
            mainVp.offscreenPageLimit = 3;

//
//        mainTlTab.setTitles(mTitleList);
//        stMain.setViewPager(mVp);
//        stMain.setOnTabListener((index, v) -> mVp.setCurrentItem(index, true));
        }

    }

    protected fun createTabTitles(): List<String> {
        val titles = resources.getStringArray(R.array.nb_fragment_title)
        return Arrays.asList(*titles)
    }

    override fun initWidget() {
        super.initWidget()
    }

    override fun getViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val token = SharedPreUtils.getInstance().getString("token")
        if (TextUtils.isEmpty(token)) {
            menu.getItem(0).title = "请登录"
        } else {
            val userName = SharedPreUtils.getInstance().getString("userName")
            menu.getItem(0).title = userName
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        var activityCls: Class<*>? = null
        when (id) {
            R.id.action_search -> activityCls = SearchActivity::class.java
            R.id.action_login -> activityCls = LoginActivity::class.java
            R.id.action_my_message -> {}
//            R.id.action_download -> activityCls = DownloadActivity::class.java
            R.id.action_sync_bookshelf -> RxBus.getInstance().post(BookSyncEvent())
            R.id.action_scan_local_book -> {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
                    if (mPermissionsChecker == null) {
                        mPermissionsChecker = PermissionsChecker(this)
                    }

                    //获取读取和写入SD卡的权限
                    if (mPermissionsChecker!!.lacksPermissions(*PERMISSIONS)) {
                        //请求权限
                        ActivityCompat.requestPermissions(
                            this,
                            PERMISSIONS,
                            PERMISSIONS_REQUEST_STORAGE
                        )
                        return super.onOptionsItemSelected(item)
                    }
                }
                activityCls = FileSystemActivity::class.java
            }

            R.id.action_wifi_book -> {}
            R.id.action_feedback -> {}
            R.id.action_night_mode -> {}
//            R.id.action_settings -> activityCls = MoreSettingActivity::class.java
            else -> {}
        }
        if (activityCls != null) {
            val intent = Intent(this, activityCls)
            startActivity(intent)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
        if (menu != null && menu is MenuBuilder) {
            try {
                val method = menu.javaClass.getDeclaredMethod(
                    "setOptionalIconsVisible",
                    java.lang.Boolean.TYPE
                )
                method.isAccessible = true
                method.invoke(menu, true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return super.onPreparePanel(featureId, view, menu)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_STORAGE -> {

                // 如果取消权限，则返回的值为0
                if (grantResults.size > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    //跳转到 FileSystemActivity
                    val intent = Intent(this, FileSystemActivity::class.java)
                    startActivity(intent)
                } else {
                    ToastUtils.show("用户拒绝开启读写权限")
                }
                return
            }
        }
    }

    override fun onBackPressed() {
        if (!isPrepareFinish) {
//            mVp.postDelayed(
//                    () -> isPrepareFinish = false, WAIT_INTERVAL
//            );
            isPrepareFinish = true
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
    override fun onPageSelected(position: Int) {}
    override fun onPageScrollStateChanged(state: Int) {}

    /******************inner class */
    internal inner class TabFragmentPageAdapter(fm: FragmentManager?) : FragmentPagerAdapter(
        fm!!
    ) {
        override fun getItem(position: Int): Fragment {
            return mFragmentList[position]
        }

        override fun getCount(): Int {
            return mFragmentList.size
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return mTitleList!![position]
        }
    }

    companion object {
        /*************Constant */
        private const val WAIT_INTERVAL = 2000
        private const val PERMISSIONS_REQUEST_STORAGE = 1
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}