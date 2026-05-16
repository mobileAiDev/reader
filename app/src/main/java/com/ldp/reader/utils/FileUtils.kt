package com.ldp.reader.utils

import android.os.Environment
import com.ldp.reader.App
import io.reactivex.Single
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.io.Reader
import java.text.DecimalFormat
import java.util.ArrayList
import kotlin.math.log10
import kotlin.math.pow

/**
 * Created by ldp on 17-5-11.
 */
object FileUtils {
    const val SUFFIX_NB = ".nb"
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
    fun getSDTxtFile(): Single<List<File>> {
        val rootPath = Environment.getExternalStorageDirectory().path
        return Single.create { emitter ->
            val files = getTxtFiles(rootPath, 0)
            emitter.onSuccess(files)
        }
    }

    @JvmStatic
    fun getCharset(fileName: String): Charset {
        var bis: BufferedInputStream? = null
        var charset = Charset.GBK
        val first3Bytes = ByteArray(3)
        try {
            var checked = false
            bis = BufferedInputStream(FileInputStream(fileName))
            bis.mark(0)
            var read = bis.read(first3Bytes, 0, 3)
            if (read == -1) return charset
            if (
                first3Bytes[0] == 0xEF.toByte() &&
                first3Bytes[1] == 0xBB.toByte() &&
                first3Bytes[2] == 0xBF.toByte()
            ) {
                charset = Charset.UTF8
                checked = true
            }

            bis.mark(0)
            if (!checked) {
                while (bis.read().also { read = it } != -1) {
                    if (read >= 0xF0) break
                    if (read in 0x80..0xBF) break
                    if (read in 0xC0..0xDF) {
                        read = bis.read()
                        if (read in 0x80..0xBF) {
                            continue
                        } else {
                            break
                        }
                    } else if (read in 0xE0..0xEF) {
                        read = bis.read()
                        if (read in 0x80..0xBF) {
                            read = bis.read()
                            if (read in 0x80..0xBF) {
                                charset = Charset.UTF8
                                break
                            } else {
                                break
                            }
                        } else {
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            IOUtils.close(bis)
        }
        return charset
    }
}
