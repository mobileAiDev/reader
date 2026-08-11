package com.ldp.reader.utils

import android.os.Environment
import com.ldp.reader.App
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.text.DecimalFormat
import java.util.ArrayList
import kotlin.math.log10
import kotlin.math.pow

/**
 * Created by ldp on 17-5-11.
 */
object FileUtils {
    const val SUFFIX_CHAPTER_CACHE = ".txt"
    const val SUFFIX_TXT = ".txt"
    const val SUFFIX_EPUB = ".epub"
    const val SUFFIX_PDF = ".pdf"

    @JvmStatic
    fun getFolder(filePath: String?): File {
        val file = File(filePath!!)
        if (!file.exists()) {
            file.mkdirs()
        }
        return file
    }

    @JvmStatic
    @Synchronized
    fun getFile(filePath: String?): File {
        val file = File(filePath!!)
        try {
            if (!file.exists()) {
                getFolder(file.parent)
                file.createNewFile()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return file
    }

    @JvmStatic
    fun getCachePath(): String {
        return if (isSdCardExist()) {
            App.getContext()
                .externalCacheDir!!
                .absolutePath
        } else {
            App.getContext()
                .cacheDir
                .absolutePath
        }
    }

    @JvmStatic
    fun getDirSize(file: File): Long {
        return if (file.exists()) {
            if (file.isDirectory) {
                val children = file.listFiles()
                var size = 0L
                for (f in children!!) {
                    size += getDirSize(f)
                }
                size
            } else {
                file.length()
            }
        } else {
            0
        }
    }

    @JvmStatic
    fun getFileSize(size: Long): String {
        if (size <= 0) return "0"
        val units = arrayOf("b", "kb", "M", "G", "T")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.##")
            .format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }

    @JvmStatic
    fun getFileContent(file: File): String {
        var reader: Reader? = null
        var str: String?
        val sb = StringBuilder()
        try {
            reader = FileReader(file)
            val br = BufferedReader(reader)
            while (br.readLine().also { str = it } != null) {
                if (str != "") {
                    sb.append("    ").append(str).append("\n")
                }
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            IOUtils.close(reader)
        }
        return sb.toString()
    }

    @JvmStatic
    fun isSdCardExist(): Boolean {
        return Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()
    }

    @JvmStatic
    @Synchronized
    fun deleteFile(filePath: String?) {
        val file = File(filePath!!)
        if (!file.exists()) return

        if (file.isDirectory) {
            val files = file.listFiles()
            for (subFile in files!!) {
                val path = subFile.path
                deleteFile(path)
            }
        }
        file.delete()
    }

    @JvmStatic
    fun getTxtFiles(filePath: String?, layer: Int): List<File> {
        val txtFiles: MutableList<File> = ArrayList()
        val file = File(filePath!!)

        if (layer == 3) {
            return txtFiles
        }

        val dirs = file.listFiles { pathname ->
            if (pathname.isDirectory && !pathname.name.startsWith(".")) {
                true
            } else if (pathname.name.endsWith(".txt")) {
                txtFiles.add(pathname)
                false
            } else {
                false
            }
        }
        for (dir in dirs!!) {
            txtFiles.addAll(getTxtFiles(dir.path, layer + 1))
        }
        return txtFiles
    }

    @JvmStatic
    fun getCharset(fileName: String): Charset {
        val file = File(fileName)
        if (!file.isFile || file.length() == 0L) return Charset.GBK

        val prefix = ByteArray(3)
        val prefixSize = try {
            FileInputStream(file).use { input -> input.read(prefix) }
        } catch (_: IOException) {
            return Charset.GBK
        }

        if (prefixSize >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte()) {
            return Charset.UTF16LE
        }
        if (prefixSize >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte()) {
            return Charset.UTF16BE
        }
        if (
            prefixSize >= 3 &&
            prefix[0] == 0xef.toByte() &&
            prefix[1] == 0xbb.toByte() &&
            prefix[2] == 0xbf.toByte()
        ) {
            return Charset.UTF8
        }

        val looksLikeUtf8 = try {
            Utf8ContentDetector.looksLikeUtf8(openStream = { FileInputStream(file) })
        } catch (_: IOException) {
            false
        }
        return if (looksLikeUtf8) Charset.UTF8 else Charset.GBK
    }
}
