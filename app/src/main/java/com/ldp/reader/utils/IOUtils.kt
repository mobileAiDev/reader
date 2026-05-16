package com.ldp.reader.utils

import java.io.Closeable
import java.io.IOException

object IOUtils {
    @JvmStatic
    fun close(closeable: Closeable?) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
