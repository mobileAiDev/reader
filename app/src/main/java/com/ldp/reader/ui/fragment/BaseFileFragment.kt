package com.ldp.reader.ui.fragment

import androidx.viewbinding.ViewBinding
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.activity.ReadActivity
import com.ldp.reader.ui.adapter.FileSystemAdapter
import com.ldp.reader.ui.base.BaseFragment
import com.ldp.reader.ui.file.LocalImportFilter
import com.ldp.reader.ui.file.LocalImportListPolicy
import com.ldp.reader.utils.MD5Utils
import java.io.File

/**
 * Created by ldp on 17-7-10.
 * FileSystemActivity的基础Fragment类
 */
abstract class BaseFileFragment<VB : ViewBinding?> : BaseFragment<VB>() {
    protected var mAdapter: FileSystemAdapter? = null
    protected var mListener: OnFileCheckedListener? = null
    private var checkedAllState = false
    private var sourceFiles: List<File> = emptyList()
    private var importedPaths: Set<String> = emptySet()
    private var searchQuery: String = ""
    private var selectedFilter: LocalImportFilter = LocalImportFilter.ALL
    private var scanAccessEnabled = false

    //设置当前列表为全选
    var isCheckedAll: Boolean
        get() = checkedAllState
        set(checkedAll) {
            if (mAdapter == null) return

            checkedAllState = checkedAll
            mAdapter!!.setCheckedAll(checkedAll)
        }

    fun setChecked(checked: Boolean) {
        checkedAllState = checked
    }

    //获取被选中的数量
    val checkedCount: Int
        get() = if (mAdapter == null) 0 else mAdapter!!.getCheckedCount()

    //获取被选中的文件列表
    val checkedFiles: List<File>
        get() = if (mAdapter != null) mAdapter!!.getCheckedFiles() else null!!

    fun markFilesLoaded(files: List<File>) {
        importedPaths = importedPaths + files.map(File::getAbsolutePath)
        // Only clear the entries that were actually saved. Re-rendering the whole list here
        // would erase selections the user made while the repository write was in flight.
        mAdapter?.markFilesLoaded(files)
        checkedAllState = mAdapter?.areAllVisibleCheckableFilesSelected() == true
    }

    fun removeSelection(files: List<File>) {
        if (files.isEmpty()) return
        mAdapter?.removeSelection(files)
        checkedAllState = mAdapter?.areAllVisibleCheckableFilesSelected() == true
    }

    fun applyDisplayOptions(query: String, filter: LocalImportFilter) {
        searchQuery = query
        selectedFilter = filter
        renderFiles()
    }

    protected fun replaceSourceFiles(
        files: List<File>,
        preserveSelection: Boolean = true
    ) {
        sourceFiles = files.toList()
        mAdapter?.reconcileSourceFiles(sourceFiles, preserveSelection)
        importedPaths = mAdapter?.getImportedPaths(sourceFiles).orEmpty()
        renderFiles()
    }

    protected fun sourceFilesSnapshot(): List<File> = sourceFiles.toList()

    protected fun restoreSelectedPaths(paths: Set<String>) {
        if (paths.isEmpty()) return
        val files = sourceFiles.filter { file -> file.absolutePath in paths }
        mAdapter?.restoreSelection(files)
        renderFiles()
    }

    fun setScanAccessEnabled(enabled: Boolean, clearData: Boolean = false) {
        val changed = scanAccessEnabled != enabled
        scanAccessEnabled = enabled
        if (changed || clearData) {
            onScanAccessChanged(enabled, clearData)
        }
    }

    protected fun isScanAccessEnabled(): Boolean = scanAccessEnabled

    protected open fun onScanAccessChanged(enabled: Boolean, clearData: Boolean) = Unit

    protected open fun onDisplayedFilesChanged(visibleFiles: List<File>) = Unit

    protected fun openImportedTextFile(file: File): Boolean {
        val bookId = MD5Utils.strToMd5By16(file.absolutePath)
        val book = BookRepository.getInstance().getCollBook(bookId) ?: return false
        ReadActivity.startActivity(requireContext(), book, true)
        return true
    }

    private fun renderFiles() {
        val adapter = mAdapter ?: return
        val visibleFiles = LocalImportListPolicy.visibleFiles(
            files = sourceFiles,
            query = searchQuery,
            filter = selectedFilter,
            importedPaths = importedPaths
        )
        adapter.refreshVisibleItems(visibleFiles, importedPaths)
        checkedAllState = adapter.areAllVisibleCheckableFilesSelected()
        onDisplayedFilesChanged(visibleFiles)
        mListener?.onCategoryChanged(this)
    }

    //获取文件的总数
    open val fileCount: Int
        get() = if (mAdapter != null) mAdapter!!.itemCount else null!!

    //获取可点击的文件的数量
    val checkableCount: Int
        get() = if (mAdapter == null) 0 else mAdapter!!.getCheckableCount()

    val areAllVisibleCheckableFilesSelected: Boolean
        get() = mAdapter?.areAllVisibleCheckableFilesSelected() == true

    fun clearSelection() {
        checkedAllState = false
        mAdapter?.clearSelection()
        mListener?.onCategoryChanged(this)
    }

    //设置文件点击监听事件
    fun setOnFileCheckedListener(listener: OnFileCheckedListener?) {
        mListener = listener
    }

    //文件点击监听
    interface OnFileCheckedListener {
        fun onItemCheckedChange(source: BaseFileFragment<*>, isChecked: Boolean)

        fun onCategoryChanged(source: BaseFileFragment<*>)
    }
}
