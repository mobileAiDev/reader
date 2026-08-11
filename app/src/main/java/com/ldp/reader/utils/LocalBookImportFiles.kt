package com.ldp.reader.utils

import java.io.File
import java.io.FileFilter
import java.util.Locale

object LocalBookImportFiles {
    private val supportedExtensions = setOf("txt", "epub", "pdf", "cbz", "zip")

    val importFileFilter: FileFilter = FileFilter { file ->
        if (file.name.startsWith(".")) return@FileFilter false

        if (file.isDirectory) {
            return@FileFilter file.listFiles(relevantChildFilter)?.isNotEmpty() == true
        }

        isImportableFile(file)
    }

    fun isTextFile(file: File): Boolean = file.extension.equals("txt", ignoreCase = true)

    fun isOpenableDocument(file: File): Boolean = isSupportedFile(file) && !isTextFile(file)

    fun isImportableFile(file: File): Boolean {
        return file.isFile && file.length() > 0L && isSupportedFile(file)
    }

    /**
     * “最近文件/智能导入”是一个降噪入口，不等同于完整文件浏览器。
     * 小文本和文件名中带 log 的文本通常来自应用日志；用户仍可在“手机目录”
     * 或系统“选择文件”中主动找到并打开这些文件。
     */
    fun isSmartImportCandidate(file: File): Boolean {
        return isImportableFile(file) &&
            file.length() > SMART_IMPORT_MIN_BYTES &&
            !file.name.lowercase(Locale.ROOT).contains("log")
    }

    fun listVisibleChildren(directory: File): List<File> {
        return directory.listFiles(importFileFilter)
            ?.toMutableList()
            ?.apply { sortWith(fileComparator) }
            .orEmpty()
    }

    fun visibleChildCount(directory: File): Int {
        return directory.listFiles(importFileFilter)?.size ?: 0
    }

    private fun isSupportedFile(file: File): Boolean {
        return supportedExtensions.contains(file.extension.lowercase())
    }

    private val relevantChildFilter = FileFilter { child ->
        !child.name.startsWith(".") && (child.isDirectory || isImportableFile(child))
    }

    private const val SMART_IMPORT_MIN_BYTES = 10L * 1024L

    private val fileComparator = Comparator<File> { left, right ->
        when {
            left.isDirectory && right.isFile -> -1
            right.isDirectory && left.isFile -> 1
            else -> left.name.compareTo(right.name, ignoreCase = true)
        }
    }
}
