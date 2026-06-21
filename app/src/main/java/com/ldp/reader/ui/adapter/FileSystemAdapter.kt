package com.ldp.reader.ui.adapter

import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.adapter.view.FileHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.utils.LocalBookImportFiles
import com.ldp.reader.utils.MD5Utils
import java.io.File
import java.util.HashMap

/**
 * Created by ldp on 17-5-27.
 */
class FileSystemAdapter : BaseListAdapter<File>() {
    private val mCheckMap = HashMap<File, Boolean>()
    private val mLoadedMap = HashMap<File, Boolean>()
    private var mCheckedCount = 0

    override fun createViewHolder(viewType: Int): IViewHolder<File> {
        return FileHolder(mCheckMap, mLoadedMap)
    }

    override fun refreshItems(list: List<File>) {
        mCheckMap.clear()
        mLoadedMap.clear()
        cacheLoadedState(list)
        for (file in list) {
            mCheckMap[file] = false
        }
        super.refreshItems(list)
    }

    override fun addItem(value: File) {
        mCheckMap[value] = false
        cacheLoadedState(listOf(value))
        super.addItem(value)
    }

    override fun addItem(index: Int, value: File) {
        mCheckMap[value] = false
        cacheLoadedState(listOf(value))
        super.addItem(index, value)
    }

    override fun addItems(values: List<File>) {
        cacheLoadedState(values)
        for (file in values) {
            mCheckMap[file] = false
        }
        super.addItems(values)
    }

    override fun removeItem(value: File) {
        if (mCheckMap.remove(value) == true) {
            --mCheckedCount
        }
        mLoadedMap.remove(value)
        super.removeItem(value)
    }

    override fun removeItems(value: List<File>) {
        for (file in value) {
            if (mCheckMap.remove(file) == true) {
                --mCheckedCount
            }
            mLoadedMap.remove(file)
        }
        super.removeItems(value)
    }

    fun setCheckedItem(pos: Int) {
        val file = getItem(pos)
        if (!LocalBookImportFiles.isTextFile(file)) return
        if (isFileLoaded(file)) return
        val isSelected = mCheckMap[file]!!
        if (isSelected) {
            mCheckMap[file] = false
            --mCheckedCount
        } else {
            mCheckMap[file] = true
            ++mCheckedCount
        }
        notifyDataSetChanged()
    }

    fun setCheckedAll(isChecked: Boolean) {
        val entrys = mCheckMap.entries
        mCheckedCount = 0
        for (entry in entrys) {
            if (entry.key.isFile &&
                LocalBookImportFiles.isTextFile(entry.key) &&
                !isFileLoaded(entry.key)
            ) {
                entry.setValue(isChecked)
                if (isChecked) {
                    ++mCheckedCount
                }
            }
        }
        notifyDataSetChanged()
    }

    fun isFileLoaded(file: File): Boolean {
        if (!file.isFile || !LocalBookImportFiles.isTextFile(file)) {
            return false
        }
        val cached = mLoadedMap[file]
        if (cached != null) {
            return cached
        }
        cacheLoadedState(listOf(file))
        return mLoadedMap[file] == true
    }

    fun markFilesLoaded(files: List<File>) {
        for (file in files) {
            if (!LocalBookImportFiles.isTextFile(file)) {
                continue
            }
            mLoadedMap[file] = true
            if (mCheckMap[file] == true) {
                mCheckMap[file] = false
                if (mCheckedCount > 0) {
                    --mCheckedCount
                }
            }
        }
        notifyDataSetChanged()
    }

    fun getCheckableCount(): Int {
        val files = items
        var count = 0
        for (file in files) {
            if (!isFileLoaded(file) &&
                file.isFile &&
                LocalBookImportFiles.isTextFile(file)
            ) {
                ++count
            }
        }
        return count
    }

    fun getItemIsChecked(pos: Int): Boolean {
        val file = getItem(pos)
        return mCheckMap[file]!!
    }

    fun getCheckedFiles(): List<File> {
        val files = ArrayList<File>()
        val entrys = mCheckMap.entries
        for (entry in entrys) {
            if (entry.value) {
                files.add(entry.key)
            }
        }
        return files
    }

    fun getCheckedCount(): Int {
        return mCheckedCount
    }

    fun getCheckMap(): HashMap<File, Boolean> {
        return mCheckMap
    }

    private fun cacheLoadedState(files: List<File>) {
        if (files.isEmpty()) {
            return
        }
        val idsByFile = LinkedHashMap<File, String>()
        for (file in files) {
            if (file.isFile && LocalBookImportFiles.isTextFile(file)) {
                val id = MD5Utils.strToMd5By16(file.absolutePath)
                if (id != null) {
                    idsByFile[file] = id
                } else {
                    mLoadedMap[file] = false
                }
            } else {
                mLoadedMap[file] = false
            }
        }
        val loadedIds = BookRepository.getInstance().getExistingCollBookIds(idsByFile.values)
        for ((file, id) in idsByFile) {
            mLoadedMap[file] = id in loadedIds
        }
    }
}
