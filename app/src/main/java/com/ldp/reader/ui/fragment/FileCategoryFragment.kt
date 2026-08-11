package com.ldp.reader.ui.fragment

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.FragmentFileCategoryBinding
import com.ldp.reader.ui.activity.DocumentOpenRouterActivity
import com.ldp.reader.ui.adapter.FileSystemAdapter
import com.ldp.reader.ui.file.LocalDirectoryNavigationState
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
    private lateinit var directoryNavigation: LocalDirectoryNavigationState
    private var directoryLoadJob: Job? = null
    private var directoryLoaded = false
    private var pendingSelectedPaths: Set<String> = emptySet()
    private var pendingScrollOffset: Int = 0

    override fun initWidget(savedInstanceState: Bundle?) {
        super.initWidget(savedInstanceState)
        binding?.let {
            mTvPath = it.fileCategoryTvPath
            mTvBackLast = it.fileCategoryTvBackLast
            mRvContent = it.fileCategoryRvContent

            setUpAdapter()
            val rootPath = Environment.getExternalStorageDirectory().absolutePath
            directoryNavigation = LocalDirectoryNavigationState.restore(
                rootPath = rootPath,
                currentPath = savedInstanceState?.getString(STATE_CURRENT_DIRECTORY),
                historyPaths = savedInstanceState?.getStringArrayList(STATE_DIRECTORY_HISTORY),
                historyScrollOffsets = savedInstanceState?.getIntArray(STATE_DIRECTORY_HISTORY_SCROLL)
            )
            pendingSelectedPaths = savedInstanceState
                ?.getStringArrayList(STATE_SELECTED_PATHS)
                .orEmpty()
                .toSet()
            pendingScrollOffset = savedInstanceState?.getInt(STATE_CURRENT_SCROLL, 0) ?: 0
            renderNavigationAffordance()
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
                if (
                    directoryNavigation.enter(
                        path = file.absolutePath,
                        currentScrollOffset = mRvContent.computeVerticalScrollOffset()
                    )
                ) {
                    toggleFileTree(file)
                }
            } else {
                if (LocalBookImportFiles.isOpenableDocument(file)) {
                    DocumentOpenRouterActivity.start(requireContext(), android.net.Uri.fromFile(file))
                    return@setOnItemClickListener
                }
                //已加入书架的文本直接续读，避免勾选状态成为无响应入口。
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

        mTvBackLast.setOnClickListener { navigateBack() }
    }

    override fun processLogic() {
        super.processLogic()
        loadCurrentDirectoryIfAllowed()
    }

    override fun onScanAccessChanged(enabled: Boolean, clearData: Boolean) {
        if (enabled) {
            loadCurrentDirectoryIfAllowed()
            return
        }
        directoryLoadJob?.cancel()
        directoryLoadJob = null
        if (clearData) {
            directoryLoaded = false
            val rootPath = Environment.getExternalStorageDirectory().absolutePath
            directoryNavigation = LocalDirectoryNavigationState.create(rootPath)
            pendingSelectedPaths = emptySet()
            pendingScrollOffset = 0
            if (binding != null) {
                mTvPath.text = getString(R.string.nb_file_path, rootPath)
                replaceSourceFiles(emptyList(), preserveSelection = false)
                renderNavigationAffordance()
            }
        }
    }

    private fun toggleFileTree(file: File) {
        if (!isScanAccessEnabled()) return
        directoryLoaded = false
        //路径名
        mTvPath.text = getString(R.string.nb_file_path, file.path)
        directoryLoadJob?.cancel()
        pendingSelectedPaths = emptySet()
        pendingScrollOffset = 0
        replaceSourceFiles(emptyList(), preserveSelection = false)
        renderNavigationAffordance()
        loadDirectory(file, preserveSelection = false)
    }

    private fun loadCurrentDirectoryIfAllowed() {
        if (!isScanAccessEnabled() || binding == null || directoryLoaded || directoryLoadJob != null) {
            return
        }
        val currentDirectory = File(directoryNavigation.currentPath)
        mTvPath.text = getString(R.string.nb_file_path, currentDirectory.path)
        renderNavigationAffordance()
        loadDirectory(currentDirectory, preserveSelection = true)
    }

    private fun loadDirectory(file: File, preserveSelection: Boolean) {
        val job = viewLifecycleOwner.lifecycleScope.launch {
            val rootFiles = withContext(Dispatchers.IO) {
                LocalBookImportFiles.listVisibleChildren(file)
            }
            if (
                !isScanAccessEnabled() ||
                directoryNavigation.currentPath != file.absolutePath
            ) {
                return@launch
            }
            directoryLoaded = true
            replaceSourceFiles(rootFiles, preserveSelection = preserveSelection)
            if (pendingSelectedPaths.isNotEmpty()) {
                restoreSelectedPaths(pendingSelectedPaths)
                pendingSelectedPaths = emptySet()
            }
            val restoredScrollOffset = pendingScrollOffset
            pendingScrollOffset = 0
            if (restoredScrollOffset > 0) {
                mRvContent.post { mRvContent.scrollBy(0, restoredScrollOffset) }
            }
            renderNavigationAffordance()
        }
        directoryLoadJob = job
        job.invokeOnCompletion {
            if (directoryLoadJob === job) {
                directoryLoadJob = null
            }
        }
    }

    fun navigateBack(): Boolean {
        if (!::directoryNavigation.isInitialized || !isScanAccessEnabled()) return false
        val destination = directoryNavigation.navigateBack() ?: return false
        directoryLoadJob?.cancel()
        directoryLoaded = false
        pendingSelectedPaths = emptySet()
        pendingScrollOffset = destination.scrollOffset
        val directory = File(destination.path)
        mTvPath.text = getString(R.string.nb_file_path, destination.path)
        replaceSourceFiles(emptyList(), preserveSelection = false)
        renderNavigationAffordance()
        loadDirectory(directory, preserveSelection = false)
        return true
    }

    override fun onDisplayedFilesChanged(visibleFiles: List<File>) {
        binding?.fileCategoryEmpty?.visibility =
            if (directoryLoaded && visibleFiles.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::directoryNavigation.isInitialized) {
            val history = directoryNavigation.historySnapshot()
            outState.putString(STATE_CURRENT_DIRECTORY, directoryNavigation.currentPath)
            outState.putStringArrayList(
                STATE_DIRECTORY_HISTORY,
                ArrayList(history.map { entry -> entry.path })
            )
            outState.putIntArray(
                STATE_DIRECTORY_HISTORY_SCROLL,
                history.map { entry -> entry.scrollOffset }.toIntArray()
            )
            outState.putStringArrayList(
                STATE_SELECTED_PATHS,
                ArrayList(checkedFiles.map(File::getAbsolutePath))
            )
            outState.putInt(
                STATE_CURRENT_SCROLL,
                if (::mRvContent.isInitialized) mRvContent.computeVerticalScrollOffset() else 0
            )
        }
        super.onSaveInstanceState(outState)
    }

    private fun renderNavigationAffordance() {
        if (!::mTvBackLast.isInitialized || !::directoryNavigation.isInitialized) return
        val enabled = directoryNavigation.canNavigateBack
        mTvBackLast.isEnabled = enabled
        mTvBackLast.alpha = if (enabled) 1f else 0.38f
        mTvBackLast.contentDescription = if (enabled) "返回上一级目录" else "已在存储根目录"
    }

    override val fileCount: Int
        get() = sourceFilesSnapshot().count(File::isFile)

    companion object {
        private const val TAG = "FileCategoryFragment"
        private const val STATE_CURRENT_DIRECTORY = "phoneDirectory.current"
        private const val STATE_DIRECTORY_HISTORY = "phoneDirectory.history"
        private const val STATE_DIRECTORY_HISTORY_SCROLL = "phoneDirectory.historyScroll"
        private const val STATE_SELECTED_PATHS = "phoneDirectory.selectedPaths"
        private const val STATE_CURRENT_SCROLL = "phoneDirectory.currentScroll"
    }
}
