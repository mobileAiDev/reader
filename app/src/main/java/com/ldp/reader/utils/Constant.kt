package com.ldp.reader.utils

import java.io.File

/**
 * Created by ldp on 17-4-16.
 */
object Constant {
    const val APP_KEY = "2dc105548a750"
    const val APP_SECRET = "b63ac145473ed640a5a449f368570596"

    const val API_BASE_URL = "http://api.zhuishushenqi.com"
    const val API_BASE_URL_OWN = "http://122.51.249.120"

    const val FORMAT_BOOK_DATE = "yyyy-MM-dd'T'HH:mm:ss"
    const val FORMAT_TIME = "HH:mm"
    const val FORMAT_FILE_DATE = "yyyy-MM-dd"

    @JvmField
    var BOOK_CACHE_PATH: String = FileUtils.getCachePath() + File.separator +
        "book_cache" + File.separator

    @JvmField
    var BOOK_RECORD_PATH: String = FileUtils.getCachePath() + File.separator +
        "book_record" + File.separator
}
