package com.ldp.reader.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.databinding.FragmentLocalBookBinding
import com.ldp.reader.ui.activity.DocumentOpenRouterActivity
import com.ldp.reader.ui.adapter.FileSystemAdapter
import com.ldp.reader.utils.LocalBookImportFiles
import com.ldp.reader.utils.media.LoaderCreator
import com.ldp.reader.utils.media.MediaStoreHelper
import com.ldp.reader.widget.RefreshLayout
import com.ldp.reader.widget.itemdecoration.DividerItemDecoration
import java.io.File

/**
 * Created by ldp on 17-5-27.
 * 本地书籍
 */
class LocalBookFragment : BaseFileFragment<FragmentLocalBookBinding>() {
    private lateinit var mRlRefresh: RefreshLayout
    private lateinit var mRvContent: RecyclerView
    private var scanStarted = false

    override fun initWidget(savedInstanceState: Bundle?) {
        super.initWidget(savedInstanceState)
        binding?.let {
            mRlRefresh = it.refreshLayout
            mRvContent = it.localBookRvContent
        }

        setUpAdapter()
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLocalBookBinding {
        return FragmentLocalBookBinding.inflate(inflater, container, false)
    }

    private fun setUpAdapter() {
        mAdapter = FileSystemAdapter()
        mRvContent.layoutManager = LinearLayoutManager(context)
        mRvContent.addItemDecoration(DividerItemDecoration(context))
        mRvContent.adapter = mAdapter
    }

    override fun initClick() {
        super.initClick()
        mAdapter!!.setOnItemClickListener { _, pos ->
            //已加入书架的文本直接续读，避免勾选状态成为无响应入口。
            val file = mAdapter!!.getItem(pos)
            if (LocalBookImportFiles.isOpenableDocument(file)) {
                DocumentOpenRouterActivity.start(requireContext(), android.net.Uri.fromFile(file))
                return@setOnItemClickListener
            }
            if (mAdapter!!.isFileLoaded(file)) {
                openImportedTextFile(file)
                return@setOnItemClickListener
            }

            //点击选中
            mAdapter!!.setCheckedItem(pos)

            //反馈
            mListener?.onItemCheckedChange(this, mAdapter!!.getItemIsChecked(pos))
        }
    }

    override fun processLogic() {
        super.processLogic()
        startScanIfAllowed()
    }

    override fun onScanAccessChanged(enabled: Boolean, clearData: Boolean) {
        if (enabled) {
            startScanIfAllowed()
            return
        }
        if (scanStarted && isAdded) {
            requireActivity().supportLoaderManager.destroyLoader(LoaderCreator.ALL_BOOK_FILE)
        }
        scanStarted = false
        if (clearData && binding != null) {
            replaceSourceFiles(emptyList(), preserveSelection = false)
        }
    }

    private fun startScanIfAllowed() {
        if (!isScanAccessEnabled() || !isAdded || binding == null || scanStarted) return
        scanStarted = true
        MediaStoreHelper.getAllBookFile(
            requireActivity(),
            object : MediaStoreHelper.MediaResultCallback {
                override fun onResultCallback(files: List<File>) {
                    if (!isScanAccessEnabled() || binding == null) return
                    replaceSourceFiles(
                        files.filter { file -> LocalBookImportFiles.isSmartImportCandidate(file) }
                    )
                }
            }
        )
    }

    override fun onDisplayedFilesChanged(visibleFiles: List<File>) {
        if (visibleFiles.isEmpty()) {
            mRlRefresh.showEmpty()
        } else {
            mRlRefresh.showFinish()
        }
    }
}
