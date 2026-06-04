package com.ldp.reader.utils

import java.io.File
import java.io.FileFilter

object LocalBookImportFiles {
    private val supportedExtensions = setOf("txt", "epub", "pdf", "cbz", "zip")

    val importFileFilter: FileFilter = FileFilter { file ->
        if (file.name.startsWith(".")) return@FileFilter false

        if (file.isDirectory) {
            val children = file.list() ?: return@FileFilter false
            return@FileFilter children.isNotEmpty()
        }

        file.length() > 0L && isSupportedFile(file)
    }

    fun isTextFile(file: File): Boolean = file.extension.equals("txt", ignoreCase = true)

    fun isOpenableDocument(file: File): Boolean = isSupportedFile(file) && !isTextFile(file)

    fun listVisibleChildren(directory: File): List<File> {
        return directory.listFiles(importFileFilter)
            ?.toMutableList()
            ?.apply { sortWith(fileComparator) }
            .orEmpty()
    }

    fun visibleChildCount(directory: File): Int {
        return directory.list()?.size ?: 0
    }

    private fun isSupportedFile(file: File): Boolean {
        return supportedExtensions.contains(file.extension.lowercase())
    }

    private val fileComparator = Comparator<File> { left, right ->
        when {
            left.isDirectory && right.isFile -> -1
            right.isDirectory && left.isFile -> 1
            else -> left.name.compareTo(right.name, ignoreCase = true)
        }
    }
}
