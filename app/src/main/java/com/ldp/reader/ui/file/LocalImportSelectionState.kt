package com.ldp.reader.ui.file

import java.io.File

/**
 * Selection belongs to the source list, not to its current search/filter projection.
 * Keeping it here prevents a UI-only re-render from silently discarding user choices.
 */
internal class LocalImportSelectionState {
    private val selectedByPath = LinkedHashMap<String, File>()

    val count: Int
        get() = selectedByPath.size

    fun isSelected(file: File): Boolean = selectedByPath.containsKey(file.absolutePath)

    fun toggle(file: File): Boolean {
        val path = file.absolutePath
        if (selectedByPath.remove(path) != null) {
            return false
        }
        selectedByPath[path] = file
        return true
    }

    fun setSelected(files: Iterable<File>, selected: Boolean) {
        files.forEach { file ->
            if (selected) {
                selectedByPath[file.absolutePath] = file
            } else {
                selectedByPath.remove(file.absolutePath)
            }
        }
    }

    fun replaceSelection(files: Iterable<File>) {
        clear()
        setSelected(files, selected = true)
    }

    /**
     * An async refresh of the same source never treats a temporarily missing row as an explicit
     * user cancellation. A real directory transition passes [preserveSelection] as false so
     * choices cannot leak to another folder.
     */
    fun reconcileSource(files: List<File>, preserveSelection: Boolean) {
        if (!preserveSelection) {
            clear()
            return
        }
        val currentByPath = files.associateBy(File::getAbsolutePath)
        val refreshedSelections = selectedByPath.map { (path, oldFile) ->
            path to (currentByPath[path] ?: oldFile)
        }
        selectedByPath.clear()
        selectedByPath.putAll(refreshedSelections)
    }

    fun remove(files: Iterable<File>) {
        files.forEach { file -> selectedByPath.remove(file.absolutePath) }
    }

    fun clear() {
        selectedByPath.clear()
    }

    fun selectedFiles(): List<File> = selectedByPath.values.toList()
}
