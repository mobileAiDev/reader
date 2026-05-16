package com.ldp.reader.ui.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.Toolbar
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
import com.ldp.reader.ui.fragment.BookStoreFragment
import com.ldp.reader.ui.fragment.BookShelfFragment
import com.ldp.reader.ui.fragment.MineFragment
import com.ldp.reader.ui.widget.HomeMoreMenuView
import com.ldp.reader.utils.SharedPreUtils

/**
 * @author ldp
 */
class MainActivity : BaseActivity<ActivityMainBinding>(), ViewPager.OnPageChangeListener {
    /***************Object */
    private val mFragmentList = ArrayList<Fragment>()
    private val visibleTabs = visibleHomeTabs()
    private val menuIdToPageIndex = HashMap<Int, Int>()

    private var mTitleList: List<String> = emptyList()
    private var homeMoreMenuView: HomeMoreMenuView? = null
    private var statusBarColorBeforeMenu = Color.TRANSPARENT
    private var systemUiBeforeMenu = 0

    /*****************Params */
    private var isPrepareFinish = false

    /**************init method */
    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        toolbar?.title = ""
        toolbar?.subtitle = ""
        supportActionBar!!.setDisplayHomeAsUpEnabled(false)
        supportActionBar!!.title = ""
        setUpTabLayout()
        setHomeStatusBar()
    }

    override fun toolbarView(): Toolbar {
        return binding.toolbar.root
    }

    private fun setHomeStatusBar() {
        BarUtils.transparentStatusBar(this)
        applyHomeStatusBarFlags()
    }

    private fun applyHomeStatusBarFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
        }
        var flags = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    protected fun createTabFragments(): List<Fragment> {
        initFragment()
        return mFragmentList
    }

    private fun initFragment() {
        mFragmentList.clear()
        menuIdToPageIndex.clear()
        visibleTabs.forEachIndexed { index, tab ->
            mFragmentList.add(createFragment(tab.key))
            menuIdToPageIndex[tab.menuItemId] = index
        }
    }

    private fun createFragment(key: HomeTabKey): Fragment {
        return when (key) {
            HomeTabKey.BOOKSHELF -> BookShelfFragment()
            HomeTabKey.BOOKSTORE -> BookStoreFragment()
            HomeTabKey.MINE -> MineFragment()
        }
    }

    private fun setUpTabLayout() {
        createTabFragments()
        mTitleList = createTabTitles()
        val adapter: TabFragmentPageAdapter = TabFragmentPageAdapter(
            supportFragmentManager
        )
        binding?.apply {


            mainVp.adapter = adapter;
            mainVp.offscreenPageLimit = visibleTabs.size;
            mainVp.addOnPageChangeListener(this@MainActivity)
            homeNavBookshelf.setOnClickListener { selectHomeTab(HomeTabKey.BOOKSHELF) }
            homeNavMine.setOnClickListener { selectHomeTab(HomeTabKey.MINE) }
            if (visibleTabs.isNotEmpty()) {
                updateBottomNavSelection(0)
                updateToolbarTitle(0)
            }
        }

    }

    protected fun createTabTitles(): List<String> {
        return visibleTabs.map { getString(it.titleRes) }
    }

    private fun updateToolbarTitle(position: Int) {
        if (position < 0 || position >= mTitleList.size) {
            return
        }
        val isMinePage = visibleTabs[position].key == HomeTabKey.MINE
        binding?.toolbar?.root?.let { toolbar ->
            toolbar.visibility = if (isMinePage) View.GONE else View.VISIBLE
        }
        binding?.toolbar?.homeToolbarTitle?.text = mTitleList[position]
        applyHomeStatusBarFlags()
    }

    fun selectHomeTab(tabKey: HomeTabKey) {
        val index = visibleTabs.indexOfFirst { it.key == tabKey }
        if (index < 0) {
            return
        }
        binding?.mainVp?.setCurrentItem(index, false)
        updateBottomNavSelection(index)
        updateToolbarTitle(index)
    }

    private fun updateBottomNavSelection(position: Int) {
        val selectedKey = visibleTabs.getOrNull(position)?.key ?: return
        binding?.apply {
            applyBottomNavItemState(
                homeNavBookshelf,
                homeNavBookshelfIcon,
                homeNavBookshelfLabel,
                selectedKey == HomeTabKey.BOOKSHELF
            )
            applyBottomNavItemState(
                homeNavMine,
                homeNavMineIcon,
                homeNavMineLabel,
                selectedKey == HomeTabKey.MINE
            )
        }
    }

    private fun applyBottomNavItemState(
        item: View,
        icon: ImageView,
        label: TextView,
        selected: Boolean
    ) {
        item.isSelected = selected
        icon.isSelected = selected
        label.isSelected = selected
        val color = resources.getColor(
            if (selected) R.color.home_primary else R.color.home_text_secondary
        )
        icon.imageTintList = ColorStateList.valueOf(color)
        label.setTextColor(color)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        var activityCls: Class<*>? = null
        when (id) {
            R.id.action_search -> activityCls = SearchActivity::class.java
            R.id.action_more_custom -> showHomeMoreMenu()
            else -> return super.onOptionsItemSelected(item)
        }
        if (activityCls != null) {
            val intent = Intent(this, activityCls)
            startActivity(intent)
        }
        return true
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

    private fun showHomeMoreMenu() {
        homeMoreMenuView?.dismiss()
        val menuView = HomeMoreMenuView(this)
        val token = SharedPreUtils.getInstance().getString("token")
        val userName = SharedPreUtils.getInstance().getString("userName")
        menuView.setAccountTitle(if (token.isNullOrEmpty()) "请登录" else userName)
        menuView.onImportClick = { openLocalImport() }
        menuView.onSyncClick = { syncBookShelf() }
        menuView.onAccountClick = { openAccount() }
        menuView.onDismiss = {
            restoreMenuSystemBars()
            homeMoreMenuView = null
        }
        homeMoreMenuView = menuView
        dimMenuSystemBars()
        (window.decorView as ViewGroup).addView(
            menuView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        menuView.showFrom(actionMenuItemView(R.id.action_more_custom))
    }

    private fun dimMenuSystemBars() {
        systemUiBeforeMenu = window.decorView.systemUiVisibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            statusBarColorBeforeMenu = window.statusBarColor
            window.statusBarColor = Color.TRANSPARENT
        }
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun restoreMenuSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = statusBarColorBeforeMenu
        }
        window.decorView.systemUiVisibility = systemUiBeforeMenu
    }

    private fun findMoreMenuAnchor(): View? {
        val titleResId = resources.getIdentifier("nb.menu.action.more", "string", packageName)
        val title = if (titleResId != 0) getString(titleResId) else "更多"
        return findViewByContentDescription(window.decorView, title)
    }

    private fun actionMenuItemView(itemId: Int): View? {
        return viewWithId(binding.toolbar.root, itemId) ?: findMoreMenuAnchor()
    }

    private fun viewWithId(view: View, itemId: Int): View? {
        if (view.id == itemId) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val matched = viewWithId(view.getChildAt(index), itemId)
                if (matched != null) {
                    return matched
                }
            }
        }
        return null
    }

    private fun findViewByContentDescription(view: View, contentDescription: String): View? {
        if (view.contentDescription?.toString() == contentDescription) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = findViewByContentDescription(view.getChildAt(index), contentDescription)
                if (child != null) {
                    return child
                }
            }
        }
        return null
    }

    private fun openLocalImport() {
        startActivity(Intent(this, FileSystemActivity::class.java))
    }

    private fun syncBookShelf() {
        if (SharedPreUtils.getInstance().getString("token").isNullOrEmpty()) {
            startActivity(LoginActivity.syncIntent(this))
            return
        }
        RxBus.getInstance().post(BookSyncEvent())
    }

    private fun openAccount() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    override fun onBackPressed() {
        if (homeMoreMenuView != null) {
            homeMoreMenuView?.dismiss()
            return
        }
        if (exitBookshelfFilterMenuIfNeeded()) {
            return
        }
        if (exitBookshelfEditModeIfNeeded()) {
            return
        }
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

    private fun exitBookshelfEditModeIfNeeded(): Boolean {
        val currentIndex = binding?.mainVp?.currentItem ?: return false
        val currentFragment = mFragmentList.getOrNull(currentIndex)
        return (currentFragment as? BookShelfFragment)?.exitEditModeIfNeeded() == true
    }

    private fun exitBookshelfFilterMenuIfNeeded(): Boolean {
        val currentIndex = binding?.mainVp?.currentItem ?: return false
        val currentFragment = mFragmentList.getOrNull(currentIndex)
        return (currentFragment as? BookShelfFragment)?.exitFilterMenuIfNeeded() == true
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
    override fun onPageSelected(position: Int) {
        updateToolbarTitle(position)
        if (position < 0 || position >= visibleTabs.size) {
            return
        }
        updateBottomNavSelection(position)
    }
    override fun onPageScrollStateChanged(state: Int) {}

    private fun visibleHomeTabs(): List<HomeTab> {
        return allHomeTabs().filter { it.visible }
    }

    private fun allHomeTabs(): List<HomeTab> {
        return listOf(
            HomeTab(HomeTabKey.BOOKSHELF, R.id.home_nav_bookshelf, R.string.home_tab_bookshelf, true),
            HomeTab(HomeTabKey.BOOKSTORE, R.id.home_nav_bookstore, R.string.home_tab_bookstore, false),
            HomeTab(HomeTabKey.MINE, R.id.home_nav_mine, R.string.home_tab_mine, true)
        )
    }

    enum class HomeTabKey {
        BOOKSHELF,
        BOOKSTORE,
        MINE
    }

    private data class HomeTab(
        val key: HomeTabKey,
        val menuItemId: Int,
        val titleRes: Int,
        val visible: Boolean
    )

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
            return mTitleList[position]
        }
    }

    companion object {
        /*************Constant */
        private const val WAIT_INTERVAL = 2000
    }
}
