package com.ldp.reader.ui.adapter.view

import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemFileBinding
import com.ldp.reader.ui.base.adapter.ViewHolderImpl
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.FileUtils
import com.ldp.reader.utils.LocalBookImportFiles
import com.ldp.reader.utils.StringUtils
import java.io.File
import java.util.HashMap
import java.util.Locale

/**
 * Created by ldp on 17-5-27.
 */
class FileHolder(
    private val mSelectedMap: HashMap<File, Boolean>,
    private val mLoadedMap: HashMap<File, Boolean>,
    private val duplicateNameKeys: Set<String>
) : ViewHolderImpl<File>() {
    private lateinit var mIvIcon: ImageView
    private lateinit var mCbSelect: CheckBox
    private lateinit var mTvName: TextView
    private lateinit var mTvLocation: TextView
    private lateinit var mLlBrief: LinearLayout
    private lateinit var mTvTag: TextView
    private lateinit var mTvSize: TextView
    private lateinit var mTvDate: TextView
    private lateinit var mTvSubCount: TextView

    override fun initView() {
        val binding = ItemFileBinding.bind(getItemView())
        mIvIcon = binding.fileIvIcon
        mCbSelect = binding.fileCbSelect
        mTvName = binding.fileTvName
        mTvLocation = binding.fileTvLocation
        mLlBrief = binding.fileLlBrief
        mTvTag = binding.fileTvTag
        mTvSize = binding.fileTvSize
        mTvDate = binding.fileTvDate
        mTvSubCount = binding.fileTvSubCount
    }

    override fun onBind(data: File, pos: Int) {
        if (data.isDirectory) {
            setFolder(data)
        } else {
            setFile(data)
        }
    }

    private fun setFile(file: File) {
        val isLoaded = mLoadedMap[file] == true
        if (LocalBookImportFiles.isOpenableDocument(file)) {
            mIvIcon.setImageResource(R.drawable.ic_file_row_open_32)
            mIvIcon.contentDescription = "打开${fileType(file)}文档"
            mIvIcon.visibility = View.VISIBLE
            mCbSelect.visibility = View.GONE
        } else if (isLoaded) {
            mIvIcon.setImageResource(R.drawable.ic_file_row_loaded_32)
            mIvIcon.contentDescription = "已加入书架"
            mIvIcon.visibility = View.VISIBLE
            mCbSelect.visibility = View.GONE
        } else {
            val isSelected = mSelectedMap[file]!!
            mCbSelect.isChecked = isSelected
            mCbSelect.contentDescription = if (isSelected) "取消选择" else "选择文件"
            mIvIcon.visibility = View.GONE
            mCbSelect.visibility = View.VISIBLE
        }

        mLlBrief.visibility = View.VISIBLE
        mTvSubCount.visibility = View.GONE
        mTvName.text = file.name
        val duplicateParent = duplicateParentLabel(file)
        mTvName.maxLines = if (duplicateParent == null) 2 else 1
        mTvLocation.text = duplicateParent.orEmpty()
        mTvLocation.visibility = if (duplicateParent == null) View.GONE else View.VISIBLE
        mTvTag.text = if (isLoaded) "已导入" else fileType(file)
        mTvSize.text = FileUtils.getFileSize(file.length())
        mTvDate.text = StringUtils.dateConvert(file.lastModified(), Constant.FORMAT_FILE_DATE)
    }

    fun setFolder(folder: File) {
        mIvIcon.visibility = View.VISIBLE
        mCbSelect.visibility = View.GONE
        mIvIcon.setImageResource(R.drawable.ic_file_row_folder_32)
        mIvIcon.contentDescription = "打开文件夹"
        mTvName.text = folder.name
        mTvName.maxLines = 2
        mTvLocation.visibility = View.GONE
        mLlBrief.visibility = View.GONE
        mTvSubCount.visibility = View.VISIBLE
        mTvSubCount.text = "文件夹"
    }

    private fun duplicateParentLabel(file: File): String? {
        val nameKey = file.name.lowercase(Locale.ROOT)
        if (nameKey !in duplicateNameKeys) return null
        return file.parentFile?.absolutePath ?: "位置未知"
    }

    private fun fileType(file: File): String {
        val name = file.name
        val index = name.lastIndexOf('.')
        if (index >= 0 && index + 1 < name.length) {
            return name.substring(index + 1).uppercase(Locale.CHINA)
        }
        return "TXT"
    }

    override fun getItemLayoutId(): Int {
        return R.layout.item_file
    }
}
