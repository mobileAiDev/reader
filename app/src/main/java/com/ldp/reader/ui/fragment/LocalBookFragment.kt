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
import com.ldp.reader.utils.media.MediaStoreHelper
import com.ldp.reader.widget.RefreshLayout
import com.ldp.reader.widget.itemdecoration.DividerItemDecoration
import java.io.File
import java.util.Locale

/**
 * Created by ldp on 17-5-27.
 * 本地书籍
 */
class LocalBookFragment : BaseFileFragment<FragmentLocalBookBinding>() {
    private lateinit var mRlRefresh: RefreshLayout
    private lateinit var mRvContent: RecyclerView

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
            //如果是已加载的文件，则点击事件无效。
            val file = mAdapter!!.getItem(pos)
            if (LocalBookImportFiles.isOpenableDocument(file)) {
                DocumentOpenRouterActivity.start(requireContext(), android.net.Uri.fromFile(file))
                return@setOnItemClickListener
            }
            if (mAdapter!!.isFileLoaded(file)) {
                return@setOnItemClickListener
            }

            //点击选中
            mAdapter!!.setCheckedItem(pos)

            //反馈
            mListener?.onItemCheckedChange(mAdapter!!.getItemIsChecked(pos))
        }
    }

    override fun processLogic() {
        super.processLogic()
        MediaStoreHelper.getAllBookFile(
            activity!!,
            object : MediaStoreHelper.MediaResultCallback {
                override fun onResultCallback(files: List<File>) {
                    if (files.isEmpty()) {
                        mRlRefresh.showEmpty()
                    } else {
                        val validFiles: MutableList<File> = ArrayList()
                        for (file in files) {
                            // Check if the file size is greater than 10KB
                            if (file.length() > 1024 * 10) {
                                // Check if the file name does not contain the word "log"
                                if (!file.name.lowercase(Locale.getDefault()).contains("log")) {
                                    validFiles.add(file)
                                }
                            }
                        }
                        mAdapter!!.refreshItems(validFiles)
                        mRlRefresh.showFinish()
                        //反馈
                        mListener?.onCategoryChanged()
                    }
                }
            }
        )
    }
}
