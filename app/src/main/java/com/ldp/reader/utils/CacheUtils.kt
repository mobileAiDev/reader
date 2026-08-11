package com.ldp.reader.utils

import android.content.Context
import java.io.File

object CacheUtils {
    @JvmStatic
    fun getAppCacheSize(context: Context?): Long {
        if (context == null) {
            return 0L
        }
        var size = directorySize(context.cacheDir)
        size += directorySize(context.externalCacheDir)
        return size
    }

    @JvmStatic
    fun getAppCacheSizeLabel(context: Context?): String {
        return FileUtils.getFileSize(getAppCacheSize(context))
    }

    @JvmStatic
    fun clearAppCache(context: Context?): Boolean {
        if (context == null) {
            return false
        }
        val internalCleared = deleteChildren(context.cacheDir)
        val externalCleared = deleteChildren(context.externalCacheDir)
        return internalCleared && externalCleared
    }

    private fun directorySize(file: File?): Long {
        if (file == null || !file.exists()) {
            return 0L
        }
        if (file.isFile) {
            return file.length()
        }
        val children = file.listFiles() ?: return 0L
        var size = 0L
        for (child in children) {
            size += directorySize(child)
        }
        return size
    }

    private fun deleteChildren(directory: File?): Boolean {
        if (directory == null || !directory.exists() || !directory.isDirectory) {
            return true
        }
        val children = directory.listFiles() ?: return false
        var cleared = true
        for (child in children) {
            cleared = deleteRecursively(child) && cleared
        }
        return cleared
    }

    private fun deleteRecursively(file: File?): Boolean {
        if (file == null || !file.exists()) {
            return true
        }
        var cleared = true
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children == null) {
                cleared = false
            } else {
                for (child in children) {
                    cleared = deleteRecursively(child) && cleared
                }
            }
        }
        return file.delete() && cleared
    }
}
