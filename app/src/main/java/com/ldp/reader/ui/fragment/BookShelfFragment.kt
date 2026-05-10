package com.ldp.reader.ui.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.recyclerview.widget.LinearLayoutManager
import com.ldp.reader.R
import com.ldp.reader.RxBus
import com.ldp.reader.databinding.FooterBookShelfBinding
import com.ldp.reader.databinding.FragmentBookshelfBinding
import com.ldp.reader.event.BookSyncEvent
import com.ldp.reader.event.DeleteResponseEvent
import com.ldp.reader.event.DeleteTaskEvent
import com.ldp.reader.event.DownloadMessage
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.model.local.Void
import com.ldp.reader.presenter.BookShelfPresenter
import com.ldp.reader.presenter.contract.BookShelfContract
import com.ldp.reader.ui.activity.FileSystemActivity
import com.ldp.reader.ui.activity.ReadActivity
import com.ldp.reader.ui.activity.SearchActivity
import com.ldp.reader.ui.adapter.CollBookAdapter
import com.ldp.reader.ui.base.BaseMVPFragment
import com.ldp.reader.utils.RxUtils
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils
import com.ldp.reader.widget.adapter.WholeAdapter
import com.ldp.reader.widget.refresh.ScrollRefreshRecyclerView
import com.mob.pushsdk.MobPush
import com.tbruyelle.rxpermissions2.RxPermissions
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.Consumer
import java.io.File

/**
 * Created by ldp on 17-4-15.
 */
