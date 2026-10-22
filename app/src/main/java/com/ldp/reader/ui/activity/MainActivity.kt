package com.ldp.reader.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityMainBinding
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.fragment.BookStoreFragment
import com.ldp.reader.ui.fragment.BookShelfFragment
import com.ldp.reader.ui.fragment.MineFragment
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.ui.motion.SpringValue
import com.ldp.reader.ui.theme.EdgeToEdge
import com.ldp.reader.ui.widget.HomeMoreMenuView
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils

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
    private var navIndicatorSpring: SpringValue? = null
    private var navBarSpring: SpringValue? = null
    private var navIndicatorStep = 0f
    private var navIndicatorPlaced = false
    private val loginSyncLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK &&
                BookshelfSyncRequest.isRequested(result.data)
            ) {
                requestBookShelfSync()
            }
        }

    /*****************Params */
    private val backPressState = MainBackPressState(WAIT_INTERVAL)

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
        EdgeToEdge.apply(this)
        EdgeToEdge.padTop(binding.toolbar.root)
        EdgeToEdge.liftAboveNavigation(binding.homeBottomNav)
        setUpNavMotion()
    }

    /** The selection pill glides between tabs; the picked icon gives a small spring pop. */
    private fun setUpNavMotion() {
        val indicatorWidth = resources.getDimension(R.dimen.home_tab_indicator_width)
        navIndicatorSpring = SpringValue(this, response = 0.42f, dampingRatio = 0.78f, restDelta = 0.5f).apply {
            listener = SpringValue.Listener { spring ->
                binding.homeNavIndicator.translationX = spring.value
            }
        }
        navIndicatorStep = indicatorWidth
        navBarSpring = SpringValue(this, response = 0.4f, dampingRatio = 0.82f, restDelta = 0.002f).apply {
            listener = SpringValue.Listener { spring ->
                val hidden = spring.value
                val nav = binding.homeBottomNav
                nav.translationY = hidden * (nav.height + nav.marginBottomPx())
                nav.alpha = 1f - hidden
                nav.visibility = if (hidden >= 0.999f && !spring.isRunning) View.INVISIBLE else View.VISIBLE
            }
            jumpTo(0f)
        }
        // The pager restores its page after recreation (e.g. a dark-mode switch from 我的);
        // sync the tab chrome to whatever page it actually shows.
        binding.mainVp.post {
            val current = binding.mainVp.currentItem
            updateBottomNavSelection(current)
            updateToolbarTitle(current)
        }
    }

    private fun View.marginBottomPx(): Int = (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

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
    }

    fun selectHomeTab(tabKey: HomeTabKey) {
        val index = visibleTabs.indexOfFirst { it.key == tabKey }
        if (index < 0) {
            return
        }
        val pager = binding?.mainVp ?: return
        if (pager.currentItem != index) {
            // Fade through: the new page rises in instead of cutting.
            pager.alpha = 0f
            pager.translationY = resources.displayMetrics.density * 10f
            pager.animate().alpha(1f).translationY(0f).setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
        pager.setCurrentItem(index, false)
        updateBottomNavSelection(index)
        updateToolbarTitle(index)
    }

    /** The tab capsule sinks away during shelf editing, making room for the edit bar. */
    fun setHomeBottomNavigationVisible(visible: Boolean) {
        navBarSpring?.animateTo(if (visible) 0f else 1f)
    }

    private fun updateBottomNavSelection(position: Int) {
        val selectedKey = visibleTabs.getOrNull(position)?.key ?: return
        val indicator = navIndicatorSpring
        if (indicator != null) {
            val target = position * navIndicatorStep
            if (navIndicatorPlaced) indicator.animateTo(target) else indicator.jumpTo(target)
            navIndicatorPlaced = true
        }
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
        if (selected && !item.isSelected && navIndicatorPlaced) {
            icon.scaleX = 0.82f
            icon.scaleY = 0.82f
            icon.animate().scaleX(1f).scaleY(1f).setDuration(320L)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
        }
        item.isSelected = selected
        icon.isSelected = selected
        label.isSelected = selected
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

    private fun showHomeMoreMenu() {
        homeMoreMenuView?.dismiss()
        val menuView = HomeMoreMenuView(this)
        val token = SharedPreUtils.getInstance().getString("token")
        val userName = SharedPreUtils.getInstance().getString("userName")
        menuView.setAccountTitle(if (token.isNullOrEmpty()) "请登录" else userName)
        menuView.onImportClick = { openLocalImport() }
        menuView.onImportSourceClick = { openSourceImport() }
        menuView.onSyncClick = { syncBookShelf() }
        menuView.onAccountClick = { openAccount() }
        menuView.onDismiss = {
            homeMoreMenuView = null
        }
        homeMoreMenuView = menuView
        (window.decorView as ViewGroup).addView(
            menuView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        menuView.showFrom(actionMenuItemView(R.id.action_more_custom))
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

    private fun openSourceImport() {
        startActivity(Intent(this, SourceImportActivity::class.java))
    }

    private fun syncBookShelf() {
        requestBookShelfSync()
    }

    fun requestBookShelfSync() {
        if (SharedPreUtils.getInstance().getString("token").isNullOrEmpty()) {
            loginSyncLauncher.launch(LoginActivity.syncIntent(this))
            return
        }
        bookShelfFragment()?.requestBookShelfSync()
    }

    private fun openAccount() {
        loginSyncLauncher.launch(Intent(this, LoginActivity::class.java))
    }

    override fun onBackPressed() {
        if (homeMoreMenuView != null) {
            homeMoreMenuView?.dismiss()
            backPressState.onBackPressed(
                SystemClock.elapsedRealtime(),
                MainBackLayer.MORE_MENU
            )
            return
        }
        if (exitBookshelfEditModeIfNeeded()) {
            backPressState.onBackPressed(
                SystemClock.elapsedRealtime(),
                MainBackLayer.BOOKSHELF_EDIT
            )
            return
        }
        when (backPressState.onBackPressed(SystemClock.elapsedRealtime())) {
            MainBackAction.SHOW_EXIT_PROMPT -> {
                ToastUtils.show("再按一次退出")
            }
            MainBackAction.EXIT -> super.onBackPressed()
            MainBackAction.LAYER_DISMISSED -> Unit
        }
    }

    private fun exitBookshelfEditModeIfNeeded(): Boolean {
        val currentIndex = binding?.mainVp?.currentItem ?: return false
        val currentFragment = activeFragmentAt(currentIndex)
        return (currentFragment as? BookShelfFragment)?.exitEditModeIfNeeded() == true
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldSelectBookshelf(intent)) {
            selectHomeTab(HomeTabKey.BOOKSHELF)
        }
        if (BookshelfSyncRequest.isRequested(intent)) {
            requestBookShelfSync()
        }
    }

    override fun onPause() {
        backPressState.reset()
        super.onPause()
    }

    private fun shouldSelectBookshelf(intent: Intent?): Boolean {
        return intent?.getStringExtra(EXTRA_HOME_TAB) == HomeTabKey.BOOKSHELF.name
    }

    private fun activeFragmentAt(index: Int): Fragment? {
        val pagerId = binding?.mainVp?.id ?: R.id.main_vp
        return supportFragmentManager.findFragmentByTag(fragmentPagerTag(pagerId, index))
            ?: mFragmentList.getOrNull(index)?.takeIf { it.isAdded }
    }

    private fun bookShelfFragment(): BookShelfFragment? {
        val index = visibleTabs.indexOfFirst { it.key == HomeTabKey.BOOKSHELF }
        if (index < 0) return null
        return activeFragmentAt(index) as? BookShelfFragment
    }

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
        fm!!,
        FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
    ) {
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val restoredOrCreated = super.instantiateItem(container, position)
            if (restoredOrCreated is Fragment && position in mFragmentList.indices) {
                // FragmentPagerAdapter restores instances from FragmentManager after a
                // configuration change. Rebind our tab registry so sync/back handling never
                // targets the fresh, unattached placeholders created by initFragment().
                mFragmentList[position] = restoredOrCreated
            }
            return restoredOrCreated
        }

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
        private const val WAIT_INTERVAL = 2_000L
        private const val EXTRA_HOME_TAB = "homeTab"

        @JvmStatic
        fun fragmentPagerTag(viewPagerId: Int, position: Int): String {
            return "android:switcher:$viewPagerId:$position"
        }

        fun bookshelfIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_HOME_TAB, HomeTabKey.BOOKSHELF.name)
        }

        fun syncRequestIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtras(BookshelfSyncRequest.resultIntent())
        }
    }
}
