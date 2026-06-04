package com.ldp.reader.ui.adapter.view

import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemFileBinding
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.base.adapter.ViewHolderImpl
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.FileUtils
import com.ldp.reader.utils.LocalBookImportFiles
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.utils.StringUtils
import java.io.File
import java.util.HashMap
import java.util.Locale

/**
 * Created by ldp on 17-5-27.
 */
class FileHolder(private val mSelectedMap: HashMap<File, Boolean>) : ViewHolderImpl<File>() {
    private lateinit var mIvIcon: ImageView
    private lateinit var mCbSelect: CheckBox
    private lateinit var mTvName: TextView
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
        val id = MD5Utils.strToMd5By16(file.absolutePath)
        if (LocalBookImportFiles.isOpenableDocument(file)) {
            mIvIcon.setImageResource(R.drawable.ic_file_row_open_32)
            mIvIcon.visibility = View.VISIBLE
            mCbSelect.visibility = View.GONE
        } else if (BookRepository.getInstance().getCollBook(id) != null) {
            mIvIcon.setImageResource(R.drawable.ic_file_row_loaded_32)
            mIvIcon.visibility = View.VISIBLE
            mCbSelect.visibility = View.GONE
        } else {
            val isSelected = mSelectedMap[file]!!
            mCbSelect.isChecked = isSelected
            mIvIcon.visibility = View.GONE
            mCbSelect.visibility = View.VISIBLE
        }

        mLlBrief.visibility = View.VISIBLE
        mTvSubCount.visibility = View.GONE
        mTvName.text = file.name
        mTvTag.text = fileType(file)
        mTvSize.text = FileUtils.getFileSize(file.length())
        mTvDate.text = StringUtils.dateConvert(file.lastModified(), Constant.FORMAT_FILE_DATE)
    }

    fun setFolder(folder: File) {
        mIvIcon.visibility = View.VISIBLE
        mCbSelect.visibility = View.GONE
        mIvIcon.setImageResource(R.drawable.ic_file_row_folder_32)
        mTvName.text = folder.name
        mLlBrief.visibility = View.GONE
        mTvSubCount.visibility = View.VISIBLE
        mTvSubCount.text = getContext().getString(R.string.nb_file_sub_count, LocalBookImportFiles.visibleChildCount(folder))
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
