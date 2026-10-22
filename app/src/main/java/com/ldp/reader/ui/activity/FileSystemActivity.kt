package com.ldp.reader.ui.activity

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewbinding.ViewBinding
import androidx.viewpager.widget.ViewPager
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityFileSystemBinding
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.theme.InkSystemBars
import com.ldp.reader.ui.fragment.BaseFileFragment
import com.ldp.reader.ui.fragment.BaseFileFragment.OnFileCheckedListener
import com.ldp.reader.ui.fragment.DocumentImportFragment
import com.ldp.reader.ui.fragment.FileCategoryFragment
import com.ldp.reader.ui.fragment.LocalBookFragment
import com.ldp.reader.ui.file.LocalImportEntryPolicy
import com.ldp.reader.ui.file.LocalImportFilter
import com.ldp.reader.ui.file.LocalImportTab
import com.ldp.reader.ui.file.LocalImportUiState
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.LocalBookImportFiles
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.utils.StringUtils
import com.ldp.reader.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Arrays

/**
 * Created by ldp on 17-5-27.
 */
class FileSystemActivity : BaseActivity<ActivityFileSystemBinding>() {
    private val fileActionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mLocalFragment: LocalBookFragment? = null
    private var mCategoryFragment: FileCategoryFragment? = null
    private var mDocumentFragment: DocumentImportFragment? = null
    private var mCurFragment: BaseFileFragment<out ViewBinding>? = null
    private var isSavingSelectedBooks = false
    private var importUiState = LocalImportUiState()
    private var restoredImportUiState = false
    private var storagePermissionPromptShown = false
    private var lastStoragePermissionState: Boolean? = null
    private val mListener: OnFileCheckedListener = object : OnFileCheckedListener {
        override fun onItemCheckedChange(
            source: BaseFileFragment<*>,
            isChecked: Boolean
        ) {
            if (source !== mCurFragment) return
            changeMenuStatus()
        }

        override fun onCategoryChanged(source: BaseFileFragment<*>) {
            // ViewPager keeps neighbouring fragments alive. Their async callbacks must never
            // rewrite the selection controls for the page the user is actually viewing.
            if (source !== mCurFragment) return
            //改变菜单
            changeMenuStatus()
            //改变是否能够全选
            changeCheckedAllStatus()
        }
    }

    protected fun createTabFragments(): List<Fragment> {
        mLocalFragment = LocalBookFragment()
        mCategoryFragment = FileCategoryFragment()
        mDocumentFragment = DocumentImportFragment()
        return Arrays.asList<Fragment>(mLocalFragment, mCategoryFragment, mDocumentFragment)
    }

    protected fun createTabTitles(): List<String> {
        return Arrays.asList("最近文件", "手机目录", "选择文件")
    }

