package com.ldp.reader.ui.adapter

import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.adapter.view.FileHolder
import com.ldp.reader.ui.base.adapter.BaseListAdapter
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.ui.file.LocalImportSelectionState
import com.ldp.reader.utils.LocalBookImportFiles
import com.ldp.reader.utils.MD5Utils
import java.io.File
import java.util.HashMap
import java.util.Locale

/**
 * Created by ldp on 17-5-27.
 */
class FileSystemAdapter : BaseListAdapter<File>() {
    private val mCheckMap = HashMap<File, Boolean>()
    private val mLoadedMap = HashMap<File, Boolean>()
    private val mDuplicateNameKeys = LinkedHashSet<String>()
    private val selectionState = LocalImportSelectionState()

    override fun createViewHolder(viewType: Int): IViewHolder<File> {
        return FileHolder(mCheckMap, mLoadedMap, mDuplicateNameKeys)
    }

    override fun refreshItems(list: List<File>) {
        reconcileSourceFiles(list, preserveSelection = true)
        cacheLoadedState(list)
        val importedPaths = list.asSequence()
            .filter { file -> mLoadedMap[file] == true }
            .map(File::getAbsolutePath)
            .toSet()
        refreshVisibleItems(list, importedPaths)
    }

    fun refreshVisibleItems(list: List<File>, importedPaths: Set<String>) {
        mCheckMap.clear()
        mLoadedMap.clear()
        mDuplicateNameKeys.clear()
        list.groupingBy { file -> file.name.lowercase(Locale.ROOT) }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .let(mDuplicateNameKeys::addAll)
        for (file in list) {
            mCheckMap[file] = selectionState.isSelected(file)
            mLoadedMap[file] = file.isFile &&
                LocalBookImportFiles.isTextFile(file) &&
                file.absolutePath in importedPaths
        }
        super.refreshItems(list)
    }

    override fun addItem(value: File) {
        mCheckMap[value] = selectionState.isSelected(value)
        cacheLoadedState(listOf(value))
        super.addItem(value)
    }

    override fun addItem(index: Int, value: File) {
        mCheckMap[value] = selectionState.isSelected(value)
        cacheLoadedState(listOf(value))
        super.addItem(index, value)
    }

    override fun addItems(values: List<File>) {
        cacheLoadedState(values)
        for (file in values) {
            mCheckMap[file] = selectionState.isSelected(file)
        }
        super.addItems(values)
    }

    override fun removeItem(value: File) {
        selectionState.remove(listOf(value))
        mCheckMap.remove(value)
        mLoadedMap.remove(value)
        super.removeItem(value)
    }

    override fun removeItems(value: List<File>) {
        selectionState.remove(value)
        for (file in value) {
            mCheckMap.remove(file)
            mLoadedMap.remove(file)
        }
        super.removeItems(value)
    }

    fun setCheckedItem(pos: Int) {
        val file = getItem(pos)
        if (!LocalBookImportFiles.isTextFile(file)) return
        if (isFileLoaded(file)) return
        mCheckMap[file] = selectionState.toggle(file)
        notifyDataSetChanged()
    }

    fun setCheckedAll(isChecked: Boolean) {
        val checkableVisibleFiles = items.filter { file ->
            file.isFile && LocalBookImportFiles.isTextFile(file) && !isFileLoaded(file)
        }
        selectionState.setSelected(checkableVisibleFiles, isChecked)
        for (file in checkableVisibleFiles) {
            mCheckMap[file] = isChecked
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectionState.clear()
        mCheckMap.keys.toList().forEach { file -> mCheckMap[file] = false }
        notifyDataSetChanged()
    }

    fun removeSelection(files: List<File>) {
        if (files.isEmpty()) return
        selectionState.remove(files)
        val removedPaths = files.mapTo(HashSet(), File::getAbsolutePath)
        mCheckMap.keys
            .filter { file -> file.absolutePath in removedPaths }
            .forEach { file -> mCheckMap[file] = false }
        notifyDataSetChanged()
    }

    fun restoreSelection(files: List<File>) {
        val restorable = files.filter { file ->
            file.isFile && LocalBookImportFiles.isTextFile(file) && !isFileLoaded(file)
        }
        selectionState.replaceSelection(restorable)
    }

    fun reconcileSourceFiles(files: List<File>, preserveSelection: Boolean) {
        selectionState.reconcileSource(files, preserveSelection)
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
        selectionState.remove(files)
        for (file in files) {
            if (!LocalBookImportFiles.isTextFile(file)) {
                continue
            }
            mLoadedMap[file] = true
            mCheckMap[file] = false
        }
        notifyDataSetChanged()
    }

    fun getImportedPaths(files: List<File>): Set<String> {
        cacheLoadedState(files)
        return files.asSequence()
            .filter { file -> mLoadedMap[file] == true }
            .map(File::getAbsolutePath)
            .toSet()
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
        return selectionState.selectedFiles()
    }

    fun getCheckedCount(): Int {
        return selectionState.count
    }

    fun areAllVisibleCheckableFilesSelected(): Boolean {
        val checkableVisibleFiles = items.filter { file ->
            file.isFile && LocalBookImportFiles.isTextFile(file) && !isFileLoaded(file)
        }
        return checkableVisibleFiles.isNotEmpty() &&
            checkableVisibleFiles.all(selectionState::isSelected)
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
