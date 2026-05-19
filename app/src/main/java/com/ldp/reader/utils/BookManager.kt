package com.ldp.reader.utils

import java.io.File
import java.lang.ref.WeakReference
import java.util.HashMap

/**
 * Created by ldp on 17-5-20.
 * 处理书籍的工具类，配合PageFactory使用
 * 已弃用，
 */
class BookManager {
    private var chapterName: String? = null
    private var bookId: String? = null
    private var chapterLen = 0L
    var position = 0L

    private val cacheMap: MutableMap<String?, Cache> = HashMap()

    fun openChapter(bookId: String?, chapterName: String?): Boolean {
        return openChapter(bookId, chapterName, 0)
    }

    fun openChapter(bookId: String?, chapterName: String?, position: Long): Boolean {
        val file = findBookFile(bookId, chapterName)
        if (!file.exists()) {
            return false
        }
        this.bookId = bookId
        this.chapterName = chapterName
        this.position = position
        createCache()
        return true
    }

    private fun createCache() {
        if (!cacheMap.containsKey(chapterName)) {
            val cache = Cache()
            val file = getBookFile(bookId, chapterName)
            val array = FileUtils.getFileContent(file).toCharArray()
            val charReference = WeakReference(array)
            cache.size = array.size.toLong()
            cache.data = charReference
            cacheMap[chapterName] = cache

            chapterLen = cache.size
        } else {
            chapterLen = cacheMap[chapterName]!!.size
        }
    }

    fun getPrevPara(): String? {
        if (position < 0) {
            return null
        }

        var end = position.toInt()
        var begin = end
        val array = getContent()

        while (begin >= 0) {
            val character = array[begin]
            if ((character.toString()) == "\n" && begin != end) {
                position = begin.toLong()
                begin++
                break
            }
            begin--
        }

        if (begin < 0) {
            begin = 0
            position = -1
        }
        val size = end + 1 - begin
        return String(array, begin, size)
    }

    fun getNextPara(): String? {
        if (position >= chapterLen) {
            return null
        }

        val begin = position.toInt()
        var end = begin
        val array = getContent()

        while (end < chapterLen) {
            val character = array[end]
            if ((character.toString()) == "\n" && begin != end) {
                ++end
                position = end.toLong()
                break
            }
            end++
        }
        val size = end - begin
        return String(array, begin, size)
    }

    fun getContent(): CharArray {
        if (cacheMap.size == 0) {
            return CharArray(1)
        }
        var block = cacheMap[chapterName]!!.data!!.get()
        if (block == null) {
            val file = getBookFile(bookId, chapterName)
            block = FileUtils.getFileContent(file).toCharArray()
            val cache = cacheMap[chapterName]!!
            cache.data = WeakReference(block)
        }
        return block
    }

    fun getChapterLen(): Long {
        return chapterLen
    }

    fun clear() {
        cacheMap.clear()
        position = 0
        chapterLen = 0
    }

    inner class Cache {
        var size = 0L
        var data: WeakReference<CharArray>? = null
    }

    companion object {
        private const val TAG = "BookManager"

        @Volatile
        private var sInstance: BookManager? = null

        @JvmStatic
        fun getInstance(): BookManager {
            if (sInstance == null) {
                synchronized(BookManager::class.java) {
                    if (sInstance == null) {
                        sInstance = BookManager()
                    }
                }
            }
            return sInstance!!
        }

        @JvmStatic
        fun getBookFile(folderName: String?, fileName: String?): File {
            return FileUtils.getFile(bookFilePath(folderName, fileName))
        }

        @JvmStatic
        fun findBookFile(folderName: String?, fileName: String?): File {
            return File(bookFilePath(folderName, fileName))
        }

        @JvmStatic
        fun bookFilePath(folderName: String?, fileName: String?): String {
            return cacheFolderPath(folderName) +
                File.separator + BookCacheKey.fileSegment(fileName) + FileUtils.SUFFIX_NB
        }

        @JvmStatic
        fun cacheFolderPath(folderName: String?): String {
            return Constant.BOOK_CACHE_PATH + BookCacheKey.folderSegment(folderName)
        }

        @JvmStatic
        fun getBookSize(folderName: String?): Long {
            return FileUtils.getDirSize(
                FileUtils.getFolder(cacheFolderPath(folderName))
            )
        }

        @JvmStatic
        fun isChapterCached(folderName: String?, fileName: String?): Boolean {
            return findBookFile(folderName, fileName).exists()
        }
    }
}
