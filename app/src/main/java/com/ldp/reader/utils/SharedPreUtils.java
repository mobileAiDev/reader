package com.ldp.reader.utils;

import com.tencent.mmkv.MMKV;

/**
 * Created by ldp on 17-4-16.
 */

public class SharedPreUtils {
    private static final String SHARED_NAME = "IReader_pref";
    private static SharedPreUtils sInstance;
    private final MMKV mmkv;

    private SharedPreUtils(){
        mmkv = MMKV.mmkvWithID(SHARED_NAME);
    }

    public static SharedPreUtils getInstance(){
        if(sInstance == null){
            synchronized (SharedPreUtils.class){
                if (sInstance == null){
                    sInstance = new SharedPreUtils();
                }
            }
        }
        return sInstance;
    }

    public String getString(String key){
        return mmkv.decodeString(key,"");
    }

    public void putString(String key,String value){
        mmkv.encode(key,value);
    }

    public void putInt(String key,int value){
        mmkv.encode(key, value);
    }

    public void putLong(String key,long value){
        mmkv.encode(key, value);
    }

    public void putBoolean(String key,boolean value){
        mmkv.encode(key, value);
    }

    public int getInt(String key,int def){
        return mmkv.decodeInt(key, def);
    }

    public long getLong(String key,long def){
        return mmkv.decodeLong(key, def);
    }

    public boolean getBoolean(String key,boolean def){
        return mmkv.decodeBool(key, def);
    }
}
