package com.ldp.reader.ui.activity

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
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
import com.ldp.reader.ui.fragment.BaseFileFragment
import com.ldp.reader.ui.fragment.BaseFileFragment.OnFileCheckedListener
import com.ldp.reader.ui.fragment.DocumentImportFragment
import com.ldp.reader.ui.fragment.FileCategoryFragment
import com.ldp.reader.ui.fragment.LocalBookFragment
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.LocalBookImportFiles
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.utils.StringUtils
import com.ldp.reader.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
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
    private val mListener: OnFileCheckedListener = object : OnFileCheckedListener {
        override fun onItemCheckedChange(isChecked: Boolean) {
            changeMenuStatus()
        }

        override fun onCategoryChanged() {
            //状态归零
            mCurFragment?.isCheckedAll = false
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

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar!!.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
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
                    mCurFragment = when (position) {
                        0 -> mLocalFragment
                        1 -> mCategoryFragment
                        else -> null
                    }
                    fileSystemBottomBar.visibility = if (mCurFragment == null) View.GONE else View.VISIBLE
                    //改变菜单状态
                    changeMenuStatus()
                }

                override fun onPageScrollStateChanged(state: Int) {}
            })
            fileSystemSearch.setOnClickListener {
                ToastUtils.show("搜索本地文件暂未开放")
            }
            fileSystemFilter.setOnClickListener {
                ToastUtils.show("当前仅显示可导入文件")
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
                        val collBooks = withContext(Dispatchers.IO) {
                            val books = convertCollBook(files)
                            BookRepository.getInstance()
                                .saveCollBooks(books)
                            books
                        }
                        val savedBookIds = collBooks.mapNotNull { it.get_id() }.toSet()
                        fragment.markFilesLoaded(
                            files.filter { file -> MD5Utils.strToMd5By16(file.absolutePath) in savedBookIds }
                        )
                        //设置HashMap为false
                        fragment.isCheckedAll = false
                        //改变菜单状态
                        changeMenuStatus()
                        //改变是否可以全选
                        changeCheckedAllStatus()
                        //提示加入书架成功
                        ToastUtils.show(resources.getString(R.string.nb_file_add_succeed, collBooks.size))
                    } finally {
                        isSavingSelectedBooks = false
                    }
                }
            }
            fileSystemBtnDelete.setOnClickListener { v: View? ->
                //弹出，确定删除文件吗。
                AlertDialog.Builder(this@FileSystemActivity)
                    .setTitle("删除文件")
                    .setMessage("确定删除文件吗?")
                    .setPositiveButton(
                        resources.getString(R.string.nb_common_sure),
                        DialogInterface.OnClickListener { dialog, which -> //删除选中的文件
                            mCurFragment?.deleteCheckedFiles()
                            //提示删除文件成功
                            ToastUtils.show("删除文件成功")
                        })
                    .setNegativeButton(resources.getString(R.string.nb_common_cancel), null)
                    .show()
            }
            mLocalFragment!!.setOnFileCheckedListener(mListener)
            mCategoryFragment!!.setOnFileCheckedListener(mListener)
        }

    }

    override fun processLogic() {
        super.processLogic()
        mCurFragment = mLocalFragment
        if(!isPermissionGranted()){
            requestManageAllFilesAccessPermission()

        }

    }

    /**
     * 将文件转换成CollBook
     *
     * @param files:需要加载的文件列表
     * @return
     */
    private fun convertCollBook(files: List<File>): List<CollBookBean> {
        val collBooks: MutableList<CollBookBean> = ArrayList(files.size)
        for (file in files.filter { LocalBookImportFiles.isTextFile(it) }) {
            //判断文件是否存在
            if (!file.exists()) continue
            val collBook = CollBookBean()
            collBook._id = MD5Utils.strToMd5By16(file.absolutePath)
            BookshelfLocalProgressStore.clear(collBook._id)
            collBook.title = file.name.replace(".txt", "")
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
        return collBooks
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
                fileSystemSelectedCount.text = "已选 0 项"
                fileSystemBtnAddBook!!.text = getString(R.string.nb_file_add_shelf)
                //设置某些按钮的是否可点击
                setMenuClickable(false)
                if (fileSystemCbSelectedAll!!.isChecked) {
                    fragment.setChecked(false)
                    fileSystemCbSelectedAll!!.isChecked = fragment.isCheckedAll
                }
            } else {
                fileSystemSelectedCount.text = "已选 ${fragment.checkedCount} 项"
                fileSystemBtnAddBook!!.text =
                    getString(R.string.nb_file_add_shelves, fragment.checkedCount)
                setMenuClickable(true)

                //全选状态的设置

                //如果选中的全部的数据，则判断为全选
                if (fragment.checkedCount == fragment.checkableCount) {
                    //设置为全选
                    fragment.setChecked(true)
                    fileSystemCbSelectedAll!!.isChecked = fragment.isCheckedAll
                } else if (fragment.isCheckedAll) {
                    fragment.setChecked(false)
                    fileSystemCbSelectedAll!!.isChecked = fragment.isCheckedAll
                }
            }

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
            //设置是否可删除
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

    /*****************rewrite method */
    override fun initWidget() {
        super.initWidget()
        setUpTabLayout()
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
        mVp.offscreenPageLimit = 3
        mTlIndicator.setupWithViewPager(mVp)
    }

    fun requestManageAllFilesAccessPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        startActivity(intent)
    }

    // To check if permission is granted
    fun isPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            return  true
        }
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

        override fun getCount(): Int {
            return mFragmentList!!.size
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return mTitleList!![position]
        }
    }

    companion object {
        private const val TAG = "FileSystemActivity"
    }
}
