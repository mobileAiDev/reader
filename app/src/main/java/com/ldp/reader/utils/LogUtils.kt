package com.ldp.reader.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ldp.reader.App
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Created by ldp on 17-4-27.
 */
object LogUtils {
    private var LOG_SWITCH = true // 日志文件总开关
    private var LOG_TO_FILE = false // 日志写入文件开关
    private var LOG_TAG = "IReader" // 默认的tag
    private var LOG_TYPE = 'v' // 输入日志类型，v代表输出所有信息,w则只输出警告...
    private var LOG_SAVE_DAYS = 7 // sd卡中日志文件的最多保存天数

    private val LOG_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss") // 日志的输出格式
    private val FILE_SUFFIX = SimpleDateFormat("yyyy-MM-dd") // 日志文件格式
    private var LOG_FILE_PATH: String? = null // 日志文件保存路径
    private var LOG_FILE_NAME: String? = null // 日志文件保存名称

    @JvmStatic
    fun init(context: Context?) {
        LOG_FILE_PATH = Environment.getExternalStorageDirectory().path +
            File.separator + App.getContext().packageName
        LOG_FILE_NAME = "Log"
    }

    /****************************
     * Warn
     */
    @JvmStatic
    fun w(msg: Any?) {
        w(LOG_TAG, msg)
    }

    @JvmStatic
    fun w(tag: String?, msg: Any?) {
        w(tag, msg, null)
    }

    @JvmStatic
    fun w(tag: String?, msg: Any?, tr: Throwable?) {
        if (msg == null) return
        log(tag, msg.toString(), tr, 'w')
    }

    /***************************
     * Error
     */
    @JvmStatic
    fun e(msg: Any?) {
        if (msg is Throwable) {
            e(LOG_TAG, msg.message ?: msg.javaClass.simpleName, msg)
        } else {
            e(LOG_TAG, msg)
        }
    }

    @JvmStatic
    fun e(tag: String?, msg: Any?) {
        e(tag, msg, null)
    }

    @JvmStatic
    fun e(tag: String?, msg: Any?, tr: Throwable?) {
        if (msg == null) return
        log(tag, msg.toString(), tr, 'e')
    }

    /***************************
     * Debug
     */
    @JvmStatic
    fun d(msg: Any?) {
        d(LOG_TAG, msg)
    }

    @JvmStatic
    fun d(tag: String?, msg: Any?) {
        d(tag, msg, null)
    }

    @JvmStatic
    fun d(tag: String?, msg: Any?, tr: Throwable?) {
        if (msg == null) return
        log(tag, msg.toString(), tr, 'd')
    }

    /****************************
     * Info
     */
    @JvmStatic
    fun i(msg: Any?) {
        i(LOG_TAG, msg)
    }

    @JvmStatic
    fun i(tag: String?, msg: Any?) {
        i(tag, msg, null)
    }

    @JvmStatic
    fun i(tag: String?, msg: Any?, tr: Throwable?) {
        if (msg == null) return
        log(tag, msg.toString(), tr, 'i')
    }

    /**************************
     * Verbose
     */
    @JvmStatic
    fun v(msg: Any?) {
        v(LOG_TAG, msg)
    }

    @JvmStatic
    fun v(tag: String?, msg: Any?) {
        v(tag, msg, null)
    }

    @JvmStatic
    fun v(tag: String?, msg: Any?, tr: Throwable?) {
        if (msg == null) return
        log(tag, msg.toString(), tr, 'v')
    }

    /**
     * 根据tag, msg和等级，输出日志
     */
    private fun log(tag: String?, msg: String?, tr: Throwable?, level: Char) {
        if (tag == null || msg == null) return
        if (LOG_SWITCH) {
            if ('e' == level && ('e' == LOG_TYPE || 'v' == LOG_TYPE)) {
                if (tr == null) Log.e(tag, createMessage(msg)) else Log.e(tag, createMessage(msg), tr)
            } else if ('w' == level && ('w' == LOG_TYPE || 'v' == LOG_TYPE)) {
                if (tr == null) Log.w(tag, createMessage(msg)) else Log.w(tag, createMessage(msg), tr)
            } else if ('d' == level && ('d' == LOG_TYPE || 'v' == LOG_TYPE)) {
                if (tr == null) Log.d(tag, createMessage(msg)) else Log.d(tag, createMessage(msg), tr)
            } else if ('i' == level && ('d' == LOG_TYPE || 'v' == LOG_TYPE)) {
                if (tr == null) Log.i(tag, createMessage(msg)) else Log.i(tag, createMessage(msg), tr)
            } else {
                if (tr == null) Log.v(tag, createMessage(msg)) else Log.v(tag, createMessage(msg), tr)
            }
            if (LOG_TO_FILE) {
                log2File(
                    level.toString(),
                    tag,
                    if (msg + tr == null) "" else "\n" + Log.getStackTraceString(tr)
                )
            }
        }
    }

    private fun getFunctionName(): String? {
        val sts: Array<StackTraceElement>? = Thread.currentThread().stackTrace
        if (sts == null) {
            return null
        }
        for (st in sts) {
            if (st.isNativeMethod) {
                continue
            }
            if (st.className == Thread::class.java.name) {
                continue
            }
            if (st.fileName == "LogUtils.kt") {
                continue
            }
            return "[" + Thread.currentThread().name + "(" +
                Thread.currentThread().id + "): " + st.fileName +
                ":" + st.lineNumber + "]"
        }
        return null
    }

    private fun createMessage(msg: String): String {
        val functionName = getFunctionName()
        return if (functionName == null) msg else "$functionName - $msg"
    }

    /**
     * 打开日志文件并写入日志
     */
    @Synchronized
    private fun log2File(mylogtype: String, tag: String, text: String) {
        val nowtime = Date()
        val date = FILE_SUFFIX.format(nowtime)
        val dateLogContent = LOG_FORMAT.format(nowtime) + ":" + mylogtype + ":" + tag + ":" + text
        val destDir = File(LOG_FILE_PATH!!)
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val file = File(LOG_FILE_PATH!!, LOG_FILE_NAME + date)
        try {
            val filerWriter = FileWriter(file, true)
            val bufWriter = BufferedWriter(filerWriter)
            bufWriter.write(dateLogContent)
            bufWriter.newLine()
            bufWriter.close()
            filerWriter.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * 删除指定的日志文件
     */
    @JvmStatic
    fun delFile() {
        val needDelFiel = FILE_SUFFIX.format(getDateBefore())
        val file = File(LOG_FILE_PATH!!, needDelFiel + LOG_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * 得到LOG_SAVE_DAYS天前的日期
     */
    private fun getDateBefore(): Date {
        val nowtime = Date()
        val now = Calendar.getInstance()
        now.time = nowtime
        now[Calendar.DATE] = now[Calendar.DATE] - LOG_SAVE_DAYS
        return now.time
    }
}