class BookShelfFragment :
    BaseMVPFragment<BookShelfFragment, BookShelfContract.Presenter<BookShelfFragment>, FragmentBookshelfBinding>(),
    BookShelfContract.View {
    lateinit var mRvContent: ScrollRefreshRecyclerView

    /** */
    private var mCollBookAdapter: CollBookAdapter? = null
    private var mFooterItem: FooterItemView? = null

    //是否是第一次进入
    private var isInit = true
    override fun bindPresenter(): BookShelfContract.Presenter<BookShelfFragment> {
        return BookShelfPresenter() as BookShelfContract.Presenter<BookShelfFragment>
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun initWidget(savedInstanceState: Bundle?) {
        super.initWidget(savedInstanceState)
        binding?.apply {
            mRvContent = this.bookShelfRvContent
            setUpAdapter()
        }

    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentBookshelfBinding {
        return FragmentBookshelfBinding.inflate(inflater, container, false)
    }

    private fun setUpAdapter() {
        //添加Footer
        mCollBookAdapter = CollBookAdapter()
        mRvContent!!.setLayoutManager(LinearLayoutManager(context))
        mRvContent!!.setAdapter(mCollBookAdapter)
        mRvContent!!.getReyclerView().clipToPadding = false
        mRvContent!!.setColorSchemeColors(resources.getColor(R.color.home_primary))

    }

    override fun initClick() {
        super.initClick()
        binding?.homeSearchEntry?.setOnClickListener { openSearch() }
        binding?.homeActionSearch?.setOnClickListener { openSearch() }
        binding?.homeActionImport?.setOnClickListener { openLocalImport() }
        binding?.homeActionSync?.setOnClickListener {
            RxBus.getInstance().post(BookSyncEvent())
        }
        val downloadDisp = RxBus.getInstance()
            .toObservable(DownloadMessage::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { event: DownloadMessage ->
                //使用Toast提示
                ToastUtils.show(event.message)
            }
        addDisposable(downloadDisp)
        val bookSyncDisp = RxBus.getInstance()
            .toObservable(BookSyncEvent::class.java)
            .subscribe(Consumer {
                val token = SharedPreUtils.getInstance().getString("token")
                if (TextUtils.isEmpty(token)) {
                    ToastUtils.show("请登录")
                    return@Consumer
                }
                if (("password" == SharedPreUtils.getInstance().getString("loginType"))) {
                    mPresenter!!.getBookShelf(token)
                } else {
                    val mobile = SharedPreUtils.getInstance().getString("userName")
                    mPresenter!!.getBookShelfByMobile(mobile, token)
                }
            })
        addDisposable(bookSyncDisp)


        //删除书籍 (写的丑了点)
        val deleteDisp = RxBus.getInstance()
            .toObservable(DeleteResponseEvent::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { event: DeleteResponseEvent ->
                if (event.isDelete) {
                    val progressDialog = ProgressDialog(context)
                    progressDialog.setMessage("正在删除中")
                    progressDialog.show()
                    BookRepository.getInstance().deleteCollBookInRx(event.collBook)
                        .compose({ upstream: Single<Void?>? -> RxUtils.toSimpleSingle(upstream) })
                        .subscribe(
                            { Void: Void? ->
                                mCollBookAdapter!!.removeItem(event.collBook)
                                progressDialog.dismiss()
                            }
                        )
                } else {
                    //弹出一个Dialog
                    val tipDialog = AlertDialog.Builder(
                        (context)!!
                    )
                        .setTitle("您的任务正在加载")
                        .setMessage("先请暂停任务再进行删除")
                        .setPositiveButton(
                            "确定",
                            { dialog: DialogInterface, which: Int -> dialog.dismiss() }).create()
                    tipDialog.show()
                }
            }
        addDisposable(deleteDisp)
        mRvContent!!.setOnRefreshListener { mPresenter!!.updateCollBooks(mCollBookAdapter!!.items) }
        mCollBookAdapter!!.setOnItemClickListener { view: View?, pos: Int ->
            //如果是本地文件，首先判断这个文件是否存在
            val collBook = mCollBookAdapter!!.getItem(pos)
            if (collBook.isLocal()) {
                //id表示本地文件的路径
                val path = collBook.cover
                val file = File(path)
                //判断这个本地文件是否存在
                if (file.exists() && file.length() != 0L) {
                    ReadActivity.startActivity(
                        requireContext(),
                        mCollBookAdapter!!.getItem(pos), true
                    )
                } else {
                    val tip = requireContext().getString(R.string.nb_bookshelf_book_not_exist)
                    //提示(从目录中移除这个文件)
                    AlertDialog.Builder((context)!!)
                        .setTitle(resources.getString(R.string.nb_common_tip))
                        .setMessage(tip)
                        .setPositiveButton(resources.getString(R.string.nb_common_sure),
                            DialogInterface.OnClickListener { dialog, which -> deleteBook(collBook) })
                        .setNegativeButton(resources.getString(R.string.nb_common_cancel), null)
                        .show()
                }
            } else {
                ReadActivity.startActivity(
                    requireContext(),
                    mCollBookAdapter!!.getItem(pos), true
                )
            }
        }
        mCollBookAdapter!!.setOnItemLongClickListener { v: View?, pos: Int ->
            //开启Dialog,最方便的Dialog,就是AlterDialog
            openItemDialog(mCollBookAdapter!!.getItem(pos))
            true
        }
    }

    private fun openSearch() {
        val ctx = context ?: return
        startActivity(Intent(ctx, SearchActivity::class.java))
    }

    private fun openLocalImport() {
        val ctx = context ?: return
        startActivity(Intent(ctx, FileSystemActivity::class.java))
    }

    override fun processLogic() {
        super.processLogic()
        //  mRvContent.startRefresh();
    }


    private fun openItemDialog(collBook: CollBookBean) {
        val menus: Array<String>
        menus = if (collBook.isLocal()) {
            resources.getStringArray(R.array.nb_menu_local_book)
        } else {
            resources.getStringArray(R.array.nb_menu_net_book)
        }
        val collBookDialog = AlertDialog.Builder(
            requireContext()
        )
            .setTitle(collBook.title)
            .setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1, menus
                )
            ) { dialog: DialogInterface?, which: Int -> onItemMenuClick(menus[which], collBook) }
            .setNegativeButton(null, null)
            .setPositiveButton(null, null)
            .create()
        collBookDialog.show()
    }

    private fun onItemMenuClick(which: String, collBook: CollBookBean) {
        when (which) {
            "置顶" -> {}
            "缓存" ->                 //2. 进行判断，如果CollBean中状态为未更新。那么就创建Task，加入到Service中去。
                //3. 如果状态为finish，并且isUpdate为true，那么就根据chapter创建状态
                //4. 如果状态为finish，并且isUpdate为false。
                downloadBook(collBook)

            "删除" -> deleteBook(collBook)
            "批量管理" -> {}
            else -> {}
        }
    }

    private fun downloadBook(collBook: CollBookBean) {
        //创建任务
        mPresenter!!.createDownloadTask(collBook)
    }

    /**
     * 默认删除本地文件
     *
     * @param collBook
     */
    private fun deleteBook(collBook: CollBookBean) {
        if (collBook.isLocal()) {
            val view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_delete, null)
            val cb = view.findViewById<View>(R.id.delete_cb_select) as CheckBox
            AlertDialog.Builder(requireContext())
                .setTitle("删除文件")
                .setView(view)
                .setPositiveButton(resources.getString(R.string.nb_common_sure)) { dialog, which ->
                    val isSelected = cb.isSelected
                    if (isSelected) {
                        val progressDialog = ProgressDialog(context)
                        progressDialog.setMessage("正在删除中")
                        progressDialog.show()
                        //删除
                        val file = File(collBook.cover)
                        if (file.exists()) file.delete()
                        BookRepository.getInstance().deleteCollBook(collBook)
                        BookRepository.getInstance().deleteBookRecord(collBook._id)

                        //从Adapter中删除
                        mCollBookAdapter!!.removeItem(collBook)
                        progressDialog.dismiss()
                    } else {
                        BookRepository.getInstance().deleteCollBook(collBook)
                        BookRepository.getInstance().deleteBookRecord(collBook._id)
                        //从Adapter中删除
                        mCollBookAdapter!!.removeItem(collBook)
                    }
                }
                .setNegativeButton(resources.getString(R.string.nb_common_cancel), null)
                .show()
        } else {
            BookRepository.getInstance().deleteCollBook(collBook)
            BookRepository.getInstance().deleteBookRecord(collBook._id)
            //从Adapter中删除
            mCollBookAdapter!!.removeItem(collBook)
            synBook()
            RxBus.getInstance().post(DeleteTaskEvent(collBook))
        }
    }

    private fun synBook() {
        val collBookBeans = BookRepository.getInstance().collBooks
        val bookIds: MutableList<String> = ArrayList()
        for (collBookBean in collBookBeans) {
            if (!collBookBean.isLocal()) {
                bookIds.add(collBookBean._id)
            }
        }
        if ("password" == SharedPreUtils.getInstance().getString("loginType")) {
            mPresenter!!.setBookShelf(bookIds)
        } else {
            Log.e(TAG, "onClick:  正在删除中 同步书架")
            val mobileToken = SharedPreUtils.getInstance().getString("token")
            val mobile = SharedPreUtils.getInstance().getString("userName")
            if (mobileToken.isEmpty() || mobile.isEmpty()) {
                return
            }
            mPresenter!!.setBookShelfByMobile(bookIds, mobile, mobileToken)
        }
    }

    /*******************************************************************8 */
    override fun showError() {}
    override fun complete() {
        if (mCollBookAdapter!!.itemCount > 0 && mFooterItem == null) {
            mFooterItem = FooterItemView()
            mCollBookAdapter!!.addFooterView(mFooterItem)
        }
        if (mRvContent!!.isRefreshing) {
            mRvContent!!.finishRefresh()
        }
    }

    override fun finishRefresh(collBookBeans: List<CollBookBean>) {
        mCollBookAdapter!!.refreshItems(collBookBeans)
        //如果是初次进入，则更新书籍信息
        if (isInit) {
            isInit = false
            mRvContent!!.post { mPresenter!!.updateCollBooks(mCollBookAdapter!!.items) }
        }
    }

    override fun finishUpdate() {
        //重新从数据库中获取数据
        requireActivity().runOnUiThread {
            mCollBookAdapter!!.refreshItems(
                BookRepository
                    .getInstance().collBooks
            )
        }
    }

    override fun finishSyncBook() {
        ToastUtils.show("书架同步成功")
    }

    override fun showErrorTip(error: String) {
//        mRvContent.setTip(error);
//        mRvContent.showTip();
        com.blankj.utilcode.util.ToastUtils.showLong(error)
    }

    /** */
    internal inner class FooterItemView : WholeAdapter.ItemView {
        override fun onCreateView(parent: ViewGroup): View {
            val view = LayoutInflater.from(context)
                .inflate(R.layout.footer_book_shelf, parent, false)
            view.setOnClickListener { v: View? ->
                val intent = Intent(context, SearchActivity::class.java)
                startActivity(intent)
            }
            return view
        }

        override fun onBindView(view: View) {}
    }

    override fun onResume() {
        super.onResume()
//        rxPermissions = RxPermissions(this)
//        permission
        mPresenter!!.refreshCollBooks()
        initMobPush()
    }

    var registrationId: String? = null
    private fun initMobPush() {
        Log.d(TAG, "preDirectLogin: registrationId")
        registrationId = SharedPreUtils.getInstance().getString("registrationId")
        Log.d(TAG, "onCallback: registrationId  $registrationId")
        MobPush.setShowBadge(true)
        if (TextUtils.isEmpty(registrationId)) {
            MobPush.getRegistrationId { s: String ->
                Log.d(TAG, "onCallback: registrationId  $s")
                registrationId = s
                SharedPreUtils.getInstance().putString("registrationId", registrationId)
            }
        }
    }

    companion object {
        private const val TAG = "BookShelfFragment"
    }
}
