package com.ldp.reader.utils

import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * @Description: 将字符串转化为MD5
 */
object MD5Utils {
    @JvmStatic
    fun strToMd5By32(str: String): String? {
        var reStr: String? = null
        try {
            val md5 = MessageDigest.getInstance("MD5")
            val bytes = md5.digest(str.toByteArray(Charset.defaultCharset()))
            val stringBuffer = StringBuffer()
            for (b in bytes) {
                val bt = b.toInt() and 0xff
                if (bt < 16) {
                    stringBuffer.append(0)
                }
                stringBuffer.append(Integer.toHexString(bt))
            }
            reStr = stringBuffer.toString()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }
        return reStr
    }

    @JvmStatic
    fun strToMd5By16(str: String): String? {
        var reStr = strToMd5By32(str)
        if (reStr != null) {
            reStr = reStr.substring(8, 24)
        }
        return reStr
    }
}
