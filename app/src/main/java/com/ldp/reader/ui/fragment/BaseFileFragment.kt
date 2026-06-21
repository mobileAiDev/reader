package com.ldp.reader.ui.fragment

import androidx.viewbinding.ViewBinding
import com.ldp.reader.ui.adapter.FileSystemAdapter
import com.ldp.reader.ui.base.BaseFragment
import java.io.File

/**
 * Created by ldp on 17-7-10.
 * FileSystemActivity的基础Fragment类
 */
abstract class BaseFileFragment<VB : ViewBinding?> : BaseFragment<VB>() {
    protected var mAdapter: FileSystemAdapter? = null
    protected var mListener: OnFileCheckedListener? = null
    private var checkedAllState = false

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
        mAdapter?.markFilesLoaded(files)
    }

    //获取文件的总数
    open val fileCount: Int
        get() = if (mAdapter != null) mAdapter!!.itemCount else null!!

    //获取可点击的文件的数量
    val checkableCount: Int
        get() = if (mAdapter == null) 0 else mAdapter!!.getCheckableCount()

    /**
     * 删除选中的文件
     */
    fun deleteCheckedFiles() {
        //删除选中的文件
        val files = checkedFiles
        //删除显示的文件列表
        mAdapter!!.removeItems(files)
        //删除选中的文件
        for (file in files) {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    //设置文件点击监听事件
    fun setOnFileCheckedListener(listener: OnFileCheckedListener?) {
        mListener = listener
    }

    //文件点击监听
    interface OnFileCheckedListener {
        fun onItemCheckedChange(isChecked: Boolean)

        fun onCategoryChanged()
    }
}
