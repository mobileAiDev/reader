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
    fun clearAppCache(context: Context?) {
        if (context == null) {
            return
        }
        deleteChildren(context.cacheDir)
        deleteChildren(context.externalCacheDir)
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

    private fun deleteChildren(directory: File?) {
        if (directory == null || !directory.exists() || !directory.isDirectory) {
            return
        }
        val children = directory.listFiles() ?: return
        for (child in children) {
            deleteRecursively(child)
        }
    }

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) {
            return
        }
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursively(child)
                }
            }
        }
        file.delete()
    }
}
