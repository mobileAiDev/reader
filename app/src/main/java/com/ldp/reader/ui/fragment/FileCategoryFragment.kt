package com.ldp.reader.ui.fragment

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.FragmentFileCategoryBinding
import com.ldp.reader.ui.activity.DocumentOpenRouterActivity
import com.ldp.reader.ui.adapter.FileSystemAdapter
import com.ldp.reader.utils.FileStack
import com.ldp.reader.utils.LocalBookImportFiles
import com.ldp.reader.widget.itemdecoration.DividerItemDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Created by ldp on 17-5-27.
 */
class FileCategoryFragment : BaseFileFragment<FragmentFileCategoryBinding>() {
    private lateinit var mTvPath: TextView
    private lateinit var mTvBackLast: TextView
    private lateinit var mRvContent: RecyclerView
    private lateinit var mFileStack: FileStack
    private var directoryLoadJob: Job? = null

    override fun initWidget(savedInstanceState: Bundle?) {
        super.initWidget(savedInstanceState)
        binding?.let {
            mTvPath = it.fileCategoryTvPath
            mTvBackLast = it.fileCategoryTvBackLast
            mRvContent = it.fileCategoryRvContent

            mFileStack = FileStack()
            setUpAdapter()
        }
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentFileCategoryBinding {
        return FragmentFileCategoryBinding.inflate(inflater, container, false)
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
            val file = mAdapter!!.getItem(pos)
            if (file.isDirectory) {
                //保存当前信息。
                val snapshot = FileStack.FileSnapshot()
                snapshot.filePath = mTvPath.text.toString()
                snapshot.files = ArrayList(mAdapter!!.items)
                snapshot.scrollOffset = mRvContent.computeVerticalScrollOffset()
                mFileStack.push(snapshot)
                //切换下一个文件
                toggleFileTree(file)
            } else {
                if (LocalBookImportFiles.isOpenableDocument(file)) {
                    DocumentOpenRouterActivity.start(requireContext(), android.net.Uri.fromFile(file))
                    return@setOnItemClickListener
                }
                //如果是已加载的文件，则点击事件无效。
                if (mAdapter!!.isFileLoaded(file)) {
                    return@setOnItemClickListener
                }
                //点击选中
                mAdapter!!.setCheckedItem(pos)
                //反馈
                mListener?.onItemCheckedChange(mAdapter!!.getItemIsChecked(pos))
            }
        }

        mTvBackLast.setOnClickListener {
            val snapshot = mFileStack.pop() ?: return@setOnClickListener
            directoryLoadJob?.cancel()
            val oldScrollOffset = mRvContent.computeHorizontalScrollOffset()
            mTvPath.text = snapshot.filePath
            mAdapter!!.refreshItems(snapshot.files!!)
            mRvContent.scrollBy(0, snapshot.scrollOffset - oldScrollOffset)
            //反馈
            mListener?.onCategoryChanged()
        }
    }

    override fun processLogic() {
        super.processLogic()
        val root = Environment.getExternalStorageDirectory()
        toggleFileTree(root)
    }

    private fun toggleFileTree(file: File) {
        //路径名
        mTvPath.text = getString(R.string.nb_file_path, file.path)
        directoryLoadJob?.cancel()
        mAdapter!!.refreshItems(emptyList())
        mListener?.onCategoryChanged()
        directoryLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val rootFiles = withContext(Dispatchers.IO) {
                LocalBookImportFiles.listVisibleChildren(file)
            }
            //加入
            mAdapter!!.refreshItems(rootFiles)
            //反馈
            mListener?.onCategoryChanged()
        }
    }

    override val fileCount: Int
        get() {
            var count = 0
            val entrys = mAdapter!!.getCheckMap().entries
            for (entry in entrys) {
                if (!entry.key.isDirectory) {
                    ++count
                }
            }
            return count
        }

    companion object {
        private const val TAG = "FileCategoryFragment"
    }
}