    override fun initData(savedInstanceState: Bundle?) {
        super.initData(savedInstanceState)
        if (savedInstanceState == null) return
        restoredImportUiState = true
        importUiState = LocalImportUiState.restore(
            tabName = savedInstanceState.getString(STATE_IMPORT_TAB),
            filterName = savedInstanceState.getString(STATE_IMPORT_FILTER),
            query = savedInstanceState.getString(STATE_IMPORT_QUERY),
            searchVisible = savedInstanceState.getBoolean(STATE_IMPORT_SEARCH_VISIBLE)
        )
    }

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar!!.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_ink_back)
        toolbar?.setNavigationContentDescription(R.string.file_system_navigate_back)
        InkSystemBars.apply(this)
    }

    override fun toolbarView(): Toolbar {
        return binding.toolbar
    }

    //    /************Params*******************/
    private var mFragmentList: List<Fragment>? = null
    private var mTitleList: List<String>? = null
    override fun initClick() {
        super.initClick()
        binding.apply {

            toolbar.setNavigationOnClickListener { handleBackNavigation() }

            fileSystemCbSelectedAll.setOnClickListener { view: View? ->
                val fragment = mCurFragment ?: return@setOnClickListener
                //设置全选状态
                val isChecked = fileSystemCbSelectedAll!!.isChecked
                fragment.isCheckedAll = isChecked
                //改变菜单状态
                changeMenuStatus()
            }
            tabVp.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                }

                override fun onPageSelected(position: Int) {
                    selectTab(position, explainMissingPermission = true)
                }

                override fun onPageScrollStateChanged(state: Int) {}
            })
            fileSystemTitle.setOnClickListener {
                showImportModeDialog()
            }
            fileSystemSearch.setOnClickListener {
                openSearch()
            }
            fileSystemFilter.setOnClickListener {
                showFilterDialog()
            }
            fileSystemSearchInput.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    importUiState = importUiState.updateQuery(query.orEmpty())
                    applyDisplayOptions()
                    fileSystemSearchInput.clearFocus()
                    hideKeyboard(fileSystemSearchInput)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    importUiState = importUiState.updateQuery(newText.orEmpty())
                    applyDisplayOptions()
                    return true
                }
            })
            fileSystemSearchInput.setOnCloseListener {
                closeSearch()
                true
            }
            fileSystemBtnAddBook!!.setOnClickListener { v: View? ->
                val fragment = mCurFragment ?: return@setOnClickListener
                if (isSavingSelectedBooks) return@setOnClickListener
                //获取选中的文件
                val files = fragment.checkedFiles
                isSavingSelectedBooks = true
                fileActionScope.launch {
                    try {
                        //转换成CollBook,并存储
                        val conversion = withContext(Dispatchers.IO) {
                            val result = convertCollBook(files)
                            val books = result.books
                            if (books.isNotEmpty()) {
                                BookRepository.getInstance()
                                    .saveCollBooks(books)
                            }
                            result
                        }
                        val collBooks = conversion.books
                        val savedBookIds = collBooks.mapNotNull { it.get_id() }.toSet()
                        fragment.markFilesLoaded(
                            files.filter { file -> MD5Utils.strToMd5By16(file.absolutePath) in savedBookIds }
                        )
                        // Deleted, empty, or otherwise invalid selections are no longer actionable.
                        // Remove only those original selections so choices made while saving stay intact.
                        fragment.removeSelection(conversion.skippedFiles)
                        // 导入可能跨过一次切页或筛选；只刷新发起导入的列表，且不要清掉
                        // 用户在等待期间于当前页面新做的选择。
                        if (mCurFragment === fragment) {
                            changeMenuStatus()
                            changeCheckedAllStatus()
                        }
                        when {
                            collBooks.isEmpty() -> ToastUtils.show(
                                "所选文件已不存在或无法导入，请重新选择"
                            )
                            conversion.skippedCount > 0 -> ToastUtils.show(
                                "其中 ${conversion.skippedCount} 个文件无法读取，未加入书架"
                            )
                            else -> ToastUtils.show(
                                resources.getString(R.string.nb_file_add_succeed, collBooks.size)
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        ToastUtils.show("加入书架失败，请重试")
                    } finally {
                        isSavingSelectedBooks = false
                    }
                }
            }
            fileSystemBtnDelete.setOnClickListener { v: View? ->
                mCurFragment?.clearSelection()
            }
            mLocalFragment?.setOnFileCheckedListener(mListener)
            mCategoryFragment?.setOnFileCheckedListener(mListener)
        }

    }

    override fun processLogic() {
        super.processLogic()
        selectTab(binding.tabVp.currentItem, explainMissingPermission = false)
    }

    private fun selectTab(position: Int, explainMissingPermission: Boolean) {
        val selectedTab = LocalImportTab.fromPageIndex(position)
        if (
            LocalImportEntryPolicy.requiresBroadStorageAccess(selectedTab) &&
            !isPermissionGranted()
        ) {
            switchToSaf(clearScanData = true)
            if (explainMissingPermission) {
                showStorageAccessDialog()
            }
            return
        }

        importUiState = importUiState.selectTab(selectedTab)
        mCurFragment = fragmentFor(selectedTab)
        if (mCurFragment == null && importUiState.searchVisible) {
            resetSearchState()
        }
        updateScanAccess(clearData = false)
        binding.fileSystemBottomBar.visibility = if (mCurFragment == null) View.GONE else View.VISIBLE
        mCurFragment?.applyDisplayOptions(importUiState.query, importUiState.filter)
        renderTopControls()
        changeMenuStatus()
        changeCheckedAllStatus()
    }

    private fun fragmentFor(tab: LocalImportTab): BaseFileFragment<out ViewBinding>? {
        return when (tab) {
            LocalImportTab.RECENT_FILES -> mLocalFragment
            LocalImportTab.PHONE_DIRECTORY -> mCategoryFragment
            LocalImportTab.PICK_FILE -> null
        }
    }

    private fun updateScanAccess(clearData: Boolean) {
        val hasAccess = isPermissionGranted()
        mLocalFragment?.setScanAccessEnabled(
            enabled = importUiState.tab == LocalImportTab.RECENT_FILES &&
                LocalImportEntryPolicy.canScan(importUiState.tab, hasAccess),
            clearData = clearData
        )
        mCategoryFragment?.setScanAccessEnabled(
            enabled = importUiState.tab == LocalImportTab.PHONE_DIRECTORY &&
                LocalImportEntryPolicy.canScan(importUiState.tab, hasAccess),
            clearData = clearData
        )
    }

    private fun switchToSaf(clearScanData: Boolean) {
        resetSearchState()
        importUiState = importUiState.selectTab(LocalImportTab.PICK_FILE)
        mCurFragment = null
        updateScanAccess(clearData = clearScanData)
        binding.fileSystemBottomBar.visibility = View.GONE
        renderTopControls()
        changeMenuStatus()
        changeCheckedAllStatus()
        binding.tabVp.post {
            if (binding.tabVp.currentItem != LocalImportTab.PICK_FILE.pageIndex) {
                binding.tabVp.setCurrentItem(LocalImportTab.PICK_FILE.pageIndex, false)
            }
        }
    }

    private fun resetSearchState() {
        importUiState = importUiState.clearSearch()
        binding.fileSystemSearchInput.apply {
            setQuery("", false)
            clearFocus()
        }
        hideKeyboard(binding.fileSystemSearchInput)
    }

    private fun showImportModeDialog() {
        val tabs = LocalImportTab.entries
        val labels = tabs.map { tab ->
            when (tab) {
                LocalImportTab.RECENT_FILES -> "智能导入（最近文件）"
                LocalImportTab.PHONE_DIRECTORY -> "浏览手机目录"
                LocalImportTab.PICK_FILE -> "选择单个文件"
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("导入方式")
            .setSingleChoiceItems(labels, importUiState.tab.ordinal) { dialog, which ->
                binding.tabVp.currentItem = tabs[which].pageIndex
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.nb_common_cancel), null)
            .show()
    }

    private fun showFilterDialog() {
        val filters = LocalImportFilter.entries
        val labels = filters.map(LocalImportFilter::label).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("筛选文件")
            .setSingleChoiceItems(labels, importUiState.filter.ordinal) { dialog, which ->
                importUiState = importUiState.selectFilter(filters[which])
                applyDisplayOptions()
                renderTopControls()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.nb_common_cancel), null)
            .show()
    }

    private fun openSearch() {
        if (mCurFragment == null) return
        importUiState = importUiState.showSearch()
        renderTopControls()
        binding.fileSystemSearchInput.apply {
            setQuery(importUiState.query, false)
            isIconified = false
            requestFocus()
            post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun closeSearch() {
        resetSearchState()
        applyDisplayOptions()
        renderTopControls()
    }

    private fun handleBackNavigation() {
        if (importUiState.searchVisible) {
            closeSearch()
            return
        }
        if (
            importUiState.tab == LocalImportTab.PHONE_DIRECTORY &&
            mCategoryFragment?.navigateBack() == true
        ) {
            return
        }
        finish()
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun applyDisplayOptions() {
        mCurFragment?.applyDisplayOptions(importUiState.query, importUiState.filter)
        changeMenuStatus()
        changeCheckedAllStatus()
    }

    private fun renderTopControls() {
        val isListTab = importUiState.tab != LocalImportTab.PICK_FILE
        binding.fileSystemTitle.text = importUiState.toolbarTitle
        binding.fileSystemTitle.contentDescription = "导入方式：${importUiState.toolbarTitle}"
        binding.fileSystemTitle.visibility =
            if (importUiState.searchVisible) View.GONE else View.VISIBLE
        binding.fileSystemSearchInput.visibility =
            if (importUiState.searchVisible) View.VISIBLE else View.GONE
        binding.fileSystemSearch.visibility =
            if (isListTab && !importUiState.searchVisible) View.VISIBLE else View.GONE
        binding.fileSystemFilter.visibility =
            if (isListTab && !importUiState.searchVisible) View.VISIBLE else View.GONE
        binding.fileSystemFilter.isSelected = importUiState.hasActiveFilter
        binding.fileSystemFilter.contentDescription = "筛选：${importUiState.filterLabel}"
        binding.fileSystemFilter.alpha = if (importUiState.hasActiveFilter) 1f else 0.72f
    }

    private fun showStorageAccessDialog() {
        if (storagePermissionPromptShown || isFinishing) return
        storagePermissionPromptShown = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("访问本机书籍")
            .setMessage(
                "“最近文件”和“手机目录”需要文件访问权限。你也可以不授权，使用系统“选择文件”导入单个文件。"
            )
            .setPositiveButton("前往授权") { _, _ ->
                requestManageAllFilesAccessPermission()
            }
            .setNegativeButton("使用选择文件") { _, _ ->
                binding.tabVp.currentItem = LocalImportTab.PICK_FILE.pageIndex
            }
            .setNeutralButton(getString(R.string.nb_common_cancel), null)
            .create()
        dialog.setOnDismissListener {
            storagePermissionPromptShown = false
        }
        dialog.show()
    }

    /**
     * 将文件转换成CollBook
     *
     * @param files:需要加载的文件列表
     * @return
     */
    private fun convertCollBook(files: List<File>): LocalTextSelectionConversion {
        val collBooks: MutableList<CollBookBean> = ArrayList(files.size)
        val skippedFiles = ArrayList<File>()
        for (file in files) {
            if (
                !LocalBookImportFiles.isTextFile(file) ||
                !LocalBookImportFiles.isImportableFile(file)
            ) {
                skippedFiles.add(file)
                continue
            }
            val collBook = CollBookBean()
            collBook._id = MD5Utils.strToMd5By16(file.absolutePath)
            BookshelfLocalProgressStore.clear(collBook._id)
            collBook.title = file.nameWithoutExtension
            collBook.author = ""
            collBook.shortIntro = "无"
            collBook.cover = file.absolutePath
            collBook.setLocal(true)
            collBook.lastChapter = "开始阅读"
            collBook.updated =
                StringUtils.dateConvert(file.lastModified(), Constant.FORMAT_BOOK_DATE)
            collBook.lastRead =
                StringUtils.dateConvert(System.currentTimeMillis(), Constant.FORMAT_BOOK_DATE)
            collBooks.add(collBook)
        }
        return LocalTextSelectionConversion(
            books = collBooks,
            skippedFiles = skippedFiles
        )
    }

    /**
     * 改变底部选择栏的状态
     */
    private fun changeMenuStatus() {
        binding?.apply {
            val fragment = mCurFragment
            if (fragment == null) {
                fileSystemSelectedCount.text = "已选 0 项"
                fileSystemBtnAddBook!!.text = getString(R.string.nb_file_add_shelf)
                fileSystemCbSelectedAll!!.isChecked = false
                fileSystemCbSelectedAll!!.text = "全选"
                setMenuClickable(false)
                return
            }
            //点击、删除状态的设置
            if (fragment.checkedCount == 0) {
                fragment.setChecked(false)
                fileSystemSelectedCount.text = "已选 0 项"
                fileSystemBtnAddBook!!.text = getString(R.string.nb_file_add_shelf)
                //设置某些按钮的是否可点击
                setMenuClickable(false)
            } else {
                fileSystemSelectedCount.text = "已选 ${fragment.checkedCount} 项"
                fileSystemBtnAddBook!!.text =
                    getString(R.string.nb_file_add_shelves, fragment.checkedCount)
                setMenuClickable(true)

                //全选状态的设置

                //如果选中的全部的数据，则判断为全选
                if (fragment.areAllVisibleCheckableFilesSelected) {
                    //设置为全选
                    fragment.setChecked(true)
                } else if (fragment.isCheckedAll) {
                    fragment.setChecked(false)
                }
            }

            // The CheckBox is activity-owned while each tab owns independent selection state.
            // Always render from the current fragment so the previous tab cannot leak its visual
            // checked state into a partially selected tab.
            fileSystemCbSelectedAll!!.isChecked = fragment.isCheckedAll

            //重置全选的文字
            if (fragment.isCheckedAll) {
                fileSystemCbSelectedAll!!.text = "取消"
            } else {
                fileSystemCbSelectedAll!!.text = "全选"
            }
        }


    }

    private fun setMenuClickable(isClickable: Boolean) {
        binding?.apply {
            //设置是否可取消当前选择
            fileSystemBtnDelete!!.isEnabled = isClickable
            fileSystemBtnDelete!!.isClickable = isClickable
            fileSystemBtnDelete!!.alpha = if (isClickable) 1f else 0.36f

            //设置是否可添加书籍
            fileSystemBtnAddBook!!.isEnabled = isClickable
            fileSystemBtnAddBook!!.isClickable = isClickable
            fileSystemBtnAddBook!!.alpha = if (isClickable) 1f else 0.72f
        }
    }

    /**
     * 改变全选按钮的状态
     */
    private fun changeCheckedAllStatus() {
        //获取可选择的文件数量
        val count = mCurFragment?.checkableCount ?: 0

        binding.apply {
            //设置是否能够全选
            if (count > 0) {
                fileSystemCbSelectedAll!!.isClickable = true
                fileSystemCbSelectedAll!!.isEnabled = true
                fileSystemCbSelectedAll!!.alpha = 1f
            } else {
                fileSystemCbSelectedAll!!.isClickable = false
                fileSystemCbSelectedAll!!.isEnabled = false
                fileSystemCbSelectedAll!!.alpha = 0.36f
            }

        }

    }

    override fun getViewBinding(): ActivityFileSystemBinding {
        return ActivityFileSystemBinding.inflate(layoutInflater)
    }

    override fun onDestroy() {
        fileActionScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        importUiState = importUiState.updateQuery(binding.fileSystemSearchInput.query.toString())
        outState.putString(STATE_IMPORT_TAB, importUiState.tab.name)
        outState.putString(STATE_IMPORT_FILTER, importUiState.filter.name)
        outState.putString(STATE_IMPORT_QUERY, importUiState.query)
        outState.putBoolean(STATE_IMPORT_SEARCH_VISIBLE, importUiState.searchVisible)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    /*****************rewrite method */
    override fun initWidget() {
        super.initWidget()
        val hasStorageAccess = isPermissionGranted()
        if (
            !restoredImportUiState ||
            (LocalImportEntryPolicy.requiresBroadStorageAccess(importUiState.tab) &&
                !hasStorageAccess)
        ) {
            importUiState = importUiState.selectTab(
                LocalImportEntryPolicy.initialTab(hasStorageAccess)
            )
        }
        binding.fileSystemSearchInput.isSaveEnabled = false
        binding.fileSystemSearchInput.setQuery(importUiState.query, false)
        setUpTabLayout()
        binding.tabVp.setCurrentItem(importUiState.tab.pageIndex, false)
    }

    private fun setUpTabLayout() {
        mFragmentList = createTabFragments()
        mTitleList = createTabTitles()
        checkParamsIsRight()
        val adapter: TabFragmentPageAdapter = TabFragmentPageAdapter(
            supportFragmentManager
        )
        val mTlIndicator = binding.tabTlIndicator
        val mVp = binding.tabVp
        mVp.adapter = adapter
        mVp.offscreenPageLimit = 1
        mTlIndicator.setupWithViewPager(mVp)
    }

    fun requestManageAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(appSettings) }
                .onFailure {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_READ_STORAGE
            )
        }
    }

    // To check if permission is granted
    fun isPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onResume() {
        super.onResume()
        val permissionGranted = isPermissionGranted()
        if (lastStoragePermissionState == false && permissionGranted) {
            recreate()
            return
        }
        if (lastStoragePermissionState == true && !permissionGranted) {
            lastStoragePermissionState = false
            switchToSaf(clearScanData = true)
            return
        }
        lastStoragePermissionState = permissionGranted
    }

    /**
     * 检查输入的参数是否正确。即Fragment和title是成对的。
     */
    private fun checkParamsIsRight() {
        require(!(mFragmentList == null || mTitleList == null)) { "fragmentList or titleList doesn't have null" }
        require(mFragmentList!!.size == mTitleList!!.size) { "fragment and title size must equal" }
    }

    /******************inner class */
    internal inner class TabFragmentPageAdapter(fm: FragmentManager?) : FragmentPagerAdapter(
        fm!!
    ) {
        override fun getItem(position: Int): Fragment {
            return mFragmentList!![position]
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val restoredOrCreated = super.instantiateItem(container, position)
            if (restoredOrCreated is Fragment) {
                bindTabFragment(position, restoredOrCreated)
            }
            return restoredOrCreated
        }

        override fun getCount(): Int {
            return mFragmentList!!.size
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return mTitleList!![position]
        }
    }

    private fun bindTabFragment(position: Int, fragment: Fragment) {
        when (position) {
            LocalImportTab.RECENT_FILES.pageIndex -> {
                val actual = fragment as? LocalBookFragment ?: return
                mLocalFragment = actual
                actual.setOnFileCheckedListener(mListener)
            }
            LocalImportTab.PHONE_DIRECTORY.pageIndex -> {
                val actual = fragment as? FileCategoryFragment ?: return
                mCategoryFragment = actual
                actual.setOnFileCheckedListener(mListener)
            }
            LocalImportTab.PICK_FILE.pageIndex -> {
                mDocumentFragment = fragment as? DocumentImportFragment ?: return
            }
        }
        if (importUiState.tab.pageIndex == position) {
            mCurFragment = fragmentFor(importUiState.tab)
            mCurFragment?.applyDisplayOptions(importUiState.query, importUiState.filter)
        }
        updateScanAccess(clearData = false)
    }

    companion object {
        private const val TAG = "FileSystemActivity"
        private const val REQUEST_READ_STORAGE = 3101
        private const val STATE_IMPORT_TAB = "localImport.tab"
        private const val STATE_IMPORT_FILTER = "localImport.filter"
        private const val STATE_IMPORT_QUERY = "localImport.query"
        private const val STATE_IMPORT_SEARCH_VISIBLE = "localImport.searchVisible"
    }

    private data class LocalTextSelectionConversion(
        val books: List<CollBookBean>,
        val skippedFiles: List<File>
    ) {
        val skippedCount: Int
            get() = skippedFiles.size
    }
}
