package com.ldp.reader.utils;

import java.io.File;

/**
 * Created by ldp on 17-4-16.
 */

public class Constant {
    /*SharedPreference*/
    public static final String APP_KEY = "2dc105548a750";
    public static final String APP_SECRET = "b63ac145473ed640a5a449f368570596";

    /*URL_BASE*/
    public static final String API_BASE_URL = "http://api.zhuishushenqi.com";

//    腾讯云
    public static final String API_BASE_URL_OWN = "http://122.51.249.120";
    //本地
//    public static final String API_BASE_URL_OWN = "http://192.168.123.103";




    //Book Date Convert Format
    public static final String FORMAT_BOOK_DATE = "yyyy-MM-dd'T'HH:mm:ss";
    public static final String FORMAT_TIME = "HH:mm";
    public static final String FORMAT_FILE_DATE = "yyyy-MM-dd";
    //BookCachePath (因为getCachePath引用了Context，所以必须是静态变量，不能够是静态常量)
    public static String BOOK_CACHE_PATH = FileUtils.getCachePath() + File.separator
            + "book_cache" + File.separator;
    //文件阅读记录保存的路径
    public static String BOOK_RECORD_PATH = FileUtils.getCachePath() + File.separator
            + "book_record" + File.separator;
}
